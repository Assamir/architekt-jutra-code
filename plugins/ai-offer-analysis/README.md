# AI Offer Analysis plugin (`ai-offer-analysis`, port 3011)

An iframe plugin that checks a product offer with an LLM and stores the result in the host. One BAML function, `AnalyzeOffer`, returns typed verdicts on three things:

- **Description quality**: GOOD / ACCEPTABLE / POOR, with a score, issues and suggestions.
- **Category fit**: FITS / QUESTIONABLE / MISMATCH, with an optional suggested category.
- **Price vs market**: FAIR / ABOVE_MARKET / BELOW_MARKET / INSUFFICIENT_DATA, with the product type it priced (`pricedAs`), a market range for that type and confidence.

The LLM only gives verdicts. The plugin code makes the decisions: peer price stats from the product's assigned category (the product itself excluded), the deviation % (set to null with LOW confidence when the category is MISMATCH and the LLM's market range sits inside the wrong-category peer range, i.e. it was copied from peers; the record then carries `marketRangeUnreliable: true` and the tab shows "market range unreliable" with a note instead of the range), validation of the suggested category against the real category list, and the `needsReview` rule (POOR, or MISMATCH, or ABOVE/BELOW with |deviation| >= 30%).

Stack: Next.js 15 (Pages Router), BAML `0.220.0` (pinned; the generator version in `baml_src/generators.baml` must match the package), Jest 29. All model traffic goes through the LiteLLM gateway on `:4000`.

## Extension points

| Type | Label | Path / key |
|---|---|---|
| `product.detail.tabs` | AI Offer Analysis | `/product-tab` |
| `product.detail.info` | AI Review | `/product-badge` |
| `product.list.filters` | AI: needs review | `needsReview` (boolean) |

## Architecture and data flow

1. The tab iframe on `:3011` gets the user's token from the host via postMessage (`hostApp.getToken()`).
2. It sends a same-origin `POST /api/analyze` with `{productId}` and the Bearer token to the Next.js route (`src/pages/api/analyze.ts`).
3. The route rejects requests without the `EDIT` permission (403, no host or LLM call), then calls the host on `:8180` with the forwarded token: `GET /api/products/{id}`, `GET /api/categories` and `GET /api/products?category=<assigned category id>`.
4. It runs BAML `AnalyzeOffer` (45 s timeout) against LiteLLM on `:4000`: Presidio pre-call guardrail, then the upstream model; the spend log goes to litellm-db and a trace to Langfuse.
5. It stores the result in the host:
   - the full record as a plugin object: `PUT /api/plugins/ai-offer-analysis/objects/analysis/{id}?entityType=PRODUCT&entityId={id}`;
   - a compact summary for the badge and the filter: `PUT /api/plugins/ai-offer-analysis/products/{id}/data` with `needsReview`, `descriptionVerdict`, `categoryVerdict`, `priceVerdict` and `priceDeviationPct` (integer, omitted when null).
6. It returns 200 with the record. The tab renders it and posts `{type: "analysis-saved", productId, needsReview}` on the `BroadcastChannel("ai-offer-analysis")`; the info badge re-reads `getData` without a page reload.
7. The product list filter uses `pluginFilter=ai-offer-analysis:needsReview:bool:true`.

Errors come back as `{error, code}` with a sanitized message; raw details go only to the plugin's server log.

| Status | `code` | When |
|---|---|---|
| 400 | `BAD_REQUEST` | Missing or non-numeric `productId` |
| 401 | `UNAUTHENTICATED` | No bearer token, or the host returned 401 |
| 403 | `FORBIDDEN` | Token without `EDIT`, or the host returned 403 |
| 404 | `PRODUCT_NOT_FOUND` | Host 404 when reading the product |
| 405 | `METHOD_NOT_ALLOWED` | Not a POST |
| 502 | `CONTENT_BLOCKED` | LiteLLM returned 400 with a guardrail marker (`guardrail`, `presidio`, `blocked pii`, `pii entit`) in the error message or raw response |
| 502 | `LLM_TIMEOUT` | The call was aborted or timed out |
| 502 | `LLM_INVALID_OUTPUT` | The model output did not parse as `OfferAnalysis` |
| 502 | `LLM_UNAVAILABLE` | Any other gateway error (other 400s such as an unknown model, auth, 429, 5xx, connection refused) |
| 502 | `HOST_ERROR` | Any other host error, including an invalid product or categories payload and a failed save |
| 500 | `INTERNAL` | Anything unclassified |

When the LLM call fails, nothing is saved.

## Setup

Prerequisites: the host on `:8180` with the `dev` context, and the Docker Compose AI tooling (`docker compose up -d`: LiteLLM, Langfuse, Presidio) with `litellm/.env` filled in (see the root README).

```bash
cd plugins/ai-offer-analysis
cp .env.example .env    # LITELLM_API_KEY = LITELLM_MASTER_KEY from litellm/.env (sk-litellm-dev-key by default)
npm install             # also runs `baml-cli generate` (postinstall) and creates baml_client/
npm run dev             # http://localhost:3011
```

Dev ports: host `:8180`, MCP server `:8181` (management `:9181`); configure MCP clients at `http://localhost:8181`. `HOST_BASE_URL` defaults to `http://localhost:8180`, so the plugin needs no extra setting while the host runs on its default port. `start-dev` wires matching OAuth client secrets for the host and the MCP server; if you start them by hand, export the same `AJ_OAUTH_CLIENT_SECRET` in both shells (the value `start-dev` stored in the gitignored `.aj-dev.env`, if any), because a mismatch fails closed: the host answers `401 invalid_client` and the MCP client sees a 5xx or a tool error (see "Manual Start" in the root README). The plugin itself never needs that secret.

After editing anything in `baml_src/`, run `npm run generate`. `baml_client/`, `.next/` and `.env` are gitignored.

The plugin `.env` holds only the LiteLLM proxy key (`LITELLM_API_KEY`), `BAML_LOG=warn` (keeps BAML from echoing prompts and responses, which contain unmasked product text, to stdout) and, optionally, `HOST_BASE_URL` (default `http://localhost:8180`). Provider keys live only in `litellm/.env`.

## Registration

On a dev database the plugin is registered automatically by the Liquibase dev seed `src/main/resources/db/changelog/2026/013-insert-ai-offer-analysis-plugin.yaml`. The seed also adds two deliberately flawed demo products with fictional brands: `Vistara 65" OLED 4K TV` (SKU `AOA-DEMO-TV65`, in Smart Home) and `Skylink AX3000 Mesh Wi-Fi 6 System (2-Pack)` (SKU `AOA-DEMO-MESH2`, in Home Appliances).

To register by hand, log in as the dev admin user (the token is in the `token` field) and upload the manifest with a token that has `PERMISSION_PLUGIN_MANAGEMENT`:

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -X PUT http://localhost:8180/api/plugins/ai-offer-analysis/manifest \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d @manifest.json
```

```powershell
$token = (Invoke-RestMethod -Method Post -Uri http://localhost:8180/api/auth/login -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}').token
Invoke-RestMethod -Method Put -Uri http://localhost:8180/api/plugins/ai-offer-analysis/manifest -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json' -InFile manifest.json
```

**Manifest changes.** The seed uses `ON CONFLICT (id) DO NOTHING` and never updates an existing row. After editing `manifest.json`, re-register with the same `PUT .../manifest` command. Keep the JSONB in the 013 seed in sync with `manifest.json` for fresh databases.

## When the analysis runs

- **Only when an editor actually opens the tab.** The host mounts every tab iframe hidden on product page load; the tab loads the saved analysis at once but starts a run only when its iframe is visible. Visiting a product page without opening the tab makes no `/api/analyze` call and spends no tokens.
- **Only for a new or changed product.** The tab compares a content fingerprint (name, description, price, SKU, category id and name) with the one stored in the analysis. A current analysis is shown without a call; Re-analyze runs on demand.
- **No automatic repeat after a failure.** Each tab iframe makes at most one automatic attempt. After an error the tab shows the message, hides Re-analyze and offers Retry, which is the only way to run again.
- **Viewers** (READ only) never trigger a run. They see the saved result with an out-of-date note, or a "Not analyzed yet" card.

The info badge shows "AI review: Needs review" with the reasons, "AI review: Looks good", or "AI review: not analyzed yet" until an editor has opened the tab for that product.

## Tests

```bash
npm test     # node node_modules/jest/bin/jest.js: domain functions and the API route
npm run build
```

BAML and the host SDK are mocked, so the tests make no LLM or host calls. The UI pages are covered by the live verification below.

## LiteLLM

The plugin always calls the logical model name `gpt-4o-mini` (`baml_src/clients.baml`, `ANALYSIS_MODEL` in `src/domain.ts`). The upstream provider is chosen in `litellm/config.yaml`: `gpt-4o-mini` goes via OpenRouter (`openrouter/openai/gpt-4o-mini`) reading the key from `OPENAI_API_KEY` in `litellm/.env` (set to an OpenRouter key). The alias is shared with ai-description. To call OpenAI directly, put an OpenAI key there and switch the model to `openai/gpt-4o-mini`. Switching providers does not touch the plugin.

The BAML client has a single LiteLLM client with no BAML fallback or retry policy (routing and fallback belong to LiteLLM), so one analysis makes exactly one LiteLLM call. Every call is attributed statically:

- **Request body** (sent by BAML 0.220, confirmed): `user: "ai-offer-analysis"` and `metadata: {tags: ["ai-offer-analysis"], trace_name: "ai-offer-analysis", generation_name: "AnalyzeOffer"}`. LiteLLM records these as `end_user = "ai-offer-analysis"` and `request_tags = ["ai-offer-analysis"]` in its spend logs, and Langfuse shows trace name `ai-offer-analysis`, user `ai-offer-analysis`, tag `ai-offer-analysis` and generation `AnalyzeOffer` (verified live on LiteLLM 1.106.6, 2026-11-12: both mechanisms work through the request body, so the header fallback is not used). The spend-log `user` column stays `default_user_id`, because it holds the LiteLLM key's owner, not the end user.
- **Header fallback** (not needed with LiteLLM 1.106.6): if LiteLLM does not record the tags or the end user (verification step 6), add `headers { "x-litellm-tags" "ai-offer-analysis" }` to the client options in `baml_src/clients.baml`, run `npm run generate`, and repeat the verification.

Each BAML call writes exactly one line to the plugin's server log (the `npm run dev` terminal), with no prompt or error detail:

```
[ai-offer-analysis] llm-call productId=<id> outcome=<ok|error code> durationMs=<n>
```

A failed analysis adds one `console.error` line with the product id, stage, code, error name, HTTP status (if any) and the error message cut to 200 characters. The error object, prompt and raw LLM output are never logged.

### Verification procedure

**Preconditions**
- `litellm/.env` holds a real key in `OPENAI_API_KEY` matching the `gpt-4o-mini` mapping in `litellm/config.yaml` (an OpenRouter key for `openrouter/openai/gpt-4o-mini`, an OpenAI key for `openai/gpt-4o-mini`).
- `docker compose up -d` is running, with litellm, litellm-db, langfuse (+ worker, ClickHouse, MinIO, Redis) and Presidio healthy.
- The host runs on `:8180` with the dev context, so the 013 seed is applied.
- The plugin has `.env` and is started with `npm run dev` on `:3011`.

**Ground truth.** The number of LLM calls is the number of `[ai-offer-analysis] llm-call` lines printed in the plugin's `npm run dev` terminal during the run. The browser's `POST /api/analyze` count is only a cross-check, because it also includes requests rejected before BAML. Expected LiteLLM entries:
- each `outcome=ok` or `outcome=LLM_INVALID_OUTPUT` line: exactly one success entry;
- each `outcome=LLM_TIMEOUT` line: zero or one entry (LiteLLM may finish and log a call the plugin aborted at 45 s); exclude these from the equality check and note them;
- `outcome=CONTENT_BLOCKED` / `LLM_UNAVAILABLE` lines: no success entry (they may show as failures in the UI).

In a normal run every line is `outcome=ok`, so the check is simple equality.

**Step 0: prepare the shell and take a baseline (before any traffic).** Run the commands from the repo root.

- Load the master key from `litellm/.env` into the shell (it is not exported by default; `sk-litellm-dev-key` is the dev default):
  - bash: `export LITELLM_MASTER_KEY=$(grep -E '^LITELLM_MASTER_KEY=' litellm/.env | cut -d= -f2-)`
  - PowerShell: `$env:LITELLM_MASTER_KEY = ((Get-Content litellm/.env | Select-String '^LITELLM_MASTER_KEY=').Line -split '=', 2)[1]`
- Record the start time (UTC) for the report and the UI filter, and fix a date window:
  - bash (GNU date, as in Git Bash): `START=$(date -u +%Y-%m-%dT%H:%M:%SZ); FROM=$(date -u +%Y-%m-%d); TO=$(date -u -d tomorrow +%Y-%m-%d)`
  - PowerShell: `$start = [datetime]::UtcNow; $from = $start.ToString('yyyy-MM-dd'); $to = $start.AddDays(1).ToString('yyyy-MM-dd')`
- Run the count command below once and write the number down as the **baseline**. Earlier ai-offer-analysis runs today are part of it, so the delta after step 1 isolates this run.
- **Count command** (`summarize=false` returns individual rows for the date window; if an older LiteLLM returns an aggregated object instead of a list, drop the three date/summarize parameters, the baseline still isolates the run):
  - bash with jq:
    ```bash
    curl -s -H "Authorization: Bearer $LITELLM_MASTER_KEY" "http://localhost:4000/spend/logs?start_date=$FROM&end_date=$TO&summarize=false" | jq '[.[] | select((((.request_tags // []) | index("ai-offer-analysis")) != null) or .end_user == "ai-offer-analysis")] | length'
    ```
  - bash without jq: use the PowerShell variant or the LiteLLM UI filter (step 2); `jq` is optional.
  - PowerShell:
    ```powershell
    $logs = Invoke-RestMethod -Headers @{ Authorization = "Bearer $env:LITELLM_MASTER_KEY" } -Uri "http://localhost:4000/spend/logs?start_date=$from&end_date=$to&summarize=false"; $hits = @($logs | Where-Object { ((@($_.request_tags) -join ',') -like '*ai-offer-analysis*') -or $_.end_user -eq 'ai-offer-analysis' }); $hits.Count
    ```
  - To list details: bash `... | jq '.[] | select(...) | {startTime, model, spend, total_tokens, end_user, request_tags}'`; PowerShell `$hits | Select-Object startTime, model, spend, total_tokens, end_user, request_tags`.
- **Alternative without the master key:** count the same rows (the LiteLLM UI Logs page reads this table) directly in litellm-db. `startTime` is stored in UTC; use the recorded start time:
  ```bash
  docker exec architekt-jutra-code-litellm-db-1 psql -U litellm -d litellm -tAc \
    "select count(*) from \"LiteLLM_SpendLogs\" where \"startTime\" >= '<START, e.g. 2026-11-12 19:07:31>' and status = 'success' and (request_tags ? 'ai-offer-analysis' or end_user = 'ai-offer-analysis');"
  ```
  For details select `request_id, "startTime", model, model_group, status, end_user, request_tags, spend, total_tokens` instead of `count(*)`. Failed calls are stored too (`status = 'failure'`, still tagged), hence the status condition.

**Step 1: trigger traffic.** Clear the plugin terminal (or note its last line) first.
- Log in as `editor`. Open the product `Vistara 65" OLED 4K TV` and stay on the default "Details" tab: **no** `llm-call` line appears (the hidden tab does not auto-run), and the badge shows "not analyzed yet".
- Click the "AI Offer Analysis" tab: the auto-run starts now and should show "Needs review" (at least the POOR description and the category MISMATCH). Switch back to "Details": the info badge shows the new verdict without a reload. Switch to the AI tab again: no new `llm-call` line.
- Open `LG C4 65" OLED evo 4K Smart TV` and click the tab: expect "Looks good". With a FAIR price the price section is a single compact line (`USD 1,799.99 vs market ... · peers 1,299.99 / 1,749.99 / 2,199.99`, i.e. the two other Televisions); the labelled row "Catalog peers (Televisions, 2)" appears only in the full price table of a non-FAIR verdict.
- Open `Skylink AX3000 ...` and click the tab: expect "Needs review" with at least the category MISMATCH (suggests Networking); a BELOW_MARKET price flag is expected but not required.
- Click Re-analyze once.
- On the product list, switch on "AI: needs review" (click the switch itself; clicking its label text does not toggle it): both demo products are listed.
- Log in as `viewer` and open an analyzed product's tab: no new `llm-call` line.
- Expected on a fresh dev database: 4 `llm-call` lines (3 auto-runs + 1 Re-analyze), all `outcome=ok`. A product that already has a current analysis from an earlier run shows it without a call, so the ground truth is always the actual `llm-call` lines, not this number.

**Step 2: LiteLLM UI** at `http://localhost:4000/ui` (username `admin`, password the `LITELLM_MASTER_KEY`, `sk-litellm-dev-key` by default), Logs page, entries after the recorded start time:
- One entry per `llm-call` line (per the ground-truth rules above), model group `gpt-4o-mini` (the model column shows the upstream deployment, e.g. `openrouter/openai/gpt-4o-mini`), status success.
- In the database the prompt is stored in `proxy_server_request` (the `messages` column stays `{}`) and is truncated for storage (`MAX_STRING_LENGTH_PROMPT_IN_DB`); the full prompt and response are in Langfuse.
- Request tags contain `ai-offer-analysis` and/or end user `ai-offer-analysis`.
- The stored prompt shows the product text with `USD` prices (not masked); the response is JSON matching `OfferAnalysis`.

**Step 3: spend logs API.** Run the count command again. **Delta = new count - baseline** must equal the expected entries from the ground-truth rules (4 for the script above on a fresh database). Note the `model`, `spend` and `total_tokens` values of the new rows. If the delta is 0 although `llm-call` lines exist, attribution is not recorded: go to step 6.

**Step 4: Langfuse** at `http://localhost:3100` (`admin@aj.local` / `admin123`), project "AJ Plugins", Traces: the traces carry the `ai-offer-analysis` tag or trace name, with input and output for each generation.

**Step 5: no bypass.**
- The plugin config holds only `LITELLM_API_KEY` and the `:4000` base_url; a grep of the plugin for `api.openai.com` or provider keys returns nothing.
- Optional: stop the `litellm` container, click Re-analyze, and confirm the tab shows "The AI gateway is unavailable..." (502). Then restart it.

**Step 6: attribution fallback.** If step 2 or 3 shows no tag and no end user, apply the `x-litellm-tags` header fallback (see above), repeat steps 1-3, and record in this README which mechanism works.

**Step 7: record results** in the task's verification report: start time, `llm-call` line count and outcomes, baseline and delta, tag and end-user evidence, Langfuse trace ids or screenshots.

## Known issues and limitations

- **Shared `plugins/server-sdk.ts` 204 bug.** `removeData` and `objects.delete` call `res.json()` on a 204 No Content response and throw. This plugin never calls them, so analyses are never deleted, and nothing is cleaned up when a product is deleted.
- **Presidio masking.** The LiteLLM guardrail masks PII such as phone numbers and 11-digit runs (PL_PESEL), which can mangle long digit sequences in a description. Prices are sent as `USD 1799.99` without thousands separators and SKUs are not sent; if a verdict looks odd, inspect the prompt in the LiteLLM spend logs (`proxy_server_request`, kept by `store_prompts_in_spend_logs: true`) or in the Langfuse trace. The plugin log no longer contains prompts.
- **LLM nondeterminism.** Price and category verdicts can change between runs even at `temperature 0.1`. A fictional or unknown brand may still get `INSUFFICIENT_DATA` for price. Uncertain verdicts use a plain badge, and `needsReview` is decided by a fixed rule in code.
- **`setData` after save.** If storing the summary fails after the analysis object was saved, the route returns 502 `HOST_ERROR`. The stored analysis is current (its fingerprint matches), so no auto-run follows, and the badge and the list filter stay outdated until the next Re-analyze.
- **Staleness scope.** The fingerprint covers only the product's own fields and its category id and name. Adding or renaming other categories, or repricing peer products, does not trigger a re-analysis; use Re-analyze.
- **Currency.** Prices are assumed to be USD.
