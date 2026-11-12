# Codebase Analysis Report

**Date**: 2026-11-12
**Task**: New plugin "AI offer analysis" (`ai-offer-analysis`, port 3011): BAML + LiteLLM structured LLM analysis of product description quality, category fit and price deviation
**Description**: Create a new plugin "AI offer analysis" (port 3011) that integrates with the aj host backend and uses BAML to call an LLM with structured output, all LLM traffic routed through the LiteLLM proxy. It automatically checks product description quality, verifies the assigned category fits, and assesses whether the price deviates significantly from market norms. After implementation, verify in the LiteLLM panel that all model communication is processed and visible in logs. (Module 3 "Enterprise AI Architecture" course exercise: LiteLLM as AI gateway with Presidio guardrails + Langfuse observability; BAML typed prompt-as-function with `ctx.output_format` and SAP parsing; BAML `openai`/`openai-generic` client pointed at LiteLLM `base_url` with the LiteLLM key and a logical model name.)
**Analyzer**: codebase-analyzer skill (3 Explore agents: File Discovery + Code Analysis, Pattern Mining, Context Discovery)

---

## TL;DR
- `plugins/ai-description` (Next.js 15 Pages Router + BAML 0.220 + LiteLLM, port 3003) already implements ~90% of the needed pattern: BAML client on `http://localhost:4000/v1`, a server-side API route using `createServerSDK`, and storage of the result in `plugin_objects` bound to PRODUCT. Clone it into `plugins/ai-offer-analysis`; don't build on the Vite plugins.
- No host backend changes are needed. The read APIs (product incl. price and category, categories, peer products by category) and plugin storage already exist; registration can be a Liquibase dev seed `013-*` following the `012` shipping precedent.
- Use the logical model `gpt-4o-mini`. `claude-sonnet` would fail locally because `ANTHROPIC_API_KEY` is absent from `litellm/.env`.
- Verify LiteLLM logs via the UI at `http://localhost:4000/ui` (Logs page), `GET /spend/logs`, or the `"LiteLLM_SpendLogs"` table on port 5434. Langfuse traces are on :3100.
- Don't copy the template's defects: leaked raw errors, 2 broken tests, `atob` JWT decoding, ungated LLM calls, committed `.playwright-mcp/` and `tsbuildinfo` files.

## Key Decisions
- **Template = `plugins/ai-description`, not shipping/warehouse (Vite).** Only it has the BAML toolchain, a server-side API route (the LLM call stays off the browser and avoids the SDK's 10s postMessage timeout), and the `_document.tsx` SDK/CSS loading for Next.js.
- **Plugin id `ai-offer-analysis`, package `ai-offer-analysis-plugin`, port 3011.** 3011 is reserved in the standards for the "next business module". Use `next dev -p 3011` and `next start -p 3011`. The manifest url must be exactly `http://localhost:3011`.
- **Single BAML function with a typed result class.** It covers three sub-assessments (description quality, category fit, price assessment) with enums/verdicts plus reasoning. `{{ ctx.output_format }}` in the prompt and SAP parsing give typed output.
- **Price baseline = LLM market knowledge plus in-catalog peers.** Peers come from `getProducts({category})` and the price is assumed to be USD (the host has no currency field and the UI renders `$`).
- **Persistence:** call `objects.save("analysis", productId, result, {entityType:"PRODUCT", entityId})` for the full result. Optionally add a compact `setData(productId, {needsReview, ...})` so the native `product.list.filters` and a `product.detail.info` badge can use it.
- **"Automatic" check:** on tab open, auto-run when there's no stored analysis or it is stale (`product.updatedAt` ≠ stored `analyzedProductUpdatedAt`), and only when the user has EDIT permission. Otherwise show the stored result or read-only state.
- **Registration via Liquibase dev seed `013-insert-ai-offer-analysis-plugin.yaml`** (`INSERT INTO plugins ... ON CONFLICT (id) DO NOTHING`, context `dev`). Document a curl `PUT /api/plugins/ai-offer-analysis/manifest` fallback.
- **Keep LiteLLM traffic attributable.** Pass BAML metadata/tags or a per-plugin virtual key so the plugin's calls can be told apart from ai-description in the LiteLLM logs. Minimum viable: the master key plus a distinct `user` or tag.

## Open Questions / Risks
- **Interpretation of "automatically".** Options are auto-run on tab open (recommended), a bulk "analyze all" from a `menu.main` page, or both. This affects scope and token spend.
- **Viewer (READ-only) behaviour.** Options are to show stored results only, or to run the analysis without saving (spends tokens). Recommendation: no LLM call without EDIT.
- **Market price baseline.** The LLM's general knowledge may be stale or hallucinated, and peer products are few (about 2-4 per category in the seed data). The output should carry a confidence value and an "insufficient data" verdict.
- **Seed data is "clean".** All ~14 seeded products have rich descriptions, correct categories and realistic prices, so the demo needs an edited product (short description, wrong category, price 9.99 or 9999) to show detections. A seeded "bad" product could be considered.
- **Presidio guardrail.** Credit-card-like numbers are BLOCKED, and 11-digit numbers (PESEL regex) are masked. SKUs or descriptions with long digit runs could be masked or blocked and should be handled gracefully.
- **No timeout on the LLM call in the template.** Configure a BAML client timeout or HTTP options.
- **`server-sdk.ts` bug.** `removeData` and `delete` call `res.json()` on a 204 and throw. Avoid these calls or fix the bug, which is shared code.
- **`plugin-sdk/types.ts` `Product` type doesn't match the real payload.** Validate and normalize the `getProduct` result (description is nullable) instead of casting it.
- **Windows Jest script.** `node --experimental-vm-modules node_modules/.bin/jest` may fail on Windows. Use `node node_modules/jest/bin/jest.js`.
- **`start-dev` scripts** don't run `npm install` or `baml generate`. `baml_client` is produced by `postinstall`, so first-time setup must be documented.
- **LiteLLM log verification is undocumented in the repo.** It has to be written down as part of this task.

---

## Summary

Every building block for the new plugin already exists. The host exposes products (price, category, description), categories, peer products by category, and two plugin storage mechanisms. `plugins/ai-description` provides a working Next.js + BAML + LiteLLM template, and `compose.yml` already runs LiteLLM with Presidio guardrails, Langfuse callbacks and spend logs persisted to Postgres. The work is mostly greenfield inside `plugins/ai-offer-analysis/`, plus one Liquibase dev seed and documentation updates. The main quality risks are copying the template's known defects and designing a robust price-deviation prompt.

---

## Files Identified

### Primary Files (template and contracts)

**plugins/ai-description/baml_src/clients.baml** (19 lines)
- `LiteLlmProvider` (provider `openai`, model `gpt-4o-mini`, `api_key env.LITELLM_API_KEY`, `base_url "http://localhost:4000/v1"`) plus a `LlmProvider` fallback wrapper.
- Reuse it as-is. Consider `env.LITELLM_BASE_URL` instead of the hardcoded host, and add a timeout.

**plugins/ai-description/baml_src/generators.baml** (5 lines)
- TypeScript generator, `output_dir "../"`, `version "0.220.0"`. The npm `@boundaryml/baml` version must match this exactly; pin it rather than using `^`.

**plugins/ai-description/baml_src/main.baml** (31 lines)
- Example class plus a function with Jinja conditionals and `{{ ctx.output_format }}`. This is the pattern for `AnalyzeOffer`.

**plugins/ai-description/src/pages/api/generate.ts** (76 lines)
- POST-only route: validate productId → `createServerSDK("ai-description", undefined, req)` → `getProduct` → `b.GenerateProductDescription` → `objects.save(... {entityType:"PRODUCT"})`.
- Template for `/api/analyze`. Its error handling leaks raw messages, and its regex maps a product-fetch failure to 404.

**plugins/ai-description/src/pages/product-tab.tsx** (165 lines)
- `product.detail.tabs` UI: SSR-guarded `getSDK()`, JWT → canEdit, `objects.listByEntity`, `fetch('/api/generate')` with Bearer, `tc-*` classes, loading states.

**plugins/ai-description/src/pages/_document.tsx** (16 lines)
- Loads `http://localhost:8080/assets/plugin-sdk.js` and `plugin-ui.css` in `<Head>`. This is mandatory for Next.js plugins.

**plugins/ai-description/src/domain.ts** (21 lines)
- Mapper `toProductDescription(obj: PluginObject)` with defaults. Pattern for `toOfferAnalysis`.

**plugins/ai-description/{package.json, next.config.js, tsconfig.json, jest.config.js, .env.example, .gitignore, manifest.json}**
- Project scaffolding. `next.config.js` needs `experimental.externalDir: true` for the `../../sdk` and `../../server-sdk` imports. Jest uses the `ts-jest` preset with a node env. `.env.example` holds `LITELLM_API_KEY` (its comment wrongly says `.env.local`).

**plugins/server-sdk.ts** (144 lines)
- `createServerSDK(pluginId, hostBaseUrl?, request?)` forwards the user's bearer token. It provides `hostApp.getProducts/getProduct/fetch` and `thisPlugin` data/objects. Non-2xx responses throw `Error("Host API error <status> ...")`. Bug: `removeData`/`delete` call `.json()` on a 204.

**plugins/sdk.ts** (102 lines)
- Browser SDK types (`getSDK`, `PluginObject`, `hostApp.*`, `thisPlugin.*`). There is no category API; use `fetch('/api/categories')`.

**plugins/CLAUDE.md** (408 lines)
- Mandatory plugin rules: manifest format, extension points, SDK API, storage patterns, `tc-*` styling.

**.maister/docs/standards/plugins/plugin-development.md**
- Port 3011 = next business module, `strictPort`, `<id>-plugin` naming, React 19, TS ~5.9.3, `src/domain.ts` and `src/pages/` layout, try/catch around every SDK call.

**.maister/docs/standards/backend/plugin-auth.md**
- `getToken` → own backend → `createServerSDK(id, undefined, req)`; canEdit decoded from the JWT `permissions` claim.

**litellm/config.yaml** (32 lines)
- `model_list`: `gpt-4o-mini` → `openai/gpt-4o-mini`; `claude-sonnet` → `anthropic/claude-sonnet-4-20250514`.
- Guardrail `presidio-pii` (pre_call, default on): CREDIT_CARD is blocked; PHONE_NUMBER, EMAIL_ADDRESS and PL_PESEL are masked.
- `store_prompts_in_spend_logs: true`; Langfuse success/failure callbacks.

**compose.yml** (249 lines)
- `litellm` on :4000 (config mount, `env_file litellm/.env`, DB on `litellm-db` :5434), Langfuse :3100 (project "AJ Plugins", admin@aj.local), Presidio analyzer :5002 / anonymizer :5001, app Postgres :5435.

### Related Files

**plugins/shipping/src/domain.ts** (114 lines)
- `canEdit(token)` (lines ~104-114) with base64url-safe JWT decoding and unit tests. Prefer it over the template's `atob`.

**src/main/resources/db/changelog/2026/012-insert-shipping-sample-data.yaml** (53 lines)
- Precedent for plugin auto-registration (`INSERT INTO plugins ... ON CONFLICT (id) DO NOTHING`, context `dev`). The next file number is `013`.

**plugins/box-size/src/pages/ProductBoxBadge.tsx**
- Compact `product.detail.info` badge pattern (60px iframe). Optional for a "needs review" badge.

**plugins/warehouse/manifest.json, src/pages/WarehousePage.tsx:121**
- `product.list.filters` plus `setData(productId, {stock: bool})`. Pattern for an optional `needsReview` boolean filter.

**src/main/java/pl/devstyle/aj/product/ProductResponse.java** (36 lines)
- `{id, name, description|null (≤2000), photoUrl, price BigDecimal(19,2) → JSON number, sku, category: CategoryResponse, pluginData|null, createdAt, updatedAt}`. No currency field and no top-level categoryId.

**src/main/java/pl/devstyle/aj/category/***
- `CategoryResponse {id, name ≤100, description|null ≤500, createdAt, updatedAt}`; `GET /api/categories` (sorted by createdAt desc) and `GET /api/categories/{id}`.

**src/main/java/pl/devstyle/aj/core/plugin/***
- Manifest `PUT /api/plugins/{id}/manifest` (id `^[a-zA-Z0-9_-]+$`, name non-blank, url http(s), upsert, new plugins start enabled). Data `/api/plugins/{pid}/products/{productId}/data`. Objects `/api/plugins/{pid}/objects/...`. The plugin must be registered and enabled or calls return 404.

**src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java** (176 lines)
- GET on products/categories requires READ or mcp:read. GET on plugin data/objects requires READ. PUT/DELETE on data/objects requires EDIT. Manifest upload requires PLUGIN_MANAGEMENT. CORS `localhost:*` already covers 3011. No change needed.

**src/main/frontend/src/plugins/PluginMessageHandler.ts** (236 lines), **PluginFrame.tsx**, **PluginFilterBar.tsx**
- Host side of the iframe RPC: origin check against the manifest url, and context passed through the iframe name/hash (`PRODUCT_DETAIL`, `productId: number`). The filter bar renders `product.list.filters` natively.

**src/main/frontend/src/pages/ProductDetailPage.tsx** (221 lines)
- Lines ~186-220: info iframe is 60px and the tab panel is 600px. The tab UI must fit or scroll within 600px.

**src/main/frontend/src/components/shared/Icons.tsx**
- `ICON_MAP` (package, layout-list, plug, warehouse, footprint, truck). Only relevant if a `menu.main` entry with a new icon is added.

**src/main/resources/db/changelog/2026/003-insert-sample-data.yaml**
- 5 categories (Televisions, Audio Systems, Smart Home, Networking, Home Appliances) and ~14 products with realistic USD prices.

**start-dev.sh / start-dev.ps1**
- Automatically run `npm run dev` for every `plugins/*/` that has a `package.json`, so a new plugin starts without script changes. They don't run `npm install` or BAML generation.

**litellm/.env.example**, **.gitignore** (root)
- The root `.gitignore` only covers `plugins/*/node_modules/`, `plugins/*/dist/`, `litellm/.env`, `.playwright-mcp/` and `*.tsbuildinfo`. The plugin needs its own `.gitignore` for `.next/`, `baml_client/`, `.env` and `.env*.local`.

**Documentation to update:** README.md (~line 25, Quick Start), root CLAUDE.md (~line 39, plugin list/ports), plugins/CLAUDE.md, .maister/docs/project/tech-stack.md (lines 41-49), .maister/docs/standards/plugins/plugin-development.md (port list), .maister/docs/standards/global/conventions.md (env-file list).

---

## Current Functionality

### Existing AI plugin flow (ai-description)
1. Host `PluginFrame` renders the iframe at `new URL(path, manifest.url)` and passes the `PRODUCT_DETAIL` context `{pluginId, pluginName, hostOrigin, productId}`.
2. The tab page calls `getSDK()` (SSR-guarded), reads `productId`, calls `hostApp.getToken()`, and decodes permissions to get canEdit.
3. It loads stored results with `thisPlugin.objects.listByEntity("PRODUCT", productId)`.
4. On user action it makes a same-origin `fetch('/api/generate', {POST, Authorization: Bearer})`. This bypasses the SDK's 10s RPC timeout, and no CORS is involved.
5. The API route calls `createServerSDK(...)` → `getProduct`, then the BAML function → LiteLLM `:4000/v1` → Presidio pre-call → OpenAI → Langfuse callback and SpendLogs row, then `objects.save` and returns JSON.

### Key Components/Functions
- **`createServerSDK`**: server-side host client that forwards the user's JWT.
- **`hostApp.getProduct(id)`**: returns the full `ProductResponse`, including `price` and `category`.
- **`hostApp.getProducts({category})`**: peer products for an in-catalog price baseline (the server SDK supports `pluginFilter`; the browser SDK only forwards category/search/sort).
- **`hostApp.fetch('/api/categories')`**: full category list for the category-fit check (the LLM can suggest a better existing category).
- **`thisPlugin.objects.save/listByEntity/get`**: plugin-owned collection with PRODUCT binding. The binding must be passed on every save, otherwise it is stripped.
- **`thisPlugin.setData`**: replaces the plugin's whole node in `products.plugin_data`. It feeds the native list filters (top-level keys only; boolean filters apply only when ON; number filters compare as text, so store integers; string filters are exact and case-sensitive).
- **BAML `b.<Function>`**: typed, SAP-parsed structured output.

### Data Flow (target)
```
iframe tab (3011) --getToken--> host
iframe --POST /api/analyze (Bearer)--> Next API route (3011)
  route --GET /api/products/{id}, /api/categories, /api/products?category=--> host (8080)
  route --b.AnalyzeOffer()--> LiteLLM :4000/v1 --Presidio--> OpenAI gpt-4o-mini
                                        \--> litellm-db SpendLogs, Langfuse traces
  route --PUT /api/plugins/ai-offer-analysis/objects/analysis/{id}?entityType=PRODUCT&entityId={id}--> host
  [optional] route --PUT .../products/{id}/data {needsReview,...}--> host
iframe <-- typed analysis JSON
```

---

## Dependencies

### Imports (What the new plugin depends on)
- `@boundaryml/baml` 0.220.0 (pinned, matching `generators.baml`): typed LLM functions.
- `next` ^15.3.3, `react`/`react-dom` ^19.2.4: plugin runtime.
- `../../sdk` (browser types/SDK) and `../../server-sdk` (host client) via `externalDir`.
- Host REST API at :8080: products, categories, plugin objects/data.
- LiteLLM proxy at :4000, with `LITELLM_API_KEY` from the plugin's `.env`.
- Dev dependencies: jest ^29.7, ts-jest ^29.3, node-mocks-http ^1.16, @jest/globals, @types/*, typescript ~5.9.3.

### Consumers (What depends on this)
- **Host frontend** (`ProductDetailPage`, `PluginFrame`, optionally `PluginFilterBar`): renders the plugin's extension points from the manifest.
- **`plugins` table** (via the 013 seed or manifest PUT): registration record.

**Consumer Count**: 0 code consumers (new, isolated plugin); 1 integration surface (the host's extension-point rendering)
**Impact Scope**: Low. The plugin is additive and isolated; no existing code paths change. Shared files (`server-sdk.ts`, docs) are touched only optionally.

---

## Test Coverage

### Test Files (template)
- **plugins/ai-description/src/__tests__/domain.test.ts** (57 lines): mapper defaults.
- **plugins/ai-description/src/__tests__/generate.test.ts** (180 lines): API route with `node-mocks-http`, `jest.mock("../../baml_client")` and `jest.mock("../../../server-sdk")` (hoisted `jest.fn`). It covers 400 missing productId, 200 happy path, save arguments, passthrough, BAML failure, and product fetch failure.
- **plugins/shipping** domain tests: `canEdit` coverage.

### Coverage Assessment
- **Test count**: ~2 test files and about 6-8 route cases in the template. Two of those cases probably fail today: one expects no error leak, and one expects 500 where the handler maps to 404.
- **Gaps**: no tests for UI components in the Next.js plugins, no end-to-end LLM/LiteLLM test, and no BAML prompt tests. The new plugin should add domain mapper tests, API route tests (validation, 405, EDIT gating, error mapping, save arguments including entity binding, peer-product baseline), and optionally BAML `test` blocks in `.baml`.

---

## Coding Patterns

### Naming Conventions
- **Plugin dir/id**: kebab-case `ai-offer-analysis`; package `ai-offer-analysis-plugin`, private, package version equals manifest version.
- **Components**: named `export function` in `src/pages/*.tsx`; Next.js routes need a default export per page (framework requirement, as in the template).
- **Domain**: `src/domain.ts` with interfaces and `to<X>(obj: PluginObject)` mappers.
- **BAML**: PascalCase classes/enums/functions (`OfferAnalysis`, `AnalyzeOffer`).
- **Tests**: `src/__tests__/*.test.ts`.

### Architecture Patterns
- **Style**: functional React with hooks and local state. A thin Next.js API route acts as the backend-for-frontend.
- **State**: local `useState`. Results are persisted in host `plugin_objects`; the plugin keeps no database of its own.
- **Styling**: host `plugin-ui.css` `tc-*` classes, with inline styles for layout only. Available classes: `tc-plugin`, `tc-primary-button`, `tc-ghost-button(--danger)`, `tc-input`, `tc-select`, `tc-table`, `tc-badge`, `tc-badge--success`, `tc-badge--danger`, `tc-card`, `tc-section`, `tc-flex`, `tc-error`. There is no warning or neutral badge, so use plain `tc-badge` for "borderline".
- **Secrets**: plugin `.env` holds only `LITELLM_API_KEY` (optionally `HOST_BASE_URL` and `LITELLM_BASE_URL`); provider keys live only in `litellm/.env`; commit `.env.example`.

---

## Complexity Assessment

| Factor | Value | Level |
|--------|-------|-------|
| File Size | ~12-15 new files, each <200 lines (template sizes: 16-180 lines) | Medium |
| Dependencies | ~5 runtime (BAML, Next, React, SDK, server-SDK) + host API + LiteLLM | Medium |
| Consumers | 0 code consumers; 1 host integration surface | Low |
| Test Coverage | Template has partial route/mapper tests, 2 likely broken | Medium |

### Overall: Moderate
The structure is a near-copy of an existing plugin, but it adds new domain logic: a three-part structured LLM assessment, a peer-price baseline, a staleness/auto-run policy and permission gating. It also crosses infrastructure boundaries (BAML codegen, LiteLLM/Presidio/Langfuse) and needs operational verification steps.

---

## Key Findings

### Strengths
- A complete working template exists for Next.js + BAML + LiteLLM + server SDK + `plugin_objects` persistence.
- The host already exposes everything needed: price, category, categories list, peers by category, storage and filters. No backend changes are required.
- LiteLLM is already configured with spend-log persistence (`store_prompts_in_spend_logs`), Presidio guardrails and Langfuse callbacks, so the verification step mostly works out of the box.
- `start-dev` scripts pick up new plugins automatically, CORS already covers `localhost:*`, and the 012 seed sets the auto-registration precedent.

### Concerns
- Template defects that are easy to copy by accident: raw error leakage, `atob` JWT decoding, LLM calls not gated on EDIT (a READ user spends tokens and the save then returns 403), unsafe `getProduct` cast, caret BAML version drift, committed `.playwright-mcp/` and `tsconfig.tsbuildinfo`, `.env.local` wording, the unused `@sdk` alias, and 2 failing tests.
- The SDK `Product` type doesn't match the real payload, and `server-sdk.ts` throws on 204 responses.
- Fixed iframe heights (600px tab, 60px info).
- No currency field, so the price analysis has to assume USD.
- Seed data won't show detections without manual edits.
- Nothing in the repo documents how to verify LiteLLM logs.

### Opportunities
- Store a compact `setData` summary (for example `needsReview: true`) to enable a native list filter "Needs review" and an info badge.
- Use a per-plugin LiteLLM virtual key (`POST /key/generate`, `store_model_in_db` is on) or BAML request metadata/tags so the plugin's traffic is distinguishable in LiteLLM logs and Langfuse. This also fits the course's gateway theme.
- Add a `LITELLM_BASE_URL` env variable in `clients.baml` instead of the hardcoded host.
- Optionally fix the `server-sdk.ts` 204 bug, since it is a shared improvement.

---

## Impact Assessment

- **Primary changes (new)**: `plugins/ai-offer-analysis/` with `package.json`, `package-lock.json`, `manifest.json`, `next.config.js`, `tsconfig.json`, `jest.config.js`, `.env.example`, `.gitignore`, `next-env.d.ts`, `baml_src/{clients,generators,main}.baml`, `src/domain.ts`, `src/pages/_document.tsx`, `src/pages/product-tab.tsx` (or `offer-analysis.tsx`), `src/pages/api/analyze.ts`, and `src/__tests__/{domain,analyze}.test.ts`.
- **Related changes**: `src/main/resources/db/changelog/2026/013-insert-ai-offer-analysis-plugin.yaml` (dev seed); docs (README.md, CLAUDE.md, plugins/CLAUDE.md, tech-stack.md, plugin-development.md ports, conventions.md env files). Optional: `plugins/server-sdk.ts` 204 fix; `Icons.tsx` only if a `menu.main` entry with a new icon is added.
- **Test updates**: new Jest suites in the plugin. The host suite is unaffected, except that the new seed loads in the `dev` context only (confirm the tests don't run with `dev`).

### Risk Level: Low-Medium
The plugin is additive and isolated, and no existing code paths change. The medium part comes from LLM output quality and nondeterminism in the price and category judgments, the external infrastructure dependencies (LiteLLM key, OpenAI key, Presidio masking), permission/token-spend edge cases, and the manual verification step in the LiteLLM UI.

---

## Recommendations

**Type: new capability, cloned from an existing pattern**

1. **Scaffold** by copying `plugins/ai-description` to `plugins/ai-offer-analysis`. Then:
   - Rename id and package; set the port to 3011 in `dev` and `start`.
   - Pin `@boundaryml/baml` to `0.220.0`.
   - Write a clean `.gitignore` covering `node_modules/`, `.next/`, `baml_client/`, `.env`, `.env*.local` and `*.tsbuildinfo`.
   - Fix the `.env.example` comment to say `.env`.
   - Drop the unused `@sdk` alias, and don't copy `.playwright-mcp/` or `tsbuildinfo`.
   - Make the Jest script Windows-safe.
2. **BAML** (`baml_src/main.baml`):
   - Enums: `DescriptionQuality {GOOD, NEEDS_IMPROVEMENT, POOR}`, `CategoryFit {FITS, QUESTIONABLE, MISMATCH}`, `PriceVerdict {WITHIN_MARKET, BELOW_MARKET, ABOVE_MARKET, INSUFFICIENT_DATA}`.
   - Class `OfferAnalysis` with a nested `descriptionAssessment {quality, score int 1-10, issues string[], suggestions string[]}`, `categoryAssessment {fit, suggestedCategory string?, reasoning}`, `priceAssessment {verdict, estimatedMarketMin/Max float?, deviationPercent float?, confidence, reasoning}`, `summary`, and `needsReview bool`. Add `@description` annotations.
   - Function `AnalyzeOffer(product ProductInput, availableCategories string[], peerProducts PeerProduct[]) -> OfferAnalysis` using `client LlmProvider` and `{{ ctx.output_format }}`. Make "USD" and the instruction to compare against general market knowledge and peers explicit in the prompt.
   - Add a BAML `test` block for one seeded product. Keep the model `gpt-4o-mini`. Use `env.LITELLM_BASE_URL` with a default, and set a client timeout.
3. **API route `/api/analyze`**:
   - POST only (405 otherwise); validate `productId` with `^\d+$`.
   - Decode the bearer token's permissions and return 403 before any BAML call if EDIT is missing.
   - Call `createServerSDK("ai-offer-analysis", undefined, req)`, then `getProduct`, then normalize fields (description nullable, price as a number, category name).
   - Fetch `/api/categories` and `getProducts({category})` for peers, excluding the product itself.
   - Call `b.AnalyzeOffer`, then `objects.save("analysis", productId, {...result, analyzedAt, analyzedProductUpdatedAt}, {entityType:"PRODUCT", entityId: productId})`, plus an optional compact `setData`.
   - Error mapping: pass through only host 401/403/404 and return a generic 502/500 message for LLM failures. Log the details server-side.
4. **UI `product-tab.tsx`**:
   - Root `tc-plugin` with three `tc-card` sections plus a summary. Show verdicts with `tc-badge--success`, `tc-badge--danger` or plain `tc-badge`.
   - Auto-run when there's no stored result or it is stale and the user can edit. Otherwise show the stored result or a read-only hint. Provide a "Re-analyze" button.
   - Use the base64url-safe `canEdit` from the shipping plugin, include loading and error states, and fit within the 600px panel.
5. **Manifest**: `product.detail.tabs` with label "Offer Analysis", path `/product-tab`, priority 90 (after shipping's 80). Optionally add a `product.list.filters` boolean `needsReview` and a `product.detail.info` badge at priority 30.
6. **Registration**: add the `013` Liquibase dev seed, mirroring `012`, and document the curl fallback (admin login, then `PUT` manifest).
7. **Tests**: domain mapper tests, and route tests with `jest.mock` of `baml_client` and `server-sdk` covering 405, 400, 403 without EDIT, success with save arguments and entity binding, peer fetch, LLM failure (generic message), and host 404 passthrough.
8. **LiteLLM verification and docs**:
   - Document the steps: start compose, open `http://localhost:4000/ui` with `LITELLM_MASTER_KEY`, check the Logs page for requests with model `gpt-4o-mini` and their prompts and responses, cross-check with `curl -H "Authorization: Bearer $LITELLM_MASTER_KEY" http://localhost:4000/spend/logs`, and optionally check Langfuse at :3100 (project "AJ Plugins").
   - Optionally generate a dedicated virtual key for the plugin so its traffic is attributable.
   - Update the port, plugin and env lists in README, CLAUDE.md, plugins/CLAUDE.md, tech-stack.md and the standards.

---

## Next Steps

Invoke the **gap-analyzer** with this report. The decisions to settle with the user are:
- (a) what "automatically" means: auto-run on tab open vs bulk analysis;
- (b) viewer behaviour, and whether the LLM call is gated on EDIT;
- (c) whether to add a list filter or info badge via `setData`;
- (d) whether to use a per-plugin LiteLLM virtual key or metadata for log attribution;
- (e) whether to seed a deliberately "bad" demo product;
- (f) whether to fix the shared `server-sdk.ts` 204 bug in scope.
