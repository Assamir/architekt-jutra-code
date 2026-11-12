# Gap Analysis: AI Offer Analysis Plugin (`ai-offer-analysis`, port 3011)

## TL;DR
- Nearly everything is new, and it sits inside `plugins/ai-offer-analysis/`. The host already exposes everything the plugin needs: product, price, category, the category list, peers by category, `plugin_objects`, `setData` and native filters. **No host Java changes.**
- **Critical design gap.** The planned staleness check (`product.updatedAt` ≠ stored value) is broken by the host's own behaviour. `Product.updatedAt` is the JPA `@Version` column, and the plugin's own `setData` (and any other plugin's `setData`) bumps it. That causes a re-analysis loop that spends tokens on every tab open.
- A second gap: the `product.detail.info` badge and the tab are separate iframes with no host refresh event. After an auto-run the badge stays stale until the page is reloaded.
- Seven decisions are open: staleness mechanism, market-price grounding, category list, `needsReview` rule, demo seed, badge refresh, and the shared `server-sdk` 204 fix. Risk: low-medium.

## Key Decisions
- **Characteristics.** `creates_new_entities` (a new plugin), `involves_data_operations` (analysis object plus summary node), and `ui_heavy` (tab, info badge, list filter). `modifies_existing_code` is set only for optional shared edits (013 seed, docs, possibly `server-sdk.ts`). No reproducible defect is in scope.
- **Change type: additive.** No existing behaviour changes, and compatibility is flexible.
- **EDIT gating is safe server-side even though the plugin decodes the JWT without verifying its signature.** The route must call `getProduct` with the forwarded token *before* calling BAML. A tampered token fails host validation (401), so no LLM call happens. Order: decode permissions → 403 if no EDIT → `getProduct` → peers and categories → BAML.
- **Already decided in Phase 1:** auto-run on tab open, viewer read-only, `setData` summary with filter and badge, and shared LiteLLM key with metadata/tags. These are not re-asked.

## Open Questions / Risks
- **Presidio false positives.** `PHONE_NUMBER: MASK` may hit long numeric runs, such as peer price lists or SKUs. Verify the masked prompt in the LiteLLM logs, and consider formatting prices with a currency prefix (`USD 1799.99`).
- **BAML option passthrough.** Check that the BAML 0.220 `openai` provider forwards a `metadata` object (tags, `trace_name`) to the request body and that LiteLLM `main-stable` honours it. The fallback is LiteLLM's `x-litellm-tags` header, set via BAML's `headers` option.
- **Tests run with the `dev` Liquibase context** (`application.properties: spring.liquibase.contexts=dev` and no test override). The 013 seed therefore loads in host integration tests. It must use `ON CONFLICT DO NOTHING`, and any seeded product must not collide with test search terms ("trackball") or with test filter keys such as `reviews` and `seo`.
- **LLM nondeterminism.** The price and category verdicts can flip between runs. Keep the BAML temperature low, and derive `needsReview` deterministically in code.
- **Concurrency.** Two editors, or a double effect, can trigger two LLM calls for the same product. That is acceptable but should be guarded with an in-flight flag in the UI. The Next.js Pages Router does not enable StrictMode by default, and the template's `next.config.js` doesn't either.

## Summary
- **Risk Level**: Low-Medium
- **Estimated Effort**: Medium (about 15 new files plus a seed and docs; prompt design and verification are the non-trivial parts)
- **Detected Characteristics**: creates_new_entities, involves_data_operations, ui_heavy (and modifies_existing_code only for optional shared files and docs)

## Task Characteristics
- Has reproducible defect: no
- Modifies existing code: no for the host. Optional for shared `plugins/server-sdk.ts`, the Liquibase seed (new file) and docs.
- Creates new entities: yes (plugin `ai-offer-analysis`, object type `analysis`, plugin data node `{needsReview, ...}`)
- Involves data operations: yes
- UI heavy: yes (3 extension points)

## Gaps Identified

### Missing Features (all greenfield)
| # | Gap | Evidence / basis |
|---|-----|------------------|
| G1 | Plugin project `plugins/ai-offer-analysis` (Next.js 15 Pages Router, BAML 0.220.0 pinned, `-p 3011` in dev and start) | No directory exists; the template is `plugins/ai-description` |
| G2 | BAML function `AnalyzeOffer` with a typed `OfferAnalysis` (description quality, category fit, price verdict, reasoning, confidence) | The template has only `GenerateProductDescription` (`baml_src/main.baml`) |
| G3 | BAML client pointed at LiteLLM with attribution metadata, a timeout and an env base URL | Template `clients.baml` has hardcoded `http://localhost:4000/v1`, no metadata and no timeout |
| G4 | `POST /api/analyze` route: EDIT gate, product, peers and categories fetch, BAML call, `objects.save` with PRODUCT binding, `setData` summary, sanitized errors | The template `generate.ts` has no EDIT gate and leaks `err.message` (`res.status(status).json({ error: message })`) |
| G5 | Tab page (`product.detail.tabs`): auto-run when missing or stale, read-only for viewers, "Re-analyze" button, fits the 600px panel | New |
| G6 | Info badge page (`product.detail.info`, 60px): verdict or "Not analyzed" | New; pattern in `plugins/box-size/src/pages/ProductBoxBadge.tsx` |
| G7 | Manifest with `product.list.filters` `{filterKey:"needsReview", filterType:"boolean"}` | New; pattern in `plugins/warehouse/manifest.json` |
| G8 | Registration: `013-insert-ai-offer-analysis-plugin.yaml` (context `dev`, `ON CONFLICT DO NOTHING`) | The latest file is `012-insert-shipping-sample-data.yaml`. Note that ai-description itself has **no** seed. |
| G9 | Jest tests: domain mappers, staleness or fingerprint, `needsReview` rule, route (405/400/403/404 passthrough/502 LLM/save arguments) | New |
| G10 | Docs: LiteLLM log verification steps (UI `:4000/ui` Logs, `GET /spend/logs`, Langfuse `:3100`) plus port, plugin and env lists | Not documented anywhere in the repo |

### Incomplete Features (template defects not to copy)
- `atob` JWT decode, which is not base64url-safe. Use the shipping `canEdit` (`plugins/shipping/src/domain.ts:110`).
- Caret version `^0.220.0` for `@boundaryml/baml` vs the generator's `0.220.0`. Pin it exactly.
- `.env.example` says `.env.local`. Committed `.playwright-mcp/` and `tsconfig.tsbuildinfo`. Jest script uses `node_modules/.bin/jest` (breaks on Windows).
- `getProduct` result is cast blindly, but `description` is nullable in `ProductResponse`.

### Behavioral Changes Needed
- None in the host. A shared-code fix is optional: `server-sdk.ts` `removeData`/`delete` run `hostFetchJson`, which calls `res.json()` on a 204 and throws. The new plugin doesn't need either call.

## Staleness Detection Gap (verified, drives decision D1)
- `BaseEntity.updatedAt` is `@Version LocalDateTime` (`src/main/java/pl/devstyle/aj/core/BaseEntity.java:33-35`).
- `PluginDataService.setData` modifies `product.pluginData` and calls `productRepository.saveAndFlush(product)` (`PluginDataService.java:43-57`). Hibernate therefore increments the version, and **`updatedAt` changes on every `setData` from any plugin**.
- Consequences of the codebase-analysis proposal (`analyzedProductUpdatedAt = product.updatedAt` read before the call):
  1. The plugin's own summary `setData`, written after the analysis, immediately makes the stored analysis stale. On the next tab open an EDIT user triggers another LLM call, and it loops forever.
  2. Warehouse stock toggles, box-size saves and similar actions also mark the analysis stale even though the description, price and category didn't change.
  3. Conversely, renaming a category does *not* bump the product's `updatedAt`, so a category-fit verdict can go stale silently.
- A content fingerprint fixes all three: a hash of `name|description|price|sku|category.id|category.name`, computed in the route and stored in the analysis. The tab recomputes it from `getProduct` (available through the browser SDK) and compares.

## User Journey Impact Assessment

| Dimension | Current | After | Assessment |
|-----------|---------|-------|------------|
| Reachability | none | Product detail → "Offer Analysis" tab (priority 90, after Shipping 80); list page "Needs review" switch; info strip under product card | OK |
| Discoverability | n/a | Tab 7/10 (standard tab pattern); badge 9/10 (always visible on detail); filter 8/10 (filter bar) | OK |
| Flow Integration | n/a | Auto-run on tab open, no extra steps; viewer sees last result | OK, but the badge is stale after an in-page run (D6) |
| Multi-persona | n/a | EDIT: run and re-run. READ: view only, with a hint when no analysis exists. ADMIN: as EDIT | OK. A READ user on a never-analyzed product sees an empty state, which is expected. |

## Data Lifecycle Analysis

### Entity: Offer analysis (`plugin_objects`, type `analysis`, id = productId, bound to PRODUCT)
| Operation | Backend | UI | Access | Status |
|-----------|---------|-----|--------|--------|
| CREATE | `PUT /api/plugins/{pid}/objects/analysis/{id}?entityType=PRODUCT&entityId=` (EDIT), via `/api/analyze` | Auto-run plus "Analyze" in tab | Tab on product detail | Planned and complete |
| READ | `GET /api/plugins/{pid}/objects?entityType=PRODUCT&entityId=` (READ) | Tab result cards | Tab | Planned and complete |
| UPDATE | Same PUT (upsert) | "Re-analyze" button, plus auto-run when stale | Tab | Planned and complete |
| DELETE | Exists in host; `server-sdk.delete` is buggy on 204 | None | None | Not required. When a product is deleted, check whether the bound objects are cleaned up (the host handles entity-bound objects; not in scope). |

### Entity: Summary node (`products.plugin_data.ai-offer-analysis` = `{needsReview, verdicts..., analyzedAt}`)
| Operation | Backend | UI | Access | Status |
|-----------|---------|-----|--------|--------|
| CREATE/UPDATE | `PUT .../products/{id}/data` (EDIT), written by the route after the save | none (side effect) | automatic | Planned |
| READ | `getData` / `product.pluginData`; `pluginFilter=ai-offer-analysis:needsReview:bool:true` | Info badge; native filter switch | Product detail, product list | Planned. The badge refreshes only on reload (D6). |
| DELETE | n/a | n/a | n/a | Not required |

**Completeness**: 100% of the required operations are planned (DELETE is not needed).
**Orphaned Operations**: none. Every write has a reader: tab and badge for the object, filter and badge for the summary.
**Missing Touchpoints**: none safety-critical. The `menu.main` overview page ("all products needing review") is already covered by the native filter, so it is not recommended.

## UI Impact
- The tab panel is a fixed 600px iframe (`ProductDetailPage.tsx` ~186-220). Three `tc-card` sections plus a summary must scroll inside the plugin root.
- The badge iframe is 60px: one line with a verdict `tc-badge`. `plugin-ui.css` has no warning variant, so the mapping is `tc-badge--success` (OK), `tc-badge--danger` (needs review) and plain `tc-badge` (not analyzed or insufficient data).
- Filter: boolean filters apply only when switched ON. Store `needsReview` as a JSON boolean at the top level of the node.
- Styling must use the `tc-*` classes, with SDK and CSS loaded in `_document.tsx` (Next.js requirement).

## Issues Requiring Decisions

### Critical (Must Decide Before Proceeding)
1. **D1 – How staleness is detected.** `updatedAt` is unusable as planned (see the Staleness Detection Gap).
   - Options: (A) content fingerprint of name, description, price, sku, category id and name, stored in the analysis and recomputed by the tab; (B) `updatedAt` re-read after the plugin's own `setData` (still flips on other plugins' writes and races); (C) a host change adding a content-only timestamp (out of scope, touches Java and a migration); (D) no staleness, run only when missing and leave the rest to Re-analyze (deviates from clarification 1).
   - Recommendation: **A**. It is deterministic and host-independent, avoids the token-burning loop and also catches category renames.

### Important (Should Decide)
2. **D2 – How "market norms" is grounded for the price check.**
   - Options: (A) LLM general knowledge only; (B) LLM knowledge plus in-catalog peers from the same category (`getProducts({category})`, excluding self), with min/median/max computed in code and passed to the prompt; (C) peer statistics only, deterministic, with no LLM price judgment.
   - Default: **B**, with an explicit USD assumption, a `confidence` field and an `INSUFFICIENT_DATA` verdict, since there are only 2-3 peers per seeded category.
3. **D3 – Category context passed to the LLM.**
   - Options: (A) the full category list (names plus descriptions; 5 seeded rows, cheap) so the LLM can suggest an *existing* better category, with the suggestion validated against the list in code; (B) only the assigned category, giving a fit verdict plus free-text reasoning.
   - Default: **A**.
4. **D4 – How `needsReview` (filter and badge) is derived.**
   - Options: (A) a deterministic rule in code from the typed verdicts: description POOR, category MISMATCH, or price ABOVE/BELOW_MARKET with |deviation| ≥ a threshold (e.g. 30%), where QUESTIONABLE, NEEDS_IMPROVEMENT and INSUFFICIENT_DATA don't flag; (B) a boolean returned by the LLM; (C) A, but any non-best verdict flags.
   - Default: **A**. It is stable, testable and explainable in the UI.
5. **D5 – A deliberately flawed demo product in the 013 seed.**
   - Options: (A) seed 1-2 flawed products, such as a one-line description, a wrong category, or a price like 9.99 for a TV, using the dev context, `ON CONFLICT`-safe SQL and names that don't clash with tests; (B) only document manual edit steps for the demo; (C) no demo data.
   - Default: **A**. The course exercise must show detections, and the 14 seeded products are all "clean".
6. **D6 – Refreshing the info badge after an in-page analysis.** The tab and badge are separate iframes, and the host has no refresh event (`PluginMessageHandler` message types contain none).
   - Options: (A) `BroadcastChannel("ai-offer-analysis")` between the two same-origin (3011) iframes, where the tab posts once it has saved and the badge re-reads; (B) accept a stale badge until reload; (C) a host change adding a plugin-to-plugin event (out of scope).
   - Default: **A**. It takes a few lines and has no host impact.
7. **D7 – Shared `plugins/server-sdk.ts` 204 bug (`removeData`/`delete` call `res.json()` on No Content).**
   - Options: (A) leave it alone, since the new plugin doesn't use those calls (minimal-implementation standard), and note it as a known issue; (B) fix it in scope (about 3 lines: return `undefined` on 204) with a test.
   - Default: **A**, unless the user wants shared hygiene in this task.
8. **D8 – Shape of the LiteLLM attribution metadata** (Phase 1 already chose metadata/tags; this is about the content only).
   - Options: (A) static values in `clients.baml` (`metadata.tags ["ai-offer-analysis"]`, `trace_name`, `user "ai-offer-analysis"`); (B) A plus per-request dynamic metadata (product id, aj user `sub`) via the BAML `ClientRegistry`.
   - Default: **A**. It is enough to filter LiteLLM logs and Langfuse and keeps the BAML config simple. B adds per-user traceability, at the cost of user identifiers going into the logs.

## Recommendations
- Clone `ai-description`, then fix the template defects listed above. Set the port to 3011 in both `dev` and `start`.
- Route order: method check → `productId` `^\d+$` → EDIT decode (403) → `getProduct` (host validates the token) → peers and categories → BAML (with timeout) → `objects.save` (with binding, fingerprint, `analyzedAt`, model) → `setData` summary → 200. Map BAML or LiteLLM failures, including a Presidio BLOCK (400 from LiteLLM), to a generic 502 with a readable message and log the details server-side.
- Keep `needsReview` and fingerprint logic in `src/domain.ts` as pure functions for unit tests.
- Add a "Verifying in LiteLLM" section to the plugin README or `plugins/CLAUDE.md`: UI Logs filtered by tag, `/spend/logs`, and Langfuse "AJ Plugins".
- Update the port, plugin and env lists in README, CLAUDE.md, plugins/CLAUDE.md, tech-stack.md, plugin-development.md and conventions.md.

## Risk Assessment
- **Complexity Risk**: Medium. Prompt design for the price and category judgments and the fingerprint/`needsReview` logic carry the risk; the scaffolding is copied.
- **Integration Risk**: Low-Medium. External infrastructure (OpenAI key in `litellm/.env`, LiteLLM `main-stable`, Presidio masking, BAML metadata passthrough) has to be verified manually.
- **Regression Risk**: Low. The plugin is isolated. The only shared touchpoint is the 013 seed loading in host tests (dev context); keep it collision-free.
