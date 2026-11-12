# Requirements — AI Offer Analysis plugin

## TL;DR
New iframe plugin `ai-offer-analysis` (Next.js 15 Pages Router, port 3011). It uses BAML through LiteLLM (`gpt-4o-mini`) to judge description quality, category fit, and price versus market norms. Results are stored as plugin objects, with a compact `setData` summary that drives a native "needs review" list filter and a 60px info badge. Analysis auto-runs on tab open for editors when the content fingerprint changes. Done means: a live run is verified in the LiteLLM logs and the verification steps are documented.

## Key Decisions
- Stack: clone the ai-description stack (Next.js 15 Pages Router, `@boundaryml/baml` pinned to 0.220.0, Jest) without its known defects.
- Auth: reuse shipping's base64url-safe `canEdit`; the backend refuses LLM calls without EDIT.
- Registration: 013 Liquibase dev-context seed for the plugin row and demo products; a curl command is documented as an alternative.
- Mockups in `analysis/design-context/` (7 screens) are binding.
- Verification runs live: docker compose + host + plugin, real gpt-4o-mini calls, LiteLLM logs/spend logs checked, steps documented in the README.

## Initial description
Module 3 (Enterprise AI Architecture) course exercise: "Plugin do analizy ofert z wykorzystaniem AI" (AI-based offer analysis plugin).
- AI integration: use BAML to call the LLM with a structured data format; all requests go through LiteLLM.
- Substantive analysis: automatically check the product description for correctness and quality, verify the assigned category fits, and assess whether the price deviates significantly from market norms.
- Deployment and tests: the plugin is available on port 3011. After implementation, verify in the LiteLLM panel that all model communication is processed and visible in the logs.

Course material: `_docs/AJ_M03L02` (LiteLLM gateway: unified API, Presidio PII, budgets, fallback, routing), `AJ_M03L03` (BAML: prompt-as-function, `ctx.output_format`, SAP parsing, tests, BAML + LiteLLM via an openai-compatible client with a logical model name), `AJ_M03L06` (ai-description walkthrough; exercise suggests e.g. checking category assignment through a backend plugin, BAML, and LiteLLM).

## Q&A (all rounds)
### Phase 1
1. "Automatically checks" → auto-run on tab open when there is no stored analysis or it is stale; a Re-analyze button is always available (editors).
2. Viewers (READ) → see saved results only; the backend refuses LLM calls without EDIT.
3. Surfacing → setData summary feeds the `product.list.filters` "needs review" boolean filter and the `product.detail.info` badge, in addition to the tab.
4. LiteLLM attribution → request metadata/tags on the shared key.
### Phase 2 (scope)
5. Staleness → content fingerprint: hash of name|description|price|sku|category id+name, stored with the analysis and recomputed by the tab. Not `updatedAt`, because that is the @Version column and every setData bumps it.
6. Price grounding → LLM market knowledge plus same-category catalog peers (min/median/max computed in code); prices in USD; confidence LOW/MEDIUM/HIGH; INSUFFICIENT_DATA verdict.
7. Category context → pass all categories (name + description); validate the suggested category against the real list in code.
8. needsReview → deterministic rule in code: description POOR, OR category MISMATCH, OR price ABOVE/BELOW market with |deviation| ≥ 30%. Uncertain verdicts do not flag.
9. Demo data → seed 1–2 deliberately flawed products in 013 (dev context, `ON CONFLICT`-safe, no clashes with host test search terms such as "trackball" or pluginData keys `reviews` and `seo`).
10. Badge refresh → BroadcastChannel between the tab and badge iframes (same origin :3011).
11. Shared server-sdk.ts 204 bug → not fixed; noted as a known issue.
12. LiteLLM metadata → static in clients.baml: tags [ai-offer-analysis], trace name, user "ai-offer-analysis". If body metadata is not forwarded, fall back to the `x-litellm-tags` header.
### Phase 4
13. Mockups approved: 7 screens (see `analysis/design-context/INDEX.md`).
### Phase 5
14. User journey confirmed: editor opens a product → tab auto-runs or shows the saved result → info badge shows the verdict → product list "AI: needs review" switch finds flagged offers.
15. Stack confirmed: clone ai-description (Next.js 15 Pages Router, BAML pinned 0.220.0, Jest); shipping's `canEdit`; 013 Liquibase dev seed plus documented curl.
16. Verification: run live end-to-end, check LiteLLM logs / `/spend/logs` for tag `ai-offer-analysis`, document the steps in the README.
17. Visual assets: the Phase 4 mockups are the only design input and are binding.

## Similar features / reuse
- `plugins/ai-description/*`: package.json scripts, next.config.js (`externalDir`), tsconfig, jest.config.js, `_document.tsx`, `baml_src/clients.baml` and `generators.baml`, API route pattern, Jest mocks of `baml_client` and `server-sdk`.
- `plugins/server-sdk.ts` `createServerSDK(pluginId, undefined, req)`: getProduct, getProducts({category}), fetch("/api/categories"), objects.save, setData.
- `plugins/sdk.ts` `getSDK()`: thisPlugin.productId, objects.get/listByEntity, getData, hostApp.getToken.
- `plugins/shipping/src/domain.ts` `canEdit(token)`.
- `plugins/box-size/src/pages/ProductBoxBadge.tsx`: 60px info badge pattern.
- `plugins/warehouse/manifest.json`: boolean `product.list.filters`.
- `src/main/resources/db/changelog/2026/012-insert-shipping-sample-data.yaml`: plugin row seed.

## Visual assets
`analysis/design-context/INDEX.md` lists 7 screens: analysis-tab-needs-review-editor, analysis-tab-looks-good, analysis-tab-auto-running, analysis-tab-viewer-saved-result, analysis-tab-viewer-no-analysis, analysis-tab-error, info-badge-states. Components: verdict-badge (danger/success/plain) and analysis-section-card.

## Functional requirements summary
- FR1: Plugin `ai-offer-analysis`, Next.js, `dev`/`start` on `-p 3011`, manifest url `http://localhost:3011`. Extension points: `product.detail.tabs` `/product-tab`, `product.detail.info` `/product-badge`, `product.list.filters` filterKey `needsReview` boolean.
- FR2: BAML function (e.g. `AnalyzeOffer`) returns a typed result: description {verdict GOOD/ACCEPTABLE/POOR, score 1–10, issues[], suggestions[]}; category {verdict FITS/QUESTIONABLE/MISMATCH, suggestedCategory?, reasoning}; price {verdict FAIR/ABOVE_MARKET/BELOW_MARKET/INSUFFICIENT_DATA, estimated market min/max USD, confidence, reasoning}. The client goes through LiteLLM at `:4000/v1` with model `gpt-4o-mini`, `api_key env.LITELLM_API_KEY`, and static attribution metadata.
- FR3: POST `/api/analyze` {productId}: validate `^\d+$`; require EDIT (decode JWT, 403 otherwise); getProduct (host 401/404 passes through); fetch categories; fetch same-category peers (excluding the product) and compute min/median/max; call BAML; compute deviation % and needsReview in code; validate suggestedCategory against the list; save object `analysis`/{productId} bound to PRODUCT with the fingerprint and analyzedAt; setData summary {needsReview, descriptionVerdict, categoryVerdict, priceVerdict, priceDeviationPct (int), fingerprint?}; return the analysis. Errors map to user-facing messages (LLM/gateway → 502); details are logged server-side only.
- FR4: Tab page: loads the saved analysis plus the product; recomputes the fingerprint; auto-runs for editors when there is no analysis or the fingerprint differs; in-flight guard; states per the mockups; broadcasts on completion.
- FR5: Badge page: reads getData summary; three states; re-reads on BroadcastChannel message.
- FR6: Fingerprint function shared between client and server (`domain.ts`), deterministic.
- FR7: 013 Liquibase dev seed: plugin row with manifest JSONB (ON CONFLICT DO NOTHING) plus 1–2 flawed demo products. Seeding must not break host integration tests.
- FR8: Tests: Jest for domain (fingerprint, needsReview rule, peer stats, canEdit, mapper) and API route (validation, 403 without EDIT, happy path, LLM error → 502, host 404 passthrough), with BAML and server-sdk mocked. Host test suite still passes with the seed.
- FR9: Docs: README (setup, registration, LiteLLM log verification), root CLAUDE.md port list, plugins/CLAUDE.md reference plugins, plugin-development standard port table, tech-stack plugin table.
- FR10: Live verification: run the stack, trigger analyses for a demo product and a clean product, and confirm in the LiteLLM UI / `/spend/logs` (tag or user `ai-offer-analysis`) and Langfuse.

## Scope boundaries
In scope: the new plugin, the 013 seed, docs, tests, live LiteLLM verification.
Out of scope: host Java/frontend changes, a bulk analysis page, a server-sdk.ts 204 fix, per-request user metadata, virtual LiteLLM keys, fixing ai-description's own defects.

## Technical considerations
- BAML generator version must equal the installed package version (pin exact).
- Presidio guardrail: CREDIT_CARD blocked; PHONE/EMAIL/11-digit numbers masked. Format prices as "USD 1799.99".
- The host tab panel is fixed at 600px and the info iframe at 60px.
- `productId` arrives as a number at runtime; wrap it in `String()`.
- Liquibase dev context also loads in host tests.
- Jest script must run on Windows (`node node_modules/jest/bin/jest.js`).
- Low temperature for stable verdicts; timeout on LLM calls.
