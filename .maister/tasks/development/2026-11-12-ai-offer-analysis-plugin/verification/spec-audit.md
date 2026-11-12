# Spec Audit: AI Offer Analysis Plugin (pre-implementation)

## TL;DR
- **Verdict: pass with concerns** (Mostly Compliant). The spec covers FR1–FR10 and can be built against the current code with no host changes. Every load-bearing claim about the host, SDKs, BAML 0.220 and Jest was checked against real code or by running it.
- **Counts:** Critical 0 · High 0 · Medium 3 · Low 9.
- **Medium 1:** the host mounts every plugin tab iframe (hidden) as soon as the product page loads. "Auto-run on tab open" is really "auto-run on product page load".
- **Medium 2:** the TV demo's expected peers (Televisions prices) contradict its seeded category (Smart Home).
- **Medium 3:** the LiteLLM count check in the verification procedure can't be run as written.

## Key Decisions
- **BAML metadata question narrowed.** It is now only about LiteLLM, not BAML. Building the request offline with `BamlRuntime.buildRequest` (no network) on the real `@boundaryml/baml` 0.220.0 shows the proposed client sends `"temperature":0.1`, `"user":"ai-offer-analysis"` and `"metadata":{"tags":["ai-offer-analysis"],"trace_name":"ai-offer-analysis","generation_name":"AnalyzeOffer"}` in the body. It sends `x-litellm-tags` as an HTTP header when `headers {}` is set. Whether LiteLLM `main-stable` records these as `request_tags`/`end_user` is the only part still unverified.
- **No High finding.** None of the issues breaks core functionality. Each has a cheap spec-level fix, and the deterministic code rules (fingerprint, `needsReview`) are sound.
- **Hidden-iframe auto-run rated Medium, not High.** The behaviour is arguably more "automatic", which the course wants. The cost is wording, token spend and verification counting, not correctness.
- **Template defects confirmed and handled.** The template's test suite fails 2 of 8 (raw error leak; 404 vs 500). Its `atob` decode, caret BAML version and `.env.local` wording are real. The spec explicitly avoids each one.

## Open Questions / Risks
1. **Auto-run trigger.** Should the docs and copy say "runs when an editor opens the product page" (current host behaviour)? Or should the tab defer the run until it is actually visible (e.g. `document.visibilityState` / IntersectionObserver)? The second reduces token spend but means the badge stays "not analyzed yet" until the tab is clicked.
2. **TV demo peers.** Should they come from its assigned category, Smart Home (179.99 / 179.99 / 279.99), as the spec's algorithm says? Then the spec table, the needs-review mockup and the BAML test block args must be corrected. The alternative is to seed the TV with a different flaw so peers make sense.
3. **LiteLLM attribution.** Does LiteLLM `main-stable` store body `metadata.tags` as `request_tags` in OSS spend logs? The fallback header is ready (BAML sends it), but which mechanism works is only known at live verification.
4. **Fictional brands.** "Vistara" and "Skylink" may push gpt-4o-mini to INSUFFICIENT_DATA. That never flags, so the Skylink "price flag" step could fail and the demo would show "Looks good".

---

## 1. Scope of the audit

**Mode:** pre-implementation. No plugin code exists yet. `plugins/ai-offer-analysis/` and `013-*.yaml` are absent.

**Inputs read:**
- `implementation/spec.md` (579 lines)
- `analysis/requirements.md`, `scope-clarifications.md`, `clarifications.md`, `gap-analysis.md`, `codebase-analysis.md`
- `design-context/INDEX.md`, `design-resources.md` and all 7 mockups (text and class extraction)
- Standards: `plugins/plugin-development.md`

**Code verified:**
- `plugins/ai-description/**`, including the generated `baml_client` and `node_modules/@boundaryml/baml` 0.220.0
- `plugins/server-sdk.ts`, `plugins/sdk.ts`, `plugins/shipping/src/domain.ts`, box-size badge, warehouse manifest
- Host frontend: `PluginFrame`, `PluginFilterBar`, `PluginMessageHandler`, `PluginContext`, `ProductDetailPage`, `ProductListPage`, the browser SDK `this-plugin.ts` / `messaging.ts` / `context.ts`, `plugin-ui.css`
- Host backend: `ProductController`, `DbProductQueryService`, `ProductResponse`, `PluginData*`, `PluginObject*`, `PluginDescriptor*`, `SecurityConfiguration`
- Changelogs 001–005, 008 and 012, `db.changelog-master.yaml`, `application.properties`, `src/test/**`
- `litellm/config.yaml`, `litellm/.env.example`, `presidio_recognizers.json`, `compose.yml`

**Executed checks (no repo files modified):**
- The template Jest suite, run via `node node_modules/jest/bin/jest.js`. It runs on Windows: 6 pass, 2 fail, the known defects.
- A `tsc --noEmit --incremental false` type-check of the template.
- The Next 15.5.14 `runTypeCheck` ignore rules.
- A BAML 0.220 offline `buildRequest` probe of the proposed `OfferAnalysisLlm` client.
- A Jest node-environment probe for `atob`, `AbortSignal.timeout`, `BroadcastChannel` and `@boundaryml/baml/errors`.
- A class-identity check between `@boundaryml/baml` and `@boundaryml/baml/errors`.

## 2. Claims verified as correct (evidence)

| Spec claim | Evidence |
|---|---|
| Host renders `product.detail.info` as a 60px iframe and plugin tabs in a 600px panel | `ProductDetailPage.tsx:186-197` (`h="60px"`), `:201-219` (`h="600px"`) |
| `productId` is a number at runtime | `ProductDetailPage.tsx:193,214` pass `contextData={{ productId: product.id }}`; `context.ts` passes it through unchanged |
| Boolean list filter is native and needs the plugin **enabled** | `PluginContext.tsx` `resolveExtensionPoints` skips `!plugin.enabled`; `PluginFilterBar.tsx` builds `${pluginId}:${filterKey}:bool:true` only when switched on |
| `pluginFilter=ai-offer-analysis:needsReview:bool:true` works | `DbProductQueryService.parseFilter`: the pluginId regex allows `-`; `bool` gives `(plugin_data->'id'->>'key')::boolean = true`; rows with no node are excluded |
| Unregistered or disabled plugin → 404 on data/objects | `PluginDataService`/`PluginObjectService` → `findEnabledOrThrow` → `EntityNotFoundException` → 404 (`GlobalExceptionHandler`) |
| `getData` with no node returns `{}` | `PluginDataService.getData` returns `Map.of()` |
| Save without binding clears it | `PluginObjectService.save` sets `entityType`/`entityId` unconditionally |
| `setData` bumps `updatedAt` (`@Version`), so fingerprint staleness is required | `PluginDataService.setData` → `saveAndFlush`; `PluginDescriptor`/`BaseEntity` use `@Version LocalDateTime` |
| Permissions: product/category/objects/data GET need READ; PUT objects/data need EDIT | `SecurityConfiguration.java` READ and EDIT matcher blocks |
| JWT `permissions` claim holds `EDIT` (not `PERMISSION_EDIT`) | `JwtTokenProvider.java:38` |
| Seed users `viewer`/`editor`/`admin` exist in dev | `008-create-users-table.yaml:90-151` |
| `INSERT ... ON CONFLICT (sku)` is valid; `product_seq` exists | `002-create-products-table.yaml` (`product_seq`, `uk_products_sku`) |
| Category names Smart Home / Networking / Televisions exist (context-free) | `003-insert-sample-data.yaml:26`, categories changeset has no context |
| Dev context loads in host tests | `application.properties:11` `spring.liquibase.contexts=dev`; `src/test/resources` has no override |
| No test collisions | No test references `vistara`/`skylink`/`AOA-`/`ai-offer`/"Smart Home"/"Networking"; product-list assertions are `≥` or scoped to test-created categories; plugin-list tests use `hasItem`/`not(hasItem)` only |
| BAML `signal` call option exists in 0.220 | `ai-description/baml_client/async_client.ts:42-49,106-109,147` |
| BAML error classes exist; `/errors` loads no native module | `@boundaryml/baml/errors.d.ts`; `errors.js` header comment; `@boundaryml/baml` and `/errors` export the identical class objects (checked in Node) |
| BAML forwards `temperature`/`user`/`metadata` in the body and `headers` as HTTP headers | Offline `buildRequest` probe. Body: `{"model":"gpt-4o-mini","temperature":0.1,"user":"ai-offer-analysis","metadata":{"tags":["ai-offer-analysis"],"trace_name":"ai-offer-analysis","generation_name":"AnalyzeOffer"}}`. Headers include `x-litellm-tags: ai-offer-analysis` |
| Windows-safe Jest command works | The template suite ran with `node node_modules/jest/bin/jest.js` |
| `npm run build` type-check does not trip on Jest globals in `src/__tests__` | Next 15.5.14 `runTypeCheck.js:60-67` filters diagnostics under `__tests__`. Note that a plain `tsc` does fail on them, because there is no `@types/jest` |
| `server-sdk` `removeData`/`delete` throw on 204 | `server-sdk.ts` `hostFetchJson` always calls `res.json()` |
| `canEdit` is base64url-safe and needs `isRecord` | `shipping/src/domain.ts:47,104-114` |
| `plugin-ui.css` has no spinner or warning badge | Classes present: `tc-badge`, `--success`, `--danger`, `tc-card`, `tc-section`, `tc-flex`, `tc-table`, `tc-error`, buttons, inputs, `tc-plugin` |
| LiteLLM UI login, Langfuse credentials and project name | `litellm/.env.example` (`LITELLM_MASTER_KEY=sk-litellm-dev-key`); `compose.yml:133-141` (`admin@aj.local`/`admin123`, "AJ Plugins") |
| `store_prompts_in_spend_logs`, Presidio BLOCK/MASK, PESEL `\b\d{11}\b` | `litellm/config.yaml`, `presidio_recognizers.json` |
| Priorities follow existing items | shipping tab 80, box-size info 20, warehouse filter 10 (manifests) |

## 3. Medium findings

### M1. Auto-run fires on product page load, not on "tab open"
- **Spec reference:**
  - User story (line 29): "when I open a product's 'AI Offer Analysis' tab".
  - FR4 (line 51).
  - Mockup copy (line 77): "An editor needs to open this tab…".
  - Success criteria (line 574): "the first open auto-runs one analysis".
  - Verification step 1 (lines 521-526).
- **Evidence:**
  - `src/main/frontend/src/pages/ProductDetailPage.tsx:201-219` reads "Plugin tab panels - render all, show/hide with CSS to preserve iframe state". Every plugin tab `PluginFrame` is mounted with `display:none` as soon as the detail page renders.
  - `PluginFrame.tsx` sets `iframe.src` on the first effect, so the hidden tab page loads, gets the token and auto-runs immediately.
- **Category:** Incorrect assumption about existing code. The behaviour is acceptable, but the spec describes it wrongly.
- **Impact:**
  - Any editor visit to a never-analyzed or changed product's detail page spends one gpt-4o-mini call, even if the tab is never opened. Browsing 16 seeded products costs 16 calls.
  - A persistent failure such as `CONTENT_BLOCKED` re-fires on every editor page view.
  - The "Running" UI is usually never seen.
  - The verification count (M3) drifts.
- **Severity:** Medium. Token spend and acceptance wording are affected; correctness is not.
- **Recommendation:** Pick one.
  - (a) Accept it. Reword the user story, the viewer card copy ("An editor needs to open this product…") and the success criteria. Add a README note on token spend and repeated failures.
  - (b) Defer the auto-run until the tab document is visible. Listen for `visibilitychange`; shipping already uses `focus`/`visibilitychange`, per `plugins/CLAUDE.md:405`. Then specify that the badge stays "not analyzed yet" until the tab is opened.

### M2. TV demo peers contradict its seeded category
- **Spec reference:**
  - Seed table (line 439): TV in **Smart Home**, "price ABOVE_MARKET (peers 1299.99 / 1799.99 / 2199.99)".
  - Peer algorithm (lines 274-279, 337): same-category peers via `getProducts({category: snapshot.category.id})`.
  - BAML test block (line 243): "uses the demo TV product … and the 5 seeded categories", with no peer arguments specified.
  - Binding mockup `analysis-tab-needs-review-editor.html`: "Catalog peers (Televisions, 3) … 1 299.99 / 1 799.99 / 2 199.99" next to "Assigned category Smart Home".
- **Evidence:**
  - `003-insert-sample-data.yaml:131-165`: the Smart Home products are Echo Hub 179.99, Hue 179.99 and Nest 279.99.
  - The peers the route will actually compute for the TV are `Smart Home, 3, 179.99 / 179.99 / 279.99`.
  - Also, `analysis-tab-looks-good.html` shows LG C4 peers as `1 299.99 / 1 799.99 / 2 199.99`, which **includes the product itself**. With the exclude-self rule (line 275) the correct values are `count 2, 1299.99 / 1749.99 / 2199.99`.
- **Category:** Contradiction between the spec and the binding mockup.
- **Impact:**
  - An implementer may try to match the mockup, for example by looking up peers from the suggested category. That requires a second LLM pass and is not in the spec.
  - The BAML test block args are under-specified.
  - E2E comparison against the "binding" mockup will report a false mismatch.
- **Severity:** Medium.
- **Recommendation:**
  - State explicitly that peers always come from the **assigned** category, so the TV shows Smart Home peers.
  - Mark the peer values in both mockups as illustrative.
  - Fix the seed-table text.
  - Specify the `VistaraDemoOffer` `peerStats`/`peers` args (Smart Home, 3, USD 179.99 / 179.99 / 279.99).

### M3. LiteLLM count check is not executable as written
- **Spec reference:**
  - Step 3 (line 531-533): "Run `curl … /spend/logs` … The count must equal the number of analyses triggered in step 1."
  - Success criteria (line 578): "every analysis has exactly one LiteLLM log entry".
- **Evidence:**
  - `GET /spend/logs` without `start_date`/`end_date` returns all historical rows, including earlier dev runs and template/ai-description traffic. The tag filter removes the latter, but not earlier ai-offer-analysis runs.
  - M1 adds runs for every editor product page visited while navigating.
  - An `AbortSignal.timeout` abort at 45 s cancels the BAML call. LiteLLM may still finish the upstream call and log it, giving a log entry with no saved analysis.
  - `$LITELLM_MASTER_KEY` is not set in the operator's shell; it lives only in `litellm/.env`.
  - `jq` is not guaranteed on Windows, which is the primary dev OS here.
- **Category:** Unexecutable as written.
- **Severity:** Medium. FR10 acceptance depends on it.
- **Recommendation:**
  - Record a start timestamp before step 1, then count entries with `startTime >=` that timestamp, or pass `start_date`/`end_date`.
  - Count POSTs to `/api/analyze` from the Next dev server log (or the browser network tab) as the ground truth.
  - Tell the operator to export the key (`export LITELLM_MASTER_KEY=sk-litellm-dev-key`, or `$env:LITELLM_MASTER_KEY=...`).
  - Give a PowerShell alternative: `Invoke-RestMethod … | Where-Object { $_.request_tags -contains 'ai-offer-analysis' -or $_.end_user -eq 'ai-offer-analysis' }`.
  - Exclude aborted runs from the equality check.

## 4. Low findings

- **L1. Error-classification order is unspecified.**
  - Spec: lines 355-357. Evidence: `@boundaryml/baml/errors.js:202-204`, where `BamlTimeoutError extends BamlClientHttpError` with `status_code` 408.
  - A route that checks `instanceof BamlClientHttpError` first will send timeouts to `LLM_UNAVAILABLE` (only status 400 is `CONTENT_BLOCKED`).
  - Recommendation: specify the order: `BamlAbortError` / `BamlTimeoutError` → `BamlClientHttpError(400)` → validation / finish-reason → other BAML → host → `INTERNAL`.
- **L2. The error-class import source defeats the stated test benefit.**
  - Spec: line 362. The route imports the classes from `@boundaryml/baml`, whose index loads `./native`, so `analyze.test.ts` loads the native addon anyway.
  - Both entry points export identical classes (verified), so the route can import from `@boundaryml/baml/errors`. That keeps Jest free of the native binary and lets `instanceof` keep working.
- **L3. Self-exclusion in `computePeerStats` is type-unsafe.**
  - Spec: line 275, "Drop the product with `id === self.id`". `self.id` is a string (line 262), but host product ids are JSON numbers (`ProductResponse.id: Long`). A raw `===` keeps the product itself.
  - Recommendation: specify `String(p.id) === self.id`, and use numeric ids in the test-4 fixture.
- **L4. INSUFFICIENT_DATA wording is inconsistent and interacts with fictional brands.**
  - Enum (line 193) says "product type or market cannot be identified". Task 3 (line 240) says "when the product cannot be identified".
  - A fictional brand ("Skylink AX3000") can be read as "product cannot be identified". The result would be INSUFFICIENT_DATA, no flag, and failure of verification step "Skylink: expect a price flag" (line 523).
  - Recommendation: make the prompt say to price unknown brands against comparable products with the same type and specs, and to reserve INSUFFICIENT_DATA for products whose type cannot be identified.
- **L5. The seed's scalar category subquery can block startup on existing dev databases.**
  - Spec: line 434, `(SELECT id FROM categories WHERE name = '<cat>')`. Evidence: `002` declares `category_id` NOT NULL, and 013 is a new changeset that runs on every existing dev DB at the next start.
  - If a developer renamed or deleted "Smart Home" or "Networking" (the spec's own success criteria involve renaming categories), the insert fails and Liquibase aborts app startup.
  - Recommendation: `INSERT … SELECT nextval('product_seq'), s.name, … , c.id … FROM (VALUES …) s(…) JOIN categories c ON c.name = s.cat ON CONFLICT (sku) DO NOTHING`. Rows whose category is missing are then skipped.
- **L6. Small UI ambiguities.**
  - The needs-review row (line 73) says "The three sections use the detailed layout", while the looks-good row (line 74) implies a per-section rule. Recommendation: always per-section, so GOOD/FITS/FAIR are compact regardless of the overall verdict.
  - "Re-analyze is hidden or disabled" (line 75): pick one.
  - `MAX_PEERS_IN_PROMPT` (line 182, 239): define which 10 are sent, e.g. closest by price or first by price ascending.
  - The error mockup shows the "Analyzed …" subtitle next to a "Not analyzed" badge. The spec's rule (subtitle only when an analysis exists; badge is the previous verdict) already resolves this. Mark the mockup as superseded on that point.
- **L7. Domain helpers that drive the binding UI are untested.**
  - Lines 296-300 define `toAnalysisSummary` (null when `needsReview` is not a boolean), `verdictBadgeClass`, `priceBadgeLabel` (Unicode minus, sign) and `reviewReasons` (ordering, " · " join). The UI pages have no Jest coverage (line 485).
  - Recommendation: fold them into existing `test.each` tables, staying within the 8-test cap.
- **L8. Staleness scope is narrower than the inputs.**
  - The fingerprint (line 267) excludes the category list and peer prices, which both feed the verdicts. Adding a "Televisions"-like category or repricing peers will not trigger a re-run.
  - This is acceptable by design. Add it to the README's known limitations next to the setData partial-failure note.
- **L9. Manifest duplicated in the seed.**
  - Line 431: the JSONB "must equal `manifest.json` … in meaning". Nothing enforces this, and after a manifest edit `ON CONFLICT DO NOTHING` will not update existing rows.
  - Recommendation: the README should say that manifest changes need the curl `PUT /manifest` re-registration, per the plugin-development standard's "re-register on changes".

## 5. Requirement coverage (FR1–FR10)

| FR | Covered in spec | Notes |
|---|---|---|
| FR1 plugin project / port / extension points | Yes (lines 37-40, 151-173, 410-424) | |
| FR2 BAML function + LiteLLM client | Yes (184-257) | Passthrough verified at the BAML side (Key Decisions) |
| FR3 `/api/analyze` | Yes (326-364) | L1, L2, L3 |
| FR4 tab | Yes (366-392) | M1, L6 |
| FR5 badge + BroadcastChannel | Yes (394-408) | The tab and badge iframes are both mounted for the page's lifetime, so the channel works even when the badge's panel is hidden (`ProductDetailPage.tsx:124,186`) |
| FR6 shared fingerprint | Yes (266-269) | cyrb53 → ≤ 2^53 → 14 hex digits, consistent |
| FR7 013 seed | Yes (426-448) | M2, L5, L9; test collision check confirmed |
| FR8 tests | Yes (461-485) | L7; Jest command and globals verified |
| FR9 docs | Yes (487-509) | Every target line exists: README:25,64; CLAUDE.md:39,63; plugins/CLAUDE.md:402; tech-stack.md:46,49,54,72; conventions.md:39,43; INDEX.md:20,109 |
| FR10 live verification | Yes (511-539) | M3, L4 |

No requirement was dropped. Deviations from `requirements.md` are explicit and justified:
- The summary leaves out `fingerprint?`.
- Model attribution is static.

## 6. Extra or out-of-scope items
- None that conflict with scope.
- The BAML `test` block, the `HOST_BASE_URL` env and the `x-litellm-tags` fallback are small and justified.
- The documentation set is wider than FR9 requires (INDEX.md, conventions.md); this is consistent with the standards-evolution rules.

## 7. Recommendations (in priority order)
1. Resolve M1 by choosing between accept-and-reword and defer-until-visible, then align the user stories, the viewer copy and the success criteria.
2. Fix M2: peers come from the assigned category. Correct the seed-table text, mark the mockup peer numbers as illustrative, and specify the BAML test block args.
3. Rewrite verification step 3 (M3) with a time window, an exported key and a PowerShell variant. Treat `/api/analyze` request logs as ground truth.
4. Add the error-classification order (L1) and the `@boundaryml/baml/errors` import (L2) to the API contract.
5. Specify `String(p.id)` self-exclusion (L3), and use an INNER JOIN for the seed category lookup (L5).
6. Tighten the INSUFFICIENT_DATA prompt wording for fictional brands (L4).
7. Optionally: test the UI helpers (L7), and add README notes for staleness scope (L8) and manifest re-registration (L9).
