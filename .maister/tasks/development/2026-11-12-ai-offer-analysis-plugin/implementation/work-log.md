# Work Log

## 2026-11-12T18:36:59Z - Implementation Started

**Total Steps**: 58
**Task Groups**: 1 Scaffold, 2 BAML contract, 3 Domain, 4 Analyze route, 5 UI pages, 6 Liquibase seed, 7 Docs, 8 Test review, 9 Live LiteLLM run
**Waves**: W1 [G1] → W2 [G2, G3, G6] → W3 [G4, G5] → W4 [G7] → W5 [G8] → W6 [G9]

## Standards Reading Log

### Loaded Per Group
(Entries added as groups execute)

### Group 1: Plugin Scaffold & Configuration
**From Implementation Plan**: plugins/plugin-development.md, global/conventions.md, global/minimal-implementation.md
**From INDEX.md**: plugins/CLAUDE.md (plugin guide)
**Discovered During Execution**: none

## 2026-11-12T18:39:34Z - Group 1 Complete (wave 1)

**Steps**: 1.1 through 1.7 completed
**Tests**: 3/3 smoke checks passed (npm install + baml_client generated; jest --listTests on Windows; git check-ignore)
**Files Modified**: plugins/ai-offer-analysis/{package.json, package-lock.json, next.config.js, tsconfig.json, jest.config.js, next-env.d.ts, .gitignore, .env.example, manifest.json, baml_src/generators.baml, src/pages/_document.tsx} + local gitignored .env
**Notes**: BAML pinned 0.220.0; scripts call baml-cli directly; manifest deep-equals spec; npm audit reports 9 transitive vulns (out of scope); Group 2 must re-run `npm run generate`.

### Group 2: BAML Contract
**From Implementation Plan**: global/minimal-implementation.md, global/conventions.md, global/validation.md
**From INDEX.md**: plugins/plugin-development.md
**Discovered During Execution**: none

### Group 3: Domain Logic
**From Implementation Plan**: global/validation.md, global/coding-style.md, global/commenting.md, global/minimal-implementation.md, backend/plugin-auth.md, testing/frontend-testing.md, frontend/tooling.md
**From INDEX.md**: plugins/plugin-development.md, plugins/CLAUDE.md
**Discovered During Execution**: none

### Group 6: Liquibase Dev Seed
**From Implementation Plan**: backend/migrations.md, testing/backend-testing.md, global/conventions.md
**From INDEX.md**: plugins/plugin-development.md (re-register on manifest change)
**Discovered During Execution**: none

## 2026-11-12T18:44:20Z - Wave 2 Complete (Groups 2, 3, 6)

**Group 2**: steps 2.1-2.6. baml_src/clients.baml (OfferAnalysisLlm via LiteLLM :4000/v1, gpt-4o-mini, temp 0.1, user + metadata tags, no retry/fallback) and main.baml (4 enums, 8 classes, AnalyzeOffer, VistaraDemoOffer test). npm run generate OK (0.220.0), tsc OK. Live baml-cli test run once 18:41:29Z-18:41:33Z: reached LiteLLM (plugin key accepted), BAML body carried user + metadata.tags; upstream OpenAI 401 - OPENAI_API_KEY in litellm/.env is an OpenRouter-style key (sk-or-v1). ENV BLOCKER for Group 9.
**Group 3**: steps 3.1-3.8. src/domain.ts + 10 test blocks (42 Jest cases) all pass; tsc strict clean; no baml_client import. AnalysisContext {snapshot, categories, peerStats, fingerprint, now}.
**Group 6**: steps 6.1-6.5. 013-insert-ai-offer-analysis-plugin.yaml (plugin row + 2 demo products AOA-DEMO-TV65, AOA-DEMO-MESH2; INNER JOIN categories; ON CONFLICT; rollbacks). Manifest deep-equal OK; ./mvnw test 159 run / 0 failed; no src/test collisions.

## 2026-11-12T18:46:06Z - Environment fix: LiteLLM upstream (user decision)

User chose to route the logical model gpt-4o-mini via OpenRouter. litellm/config.yaml: gpt-4o-mini -> openrouter/openai/gpt-4o-mini (api_key os.environ/OPENAI_API_KEY, which holds an OpenRouter key). litellm container restarted, liveness 200. No key values read or printed. Plugin/BAML unchanged. To be validated by the Group 9 live run; Group 7 docs should mention it.

### Group 4: Analyze API Route
**From Implementation Plan**: global/error-handling.md, global/validation.md, backend/plugin-auth.md, backend/api.md, global/minimal-implementation.md
**From INDEX.md**: testing/backend-testing.md (naming), plugins/plugin-development.md, global/commenting.md
**Discovered During Execution**: @jest/globals explicit imports pattern (no @types/jest)

### Group 5: Plugin UI Pages
**From Implementation Plan**: plugins/plugin-development.md, frontend/components.md, frontend/css.md, frontend/accessibility.md, frontend/tooling.md, backend/plugin-auth.md
**From INDEX.md**: testing/frontend-testing.md (not applicable - UI tests out of scope)
**Discovered During Execution**: plugins/CLAUDE.md (tc-plugin root sets font; used on badge root too)

## 2026-11-12T18:48:18Z - Wave 3 Complete (Groups 4, 5)

**Group 4**: steps 4.1-4.7. src/pages/api/analyze.ts + 8 route tests (11 cases) pass; tsc clean; BAML native addon not loaded (errors from @boundaryml/baml/errors). One llm-call console.info per BAML call. Accepted deviation: "Invalid categories payload" classified as HOST_ERROR (spec named only product payload) - consistent with host-origin errors.
**Group 5**: steps 5.1-5.8. src/pages/product-tab.tsx + product-badge.tsx. tsc clean, no hex colors, acceptance self-check: 6/7 screens + 2 components exact; error screen deviation - header Re-analyze hidden while error card shown (Retry is the only action, as in mockup). Added "Loading analysis..." state (not in spec). Not yet exercised in a browser (resize gate, BroadcastChannel, en-GB time) - covered by G9/E2E.

### Group 7: Documentation
**From Implementation Plan**: global/conventions.md, plugins/plugin-development.md, global/commenting.md
**From INDEX.md**: backend/plugin-auth.md, backend/security.md
**Discovered During Execution**: none

## 2026-11-12T18:52:12Z - Wave 4 Complete (Group 7)

**Steps**: 7.1-7.6. Created plugins/ai-offer-analysis/README.md (setup, registration bash+PowerShell, run rules, tests, LiteLLM section incl. OpenRouter mapping, verification steps 0-7, known limitations). Updated README.md, CLAUDE.md, plugins/CLAUDE.md, tech-stack.md, plugin-development.md (next business module -> 3012), conventions.md, INDEX.md. 3/3 doc checks pass; course folders untouched; diffs small (34+/16-).
**Notes**: Group 9 must record in the plugin README which attribution mechanism LiteLLM recorded.

### Group 8: Test Review
**From Implementation Plan**: testing/backend-testing.md, testing/frontend-testing.md, global/minimal-implementation.md
**From INDEX.md**: global/error-handling.md
**Discovered During Execution**: none

## 2026-11-12T18:54:20Z - Wave 5 Complete (Group 8)

**Steps**: 8.1-8.5. Gaps filled within caps (selectPromptPeers tie-break; host-failure test.each incl. setData-after-save and invalid categories payload -> HOST_ERROR; llm-call line counts). 18 blocks / 55 cases pass in Git Bash and PowerShell; npm run build OK (Next 15.5); tsc clean; git hygiene clean. No production code changes, no bugs found.
- Note: Group 9 HTML marker is `class="group done deferrable"` (flipped correctly; strict grep pattern did not match the extra class).

### Group 9: LiteLLM Traffic Attribution (live run)
**From Implementation Plan**: global/conventions.md (no secrets printed), plugins/plugin-development.md
**From INDEX.md**: none additional
**Discovered During Execution**: none

## 2026-11-12T19:13:48Z - Wave 6 Complete (Group 9, live run)

**Environment**: host restarted by orchestrator (user-approved) so 013 applied (plugin row + AOA-DEMO-TV65 id 15, AOA-DEMO-MESH2 id 16); plugin dev server on :3011; LiteLLM gpt-4o-mini -> openrouter/openai/gpt-4o-mini.
**Evidence**: start 2026-11-12T19:07:31Z. Hidden Details view: no llm-call (visibility gate works). 4 llm-call lines, all ok (8293/6482/6931/6514 ms) = 4x POST /api/analyze 200. LiteLLM_SpendLogs (litellm-db, same store as UI Logs) baseline 0 -> 4 success rows, end_user=ai-offer-analysis, request_tags=["ai-offer-analysis"], model_group gpt-4o-mini, upstream openrouter/openai/gpt-4o-mini; prompt shows "USD 2799.00" unmasked; Presidio guardrails ran (0 masked). Langfuse: 4 AnalyzeOffer generations, trace/user/tag ai-offer-analysis (e.g. trace 2569515598374ce4a271c35bc46a19c7). Badge refreshed via BroadcastChannel without reload; list filter "AI: needs review" shows exactly Vistara + Skylink; viewer sees stored result, no Re-analyze, no LLM call.
**Verdicts**: Vistara Needs review (POOR 3/10, MISMATCH -> Televisions, ABOVE +1117% HIGH); LG C4 Looks good (GOOD 8/10, FITS, FAIR +3%); Skylink Needs review (MISMATCH -> Networking, BELOW -90% LOW).
**Finding for verification**: Vistara market range came out as the Smart Home peer range (179.99-279.99), so deviation +1117% is distorted although the verdict is right - prompt instruction "ignore peers of a different product type" not reliably followed by gpt-4o-mini.
**Header fallback**: not needed (body user + metadata.tags recorded by LiteLLM 1.106.6). clients.baml unchanged. README updated (attribution result, psql count alternative, corrected LG C4 peers expectation, filter switch note).
**Screenshots**: verification/screenshots/g9-*.png (10). LiteLLM UI (:4000/ui) not logged into (master key not read by policy) - user can confirm the same rows there.

## 2026-11-12T19:13:48Z - Implementation Complete

**Total Steps**: 58 completed (9 groups, 6 waves)
**Total Standards**: 17 standard files applied across groups
**Test Suite**: passed - plugin Jest 2 suites / 55 cases (re-run at finalize); host ./mvnw test 159/159 (Group 6, after seed; no Java changes since); npm run build OK (Group 8)
**Other changes**: litellm/config.yaml upstream mapping (user decision); host restarted for seed.

## 2026-11-12T19:40:30Z - Phase 11 fix cycle 1

**Fixes applied** (user-selected issues 1,3,4,5,6 + issue 2):
- 1: BAML PriceAssessment gets pricedAs + reasoning before numbers; prompt forbids peer-derived market range; domain guard (MISMATCH + range inside peer range -> deviation null, confidence LOW); "Priced as" line in tab.
- 3: CONTENT_BLOCKED only for guardrail-marked 400; other 400 -> LLM_UNAVAILABLE.
- 4: route tests for missing bearer (401) and host 401 before LLM.
- 5: sanitized single-line failure log (name, status, truncated message; no error object/prompt).
- 6: BAML_LOG=warn in plugin .env.example + README.
- 2: first attempt switched config to OPENROUTER_API_KEY; user's .env files did not contain the new line (live run -> OpenRouter 401, sanitized log + LLM_UNAVAILABLE mapping confirmed). User chose to revert to OPENAI_API_KEY (holding the OpenRouter key); litellm/config.yaml, litellm/.env.example, README.md, conventions.md, plugin README aligned to that. LiteLLM recreated.
**Tests**: plugin Jest 58/58, tsc clean, npm run build OK (fix agent).
**Live re-run** (start 2026-11-12T19:39:43Z): 2 llm-call ok -> 2 LiteLLM_SpendLogs success rows (end_user/request_tags ai-offer-analysis, openrouter/openai/gpt-4o-mini). Vistara: pricedAs "65-inch OLED 4K TV", price FAIR +12% (was ABOVE +1117%), still needsReview (POOR + MISMATCH). LG C4: FAIR +3%, looks good. Plugin totals in spend logs: 6 success, 3 failure (failures also visible).
**Open**: BAML_LOG=warn not in user's local plugin .env (documented in .env.example).

## 2026-11-12T19:56:33Z - Phase 11 fix round 2

**Fixes**: R1 spec.md/spec.html "Spec deviations (Phase 11 fix cycle 1)" (a-g) + 29 inline amended notes; R5 plugins/CLAUDE.md .env contents + plugin README prompt-location line; R4 guardrail regex /guardrail|presidio|blocked pii|pii entit/i over message + raw_response only (+ generic-blocked 400 test row); R3 price.marketRangeUnreliable flag + "market range unreliable" UI.
**Tests**: plugin Jest 59/59, tsc clean, npm run build OK.
**Incident**: npm run build overwrote .next under running next dev -> pages 500; orchestrator stopped the dev server, removed .next (generated, gitignored) and restarted -> /product-tab and /product-badge 200, GET /api/analyze 405.
**Verdict**: passed_with_issues - 0 critical, 1 warning (R2 LiteLLM UI manual check), 7 info.

## 2026-11-12T20:15:25Z - Workflow finalized (Phase 14)

**Phases**: 1,2,4,5,6,7,8,10,11,12,13 completed; 3 and 9 skipped (no reproducible defect).
**Outcome**: plugins/ai-offer-analysis on :3011 (Next.js + BAML 0.220 via LiteLLM gpt-4o-mini). Verification passed with issues (0 critical, 1 manual warning: LiteLLM UI confirmation). E2E GO with caveats (10/10). Tests: plugin 59/59, host 159/159.
**Artifacts**: all declared artifacts present (no missing).
**User actions left**: add BAML_LOG=warn to plugins/ai-offer-analysis/.env and restart the dev server; confirm LiteLLM UI Logs (:4000/ui); optionally delete the stray empty file /u.json at the Git Bash root (created by an orchestrator command mistake).
**Not committed** - awaiting user.
