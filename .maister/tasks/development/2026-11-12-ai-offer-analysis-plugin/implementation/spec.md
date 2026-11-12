# Specification: AI Offer Analysis Plugin (`ai-offer-analysis`, port 3011)

## TL;DR
New iframe plugin `plugins/ai-offer-analysis` (port 3011) clones the ai-description stack (Next.js 15 Pages Router, BAML pinned 0.220.0, Jest); one BAML function `AnalyzeOffer` goes through LiteLLM (`gpt-4o-mini`) and returns typed verdicts on description quality, category fit and price vs market.
Plugin code adds peer price stats from the product's assigned category (self excluded), the deviation %, category validation and the `needsReview` rule.
Each result is stored as a bound plugin object plus a compact `setData` summary that drives the info badge and the native "AI: needs review" list filter.
For editors the tab auto-runs only once its iframe is actually visible (the host mounts hidden tab iframes on page load), only when the analysis is missing or its fingerprint changed, and never repeats a failed auto-run in the same session.
Done means a live run is counted and attributed to `ai-offer-analysis` in the LiteLLM logs with an executable baseline/delta procedure.
Phase 11 fix cycle 1 changed parts of this contract (`pricedAs`, a peer-contamination guard with `marketRangeUnreliable`, a narrower CONTENT_BLOCKED, sanitized failure logging, `BAML_LOG=warn`, the LiteLLM upstream mapping). They are listed under **Spec deviations (Phase 11 fix cycle 1)**, and the code is the reference for them.

## Key Decisions
- **Clone the ai-description stack, minus its defects.** Next.js Pages Router, `@boundaryml/baml` pinned to exactly `0.220.0` (the BAML generator version must match the package), and Jest via `node node_modules/jest/bin/jest.js`, which works on Windows.
- **Staleness check.** Staleness is decided by a deterministic content fingerprint computed in `src/domain.ts`, not by `updatedAt`. `updatedAt` is the JPA `@Version` column, and every `setData` bumps it, which would cause an endless re-analysis loop.
- **Fingerprint algorithm.** The fingerprint is the 53-bit non-cryptographic `cyrb53` hash over a canonical JSON array, written as 14 lowercase hex characters. It is synchronous, has no dependencies and gives identical output in the browser and in Node/Jest. Web Crypto SHA-256 was rejected: it is async, and Jest/Node global availability is uncertain. The fingerprint is not a security boundary.
- **Verdicts from the LLM, decisions in code.** The plugin code computes the deviation %, peer stats, validates the suggested category against the real list, and applies the `needsReview` rule (POOR, or MISMATCH, or ABOVE/BELOW with |deviation| ≥ 30%). Code also overrides the LLM's price confidence to LOW and drops the deviation when the market range looks copied from wrong-category peers (amended — see Spec deviations).
- **EDIT gate before any host or LLM call.** The route decodes the JWT `permissions` with shipping's base64url-safe `canEdit`, moved into this plugin's `domain.ts`. If EDIT is missing it returns 403 and makes no LLM call. The host then validates the token signature on `getProduct`, so a forged token gets a host 401 before BAML runs.
- **One BAML client, no BAML fallback wrapper and no retry policy.** Routing and fallback belong to LiteLLM, and skipping retries keeps exactly one LiteLLM log entry per analysis. `base_url` is hardcoded to `http://localhost:4000/v1`, as in the template.
- **Static LiteLLM attribution in the client options.** `user "ai-offer-analysis"` plus a `metadata` object (tags `["ai-offer-analysis"]`, `trace_name`, `generation_name`). The spec audit confirmed offline (`buildRequest` on BAML 0.220.0) that these go into the request body; the `x-litellm-tags: ai-offer-analysis` header (BAML `headers`) stays as the fallback if LiteLLM does not record them.
- **LLM timeout.** It is enforced with `AbortSignal.timeout(45000)` passed in the BAML call options (`signal`, confirmed in the 0.220 generated client).
- **Auto-run only when the tab is visible.** The host mounts every tab iframe on product page load inside a `display:none` panel. The tab loads the saved analysis at once (cheap host GETs) but defers the auto-run until its iframe has a non-zero viewport, watching the window `resize` event when hidden. After one auto-run attempt (success or error) the iframe session never auto-runs again; Retry/Re-analyze are manual.
- **Peers come from the assigned category.** Peer price stats always use the product's assigned category, excluding the product itself (`String(p.id) === String(product.id)`). The prompt tells the LLM to discount peers that are a different product type, so a mis-categorized product's price verdict comes from the market estimate. The prompt now also forbids deriving the market range from peers, and code guards against a copied range (amended — see Spec deviations).
- **Registration.** The new Liquibase dev-context changeset `013-insert-ai-offer-analysis-plugin.yaml` inserts the plugin row and two deliberately flawed demo products (fictional brands, `AOA-DEMO-*` SKUs). Inserts are `ON CONFLICT DO NOTHING`; demo products join their category with an inner join so a missing category skips the row instead of failing startup. A curl command is documented as the alternative and as the way to re-register after manifest changes.
- **UI.** The 7 mockups are binding. Neutral verdicts use a plain `tc-badge`. Section layout is per section: compact for GOOD/FITS/FAIR, detailed otherwise. Re-analyze is shown disabled while a run is in flight. The mockup's CSS spinner is replaced by a native indeterminate `<progress>`, because plugin-ui.css has no spinner and custom CSS is not allowed. Number formatting is `en-US` with 2 decimals; the mockup's thin-space grouping and peer numbers are illustrative.

## Open Questions / Risks
- **LiteLLM `request_tags` recording.** BAML sends the body `metadata.tags` and `user` (verified offline); whether LiteLLM `main-stable` stores them as `request_tags`/`end_user` in OSS spend logs is only known at live verification. If not, switch to the `x-litellm-tags` header and record which mechanism worked in the README.
- **Presidio masking.** Masking (PHONE_NUMBER, PL_PESEL `\b\d{11}\b`) can mangle long digit runs. Prices are sent as `USD 1799.99`, without thousands separators, and SKUs are not sent. Inspect the logged prompt during verification (amended — see Spec deviations): prompts are visible in the LiteLLM spend logs and Langfuse; the plugin log no longer contains them.
- **LLM nondeterminism.** Price and category verdicts can flip between runs, and a fictional brand may still yield INSUFFICIENT_DATA. Mitigations: `temperature 0.1`, the deterministic code rule, the plain badge for uncertain verdicts, prompt wording that prices unknown brands against comparable products, and demo expectations that require only one strongly cued flag per demo product.
- **Visibility gate depends on browser behaviour.** It assumes an iframe inside a `display:none` panel reports a 0×0 viewport and fires `resize` when shown (Chromium and Firefox do). Verification step 1 checks that opening a product page without clicking the tab makes no `/api/analyze` call.

## Goal
Deliver a plugin on port 3011 that uses BAML structured output, with all traffic routed through LiteLLM. When an editor opens the product tab, it automatically assesses an offer's description quality, category fit and price against market norms. The result is persisted and surfaced in the tab, the info badge and the native list filter, and the LiteLLM traffic is verifiably logged.

## User Stories
- As an **editor**, when I open a product's "AI Offer Analysis" tab, I want the analysis to run on its own if the product is new to the analysis or has changed. That way I see current quality, category and price verdicts without clicking anything else, while merely visiting a product page (without opening the tab) spends no LLM tokens.
- As an **editor**, if an automatic run fails, I want it not to retry by itself, so a persistent gateway problem does not keep spending calls; I retry with the Retry button.
- As an **editor**, I want a Re-analyze button, so I can refresh the verdicts on demand after editing a product.
- As a **viewer** (READ only), I want to see the last saved analysis and know whether it is out of date, without being able to spend LLM tokens.
- As a **catalog manager**, I want an "AI: needs review" switch on the product list, so I can find every flagged offer at once.
- As **any user on a product page**, I want a one-line AI review badge under the product details, so I can see the verdict without opening the tab. Until an editor has opened the tab for that product, the badge says "not analyzed yet".
- As a **course participant or operator**, I want every model call to be visible and attributable in the LiteLLM logs and in Langfuse, so I can confirm all traffic goes through the gateway.

## Core Requirements
1. **FR1 – Plugin project.** Create `plugins/ai-offer-analysis` as package `ai-offer-analysis-plugin`, version `1.0.0` (matching the manifest).
   - Scripts: `dev` = `next dev -p 3011` and `start` = `next start -p 3011`.
   - The manifest url must be exactly `http://localhost:3011`.
   - It has three extension points: tab `/product-tab`, info badge `/product-badge`, and a boolean list filter `needsReview`.
2. **FR2 – BAML function `AnalyzeOffer`.** It returns a typed `OfferAnalysis` (schema below) through the `OfferAnalysisLlm` client. That client points at LiteLLM `http://localhost:4000/v1` with model `gpt-4o-mini`, `api_key env.LITELLM_API_KEY`, `temperature 0.1` and static attribution.
3. **FR3 – `POST /api/analyze`.** Processing order:
   - Validate the request and gate on EDIT.
   - Fetch the product, the categories and the peers: products in the product's **assigned** category, excluding the product itself.
   - Call BAML with a timeout.
   - Post-process in code: deviation, suggestion validation, `needsReview`, fingerprint.
   - Upsert the bound object, then write the `setData` summary, then return the record.
   - Errors map to sanitized, user-facing messages (contract below).
4. **FR4 – Tab page.**
   - Loads the saved analysis and the product, and recomputes the fingerprint.
   - For editors it auto-runs when there is no analysis or the fingerprint differs, but only once the tab iframe is actually visible (non-zero viewport), not when the host mounts it hidden on product page load.
   - At most one auto-run attempt per iframe session; after an auto-run error only the Retry button runs again.
   - An in-flight guard prevents double runs.
   - It renders the mockup states and broadcasts on success.
5. **FR5 – Badge page.** Reads the `getData` summary, renders one of 3 states, and re-reads it on a BroadcastChannel message.
6. **FR6 – Shared fingerprint.** The fingerprint function lives in `src/domain.ts` and is used by both the tab (browser) and the route (Node). It is deterministic.
7. **FR7 – 013 seed.** A dev-context Liquibase changeset with the plugin row (manifest JSONB) and two flawed demo products, idempotent and with no collisions with host tests.
8. **FR8 – Tests.** Jest tests for the domain functions and the API route, with BAML and server-sdk mocked. Host `./mvnw test` still passes.
9. **FR9 – Docs.**
   - A plugin README covering setup, registration and LiteLLM verification.
   - Port, plugin and env lists in the root README, CLAUDE.md, plugins/CLAUDE.md, tech-stack.md, plugin-development.md, conventions.md and INDEX.md.
10. **FR10 – Live verification.** Analyze a flawed demo product and a clean product, then confirm the traffic in the LiteLLM UI, in `/spend/logs` and in Langfuse (procedure below).

## Visual Design
Mockups in `analysis/design-context/` are binding inputs. The implementation-planner will attach `Visual References` to UI task groups.

General rules:
- Root `tc-plugin` with 16px padding.
- Layout-only inline styles. Do not copy the mockup's inline hex colors (for example `#334155`); body text uses the default `tc-plugin` color.
- The tab iframe is fixed at 600px, so content scrolls inside it. The badge iframe is 60px.

| Screen / component ID | Mockup | Implemented by | State / trigger |
|---|---|---|---|
| `screen:analysis-tab-needs-review-editor` | `analysis/design-context/mockups/analysis-tab-needs-review-editor.html` | `src/pages/product-tab.tsx` | Editor, current analysis with `needsReview=true`. Header: title, subtitle "Analyzed {d MMM yyyy, HH:mm} · {model} via LiteLLM", danger "Needs review" and Re-analyze. Each section follows the per-section layout rule below (a flagged or uncertain verdict uses the detailed layout; a GOOD/FITS/FAIR section stays compact even here). |
| `screen:analysis-tab-looks-good` | `.../analysis-tab-looks-good.html` | `product-tab.tsx` | Current analysis with `needsReview=false`. Success "Looks good" plus Re-analyze for editors. Sections follow the per-section layout rule (GOOD/FITS/FAIR compact; an ACCEPTABLE, QUESTIONABLE, INSUFFICIENT_DATA or unflagged ABOVE/BELOW section is detailed). |
| `screen:analysis-tab-auto-running` | `.../analysis-tab-auto-running.html` | `product-tab.tsx` | Request in flight (auto or manual). Header badge plain "Analyzing…". Running card shows an indeterminate `<progress>`, the text "Analyzing this offer…", a helper line about 5–15 seconds and a reason line. For editors, Re-analyze stays visible but **disabled** (with `disabled` attribute) while running. |
| `screen:analysis-tab-viewer-saved-result` | `.../analysis-tab-viewer-saved-result.html` | `product-tab.tsx` | Viewer with a saved analysis. When it is stale, a note under the header reads "Product changed since this analysis. Only editors can run a new analysis.". No Re-analyze button. |
| `screen:analysis-tab-viewer-no-analysis` | `.../analysis-tab-viewer-no-analysis.html` | `product-tab.tsx` | Viewer, no analysis. Title plus a `tc-card` with "Not analyzed yet" / "An editor needs to open this tab to run the first analysis." |
| `screen:analysis-tab-error` | `.../analysis-tab-error.html` | `product-tab.tsx` | The POST failed. Header follows the general rule: with a previous analysis, the subtitle shows its "Analyzed …" line and the badge its overall verdict; without one, no subtitle and a plain "Not analyzed" badge (the mockup's subtitle next to "Not analyzed" is superseded). An error card holds the `tc-error` message from the API and, for `CONTENT_BLOCKED` only, the hint "The product text may contain data blocked by the gateway (e.g. a card number). Edit the product and try again." Editors get a Retry primary button; the header Re-analyze button is hidden in this state (amended — see Spec deviations). Any previous result renders below the card. |
| `screen:info-badge-states` | `.../info-badge-states.html` | `src/pages/product-badge.tsx` | (a) danger "AI review: Needs review" plus reason text such as "price +46% above market · category mismatch"; (b) success "AI review: Looks good"; (c) plain "AI review: not analyzed yet". The list filter annotation corresponds to the manifest `product.list.filters` (rendered by the host). |
| `component:verdict-badge` | needs-review mockup | `product-tab.tsx`, `product-badge.tsx` (class from a domain helper) | Bad verdicts get `tc-badge tc-badge--danger`, good verdicts `tc-badge tc-badge--success`, and neutral or uncertain ones a plain `tc-badge` (mapping below). The verdict text is always shown, so color is never the only signal. |
| `component:analysis-section-card` | needs-review mockup | `product-tab.tsx` | `tc-card tc-section` with an h4 title and verdict badge(s) in a space-between `tc-flex` row, then `tc-table` facts and the reasoning or lists. |

**Badge class mapping** (domain helper):
- Description: GOOD → success; ACCEPTABLE → plain; POOR → danger.
- Category: FITS → success; QUESTIONABLE → plain; MISMATCH → danger.
- Price:
  - FAIR → success.
  - INSUFFICIENT_DATA → plain.
  - ABOVE_MARKET or BELOW_MARKET → danger when flagged (|deviationPct| ≥ 30), otherwise plain.

**Badge labels:**
- Verdicts are shown with underscores replaced by spaces.
- The price badge appends a signed deviation when one exists: "ABOVE MARKET +46%", "FAIR −2%" (Unicode minus), "INSUFFICIENT DATA".
- Secondary plain badges: "Score {n}/10" and "Confidence: {LOW|MEDIUM|HIGH}".

**Mockup values that are illustrative, not binding:**
- **Peer numbers.** Peers always come from the product's **assigned** category, excluding the product itself. The needs-review mockup shows "Catalog peers (Televisions, 3) … 1 299.99 / 1 799.99 / 2 199.99" next to "Assigned category Smart Home"; for the seeded Vistara TV (assigned Smart Home) the tab shows "Catalog peers (Smart Home, 3)" with USD 179.99 / 179.99 / 279.99. The looks-good mockup's LG C4 peers include the product itself; the correct values are "Catalog peers (Televisions, 2)" with 1,299.99 / 1,749.99 / 2,199.99.
- **Number grouping.** The mockups' thin-space grouping is illustrative; the UI uses `en-US` with 2 decimals.
- **Error-state subtitle.** Superseded by the header rule in the error row above.
- **Spinner.** Replaced by a native indeterminate `<progress>`.

**Section layouts.** The rule is per section and independent of the overall verdict: a section is **compact** when its verdict is GOOD, FITS or FAIR, and **detailed** for every other verdict.
- **Description quality.**
  - Compact (GOOD): the summary sentence.
  - Detailed: an "Issues" list and a "Suggestions" list, each only when non-empty. If both are empty, show the summary.
- **Category fit.**
  - Compact (FITS): "Assigned: {name} — {reasoning}".
  - Detailed: a `tc-table` with an "Assigned category" row and a "Suggested category" row (the latter only when a validated suggestion exists), then the reasoning paragraph.
- **Price vs market.**
  - Compact (FAIR): "USD {price} vs market USD {min}–{max} · peers {min} / {median} / {max}". The peers part is omitted when there are no peers; the market part becomes "market range unknown" when it is missing, or "market range unreliable" when `marketRangeUnreliable` is true (amended — see Spec deviations).
  - Detailed: a `tc-table` with columns Metric | USD and these rows:
    - Product price.
    - Estimated market range (or "—"; "unreliable" when `marketRangeUnreliable` is true (amended — see Spec deviations)).
    - Catalog peers ({categoryName}, {count}) min / median / max, or "No other products in this category".
  - The reasoning paragraph follows the table.
  - Both layouts (amended — see Spec deviations): when `marketRangeUnreliable` is true, a 12px note "Market estimate unreliable — it matched catalog peers from a different category."; when `pricedAs` is non-empty, a 12px line "Priced as: {pricedAs}".

**Running reason line:**
- "Started automatically: this product has not been analyzed yet."
- "Started automatically: product content changed since the last analysis."
- No reason line when the user clicked Re-analyze or Retry.
- The auto-run starts only when the tab becomes visible, so the running state is what an editor sees right after clicking the tab.

**Accessibility:** the result and status region uses `aria-live="polite"`; buttons have visible text; the progress element has an accessible label.

## Reusable Components

### Existing Code to Leverage
| Asset | Path | How it is used |
|---|---|---|
| Next.js + BAML scaffolding | `plugins/ai-description/{next.config.js, tsconfig.json, jest.config.js, next-env.d.ts, src/pages/_document.tsx, baml_src/generators.baml}` | Copy them. `externalDir` is needed for `../../sdk` and `../../server-sdk`. `_document.tsx` loads `http://localhost:8080/assets/plugin-sdk.js` and `plugin-ui.css`. Drop the unused `@sdk` tsconfig path. |
| BAML client pattern | `plugins/ai-description/baml_src/clients.baml` | Basis for `OfferAnalysisLlm` (openai provider via LiteLLM). Drop the single-entry fallback wrapper. |
| BAML function pattern | `plugins/ai-description/baml_src/main.baml` | Jinja prompt with `{{ ctx.output_format }}`. |
| API route pattern | `plugins/ai-description/src/pages/api/generate.ts` | POST-only handler, `createServerSDK(PLUGIN_ID, undefined, req)`, `objects.save` with PRODUCT binding. Do not copy its raw error forwarding or ungated LLM call. |
| Route test pattern | `plugins/ai-description/src/__tests__/generate.test.ts` | `node-mocks-http` plus hoisted `jest.mock("../../baml_client")` and `jest.mock("../../../server-sdk")`. |
| Server SDK | `plugins/server-sdk.ts` | Uses `hostApp.getProduct`, `hostApp.getProducts({category})`, `hostApp.fetch("/api/categories")`, `thisPlugin.objects.save` and `thisPlugin.setData`. Non-2xx responses throw `Host API error <status> ...`. |
| Browser SDK types | `plugins/sdk.ts` | `getSDK()`, `thisPlugin.productId`, `objects.list(type, {entityType, entityId})`, `getData`, `hostApp.getProduct`, `hostApp.getToken`. |
| `canEdit(token)` | `plugins/shipping/src/domain.ts` (≈ line 104) | Port it verbatim into this plugin's `domain.ts`, together with its small `isRecord` guard; it is base64url-safe. Used by the tab (UI gating) and the route (403). |
| Info badge pattern | `plugins/box-size/src/pages/ProductBoxBadge.tsx` | `getData` read, then a compact `tc-badge`. |
| Boolean list filter | `plugins/warehouse/manifest.json` | `product.list.filters` with `filterType: "boolean"`; the host renders the switch. |
| Plugin seed precedent | `src/main/resources/db/changelog/2026/012-insert-shipping-sample-data.yaml` | Same `INSERT INTO plugins ... ON CONFLICT (id) DO NOTHING` structure, dev context, rollback block. |
| Host APIs (no change) | `ProductController`, `CategoryController`, `PluginDataController`, `PluginObjectController`, `SecurityConfiguration` | Product read requires READ; data and object writes require EDIT; CORS `localhost:*` already covers 3011. |
| LiteLLM / Langfuse / Presidio | `compose.yml`, `litellm/config.yaml` | Model `gpt-4o-mini`, `store_prompts_in_spend_logs: true`, Langfuse callbacks, Presidio pre-call guardrail. No config change (amended — see Spec deviations): `gpt-4o-mini` now maps to `openrouter/openai/gpt-4o-mini`. |

### New Components Required
| Component | Path | Why it is new |
|---|---|---|
| Plugin project files | `plugins/ai-offer-analysis/{package.json, package-lock.json, manifest.json, .env.example, .gitignore, README.md}` | New plugin identity, port and pinned dependencies. The template's caret BAML version, `.env.local` wording and Windows-broken jest script are fixed rather than copied. |
| BAML schema + client | `baml_src/{clients,generators,main}.baml` | A new structured contract (`OfferAnalysis`) and a client with attribution metadata. Nothing like this exists. |
| Domain module | `src/domain.ts` | Pure, testable logic shared by browser and server: product snapshot, fingerprint, peer stats, deviation, suggestion validation, `needsReview`, record and summary builders, mappers, badge helpers, `canEdit`. |
| Analyze route | `src/pages/api/analyze.ts` | Orchestration plus sanitized error mapping. The template route leaks errors and has no permission gate. |
| Tab page | `src/pages/product-tab.tsx` | New UI states (auto-run, staleness, viewer, error). |
| Badge page | `src/pages/product-badge.tsx` | New 3-state badge with BroadcastChannel refresh. |
| Tests | `src/__tests__/{domain,analyze}.test.ts` | New behaviour. |
| Seed | `src/main/resources/db/changelog/2026/013-insert-ai-offer-analysis-plugin.yaml` | Registration plus demo data. |

## Technical Approach

### Project layout and configuration
- **Files.** `plugins/ai-offer-analysis/` contains:
  - `manifest.json`, `package.json`, `package-lock.json`, `next.config.js`, `tsconfig.json`, `jest.config.js`, `next-env.d.ts`, `.env.example`, `.gitignore`, `README.md`
  - `baml_src/` (3 files)
  - `src/domain.ts`
  - `src/pages/{_document.tsx, product-tab.tsx, product-badge.tsx, api/analyze.ts}`
  - `src/__tests__/{domain.test.ts, analyze.test.ts}`
- **`package.json`:**
  - Scripts:
    - `dev`: `next dev -p 3011`
    - `build`: `next build`
    - `start`: `next start -p 3011`
    - `test`: `node node_modules/jest/bin/jest.js`
    - `generate`: `baml-cli generate`
    - `postinstall`: `baml-cli generate`
  - Dependencies: `@boundaryml/baml` `0.220.0` (exact), `next` ^15.3.3, `react`/`react-dom` ^19.2.4.
  - devDependencies: as in the template (jest ^29.7, ts-jest ^29.3, node-mocks-http, @jest/globals, @types/node/react/react-dom, typescript ~5.9.3).
- **`.gitignore`:** `node_modules/`, `.next/`, `baml_client/`, `.env`, `.env*.local`, `*.tsbuildinfo`. Never commit `.playwright-mcp/` or `tsconfig.tsbuildinfo`.
- **`.env.example`:**
  - `LITELLM_API_KEY=sk-litellm-dev-key`, matching the local `LITELLM_MASTER_KEY` default.
  - A commented, optional `HOST_BASE_URL=http://localhost:8080`.
  - Comments say: copy to `.env`; provider keys live only in `litellm/.env`.
  - `BAML_LOG=warn` (amended — see Spec deviations).
- **Next.js pages.** Page components are named functions exported as `default`; Next.js requires this. All pages guard `getSDK()` behind `typeof window !== "undefined"`, so they render safely during SSR.

### Domain constants (`src/domain.ts`)
- `PLUGIN_ID = "ai-offer-analysis"`
- `OBJECT_TYPE = "analysis"`
- `CHANNEL_NAME = "ai-offer-analysis"`
- `ANALYSIS_MODEL = "gpt-4o-mini"` (must equal the `clients.baml` model; a comment links the two)
- `PRICE_DEVIATION_THRESHOLD_PCT = 30`
- `ANALYZE_TIMEOUT_MS = 45000`
- `MAX_PEERS_IN_PROMPT = 10` (which 10: see `selectPromptPeers`)

### BAML schema (`baml_src/main.baml`)
Every field carries a `@description`.

**Enums**

| Enum | Values (with description intent) |
|---|---|
| `DescriptionVerdict` | `GOOD` (specific, accurate, complete for the product type), `ACCEPTABLE` (usable but missing some useful details), `POOR` (vague, too short, misleading, unverifiable claims, or missing) |
| `CategoryVerdict` | `FITS` (assigned category is the best match in the list), `QUESTIONABLE` (plausible but another listed category may fit better), `MISMATCH` (clearly the wrong category) |
| `PriceVerdict` | `FAIR` (within the typical market range), `ABOVE_MARKET`, `BELOW_MARKET`, `INSUFFICIENT_DATA` (the product type cannot be identified from the name and description; an unknown brand alone is not a reason) |
| `Confidence` | `LOW`, `MEDIUM`, `HIGH` |

**Output classes**

| Class | Field | Type | Description |
|---|---|---|---|
| `DescriptionAssessment` | `verdict` | `DescriptionVerdict` | Overall description quality |
| | `score` | `int` | 1 (worst) to 10 (best) |
| | `summary` | `string` | One sentence on the description's quality |
| | `issues` | `string[]` | Concrete problems, max 4, each under 120 characters; empty when none |
| | `suggestions` | `string[]` | Actionable improvements, max 4, each under 120 characters; empty when none |
| `CategoryAssessment` | `verdict` | `CategoryVerdict` | Fit of the assigned category |
| | `suggestedCategory` | `string?` | Exact name of a better category taken from the provided list; null when the verdict is FITS or no listed category fits better |
| | `reasoning` | `string` | One or two sentences |
| `PriceAssessment` | `pricedAs` | `string` | The product type/class priced, never the catalog peers. First field (amended — see Spec deviations) |
| | `reasoning` | `string` | One or two sentences on typical prices for that type; moved before `verdict` and the numbers (amended — see Spec deviations) |
| | `verdict` | `PriceVerdict` | Price compared with typical US retail prices |
| | `estimatedMarketMinUsd` | `float?` | Lower bound of typical retail price in USD; null when unknown |
| | `estimatedMarketMaxUsd` | `float?` | Upper bound in USD; null when unknown |
| | `confidence` | `Confidence` | Confidence in the estimate |
| `OfferAnalysis` | `description` | `DescriptionAssessment` | |
| | `category` | `CategoryAssessment` | |
| | `price` | `PriceAssessment` | |

**Input classes**

| Class | Fields |
|---|---|
| `ProductInput` | `name string`, `description string` ("(no description)" when empty), `price string` (formatted `USD 1799.99`), `categoryName string` |
| `CategoryOption` | `name string`, `description string?` |
| `PeerPriceStats` | `categoryName string`, `count int`, `min string`, `median string`, `max string` (all prices formatted `USD x.xx`) |
| `PeerProduct` | `name string`, `price string` (formatted `USD x.xx`) |

**Function signature:** `AnalyzeOffer(product: ProductInput, categories: CategoryOption[], peerStats: PeerPriceStats?, peers: PeerProduct[]) -> OfferAnalysis`, using client `OfferAnalysisLlm`.

**Prompt outline**, in this order:
1. Role: a catalog quality analyst for a consumer-electronics web shop. All prices are in **USD**. Judge only from the given data; never invent specifications.
2. Product block: name, category, price and description.
3. Task 1, description quality.
   - Criteria: specificity (key specs for the product type), consistency with the name, no unverifiable superlatives, adequate length, readability.
   - Map the result to a verdict and a 1–10 score; list issues and suggestions (empty lists when none); give a one-sentence summary.
4. Task 2, category fit.
   - Render every category as "- {name}: {description}".
   - Judge the assigned category. If the verdict is not FITS, `suggestedCategory` must be copied exactly from the list, or be null.
5. Task 3, price.
   - Estimate the typical current US retail range for this product, or for comparable products of the same type and specifications. If the brand is unknown or unfamiliar, estimate from comparable products of the same type and specifications instead of giving up.
   - Consider the catalog peers. They are the other products in the product's assigned category. If `peerStats` is present, render its count, min, median and max plus the peer lines "- {name}: {price}" (at most `MAX_PEERS_IN_PROMPT`, chosen in code). Otherwise render "No other catalog products in this category." State that peers may differ in size, tier or even product type (for example when the product is in the wrong category), and that peers of a different product type must be ignored for the price verdict (amended — see Spec deviations): the type is identified first (`pricedAs`), the range must not be derived from peer prices, and peers come after the range instruction as "Catalog context".
   - Choose FAIR when the price is within the range, ABOVE_MARKET or BELOW_MARKET when it is outside, and INSUFFICIENT_DATA only when the product type cannot be identified. Set the confidence accordingly (LOW is the right answer for an unfamiliar brand, not INSUFFICIENT_DATA).
   - The prompt says "product" throughout (never "offer" or "item") so the wording matches the enum descriptions.
6. `{{ ctx.output_format }}`

**BAML test block:** one block, `test VistaraDemoOffer { functions [AnalyzeOffer] ... }`, for the BAML playground and `baml-cli test`, not for Jest. Its args mirror exactly what the route sends for the seeded TV:
- `product`: name `Vistara 65" OLED 4K TV`, description `The best TV ever. Buy now!`, price `USD 2799.00`, categoryName `Smart Home`.
- `categories`: the 5 seeded categories (Televisions, Audio Systems, Smart Home, Networking, Home Appliances) with their descriptions from `003-insert-sample-data.yaml`.
- `peerStats`: categoryName `Smart Home`, count 3, min `USD 179.99`, median `USD 179.99`, max `USD 279.99`.
- `peers`: `Amazon Echo Hub 8" Smart Home Control Panel` USD 179.99, `Philips Hue Starter Kit E26` USD 179.99, `Google Nest Learning Thermostat 4th Gen` USD 279.99.
- `@@assert(tv_market_range, ...)`: `estimatedMarketMinUsd > 500` (amended — see Spec deviations).

### BAML client (`baml_src/clients.baml`)
- `client<llm> OfferAnalysisLlm`, provider `openai`.
- Options:
  - `model "gpt-4o-mini"`
  - `api_key env.LITELLM_API_KEY`
  - `base_url "http://localhost:4000/v1"`
  - `temperature 0.1`
  - `user "ai-offer-analysis"`
  - `metadata`: `tags ["ai-offer-analysis"]`, `trace_name "ai-offer-analysis"`, `generation_name "AnalyzeOffer"`
- No `retry_policy`.
- Comment: all traffic goes through LiteLLM (keys, Presidio, Langfuse, spend logs).
- **Fallback.** BAML 0.220 is known to put `user` and `metadata` in the request body (audit probe). If verification shows `request_tags` and `end_user` empty in LiteLLM anyway, add `headers { "x-litellm-tags" "ai-offer-analysis" }` and keep `user`. Document in the README which mechanism was effective.
- `generators.baml`: copied unchanged (`version "0.220.0"`, `output_dir "../"`).

### Domain functions (`src/domain.ts`, all pure)

**Product and fingerprint**
- **`toProductSnapshot(raw: unknown)`** validates and normalizes a host product into `{id: string, name, description: string, price: number, sku, category: {id: number, name, description: string | null}}`.
  - `description` null becomes `""`.
  - `price` accepts a number or a numeric string.
  - On an invalid shape it throws `Error("Invalid product payload")`.
- **`computeFingerprint(snapshot)`** works as follows:
  - Build the canonical array `[name.trim(), description.trim(), price.toFixed(2), sku.trim(), category.id, category.name.trim()]`, then `JSON.stringify` it.
  - Hash the result with `cyrb53` (seed 0) and output 14 lowercase hex characters, zero-padded.
  - Identical content gives an identical value in the browser and in Node. Any change to name, description, price, SKU, category id or category name changes it.

**Formatting and price analysis**
- **`formatUsd(n)`** returns `"USD " + n.toFixed(2)` (no grouping; used for the prompt).
- **`formatAmount(n)`** uses `en-US` with 2 decimals (UI).
- **Peers** are always the products of the product's **assigned** category (`getProducts({category: snapshot.category.id})`), never of the suggested category.
- **`computePeerStats(products: unknown[], self)`** works as follows:
  - Drop the product itself with `String(p.id) === String(self.id)`. Host product ids are JSON numbers (`ProductResponse.id: Long`) while the snapshot id is a string, so a raw `===` would keep the product.
  - Keep finite positive prices and sort them ascending.
  - `count` is the number left, `min` the first, `max` the last.
  - `median` is the middle element, or the mean of the two middle elements for an even count, rounded to 2 decimals.
  - Returns `{categoryName, count, min, median, max}`, or `null` when the count is 0.
- **`selectPromptPeers(products: unknown[], self)`** returns the peer lines for the prompt: the same self-exclusion and price filter as `computePeerStats`; when more than `MAX_PEERS_IN_PROMPT` remain, keep the 10 closest in price to the product (absolute difference, ties by name); the result is sorted by price ascending. Peer stats are still computed over all peers.
- **`computeDeviationPct(price, min, max, verdict)`** returns `round((price − mid) / mid × 100)` with `mid = (min + max) / 2`. It returns `null` in any of these cases: the verdict is INSUFFICIENT_DATA, min or max is null, min ≤ 0, or max < min. `buildAnalysisRecord` also stores `null` when the peer-contamination guard fires (amended — see Spec deviations).
- **`isPriceFlagged(verdict, deviationPct)`** is true when the verdict is ABOVE_MARKET or BELOW_MARKET, `deviationPct !== null`, and `|deviationPct| ≥ 30`.

**Category and review rule**
- **`resolveSuggestedCategory(suggested, categories, assignedId)`** matches the suggestion against category names, case-insensitive and trimmed.
  - Returns `{id, name}`, or `null` when there is no match or the match is the assigned category.
  - A MISMATCH or QUESTIONABLE verdict is kept even when the suggestion resolves to null.
- **`computeNeedsReview(record)`** returns `description.verdict === "POOR" || category.verdict === "MISMATCH" || isPriceFlagged(price.verdict, price.deviationPct)`. Uncertain verdicts (ACCEPTABLE, QUESTIONABLE, INSUFFICIENT_DATA, or ABOVE/BELOW under 30%) never flag.

**Records, summary and mappers**
- **`buildAnalysisRecord(llm, ctx)`** combines the LLM output with the snapshot, peer stats, category list, fingerprint and timestamp into the stored record (shape below).
  - Verdict strings are narrowed against allow-lists. An unknown value falls back to the uncertain verdict: ACCEPTABLE, QUESTIONABLE, INSUFFICIENT_DATA, or LOW confidence.
  - `score` is clamped to an integer from 1 to 10.
  - `issues` and `suggestions` are capped at 4 entries each.
  - Peer-contamination guard (amended — see Spec deviations): category MISMATCH and an LLM range within the peer range ±5% give `deviationPct` null, confidence LOW and `marketRangeUnreliable` true.
  - The domain declares its own structural input type with `string` verdicts, so it never imports `baml_client`.
- **`toSummary(record)`** builds the `setData` node (shape below).
- **`toOfferAnalysis(obj: PluginObject)`** maps a stored object to the record with safe defaults, for the tab.
- **`toAnalysisSummary(data: unknown)`** maps `getData` output for the badge. It returns `null` when `needsReview` is not a boolean.

**UI helpers and permissions**
- **`verdictBadgeClass(...)`**, **`verdictLabel(...)`**, **`priceBadgeLabel(...)`** and **`reviewReasons(summary)`** cover the UI mapping described under Visual Design. `reviewReasons` produces items in a fixed order (price, then category, then description) such as "price +46% above market", "price −35% below market", "category mismatch" or "poor description", joined with " · "; only flagging verdicts produce an item.
- **`isCompactSection(verdict)`** is true for GOOD, FITS and FAIR (the per-section layout rule).
- **`canEdit(token: string | null)`** is ported from shipping.

### Stored analysis object (host `plugin_objects`)
- **Key:** `objectType "analysis"`, `objectId = productId` (string), saved with `{entityType: "PRODUCT", entityId: productId}`. The binding must be passed on every save, because saving without it clears the binding.
- **Data shape:**

| Field | Type | Notes |
|---|---|---|
| `productId` | string | |
| `fingerprint` | string | 14 hex characters, from `computeFingerprint` on the snapshot used for the analysis |
| `analyzedAt` | string | ISO-8601 UTC |
| `model` | string | `ANALYSIS_MODEL` |
| `needsReview` | boolean | From `computeNeedsReview` |
| `description` | object | `{verdict, score, summary, issues[], suggestions[]}` |
| `category` | object | `{verdict, assigned: {id, name}, suggested: {id, name} \| null, reasoning}` |
| `price` | object | `{verdict, confidence, pricedAs: string, productPrice, marketMin: number \| null, marketMax: number \| null, deviationPct: int \| null, marketRangeUnreliable: boolean, peers: {categoryName, count, min, median, max} \| null, reasoning}` (amended — see Spec deviations): `pricedAs` and `marketRangeUnreliable` added; older records read as `""` and `false` |

- **Response:** the `POST /api/analyze` 200 body is exactly this record.

### `setData` summary (`products.plugin_data["ai-offer-analysis"]`)
- Top-level keys only: `needsReview` (JSON boolean), `descriptionVerdict`, `categoryVerdict`, `priceVerdict` (enum strings), and `priceDeviationPct` (integer, omitted when null).
- No fingerprint and no nested objects. `setData` replaces the whole node, which is fine because the plugin owns it.
- Native filter query: `pluginFilter=ai-offer-analysis:needsReview:bool:true`.
- Integers only, because number filters compare as text.

### API contract: `POST /api/analyze` (`src/pages/api/analyze.ts`)
- **Request:**
  - Header `Authorization: Bearer <host JWT>`.
  - JSON body `{productId: string | number}`. The id is converted with `String(...).trim()` and must match `^\d+$`.

**Processing order:**
1. Method other than POST → 405 with header `Allow: POST`.
2. Invalid `productId` → 400.
3. Missing or empty bearer → 401.
4. `canEdit(token)` false → 403.
5. `createServerSDK(PLUGIN_ID, undefined, req)`, then `getProduct`, then `toProductSnapshot`.
6. In parallel: `hostApp.fetch("/api/categories")` (non-OK throws a host error) and `getProducts({category: snapshot.category.id})` (the assigned category).
7. `computePeerStats`, `selectPromptPeers` and `computeFingerprint`.
8. `b.AnalyzeOffer(...)` with `{signal: AbortSignal.timeout(ANALYZE_TIMEOUT_MS)}`. After the call settles, the route writes exactly one server log line per BAML call, `[ai-offer-analysis] llm-call productId=<id> outcome=<ok|error code> durationMs=<n>` via `console.info`. This line is the ground truth for the LiteLLM verification count; it contains no prompt or error detail.
9. `buildAnalysisRecord`.
10. `objects.save` (bound).
11. `setData(toSummary(record))`.
12. 200 with the record.

**Responses.** Every error body is `{error: string, code: string}`. Raw error details go only to the server log via `console.error`; they never appear in the response. The log is one sanitized line (amended — see Spec deviations).

| Status | `code` | `error` (user-facing) | When |
|---|---|---|---|
| 200 | — | — | Body is the stored analysis record |
| 400 | `BAD_REQUEST` | "Invalid productId." | Missing or non-numeric id |
| 401 | `UNAUTHENTICATED` | "Your session has expired. Please log in again." | No bearer, or the host returned 401 at any step |
| 403 | `FORBIDDEN` | "Only editors can run an analysis." | Token lacks EDIT, or the host returned 403 |
| 404 | `PRODUCT_NOT_FOUND` | "Product not found." | Host 404 on `getProduct` |
| 405 | `METHOD_NOT_ALLOWED` | "Method not allowed." | Not POST |
| 502 | `CONTENT_BLOCKED` | "The AI gateway rejected the request (content policy). No analysis was saved." | `BamlClientHttpError` with `status_code` 400 and a guardrail marker in `message` or `raw_response` (Presidio BLOCK) (amended — see Spec deviations) |
| 502 | `LLM_TIMEOUT` | "The AI gateway did not respond in time. No analysis was saved." | `BamlAbortError` or `BamlTimeoutError` |
| 502 | `LLM_INVALID_OUTPUT` | "The AI returned an unreadable result. No analysis was saved." | `BamlValidationError` or `BamlClientFinishReasonError` |
| 502 | `LLM_UNAVAILABLE` | "The AI gateway is unavailable. No analysis was saved." | Any other BAML or LiteLLM error (other 400s such as an unknown model (amended — see Spec deviations), auth, 429, 5xx, connection refused) |
| 502 | `HOST_ERROR` | "The host application could not provide or store the analysis data." | Any other host error, for example 5xx, a 404 on save because the plugin is not registered or disabled, or an invalid product or categories payload (amended — see Spec deviations) |
| 500 | `INTERNAL` | "Unexpected error." | Anything unclassified |

- **Error classes.** The route imports them from `@boundaryml/baml/errors`, not from `@boundaryml/baml`. Both entry points export the identical class objects (verified), so `instanceof` works against what the generated client throws, and `/errors` does not load the native addon, which keeps `analyze.test.ts` free of the native binary. Tests construct the errors from the same module.
- **Classification order** (first match wins). `BamlTimeoutError` extends `BamlClientHttpError` with `status_code` 408, so the timeout checks must come first:
  1. `BamlAbortError` or `BamlTimeoutError` → `LLM_TIMEOUT`.
  2. `BamlClientHttpError` with `status_code` 400 and a guardrail marker → `CONTENT_BLOCKED` (amended — see Spec deviations). Any other 400 falls through to step 4.
  3. `BamlValidationError` or `BamlClientFinishReasonError` → `LLM_INVALID_OUTPUT`.
  4. Any other BAML error (including other `BamlClientHttpError` statuses) → `LLM_UNAVAILABLE`.
  5. Host errors (`Host API error <status>`) → 401 / 403 / 404 (product stage only) / `HOST_ERROR`; the "Invalid product payload" and "Invalid categories payload" errors → `HOST_ERROR` (amended — see Spec deviations).
  6. Anything else → `INTERNAL`.
- **Host status.** Parse it from the server-sdk message `Host API error (\d+)`. The route tracks the current stage (product, context, llm or store) so that a 404 maps to `PRODUCT_NOT_FOUND` only in the product stage.
- **Partial failure.** When BAML fails, nothing is saved. When `setData` fails after a successful save, return 502 `HOST_ERROR`. The stored object is then current (its fingerprint matches), so no auto-run follows, and the badge and filter summary stay outdated until the next Re-analyze rewrites them. This is acceptable and documented in the README as a known limitation.

### Tab behaviour (`src/pages/product-tab.tsx`)

**Load**
1. On mount, get the SDK and `productId`. Wrap `productId` in `String(...)`, because the host passes a number at runtime even though the SDK type says string. This applies to the tab and the badge. If `productId` is missing, render an error card.
2. Load in parallel: `getToken` (then `canEdit`), `objects.list("analysis", {entityType: "PRODUCT", entityId: productId})` (take the first result, then `toOfferAnalysis`), and `hostApp.getProduct(productId)` (then `toProductSnapshot`, then `computeFingerprint`). These are cheap host GETs and run immediately on mount, even while the iframe is hidden.
3. If loading fails, show `tc-error` "Could not load the analysis." and nothing else. While loading, the tab shows "Loading analysis…" (amended — see Spec deviations).

**Decide what to show**
- **Stale** means an analysis exists and `analysis.fingerprint !== currentFingerprint`.
- **Editor, with no analysis or a stale analysis:** auto-run once, subject to the visibility gate below. The reason is "not analyzed yet" or "content changed". Until the run starts, the tab shows the saved result (if any) or the header with "Not analyzed".
- **Editor, current analysis:** show the result plus Re-analyze.
- **Viewer, with an analysis:** show the result. If it is stale, show the viewer note. No buttons.
- **Viewer, no analysis:** show the "Not analyzed yet" card.

**Auto-run visibility gate**
- Background: the host (`src/main/frontend/src/pages/ProductDetailPage.tsx`, plugin tab panels) mounts every plugin tab iframe on product page load inside a `display:none` panel and only toggles `display` when the user clicks the tab. The iframe therefore loads, and would auto-run, without being seen.
- The tab is **visible** when `window.innerWidth > 0 && window.innerHeight > 0` inside the iframe.
- After loading, if an auto-run is due:
  - Visible now: start the auto-run.
  - Not visible: add a `resize` listener on `window` (the iframe window fires `resize` when its panel switches to `display:block`). On the first `resize` where the tab is visible, remove the listener and start the auto-run. Remove the listener on unmount.
- A `useRef` flag `autoRunAttempted` is set when an auto-run starts. While it is set, no further auto-run happens in this iframe session, whatever the outcome. After an auto-run error the tab stays in the error state and only the Retry button (editors) runs again. Switching tabs away and back (which fires `resize` again) never re-triggers a run.
- A product page visit where the editor never opens the tab makes no `/api/analyze` call, so the badge keeps showing the previous summary or "not analyzed yet".

**Run**
- A `useRef` in-flight flag stops duplicate requests; a second call is a no-op while one is running. Re-analyze and Retry are rendered with the `disabled` attribute while a run is in flight.
- Steps:
  1. Set the running state.
  2. `fetch("/api/analyze", POST, JSON {productId}, Authorization Bearer)` using a fresh `getToken`.
  3. On 200, set the analysis from the body and post on the BroadcastChannel.
  4. If not OK, parse `{error, code}` and show the error state.
  5. A `TypeError` from fetch shows "Network error. Please check your connection and try again."

**Header**
- The title is "AI Offer Analysis".
- The subtitle appears only when an analysis exists (including the previous analysis shown in the error state): `analyzedAt` formatted `en-GB` as "12 Nov 2026, 18:42", then " · {model} via LiteLLM". No analysis means no subtitle.
- The overall badge is "Analyzing…" (plain) while running; otherwise "Needs review" (danger) or "Looks good" (success) from the shown analysis, or "Not analyzed" (plain) when there is none. In the error state the badge is the previous analysis's verdict, or "Not analyzed".

### BroadcastChannel contract
- **Channel:** `new BroadcastChannel("ai-offer-analysis")`. Both iframes share the origin `http://localhost:3011`, so they can talk to each other.
- **Message:** `{type: "analysis-saved", productId: string, needsReview: boolean}`, posted by the tab after a successful 200 from `/api/analyze`.
- **Badge side:** the badge listens and, when `type` and `productId` match its own product, re-reads `thisPlugin.getData(productId)`. `getData` is the source of truth; the message payload is only a trigger.
- **Lifecycle:** close the channel on unmount. Guard with `typeof BroadcastChannel !== "undefined"`; without it the badge stays as loaded.

### Badge behaviour (`src/pages/product-badge.tsx`)
- Rendering:
  - Nothing while loading.
  - `toAnalysisSummary(getData)`:
    - `needsReview` true: danger badge "AI review: Needs review" followed by the `reviewReasons` text in a 12px span.
    - `needsReview` false: success badge "AI review: Looks good".
    - `null` (no node or empty `{}`): plain badge "AI review: not analyzed yet".
  - `getData` rejects: plain badge "AI review: unavailable".
- Root: a `tc-flex` with layout-only padding (the `tc-plugin` root may be skipped for info badges, per the standard). It fits in 60px.

### Manifest (`plugins/ai-offer-analysis/manifest.json`)
```json
{
  "name": "AI Offer Analysis",
  "version": "1.0.0",
  "url": "http://localhost:3011",
  "description": "AI checks of description quality, category fit and price vs market (BAML via LiteLLM)",
  "extensionPoints": [
    { "type": "product.detail.tabs", "label": "AI Offer Analysis", "path": "/product-tab", "priority": 90 },
    { "type": "product.detail.info", "label": "AI Review", "path": "/product-badge", "priority": 30 },
    { "type": "product.list.filters", "label": "AI: needs review", "filterKey": "needsReview", "filterType": "boolean", "priority": 20 }
  ]
}
```
Priorities place these after the existing items: tabs come after shipping (80), info after box-size (20), and the filter after warehouse "In Stock" (10).

### Seed: `src/main/resources/db/changelog/2026/013-insert-ai-offer-analysis-plugin.yaml`
It is picked up automatically by the master changelog's `includeAll`.

1. **ChangeSet `013-insert-ai-offer-analysis-plugin`** (author `aj`, context `dev`)
   - SQL: `INSERT INTO plugins (id, name, version, url, description, enabled, manifest, created_at, updated_at) VALUES ('ai-offer-analysis', 'AI Offer Analysis', '1.0.0', 'http://localhost:3011', <description>, true, <manifest.json content>::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) ON CONFLICT (id) DO NOTHING`.
   - The JSONB must equal `manifest.json` byte-for-byte in meaning. Nothing enforces this, and `ON CONFLICT (id) DO NOTHING` never updates an existing row, so after any `manifest.json` change the manifest must be re-registered with the curl `PUT /api/plugins/ai-offer-analysis/manifest` (README, per the plugin-development standard's "re-register on changes"). The seed is only the first-run registration.
   - Rollback: delete `plugin_objects` where `plugin_id = 'ai-offer-analysis'`, then delete the plugin row.
2. **ChangeSet `013-insert-ai-offer-analysis-demo-products`** (author `aj`, context `dev`)
   - SQL shape: `INSERT INTO products (id, name, description, price, sku, category_id, created_at, updated_at) SELECT nextval('product_seq'), s.name, s.description, s.price, s.sku, c.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM (VALUES (...), (...)) AS s(name, description, price, sku, category_name) INNER JOIN categories c ON c.name = s.category_name ON CONFLICT (sku) DO NOTHING`.
   - Why: `category_id` is NOT NULL and 013 runs on every existing dev database at its next start. A scalar `(SELECT id FROM categories WHERE name = ...)` would yield NULL for a renamed or deleted category and abort Liquibase, and with it app startup. The inner join skips such a row instead.
   - Conflict target: `ON CONFLICT (sku)` uses the existing unique constraint `uk_products_sku` (`002-create-products-table.yaml`); `categories.name` is unique (`uk_categories_name`), so the join yields at most one row per demo product. `photo_url` is nullable and left out.
   - Rollback: `DELETE FROM products WHERE sku IN ('AOA-DEMO-TV65', 'AOA-DEMO-MESH2')`.

Each demo product has at least one **strongly cued** flag (a POOR description or a MISMATCH category with the right category in the list), so "Needs review" does not depend on the price estimate. Price flags are expected but tolerated to vary (including INSUFFICIENT_DATA for the fictional brands). Peers are from the assigned category and are deliberately not comparable products, so the price verdict comes from the LLM's market estimate.

| SKU | Name | Category | Price | Description | Required flag (strongly cued) | Expected but LLM-dependent |
|---|---|---|---|---|---|---|
| `AOA-DEMO-TV65` | `Vistara 65" OLED 4K TV` | Smart Home | 2799.00 | `The best TV ever. Buy now!` | Description POOR; category MISMATCH (suggests Televisions) | Price ABOVE_MARKET ≥ 30% from the market estimate for a 65" OLED TV. Peers shown: Smart Home, 3, USD 179.99 / 179.99 / 279.99 |
| `AOA-DEMO-MESH2` | `Skylink AX3000 Mesh Wi-Fi 6 System (2-Pack)` | Home Appliances | 19.99 | `Dual-band Wi-Fi 6 (AX3000) mesh system with two nodes covering up to 4,500 sq ft, WPA3 security, app-based setup and parental controls.` | Category MISMATCH (suggests Networking) | Description GOOD or ACCEPTABLE; price BELOW_MARKET ≥ 30% from the market estimate for a Wi-Fi 6 mesh 2-pack. Peers shown: Home Appliances, 3, USD 649.99 / 799.99 / 1099.95 |

- **Collision check, done against `src/test`.**
  - No test searches for "vistara" or "skylink" (the only search term is "trackball").
  - No test SKU starts with `AOA-`.
  - No test references the category names "Smart Home", "Networking" or "Home Appliances"; tests that use seeded categories only assert `≥` sizes, and category-filter tests create their own categories.
  - The plugin id does not appear in tests.
  - No `plugin_data` is seeded, so the `reviews`/`seo` filter tests are unaffected.
- **Fictional brands** avoid impersonating real products. The descriptions contain no 11-digit runs and no card-like numbers.

### Data flow
1. The tab iframe on :3011 gets the token from the host via postMessage.
2. It sends a same-origin `POST /api/analyze` with the Bearer token to the Next.js route on :3011.
3. The route calls the host on :8080: `GET /api/products/{id}`, `GET /api/categories` and `GET /api/products?category=`.
4. It runs BAML `AnalyzeOffer`, which goes to LiteLLM on :4000 (Presidio pre-call, then OpenAI `gpt-4o-mini`, now via OpenRouter (amended — see Spec deviations); the spend log goes to litellm-db and a trace to Langfuse).
5. It calls the host: `PUT /api/plugins/ai-offer-analysis/objects/analysis/{id}?entityType=PRODUCT&entityId={id}`, then `PUT /api/plugins/ai-offer-analysis/products/{id}/data`.
6. It returns 200 with the record. The tab renders it and broadcasts; the badge re-reads `getData`.
7. The list page filter uses the `pluginFilter` described above.

## Implementation Guidance

### Testing Approach
- Use 2–8 focused tests per implementation step group. Test verification runs only the new tests, not the entire suite, except the final host regression run.
- **Domain tests, `src/__tests__/domain.test.ts` (up to 10 tests; slightly above the usual 8 because the UI helpers that drive the binding mockups are not covered elsewhere; use `test.each` tables where several cases share one behaviour):**
  1. The fingerprint is deterministic and normalizes values: `899` vs `"899.00"`, surrounding whitespace, null vs empty description.
  2. The fingerprint changes when name, description, price, SKU, category id or category name changes.
  3. The `computeNeedsReview` truth table: POOR, MISMATCH, ABOVE +35, BELOW −30 flag; ABOVE +29, ACCEPTABLE, QUESTIONABLE and INSUFFICIENT_DATA do not.
  4. `computePeerStats` and `selectPromptPeers`: the fixture uses **numeric** host ids and a string snapshot id, and self is excluded; odd and even median; empty list gives null; more than 10 peers keeps the 10 closest in price, sorted ascending.
  5. `computeDeviationPct`: rounding, plus null when the range is invalid or the data insufficient.
  6. `resolveSuggestedCategory`: case-insensitive match, unknown name gives null, the assigned category gives null.
  7. `canEdit`: a base64url payload with EDIT returns true; a READ-only, malformed or null token returns false.
  8. `buildAnalysisRecord` and `toSummary`: verdict narrowing, score clamp, and a summary with top-level keys only, an integer deviation and the deviation omitted when null.
  9. `verdictBadgeClass` and `priceBadgeLabel` (one `test.each`): success/plain/danger per verdict, ABOVE/BELOW danger only at |deviation| ≥ 30; labels "ABOVE MARKET +46%", "FAIR −2%" with a Unicode minus, "INSUFFICIENT DATA" without a sign.
  10. `toAnalysisSummary` and `reviewReasons`: `null` for `{}`, a missing or non-boolean `needsReview`; a valid summary maps through; reasons follow the fixed order and are joined with " · ", and non-flagging verdicts add nothing.
- **Route tests, `src/__tests__/analyze.test.ts` (up to 8 tests).** Use `node-mocks-http`. Mock `../../baml_client` and `../../../server-sdk` with hoisted `jest.fn`. Build BAML errors from `@boundaryml/baml/errors`, the same module the route imports, so no native module loads. JWT fixtures are unsigned base64url tokens with a `permissions` claim.
  1. GET returns 405 with `Allow: POST`.
  2. Missing or `"abc"` productId returns 400.
  3. A token without EDIT returns 403, and neither `getProduct` nor BAML is called.
  4. A host 404 on `getProduct` returns 404 `PRODUCT_NOT_FOUND`, and BAML is not called.
  5. The happy path returns 200.
     - `save` is called with `("analysis", "42", record, {entityType: "PRODUCT", entityId: "42"})`, and the record includes `fingerprint`, `analyzedAt` and `model`.
     - `setData` is called with the summary.
     - `getProducts` is called with the assigned category id; the peers passed to BAML exclude the product itself (mocked host products use numeric ids), and prices are formatted `USD x.xx`.
  6. A `BamlClientHttpError` with status 400 returns 502 `CONTENT_BLOCKED`, no raw detail in the body, and `save` is not called (amended — see Spec deviations): the 400 must carry a guardrail marker, and the sanitized log line is asserted.
  7. One `test.each` for the classification order: `BamlAbortError` and `BamlTimeoutError` (a `BamlClientHttpError` subclass with status 408) both return 502 `LLM_TIMEOUT`; a generic BAML error returns 502 `LLM_UNAVAILABLE`. Amended rows: a non-guardrail 400 and a 400 that only says "blocked" also return `LLM_UNAVAILABLE`.
  8. An LLM suggestion that is not in the category list is stored as `suggested: null`.
- **Host regression:** `./mvnw test` must pass with the 013 seed applied (dev context). No new Java tests.
- **Not covered by Jest:** UI pages (including the visibility gate and the one-attempt auto-run rule), which are covered by the live and E2E verification against the mockups, and real LLM calls (the BAML test block plus the LiteLLM verification). The pure helpers those pages use are covered by domain tests 9–10.

### Documentation Updates (FR9)

**Plugin README (`plugins/ai-offer-analysis/README.md`)** covers:
- Purpose and architecture (the data flow above).
- Setup: `cp .env.example .env`, `npm install` (which runs `baml-cli generate`), `npm run dev`.
- Registration: automatic through the 013 dev seed. Alternative: log in with `POST /api/auth/login` as the admin user (token in the `token` field), then `PUT /api/plugins/ai-offer-analysis/manifest -d @manifest.json` with a Bearer token that has `PLUGIN_MANAGEMENT`.
- **Manifest changes:** the seed uses `ON CONFLICT DO NOTHING` and never updates an existing row, so after editing `manifest.json` re-register with the same curl `PUT .../manifest` command.
- When the analysis runs: only when an editor actually opens the tab (not on product page load), only for a new or changed product, and a failed automatic run is not repeated until Retry.
- Running the tests.
- The LiteLLM verification procedure (below).
- Known issues and limitations: the server-sdk 204 bug, Presidio masking, LLM nondeterminism (fictional brands may get INSUFFICIENT_DATA), the `setData`-after-save partial failure, and the staleness scope: the fingerprint covers only the product's own fields and its category id/name, so adding or renaming other categories or repricing peers does not trigger a re-analysis (use Re-analyze).

**Root `README.md`:**
- Add `ai-offer-analysis/` to the tree.
- Update the port line to "shipping 3010, ai-offer-analysis 3011, next business module 3012".
- Add `cp plugins/ai-offer-analysis/.env.example plugins/ai-offer-analysis/.env` to Environment Setup.
- Add `(cd plugins/ai-offer-analysis && npm install)` to Quick Start.

**Other files:**
- **Root `CLAUDE.md`:** add `ai-offer-analysis` (:3011, Next.js + BAML, Jest, needs `.env` plus `litellm/.env`) to the Commands plugin list. The Docker Compose AI tooling sentence now names both AI plugins.
- **`plugins/CLAUDE.md`:** add an "AI Offer Analysis Plugin" reference-plugin entry (Next.js, BAML via LiteLLM, plugin objects plus a `setData` summary, info badge, boolean filter, BroadcastChannel). Update the port rule sentence.
- **`.maister/docs/project/tech-stack.md`:** add a plugin table row `ai-offer-analysis` | Next.js 15 + BAML 0.220 (LLM via LiteLLM) | 3011. Update the port rule. The testing line becomes "ai-description and ai-offer-analysis plugins: Jest 29".
- **`.maister/docs/standards/plugins/plugin-development.md` (Fixed Dev Ports):** add ai-offer-analysis 3011; the next business module becomes 3012.
- **`.maister/docs/standards/global/conventions.md`:** add `plugins/ai-offer-analysis/.env` to the env-file list and its copy command.
- **`.maister/docs/INDEX.md`:** update the tech-stack plugins summary and the plugin-development port list to match.

### LiteLLM Verification Procedure (acceptance for FR10)

**Preconditions**
- `litellm/.env` holds a real `OPENAI_API_KEY` (amended — see Spec deviations): it now holds an OpenRouter key.
- `docker compose up -d` is running, with litellm, litellm-db, langfuse (+ worker, ClickHouse, MinIO, Redis) and Presidio healthy.
- The host runs on :8080 with the dev context, so the 013 seed is applied.
- The plugin has `.env` and is started with `npm run dev` on :3011.

**Ground truth.** The number of LLM calls is the number of `[ai-offer-analysis] llm-call` lines the route printed in the plugin's `npm run dev` terminal during the run (the browser network tab's `POST /api/analyze` count is a cross-check, but it also includes requests rejected before BAML). Expected LiteLLM entries:
- each `outcome=ok` or `outcome=LLM_INVALID_OUTPUT` line → exactly one success entry;
- each `outcome=LLM_TIMEOUT` line → zero or one entry (LiteLLM may finish and log a call the plugin aborted at 45 s); exclude these from the equality check and note them;
- `outcome=CONTENT_BLOCKED` / `LLM_UNAVAILABLE` lines → no success entry (they may show as failures in the UI).
In a normal run every line is `outcome=ok`, so the check is simple equality.

**Step 0 — prepare the shell and take a baseline (before any traffic).**
- Load the master key from `litellm/.env` into the shell (it is not exported by default; `sk-litellm-dev-key` is the dev default):
  - bash: `export LITELLM_MASTER_KEY=$(grep -E '^LITELLM_MASTER_KEY=' litellm/.env | cut -d= -f2-)`
  - PowerShell: `$env:LITELLM_MASTER_KEY = ((Get-Content litellm/.env | Select-String '^LITELLM_MASTER_KEY=').Line -split '=', 2)[1]`
- Record the start time (UTC) for the report and the UI filter, and fix a date window:
  - bash: `START=$(date -u +%Y-%m-%dT%H:%M:%SZ); FROM=$(date -u +%Y-%m-%d); TO=$(date -u -d tomorrow +%Y-%m-%d)` (GNU date, as in Git Bash)
  - PowerShell: `$start = [datetime]::UtcNow; $from = $start.ToString('yyyy-MM-dd'); $to = $start.AddDays(1).ToString('yyyy-MM-dd')`
- Run the count command below once and write the number down as the **baseline**. Earlier ai-offer-analysis runs today are part of it, so the delta after step 1 isolates this run.
- **Count command** (`summarize=false` returns individual rows for the date window; if an older LiteLLM returns an aggregated object instead of a list, drop the three date/summarize parameters, the baseline still isolates the run):
  - bash with jq: `curl -s -H "Authorization: Bearer $LITELLM_MASTER_KEY" "http://localhost:4000/spend/logs?start_date=$FROM&end_date=$TO&summarize=false" | jq '[.[] | select((((.request_tags // []) | index("ai-offer-analysis")) != null) or .end_user == "ai-offer-analysis")] | length'`
  - bash without jq: use the PowerShell variant or the LiteLLM UI filter (step 2); `jq` is optional.
  - PowerShell: `$logs = Invoke-RestMethod -Headers @{ Authorization = "Bearer $env:LITELLM_MASTER_KEY" } -Uri "http://localhost:4000/spend/logs?start_date=$from&end_date=$to&summarize=false"; $hits = @($logs | Where-Object { ((@($_.request_tags) -join ',') -like '*ai-offer-analysis*') -or $_.end_user -eq 'ai-offer-analysis' }); $hits.Count`
  - To list details: bash `... | jq '.[] | select(...) | {startTime, model, spend, total_tokens, end_user, request_tags}'`; PowerShell `$hits | Select-Object startTime, model, spend, total_tokens, end_user, request_tags`.

**Steps**
1. **Trigger traffic.** Clear the plugin terminal (or note its last line) first.
   - Log in as `editor`. Open the product `Vistara 65" OLED 4K TV` and stay on the default "Details" tab: **no** `llm-call` line appears (the hidden tab does not auto-run), and the badge shows "not analyzed yet".
   - Click the "AI Offer Analysis" tab: the auto-run starts now and should show "Needs review" (at least the POOR description and the category MISMATCH). Switch back to "Details": the info badge (which lives in the Details panel) shows the new verdict without a reload. Switch to the AI tab again: no new `llm-call` line.
   - Open `LG C4 65" OLED evo 4K Smart TV` and click the tab: expect "Looks good"; the peers row reads "Televisions, 2" (amended — see Spec deviations): the compact FAIR line shows only the peer numbers.
   - Open `Skylink AX3000 ...` and click the tab: expect "Needs review" with at least the category MISMATCH (suggests Networking); a BELOW_MARKET price flag is expected but not required.
   - Click Re-analyze once.
   - On the product list, switch on "AI: needs review": both demo products are listed.
   - Log in as `viewer` and open an analyzed product's tab: no new `llm-call` line.
   - Expected on a fresh dev database: 4 `llm-call` lines (3 auto-runs + 1 Re-analyze), all `outcome=ok`. A product that already has a current analysis from an earlier run shows it without a call, so the ground truth is always the actual `llm-call` lines, not this number.
2. **LiteLLM UI** at `http://localhost:4000/ui` (username `admin`, password the `LITELLM_MASTER_KEY`, `sk-litellm-dev-key` by default), Logs page, entries after the recorded start time:
   - One entry per `llm-call` line (per the ground-truth rules above), model `gpt-4o-mini`, status success.
   - Request tags contain `ai-offer-analysis` and/or end user `ai-offer-analysis`.
   - The stored prompt shows the product text with `USD` prices (not masked); the response is JSON matching `OfferAnalysis`.
3. **Spend logs API.** Run the count command again. **Delta = new count − baseline** must equal the expected entries from the ground-truth rules (4 for the script above on a fresh database). Note the `model`, `spend` and `total_tokens` values of the new rows. If the delta is 0 although `llm-call` lines exist, attribution is not recorded: go to step 6.
4. **Langfuse** at `http://localhost:3100` (`admin@aj.local` / `admin123`), project "AJ Plugins", Traces: the traces carry the `ai-offer-analysis` tag or trace name, with input and output for each generation.
5. **No bypass.**
   - The plugin config holds only `LITELLM_API_KEY` and the `:4000` base_url; a grep of the plugin for `api.openai.com` or provider keys returns nothing.
   - Optional: stop the `litellm` container, click Re-analyze, and confirm the tab shows "The AI gateway is unavailable…" (502). Then restart it.
6. **Attribution fallback.** If step 2 or 3 shows no tag and no end user, apply the `x-litellm-tags` header fallback, repeat steps 1–3, and record which mechanism works in the plugin README.
7. **Record results** in the task's verification report: start time, `llm-call` line count and outcomes, baseline and delta, tag and end-user evidence, Langfuse trace ids or screenshots.

### Standards Compliance
- **`standards/plugins/plugin-development.md`:**
  - `<id>-plugin` naming, version matches the manifest.
  - Shared `../../sdk` and `../../server-sdk` imports via `externalDir`.
  - Mandatory `plugin-ui.css` with `tc-*` classes and layout-only inline styles.
  - Manifest is the source of truth.
  - Storage choice: objects for the full record, `setData` for the filter/badge summary.
  - Fixed port 3011 with `-p` on dev and start.
  - try/catch around every SDK call, with errors shown.
- **`standards/backend/plugin-auth.md`:** `getToken`, then the plugin backend via `createServerSDK(id, undefined, req)`, with permission checking from the JWT `permissions` claim, using the base64url-safe variant.
- **`standards/global/error-handling.md`:** typed classification and clear user messages; raw details are logged server-side only.
- **`standards/global/validation.md`:** server-side validation of `productId`, allow-list narrowing of LLM verdicts, and category validation against the real list.
- **`standards/global/conventions.md`:** `.env` from a committed `.env.example`; provider keys only in `litellm/.env`; course-material folders untouched.
- **`standards/global/minimal-implementation.md`:** no bulk page, no virtual keys, no server-sdk fix, no BAML fallback or retry wrapper, a single object type, and a summary without unused fields.
- **`standards/backend/migrations.md`:** `db/changelog/2026/013-<verb>-<description>.yaml`, dev context for sample data, rollback blocks. No schema change, so no jOOQ regeneration is needed.
- **`standards/frontend/components.md` and `css.md`:** named function components (default export only because Next.js requires it), no new hex colors, `import type` for types.
- **`standards/frontend/accessibility.md`:** the verdict text accompanies the badge color, `aria-live` status, a labelled progress element.
- **`standards/testing/backend-testing.md`:** the host suite stays green with the seed.
- **Frontend testing:** the plugin follows the existing Jest pattern of the ai-description plugin; the host Vitest conventions do not apply to Next.js plugins.

## Out of Scope
- Host Java or host frontend changes, and any new host event types.
- A bulk or "analyze all" page and a `menu.main` entry.
- Fixing the shared `plugins/server-sdk.ts` 204 bug, or ai-description's own defects.
- Per-request or per-user LiteLLM metadata, virtual LiteLLM keys, and LiteLLM or Presidio config changes (amended — see Spec deviations): the user approved the `gpt-4o-mini` upstream mapping change.
- Currency support beyond the USD assumption, and external price APIs.
- UI component tests for the Next.js pages (covered by live and E2E verification).
- Deleting analyses or cleaning up on product deletion.
- Re-analysis triggered by changes outside the product's own fingerprint (adding or renaming other categories, repricing peers). Known limitation, documented in the README; Re-analyze covers it.

## Success Criteria
- `cd plugins/ai-offer-analysis && npm install && npm test` passes on Windows and bash, with up to 18 tests across the 2 suites (domain ≤ 10, route ≤ 8). `npm run build` type-checks and builds.
- `./mvnw test` passes with the 013 seed applied. On a fresh dev database the plugin row and both demo products exist, and re-running Liquibase is a no-op. On a dev database where a demo product's category was renamed or deleted, startup still succeeds and that demo product is skipped.
- `npm run dev` serves on :3011. The plugin appears on product detail as the "AI Offer Analysis" tab and the "AI Review" info badge, and as an "AI: needs review" switch on the product list.
- **Editor:** opening a product page without opening the tab makes no `/api/analyze` call. The first time the editor opens the tab for a new or changed product, exactly one analysis auto-runs. Re-opening the tab or the page without content changes does **not** call the LLM (no new `llm-call` line, no new LiteLLM entry). Editing name, description, price, SKU or category, or renaming the assigned category, triggers exactly one new auto-run the next time the tab is opened. A failed auto-run is not repeated in the same page session; Retry runs it again.
- **Viewer:** never triggers `/api/analyze`; sees the saved result with a stale note, or the "Not analyzed yet" card.
- Each demo product shows "Needs review" with at least its strongly cued flag (TV: POOR description and/or category MISMATCH; Skylink: category MISMATCH), with the reasons visible in the tab and the badge. Peer rows show the assigned category with the product itself excluded. The badge refreshes after an in-tab run without a page reload, and the list filter returns the flagged products.
- Error states show only the sanitized messages from the contract table. No raw LiteLLM or host error text reaches the browser.
- **LiteLLM verification passes:** the delta of tagged `/spend/logs` rows over the recorded baseline equals the expected count derived from the route's `llm-call` lines (aborted calls excluded), each entry is attributed to `ai-offer-analysis` by tag or end user and visible in the UI; prompts show unmasked `USD` prices; Langfuse shows the matching traces. The results are recorded in the verification report, and the procedure (bash and PowerShell variants) is documented in the plugin README.
- All docs listed under Documentation Updates mention the plugin and port 3011.

## Spec deviations (Phase 11 fix cycle 1)
These changes came out of implementation verification (fix cycle 1 and the fix round after re-verification). Where they conflict with the text above, they win and the code is the reference. Inline text that still states the old contract is marked "(amended — see Spec deviations)".

**(a) Product type pricing (`pricedAs`).**
- `PriceAssessment` gets `pricedAs: string` as its first field: the product type or class that was priced, never the catalog peers. `reasoning` now comes before `verdict` and the numeric fields, so the model names the type and reasons before it gives numbers. The min/max descriptions refer to "the product type in pricedAs".
- Prompt Task 3: identify the type from the product's own name and description first, then estimate the range for that type from general market knowledge. The range "must NOT be derived from the catalog peer prices". Peers are relabelled "Catalog context (... may be a different product type)", come after the range instruction, and must be ignored for both the range and the verdict when they are a different type.
- The BAML test block `VistaraDemoOffer` asserts `estimatedMarketMinUsd > 500` (`@@assert tv_market_range`).
- The stored price record carries `pricedAs` (trimmed; `""` when missing, which is also how older records read). The tab shows a 12px "Priced as: {pricedAs}" line in the price section when it is non-empty.
- Why: in the live run the Vistara TV was priced against its Smart Home peers (ABOVE_MARKET +1117%). After the fix it is priced as "65-inch OLED 4K TV", FAIR +12%.

**(b) Peer-contamination guard (`marketRangeUnreliable`).**
- Condition, in `buildAnalysisRecord`: the category verdict is MISMATCH, peer stats exist, both bounds are non-null, and the LLM range sits inside the peer range ±5% (`min ≥ peers.min × 0.95` and `max ≤ peers.max × 1.05`).
- Effect: `deviationPct` is `null`, `confidence` is forced to `LOW`, and `marketRangeUnreliable` is `true`. The LLM's price verdict and `marketMin`/`marketMax` are stored unchanged. With a null deviation the price cannot raise `needsReview` (MISMATCH already does). Otherwise `marketRangeUnreliable` is `false`; `toOfferAnalysis` reads a missing flag as `false`.
- UI: when the flag is true, the compact line says "market range unreliable" and the detailed table's "Estimated market range" cell says "unreliable" instead of the numbers. A 12px note reads "Market estimate unreliable — it matched catalog peers from a different category."
- This is a code-side override of the LLM's confidence, a narrow exception to "Verdicts from the LLM, decisions in code".
- Known limits: it may null a genuine range for a mismatched product whose real market range happens to fall inside the peer range (for example a USD 300 soundbar filed under Smart Home with peers at 100–450). QUESTIONABLE category verdicts, and ranges that only overlap the peer range, are not covered. The price verdict badge is still the LLM's.

**(c) CONTENT_BLOCKED only for guardrail rejections.**
- `CONTENT_BLOCKED` requires a `BamlClientHttpError` with `status_code` 400 **and** a guardrail marker (`/guardrail|presidio|blocked pii|pii entit/i`) in `message` or `raw_response`. The markers match LiteLLM's Presidio block message, "Blocked PII entity detected: <TYPE> by Guardrail: <name>".
- `detailed_message` is not scanned, because it may carry request context such as product text.
- Every other 400 (unknown model, context overflow, a parameter that is "not allowed", a generic "blocked") maps to `LLM_UNAVAILABLE`. Why: LiteLLM also answers 400 for configuration errors.

**(d) Failure log format.**
- `console.error` writes one sanitized line: `[ai-offer-analysis] analyze failed productId=<id> stage=<stage> code=<code> error=<name>[ status=<http status>] message="<message>"`. The message has its whitespace collapsed and is cut to 200 characters.
- The error object, the prompt and the raw LLM output are never logged, because BAML errors carry product text that Presidio has not yet masked.
- Known limit: the 200 characters may still include the start of the LLM output or of a host response body. This is the local server log only.

**(e) `BAML_LOG=warn`.**
- The plugin `.env.example` sets `BAML_LOG=warn` so BAML does not echo prompts and responses (unmasked product text) to stdout. The plugin `.env` holds `LITELLM_API_KEY`, plus the optional `HOST_BASE_URL` and `BAML_LOG=warn`.
- Prompts are inspected in the LiteLLM spend logs (`proxy_server_request`, kept by `store_prompts_in_spend_logs: true`) and in Langfuse traces, not in the plugin log.

**(f) LiteLLM config change (overrides Out of Scope).**
- In `litellm/config.yaml`, `gpt-4o-mini` now maps to `openrouter/openai/gpt-4o-mini`, with the key read from `OPENAI_API_KEY`. That variable now holds an OpenRouter key, per the user's decision.
- The alias is shared by ai-description and ai-offer-analysis. Clients keep calling the logical name `gpt-4o-mini`. Presidio and the rest of the config are unchanged.
- This is a user-approved exception to the Out of Scope entry "LiteLLM or Presidio config changes". `litellm/.env.example`, the root README, `conventions.md` and the plugin README document it.

**(g) Earlier accepted deviations (implementation phase, confirmed by the completeness check).**
- An "Invalid categories payload" (a non-array `/api/categories` body) maps to `HOST_ERROR`, like an invalid product payload. The route matches `^Invalid \w+ payload$`.
- Re-analyze is hidden in the error state. The error card's Retry is the only run button there.
- A "Loading analysis…" state is shown while the initial host GETs run.
- The compact FAIR price line shows the peer numbers ("· peers min / median / max") without the "(Televisions, 2)" label. The category and count label appears only in the detailed table.

## Spec Audit Resolution
Findings from `verification/spec-audit.md` and how this revision resolves them.

| ID | Finding | Resolution |
|---|---|---|
| M1 | Hidden tab iframes auto-run on product page load | User decision: auto-run only when the tab iframe is visible (non-zero viewport, else wait for `resize`); saved analysis still loads at mount; one auto-run attempt per iframe session, no automatic repeat after an error. Updated TL;DR, Key Decisions, user stories, FR4, Tab behaviour (Auto-run visibility gate), verification step 1 and success criteria. The viewer copy "An editor needs to open this tab…" is now accurate. |
| M2 | TV demo peers contradict its seeded category | Peers are always from the assigned category, self excluded (Key Decisions, FR3, Domain functions, API step 6). Demo table shows the real peer values; the mockup peer numbers are marked illustrative in Visual Design; the BAML test block args are fully specified (Smart Home, 3, USD 179.99 / 179.99 / 279.99); the prompt tells the LLM to ignore non-comparable peers so the TV's price flag comes from the market estimate. |
| M3 | LiteLLM count check not executable | Verification rewritten: key loaded from `litellm/.env`, recorded start time, `start_date`/`end_date`/`summarize=false` window, baseline/delta counting, bash (curl + optional jq) and PowerShell (`Invoke-RestMethod`) variants, the route's `llm-call` log line as ground truth, timeouts excluded from the equality. |
| L1 | Error-classification order unspecified | Explicit order in the API contract: abort/timeout first, then HTTP 400, then validation/finish-reason, other BAML, host, INTERNAL. Route test 7 covers `BamlTimeoutError`. |
| L2 | Error-class import loads the native module | The route imports from `@boundaryml/baml/errors`. |
| L3 | Self-exclusion type-unsafe | `String(p.id) === String(self.id)`; test fixtures use numeric host ids. |
| L4 | INSUFFICIENT_DATA wording vs fictional brands | Enum and prompt say INSUFFICIENT_DATA only when the product type cannot be identified; unknown brands are priced against comparable products; "product" used consistently. Demo expectations require only a strongly cued flag per product (Skylink moved to Home Appliances to get a MISMATCH cue). |
| L5 | Scalar category subquery can block startup | Demo insert uses `INSERT ... SELECT ... FROM (VALUES ...) s INNER JOIN categories c ON c.name = s.category_name ON CONFLICT (sku) DO NOTHING` (unique `uk_products_sku`). |
| L6 | UI ambiguities | Per-section layout (compact for GOOD/FITS/FAIR, detailed otherwise); Re-analyze disabled, not hidden, while running; prompt peers = up to 10 closest in price, listed ascending (`selectPromptPeers`); error-state subtitle only with a previous analysis, mockup superseded. |
| L7 | UI domain helpers untested | Domain tests 9–10 cover `verdictBadgeClass`, `priceBadgeLabel`, `toAnalysisSummary`, `reviewReasons`; domain suite cap raised to 10. |
| L8 | Staleness scope narrower than inputs | Documented as a known limitation (README, Out of Scope); Re-analyze covers it. |
| L9 | Manifest duplicated in the seed | README and seed section say to re-register with the curl `PUT .../manifest` after manifest changes. |
