# E2E Verification Report

## TL;DR
**Verdict: GO WITH CAVEATS.** 10 of 10 scenarios passed in the live browser; one passed with an issue. 0 Critical, 0 Major, 1 Minor and 2 Cosmetic findings.
Auto-run happens only when the tab is visible, runs once per session, and is not repeated after an error. The badge refreshes over BroadcastChannel, the "AI: needs review" filter works, and viewers cannot trigger a run.
I triggered exactly 2 real LLM calls (auto-run on product 2, Re-analyze on product 15), and the plugin log has exactly 2 `llm-call ... outcome=ok` lines.
Headline caveat: the running plugin still prints the full BAML prompt and LLM reply at INFO level, so `BAML_LOG=warn` (spec deviation (e)) is not in effect in this environment.

## Key Decisions
- I tested the error path with Playwright network stubbing of `POST :3011/api/analyze` (502 bodies copied from the spec's contract table) instead of breaking LiteLLM. This costs no LLM call and leaves the stack alone. It checks how the UI renders errors, not how the route classifies them; the Jest route tests cover classification.
- The auto-run running state (progress bar plus reason line) comes from the stubbed run (delayed 3 s). The live auto-run on product 2 finished before evidence could be captured. The live running state was captured on the manual Re-analyze run instead.
- Stored analyses for LG C4 (1) and Skylink (16) were opened behind a stub guard on `/api/analyze`. If they had been stale, no real call would have gone out. The guard recorded 0 hits, so both analyses were current.
- I used 2 of the 3 allowed LLM calls.

## Open Questions / Risks
- **BAML prompt echo in the plugin log (Minor, §5.3).** The plugin stdout contains `[BAML INFO] Function AnalyzeOffer` blocks with the full prompt (product text) and the raw LLM reply. Either the local plugin `.env` lacks `BAML_LOG=warn`, or the variable is not applied. I did not inspect `.env`, per the rules.
- The viewer "Product changed since this analysis…" stale note was not exercised: it needs a product edit, which would also cost an editor auto-run.

## 1. Identifier
- **Task**: 2026-11-12-ai-offer-analysis-plugin (AI Offer Analysis plugin, port 3011)
- **Task path**: C:\code\_repos\architekt-jutra\architekt-jutra-code\.maister\tasks\development\2026-11-12-ai-offer-analysis-plugin
- **Spec**: implementation/spec.md (including "Spec deviations (Phase 11 fix cycle 1)")
- **Date**: 2026-11-12
- **Git ref**: 7fc5eac (master; plugin files uncommitted in the working tree)
- **Tester**: e2e-test-verifier (maister)

## 2. Test Environment
| Field | Value |
|---|---|
| Base URL | http://localhost:8080 (host), plugin iframes at http://localhost:3011 (Next.js 15.5 dev server) |
| Browser | Playwright MCP, Chromium |
| Viewport | 2260×1120 CSS px (DPR 1.12); tab iframe 600 px high, badge iframe 60 px |
| Auth context | `viewer` (READ) for scenarios 4.6 and 4.7; `editor` (READ+EDIT) for all others; dev fixture logins |
| Test data | Live dev seed: products 1, 15 and 16 had stored analyses; 2 and 3 had none at the start |

## 3. Executive Summary
**Verdict**: ⚠️ GO WITH CAVEATS

| Metric | Count |
|---|---|
| Scenarios planned | 10 |
| Scenarios executed | 10 |
| Passed | 10 |
| Failed | 0 |
| Blocked | 0 |
| Pass rate | 100% |
| Critical issues | 0 |
| Major issues | 0 |
| Minor issues | 1 |
| Cosmetic issues | 2 |

Every user-facing behaviour in the spec that I exercised works in the live stack.
- **Visibility gate.** A product page visit on Details makes no `/api/analyze` call. Opening the tab auto-runs exactly once, and the result shows the "Priced as" line and the "Analyzed … · gpt-4o-mini via LiteLLM" subtitle.
- **Badge.** The info badge flips from "not analyzed yet" to "Looks good" without a reload.
- **Re-analyze.** The button is disabled while a run is in flight, and the result is then refreshed.
- **List filter.** The native "AI: needs review" switch sends `pluginFilter=ai-offer-analysis:needsReview:bool:true` and lists only the two flagged demo products.
- **Viewers.** They see saved results without buttons, or the "Not analyzed yet" card, and never trigger a call.
- **Error states.** With a stubbed network they render the sanitized messages, the CONTENT_BLOCKED hint and Retry. A failed auto-run is not repeated when the user switches tabs.

The one real defect is operational: the plugin log still echoes BAML prompts and replies. Two cosmetic items are dev-only or host-owned.

## 4. Verification Scenarios

### 4.1 Editor stays on Details: no auto-run, badge "not analyzed yet" — ✅ Passed
- **User story / acceptance criterion**: User stories 1 and 6; FR4 visibility gate; Success Criteria "opening a product page without opening the tab makes no `/api/analyze` call"
- **Preconditions**: logged in as `editor`; product 2 (Samsung 55" S90D) has no analysis; plugin log baseline: 0 `llm-call` lines

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open `/products/2`, wait 5 s on Details | No POST `/api/analyze` | The plugin log shows only `GET /product-badge`, `GET /product-tab`; 0 `llm-call` lines | ✅ |
| 2 | Read badge iframe | Plain `tc-badge` "AI review: not analyzed yet" | `class="tc-badge"`, text "AI review: not analyzed yet" | ✅ |
| 3 | Inspect hidden tab iframe | Viewport 0×0; header "Not analyzed" and no run | `innerWidth=0, innerHeight=0`; text "AI Offer Analysis · Not analyzed · Re-analyze" | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/e2e-01-editor-details-not-analyzed.png`
- **Acceptance criteria checklist**:
  - [x] No LLM call on a product page visit
  - [x] Badge shows "not analyzed yet"

### 4.2 Editor opens the AI tab: auto-run, then result — ✅ Passed
- **User story / acceptance criterion**: User story 1; FR4; Header rule; Spec deviation (a) "Priced as"
- **Preconditions**: continues 4.1

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Click the "AI Offer Analysis" tab | Auto-run starts once the iframe is visible | Exactly one `POST /api/analyze 200 in 6207ms`; log `llm-call productId=2 outcome=ok durationMs=6100` | ✅ |
| 2 | Result header | Title, subtitle "Analyzed {d MMM yyyy, HH:mm} · gpt-4o-mini via LiteLLM", overall badge, Re-analyze | "Analyzed 12 Nov 2026, 21:02 · gpt-4o-mini via LiteLLM", success "Looks good", Re-analyze button | ✅ |
| 3 | Sections | Per-section layout rule | Description ACCEPTABLE (plain badge, detailed Issues/Suggestions); Category FITS (compact "Assigned: Televisions — …"); Price FAIR −13% (compact line "USD 1,299.99 vs market USD 1,200.00–1,800.00 · peers 1,799.99 / 1,999.99 / 2,199.99") | ✅ |
| 4 | "Priced as" line | Present when `pricedAs` is non-empty | "Priced as: 55-inch OLED 4K Smart TV" | ✅ |
| 5 | Peers exclude self | Televisions peers without product 2 | Prompt peers: LG C4 1799.99, Sony 2199.99 (count 2); product 2 is excluded | ✅ |

- **Issues observed**: The live running state could not be captured: the run finished before the next probe. The running state is covered by 4.4 (live, manual) and 4.8 (auto, stubbed).
- **Evidence**: `screenshots/e2e-03-editor-auto-run-result.png`
- **Acceptance criteria checklist**:
  - [x] Exactly one auto-run when a new product's tab is first opened
  - [x] Result shows the subtitle and the "Priced as" line

### 4.3 Badge refresh over BroadcastChannel, and no repeat run — ✅ Passed
- **User story / acceptance criterion**: FR5; BroadcastChannel contract; Success Criteria "Re-opening the tab or the page without content changes does not call the LLM"
- **Preconditions**: continues 4.2

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Switch back to Details (no reload) | Badge re-reads `getData` | Badge is now `tc-badge tc-badge--success` "AI review: Looks good"; no `framenavigated` event (no reload) | ✅ |
| 2 | Switch to the AI tab again | No new run | No new log line | ✅ |
| 3 | Reload the page and open the tab | Current fingerprint, so no run | Saved result shown, no `<progress>`; the log still has 1 `llm-call` line | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/e2e-04-editor-details-badge-updated.png`, `screenshots/e2e-05-editor-reopen-current-no-run.png`
- **Acceptance criteria checklist**:
  - [x] Badge refreshes after an in-tab run without a reload
  - [x] Tab switches and reopening do not re-run

### 4.4 Editor on Vistara (15): stored result, then Re-analyze — ✅ Passed
- **User story / acceptance criterion**: User story 3; `screen:analysis-tab-needs-review-editor`; `screen:analysis-tab-auto-running` (manual variant); Success Criteria for the demo products
- **Preconditions**: `editor`; product 15 has a current analysis

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open the tab | Stored result, no run | "Needs review" (danger); Description POOR (danger), Score 3/10, Issues/Suggestions lists; Category MISMATCH (danger) with a table showing Assigned "Smart Home" and Suggested "Televisions" | ✅ |
| 2 | Price section, as stored | Matches the record | FAIR +12% (success), Confidence HIGH, compact line "USD 2,799.00 vs market USD 1,500.00–3,500.00 · peers 179.99 / 179.99 / 279.99", "Priced as: 65-inch OLED 4K TV" | ✅ |
| 3 | Click Re-analyze | Running state; Re-analyze `disabled`; no reason line | Badge "Analyzing…" (plain); `<progress aria-label="Analysis in progress">` with no value (indeterminate); button `Re-analyze [disabled]`; no "Started automatically" line | ✅ |
| 4 | Wait for completion | Refreshed result | Subtitle changed 20:39 → 21:03; issue texts refreshed; verdicts are stable (POOR / MISMATCH / FAIR +12%); log `llm-call productId=15 outcome=ok durationMs=6699` | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/e2e-10-editor-vistara-stored.png`, `screenshots/e2e-11-editor-vistara-reanalyze-running.png`, `screenshots/e2e-12-editor-vistara-reanalyzed.png`
- **Acceptance criteria checklist**:
  - [x] TV demo shows Needs review with POOR description and MISMATCH (suggests Televisions)
  - [x] Peers come from the assigned category (Smart Home, 3)
  - [x] Re-analyze is disabled while running, and the result is refreshed

### 4.5 Product list "AI: needs review" filter — ✅ Passed
- **User story / acceptance criterion**: User story 5; manifest `product.list.filters` (boolean `needsReview`); setData summary contract
- **Preconditions**: `editor`; flagged products 15 and 16 (product 2 is "Looks good")

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open `/products` | Switch "AI: needs review" rendered by the host | Switch present next to the category filter; 16 products listed | ✅ |
| 2 | Click the switch control | Only flagged products | Request `GET /api/products?pluginFilter=ai-offer-analysis%3AneedsReview%3Abool%3Atrue` → "Showing 2 products": Vistara 65" OLED 4K TV, Skylink AX3000 | ✅ |

- **Issues observed**: §5.4 Cosmetic #2 (the host switch checkbox has no accessible name).
- **Evidence**: `screenshots/e2e-20-list-filter-needs-review.png`
- **Acceptance criteria checklist**:
  - [x] The list filter returns the flagged products only

### 4.6 Viewer on Vistara (15): saved result, no buttons, no call — ✅ Passed
- **User story / acceptance criterion**: User story 4; `screen:analysis-tab-viewer-saved-result`; Success Criteria "Viewer never triggers `/api/analyze`"
- **Preconditions**: logged in as `viewer` (READ, FACTOR_PASSWORD)

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Details tab badge | Danger badge with reasons | "AI review: Needs review" plus "category mismatch · poor description" | ✅ |
| 2 | Open the AI tab | Saved result, no Re-analyze | Full saved result; `buttons: []`; `aria-live="polite"` region present | ✅ |
| 3 | Plugin log | No POST | Only GET page loads; 0 `llm-call` lines | ✅ |

- **Issues observed**: Stale note not exercised (the analysis was current); see §8.
- **Evidence**: `screenshots/e2e-40-viewer-vistara-details-badge.png`, `screenshots/e2e-41-viewer-vistara-tab.png`
- **Acceptance criteria checklist**:
  - [x] Viewer sees the saved result
  - [x] No Re-analyze button and no LLM call

### 4.7 Viewer on a never-analyzed product (3) — ✅ Passed
- **User story / acceptance criterion**: User story 4; `screen:analysis-tab-viewer-no-analysis`
- **Preconditions**: `viewer`; product 3 (Sony Bravia 7) has no analysis

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Details badge | "AI review: not analyzed yet" | As expected | ✅ |
| 2 | Open the AI tab | Title plus a `tc-card` "Not analyzed yet" / "An editor needs to open this tab to run the first analysis." | Exact text, one `tc-card`, no buttons; no POST in the plugin log | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/e2e-42-viewer-not-analyzed-tab.png`
- **Acceptance criteria checklist**:
  - [x] "Not analyzed yet" card for viewers

### 4.8 Error path with a stubbed network: auto-run error, no repeat, Retry and CONTENT_BLOCKED hint — ✅ Passed
- **User story / acceptance criterion**: User story 2; FR4 "at most one auto-run attempt"; `screen:analysis-tab-error`; spec deviation (g) "Re-analyze hidden in the error state"
- **Preconditions**: `editor`; product 3 (no analysis); Playwright `context.route('**/localhost:3011/api/analyze')` fulfils each request with HTTP 502 after a 3 s delay, using bodies from the API contract table; no request reaches the plugin server

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Load the page, stay on Details 2.5 s | No request | 0 route hits before the tab click | ✅ |
| 2 | Click the AI tab | Auto-run running state with the reason line | "Analyzing…" (plain), `<progress aria-label="Analysis in progress">`, "Analyzing this offer…", helper "…5–15 seconds.", "Started automatically: this product has not been analyzed yet.", `Re-analyze [disabled]` | ✅ |
| 3 | 502 LLM_UNAVAILABLE arrives | Error card with the sanitized message and Retry; header "Not analyzed" without a subtitle; Re-analyze hidden | `tc-error` "The AI gateway is unavailable. No analysis was saved."; `tc-primary-button` "Retry" only; plain "Not analyzed" badge; no subtitle | ✅ |
| 4 | Switch to Details and back to the AI tab | No second auto-run | Route hits stay at 1; error state unchanged | ✅ |
| 5 | Click Retry (stub now returns CONTENT_BLOCKED) | Running without a reason line, then the blocked message plus the hint | Running without a reason line; then "The AI gateway rejected the request (content policy). No analysis was saved." plus "The product text may contain data blocked by the gateway (e.g. a card number). Edit the product and try again." and Retry | ✅ |
| 6 | Reload; check the badge | Nothing saved | Badge is still "AI review: not analyzed yet"; plugin log has no new `/api/analyze` line | ✅ |

- **Issues observed**: _None observed._ The 2 console errors "Failed to load resource: 502" are the browser reporting the stubbed responses (expected).
- **Evidence**: `screenshots/e2e-30-editor-auto-running-mocked.png`, `screenshots/e2e-31-editor-auto-run-error-mocked.png`, `screenshots/e2e-32-editor-retry-content-blocked-mocked.png`
- **Acceptance criteria checklist**:
  - [x] A failed auto-run is not repeated in the same session
  - [x] Retry runs again
  - [x] Only sanitized contract messages are shown; the hint appears for CONTENT_BLOCKED only

### 4.9 Stored states for LG C4 (1) and Skylink (16) — ✅ Passed
- **User story / acceptance criterion**: `screen:analysis-tab-looks-good`; detailed price layout; `screen:info-badge-states`; Skylink strongly cued MISMATCH
- **Preconditions**: `editor`; a stub guard on `/api/analyze` (0 hits recorded, so both analyses were current)

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | LG C4 badge and tab | Success badge; compact sections; peers Televisions count 2 (1,299.99 / 1,749.99 / 2,199.99) | "AI review: Looks good"; GOOD 8/10 compact summary; FITS compact; FAIR +3%, line "… · peers 1,299.99 / 1,749.99 / 2,199.99"; "Priced as: 65-inch OLED 4K TV" | ✅ |
| 2 | Skylink badge | Danger badge with reasons in fixed order | "AI review: Needs review" plus "price −90% below market · category mismatch" | ✅ |
| 3 | Skylink tab, price section | Detailed `tc-table` Metric/USD; peers "(Home Appliances, 3)" | BELOW MARKET −90% (danger), Confidence LOW; table rows: Product price 19.99; Estimated market range 100.00 – 300.00; "Catalog peers (Home Appliances, 3) min / median / max" 649.99 / 799.99 / 1,099.95; reasoning paragraph | ✅ |
| 4 | Skylink category | MISMATCH, suggested Networking | As expected | ✅ |

- **Issues observed**: Skylink has no "Priced as" line because its record (20:10) predates fix cycle 1, so `pricedAs` reads as `""`. This is the specified fallback.
- **Evidence**: `screenshots/e2e-50-editor-details-badge-p1.png`, `screenshots/e2e-52-editor-tab-p1.png`, `screenshots/e2e-51-editor-details-badge-p16.png`, `screenshots/e2e-53-editor-tab-p16.png`, `screenshots/e2e-54-editor-tab-p16-price-detailed.png`
- **Acceptance criteria checklist**:
  - [x] Looks-good compact layout
  - [x] Detailed price table with assigned-category peers, self excluded
  - [x] Badge reasons in the fixed order

### 4.10 LLM-call ground truth in the plugin log — ⚠️ Passed with issues
- **User story / acceptance criterion**: API step 8 (`[ai-offer-analysis] llm-call …` exactly once per BAML call); spec deviations (d) and (e) (no prompts in the plugin log, `BAML_LOG=warn`)
- **Preconditions**: plugin log `scratchpad/plugin3.log`; baseline 0 `llm-call` lines (20:00 UTC)

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Count `llm-call` lines after the session | Equal to the runs I triggered (2) | 2 lines: `productId=2 outcome=ok durationMs=6100`, `productId=15 outcome=ok durationMs=6699`; 2 `POST /api/analyze 200`; stubbed runs never reached the server | ✅ |
| 2 | Check the log for prompt or LLM output | None (`BAML_LOG=warn`) | Each call is preceded by a `[BAML INFO] Function AnalyzeOffer:` block containing `---PROMPT---` (product name, description, price, peers) and `---LLM REPLY---` / `---Parsed Response---` | ❌ |

- **Issues observed**: §5.3 Minor #1.
- **Evidence**: plugin log lines 27–194 and 200–376 (console dump; not a screenshot)
- **Acceptance criteria checklist**:
  - [x] Exactly one `llm-call` line per triggered run
  - [ ] No prompts or LLM output in the plugin log

## 5. Discrepancies

### 5.1 Critical
_None observed._

### 5.2 Major
_None observed._

### 5.3 Minor
**#1: BAML echoes the full prompt and LLM reply to the plugin stdout**
- **Spec requirement**: Spec deviation (e): "The plugin `.env.example` sets `BAML_LOG=warn` so BAML does not echo prompts and responses (unmasked product text) to stdout." Deviation (d): "The error object, the prompt and the raw LLM output are never logged."
- **Expected**: The plugin log contains only Next.js request lines and the one-line `[ai-offer-analysis] llm-call …` entries.
- **Actual**: Each successful call logs a `[BAML INFO] Function AnalyzeOffer` block of about 170 lines: client and model, token counts, the full system prompt with product name, description, price and peer names, and the raw and parsed LLM reply.
- **Evidence**: `scratchpad/plugin3.log` lines 27–193 (product 2) and 200–375 (product 15). The Next banner shows `Environments: .env` is loaded. The committed `.env.example:12` contains `BAML_LOG=warn`.
- **Root cause hypothesis**: The most likely cause is that the local plugin `.env` was created before fix cycle 1 and lacks `BAML_LOG=warn`, so BAML runs at its default INFO level. I did not inspect `.env`, per the rules. Less likely: the variable is present, but the BAML native runtime does not pick it up from Next's `.env` loading.
- **User impact**: No end-user impact. The local dev log holds unmasked product text that Presidio has not processed, which is the leak the amendment was meant to close. The log is also noisy enough to bury the `llm-call` ground-truth lines.
- **Recommended fix**: Check that `plugins/ai-offer-analysis/.env` contains `BAML_LOG=warn` and restart `npm run dev`. If the block still appears, set `BAML_LOG` in the `dev`/`start` scripts or the process environment, and add a README note.
- **Workaround**: Start the dev server with `BAML_LOG=warn` exported in the shell.

### 5.4 Cosmetic
**#1: Next.js dev indicator overlaps plugin content**
- **Spec requirement**: The badge "fits in 60px" (Badge behaviour); badge text is always shown.
- **Expected**: The badge text "AI review: …" is fully visible.
- **Actual**: The floating Next.js dev-tools "N" button (bottom-left of each iframe) covers the first letters of the badge ("AI") in the 60 px badge iframe, and the lower-left corner of the 600 px tab.
- **Evidence**: `screenshots/e2e-51-editor-details-badge-p16.png`, `screenshots/e2e-41-viewer-vistara-tab.png`
- **Root cause hypothesis**: Next.js 15 `next dev` injects its dev indicator into every page. It is absent in `next build`/`next start`.
- **User impact**: Dev mode only. It can confuse demo screenshots.
- **Recommended fix**: Optional: set `devIndicators: false` in `next.config.js` for demo recordings.
- **Workaround**: Run with `npm run build && npm start` for demos.

**#2: The host-rendered "AI: needs review" switch has no accessible name**
- **Spec requirement**: The manifest `product.list.filters` switch is rendered by the host (out of scope for this plugin); the accessibility standard asks for labels.
- **Expected**: The checkbox is labelled "AI: needs review".
- **Actual**: The accessibility tree shows an unnamed `checkbox` plus a separate paragraph "AI: needs review".
- **Evidence**: `screenshots/e2e-20-list-filter-needs-review.png`; snapshot ref `f66e51`
- **Root cause hypothesis**: The host filter component (shared by all boolean plugin filters, e.g. warehouse "In Stock") does not link its label to the input.
- **User impact**: Screen-reader users hear an unlabelled switch. This affects all plugin boolean filters, not just this plugin.
- **Recommended fix**: In the host frontend, wrap the label or set `aria-label` from the manifest `label`. This needs a separate host task.
- **Workaround**: None needed for sighted users.

## 6. Console & Network Errors
| Source (file:line) | Message | Frequency | Severity | Impact |
|---|---|---|---|---|
| http://localhost:3011/api/analyze:0 | Failed to load resource: the server responded with a status of 502 (Bad Gateway) | 2 (only during scenario 4.8) | Cosmetic | Expected: these are the stubbed error responses; not an app defect |
| http://localhost:8080/assets/index-DZu6PCv-.js:9 (warning) | An iframe which has both allow-scripts and allow-same-origin for its sandbox attribute can escape its sandboxing. | Once per plugin iframe on each page load | Cosmetic | Pre-existing host iframe sandbox setting, shared by all plugins; out of scope |

No uncaught page errors were recorded. Live runs gave 0 non-2xx `/api/analyze` responses.

## 7. Spec Alignment
- **Fully implemented**:
  - Auto-run visibility gate: no run while hidden (0×0 viewport), run on tab open, one attempt per iframe session, no repeat after error or on tab switch
  - Staleness: a current fingerprint means no run on reopen or reload
  - Editor result header (title, en-GB subtitle with model and LiteLLM, overall badge, Re-analyze) and the running state (plain "Analyzing…", indeterminate labelled `<progress>`, disabled Re-analyze, reason line only for auto-runs)
  - Per-section layout rule (compact GOOD/FITS/FAIR, detailed otherwise), badge class mapping, signed deviation labels with Unicode minus, Score and Confidence badges
  - `pricedAs` "Priced as" line (deviation a); compact FAIR line without the category label (deviation g)
  - Peers from the assigned category with self excluded (Vistara Smart Home 3; LG C4 Televisions 2; Skylink Home Appliances 3)
  - Info badge in 3 states with reasons in the fixed order, refreshed over BroadcastChannel
  - Native boolean list filter
  - Viewer states (saved result without buttons; "Not analyzed yet" card); viewers make no calls
  - Error state: sanitized messages, CONTENT_BLOCKED-only hint, Retry only (Re-analyze hidden), "Not analyzed" header without a subtitle
  - One `llm-call` line per BAML call
- **Partially implemented**:
  - Spec deviation (e) `BAML_LOG=warn`: documented and present in `.env.example`, but not in effect in the running plugin (§5.3 #1)
- **Not implemented**:
  - _None observed._
- **Extra (unspecified) behavior**:
  - The header shows "Re-analyze" (enabled) for an editor while a not-yet-analyzed tab is still hidden. It is never seen, because the auto-run replaces it once the tab is visible.

## 8. Variances from Plan
- **Error path.** It was tested with Playwright network stubs (HTTP 502 with contract bodies) instead of a real gateway failure, as the brief instructed (do not stop LiteLLM). This covers UI rendering and the one-attempt rule, not server-side error classification.
- **Running state.** The live auto-run running state was not captured: the first tab clicks after login did not register in the harness (a Playwright click-timing quirk on the host tab buttons; later clicks worked). The successful click completed the 6 s run before the next probe. The running state was verified live on the manual Re-analyze (4.4) and as an auto-run on the stubbed network (4.8).
- **Viewer stale note.** "Product changed since this analysis…" was not exercised, because it needs a product edit (and later an editor auto-run, costing a call).
- **Product choice.** Scenario 1 used product 2 (Samsung S90D) instead of the TV demo product, which already had an analysis. The viewer no-analysis check used product 3, which was then used for the stubbed error path. No analysis was saved for it.
- **Out of scope for this E2E run.** LiteLLM UI / `/spend/logs` delta, Langfuse traces, Jest/Maven suites and the edit-triggers-re-run criterion. The `llm-call` ground truth (2 lines) is recorded here for the LiteLLM delta check.
- **LLM budget.** 2 real calls of the 3 allowed.

## 9. Evaluation Against Exit Criteria
| Criterion (from spec) | Status | Evidence |
|---|---|---|
| "The plugin appears on product detail as the 'AI Offer Analysis' tab and the 'AI Review' info badge, and as an 'AI: needs review' switch on the product list." | ✅ | 4.1, 4.5; `screenshots/e2e-20-list-filter-needs-review.png` |
| "Editor: opening a product page without opening the tab makes no `/api/analyze` call." | ✅ | 4.1; plugin log has no POST |
| "The first time the editor opens the tab for a new or changed product, exactly one analysis auto-runs." | ✅ | 4.2; one `llm-call productId=2` |
| "Re-opening the tab or the page without content changes does not call the LLM." | ✅ | 4.3; `screenshots/e2e-05-editor-reopen-current-no-run.png` |
| "A failed auto-run is not repeated in the same page session; Retry runs it again." | ✅ | 4.8; 1 route hit after the tab switch; Retry gives a second hit |
| "Viewer: never triggers `/api/analyze`; sees the saved result with a stale note, or the 'Not analyzed yet' card." | ✅ | 4.6, 4.7 (stale note not exercised, §8) |
| "Each demo product shows 'Needs review' with at least its strongly cued flag … with the reasons visible in the tab and the badge." | ✅ | 4.4 (Vistara POOR + MISMATCH), 4.9 (Skylink MISMATCH → Networking) |
| "Peer rows show the assigned category with the product itself excluded." | ✅ | 4.9 table "Catalog peers (Home Appliances, 3)"; 4.2 prompt peers exclude product 2 |
| "The badge refreshes after an in-tab run without a page reload, and the list filter returns the flagged products." | ✅ | 4.3, 4.5 |
| "Error states show only the sanitized messages from the contract table. No raw LiteLLM or host error text reaches the browser." | ✅ | 4.8 (UI renders contract messages; stubbed) |

## 10. Recommendations
- **Must fix before merge**: _None._
- **Should fix soon**: §5.3 Minor #1: make `BAML_LOG=warn` effective in the running plugin (check `.env`, restart, re-check that the log has no `[BAML INFO]` block).
- **Nice-to-have**: §5.4 Cosmetic #1 (hide the Next dev indicator for demo recordings); §5.4 Cosmetic #2 (host task: give plugin boolean filter switches an accessible name).

## 11. Artifacts
- **Screenshots**: `verification/screenshots/` (19 `e2e-*` app captures plus 7 `e2e-mockup-*` mockup renders for the fidelity comparison; the earlier `g9-*` captures were left unchanged)
- **Visual-fidelity report**: `verification/visual-fidelity.md` (+ `verification/visual-fidelity.html`)
- **Console log dump**: inline in §6

## 12. Conclusion
⚠️ GO WITH CAVEATS: the AI Offer Analysis plugin behaves as specified across all 10 runtime scenarios, including the visibility-gated single auto-run, viewer restrictions, badge refresh, list filter and error rendering. The only real deviation is that the dev plugin still logs BAML prompts and replies, which looks like an environment `.env` gap rather than a code defect, plus two cosmetic dev/host items. Recommendation: merge after checking that `BAML_LOG=warn` is present in the plugin `.env` and that the `[BAML INFO]` blocks disappear on the next run.
