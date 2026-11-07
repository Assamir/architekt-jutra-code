# Codebase Analysis Report

**Date**: 2026-11-07
**Task**: New iframe plugin `plugins/shipping` (Vite + React, port 3010) for carriers, delivery methods and per-product method opt-out
**Description**: Build a new iframe plugin `plugins/shipping` (Vite + React, dev port 3010, strictPort) for the aj microkernel host: (1) global management of carriers (DHL, DPD, InPost, Poczta Polska…) and delivery methods as plugin objects (types `carrier`, `shipping-method`; methods have an active flag and optional max L×W×H cm); (2) per-product opt-out of methods stored as product pluginData `{disabledMethodIds: string[]}`; the product tab warns (no auto-disable) when box-size plugin dimensions (read via `hostApp.getProduct(id).pluginData['box-size']`) exceed method limits; (3) admin = PERMISSION_EDIT, UI hides write controls via the JWT permissions claim; no backend changes expected; docs reserve 3010+ for new business modules. Pre-agreed plan: `analysis/initial-plan.md`.
**Analyzer**: codebase-analyzer skill (2 Explore agents: File Discovery + Code Analysis, Pattern Mining)

---

## TL;DR
The host already supports everything the plugin needs: plugin objects, per-product pluginData, a full product read including `pluginData['box-size']`, and EDIT-gated writes. The backend needs no changes. The one host change is a single line: `menu.main` icon `truck` currently renders as the generic Plug icon because `resolveIcon` rejects lucide's forwardRef objects, so add `truck: Truck` to `ICON_MAP`. Three runtime details will catch you out if missed. `productId` arrives in the context as a number. Deleting an object that doesn't exist returns 404. The plugin must be registered by uploading its manifest with an admin token, and its origin must be exactly `http://localhost:3010`. Most of the work is a near-copy of the `warehouse` plugin, plus the `canEdit` pattern from ai-description.

## Key Decisions
- **Add `truck: Truck` to `ICON_MAP` in `src/main/frontend/src/components/shared/Icons.tsx`.** This is the only host-code change. It is the minimal, explicit fix. Loosening the `typeof === "function"` check would change behaviour for every plugin, so a one-line map entry is safer and matches how the five existing icons are mapped.
- **Use `plugins/warehouse` as the scaffold template (files, configs, `main.tsx` router, page patterns), and add ProductBoxTab's save flow and ai-description's `canEdit`.** Warehouse is the most complete Vite plugin and the template the standard recommends.
- **Manifest `url` is the bare origin `http://localhost:3010`.** PluginFrame resolves paths with `new URL(path, pluginUrl)`, so any path inside the url is discarded. PluginMessageHandler also compares `event.origin` exactly, so `127.0.0.1` or a trailing path breaks RPC.
- **Normalise `productId` with `String(sdk.thisPlugin.productId ?? "")`.** ProductDetailPage passes `product.id`, a number, even though the SDK types say string.
- **Read box-size dimensions through `hostApp.getProduct(productId)` → `pluginData?.["box-size"]`, with a locally declared type.** `thisPlugin.getData()` is namespaced to `shipping`, and `plugins/sdk.ts` types `getProduct` as `unknown`. Copy `toBoxDimensions` locally rather than importing across plugins.
- **Treat a 404 on delete as success, or delete only ids just listed.** Deleting a missing object returns 404 and is not idempotent.
- **Object ids are `crypto.randomUUID()` and object types are slugs.** The host puts `objectType`/`objectId` into the URL without `encodeURIComponent`.
- **Priorities: `menu.main` 110, `product.detail.tabs` 80.** These continue the existing sequence (warehouse 100 / tabs 50, 60, 70).
- **Avoid `/detail` and `/edit` as `menu.main` paths.** `router.tsx` reserves `plugins/:pluginId/detail|edit`.
- **Update the docs for the port.** Change `plugin-development.md` "Fixed Dev Ports" (it currently says the next port is 3004), root `CLAUDE.md` (~line 39), `README.md` (~lines 18 and 71) and the Reference Plugins section of `plugins/CLAUDE.md` to reserve 3010+ for business modules.

## Open Questions / Risks
- **Icon blocker**: if the `ICON_MAP` line is skipped, the sidebar shows Plug instead of Truck. No test covers `resolveIcon` with an unmapped name, so add a host Vitest case.
- **Registration is manual**: there is no Liquibase seed and no auto-registration. Until `PUT /api/plugins/shipping/manifest` runs with a `PERMISSION_PLUGIN_MANAGEMENT` (admin) token, every SDK call returns "404 Not Found". box-size must also be registered under the id `box-size`, or the dimension warning never triggers.
- **Error opacity**: the plugin only sees `"<status> <statusText>"` (for example "403 Forbidden"), so backend JSON error bodies are lost. A 401 clears the token and redirects the whole host to `/login`.
- **The JWT decode in the existing pattern is fragile**: ai-description calls `atob` without converting base64url to base64. Do the conversion as the host's `AuthContext.decodeJwtPayload` does. UI gating is cosmetic only; the backend enforces EDIT.
- **Limits**: object lists are capped at 1000 (fine at this scale), and SDK calls time out after 10 s, so surface the error rather than swallowing it.
- **Dev credentials** (viewer123/editor123/admin123) come from an older task document and have not been verified against the BCrypt hashes in `008-create-users-table.yaml`.
- **Plugin tests**: warehouse and box-size have no test setup. Adding Vitest to the shipping plugin for pure domain helpers (`fitsLimits`, mappers) is a new devDependency, so decide whether it is in scope.
- **`plugin-ui.css` has no checkbox/toggle, warning badge, label or muted-text classes.** Use a native checkbox inside `label.tc-flex` and `tc-badge--danger`/`tc-error` for warnings. Any new class goes into `plugin-ui.css` and is documented, never written as inline hex.

---

## Summary

The aj host provides a complete, permission-enforced plugin runtime: postMessage RPC, the objects API, per-product pluginData, and product reads that include all plugin namespaces. The shipping plugin can therefore be built entirely as a new `plugins/shipping` Vite app, patterned on `warehouse`. The only host change is a one-line icon-map fix. The main risks are integration details (number `productId`, exact origin, manual manifest registration, non-idempotent delete, status-text-only errors), not missing capability.

---

## Files Identified

### Primary Files (to create or modify)

**plugins/shipping/** (new)
- `index.html`, `package.json` (`shipping-plugin`), `package-lock.json`, `tsconfig.json`, `vite.config.ts` (port 3010, strictPort), `manifest.json`, `src/vite-env.d.ts`, `src/main.tsx`, `src/domain.ts`, `src/pages/ShippingConfigPage.tsx` (menu.main), `src/pages/ProductShippingTab.tsx` (product.detail.tabs).

**src/main/frontend/src/components/shared/Icons.tsx** (46 lines)
- `ICON_MAP` (lines 18–24) holds only package, layout-list, plug, warehouse and footprint. The fallback at line 38 checks `typeof icon === "function"`, but lucide-react 1.7.0 icons are forwardRef objects, so every dynamic name falls back to Plug. Add `truck: Truck` and import `Truck`.

**Docs**: `.maister/docs/standards/plugins/plugin-development.md` (Fixed Dev Ports), `CLAUDE.md` (~line 39), `README.md` (~lines 18, 71), `plugins/CLAUDE.md` (Reference Plugins, plus a fix to the inaccurate `../../sdk` import path for pages).

### Related Files (reference / read-only)

**Host plugin runtime** (`src/main/frontend/src/plugins/`)
- **PluginFrame.tsx**: builds the context `PREFIX + JSON({pluginId, pluginName, hostOrigin, ...contextData})` and passes it via `iframe.name` and the URL hash. Paths resolve with `new URL(path, pluginUrl)`. Sandbox: allow-scripts, allow-same-origin, allow-forms, allow-popups, allow-modals, allow-downloads.
- **PluginMessageHandler.ts**: requires the `aj.plugin.` requestId prefix, a registered source iframe and an exact origin match. Maps each SDK message to a REST call. `pluginId` always comes from the registry.
- **iframeRegistry.ts**, **PluginContext.tsx**: track iframes and plugin metadata.
- **pages/ProductDetailPage.tsx** (~lines 188, 209): `contextData={{ productId: product.id }}` passes a number.
- **pages/PluginPageRoute.tsx**, **router.tsx** (lines 60–61, reserved `/detail` and `/edit`), **components/layout/Sidebar.tsx** (`/plugins/${pluginId}${path}`).
- **api/client.ts**: `/api` prefix, Bearer token from `localStorage.auth_token`, throws `ApiError("<status> <statusText>")`, 401 → redirect to `/login`.
- **auth/AuthContext.tsx**: `decodeJwtPayload` with correct base64url handling (the reference to copy).
- **api/products.ts:12**: `pluginData: Record<string, Record<string, unknown>> | null`.

**Plugin SDK** (`src/main/frontend/src/plugin-sdk/`)
- **context.ts**: parses `window.name`, then the hash, and copies `productId` as-is.
- **messaging.ts**: 10 s timeout, origin-checked responses.
- **index.ts**, **host-app.ts**, **this-plugin.ts**, **vite.sdk.config.ts**: IIFE bundle at `/assets/plugin-sdk.js`.
- **plugins/sdk.ts**: the shared types (`getSDK`, `PluginSDKType`, `PluginContext`, `PluginObject`). `getProduct` returns `unknown`.

**Backend** (`src/main/java/pl/devstyle/aj/`), no changes
- **core/plugin/PluginObjectController.java / PluginObjectService.java**: upsert with full replace, 404 on get or delete of a missing object, `findEnabledOrThrow` on every call, limit capped at 1000, entityType/entityId paired.
- **core/plugin/PluginObject.java**: VARCHAR(255) id columns, unique `(pluginId, objectType, objectId)`.
- **core/plugin/DbPluginObjectQueryService.java:101**: filter regex `^[a-zA-Z0-9_.-]+$`, ops `eq|gt|lt|exists|bool`.
- **core/plugin/PluginDataController.java / PluginDataService.java**: GET returns `{}` when empty. PUT replaces only the plugin's own key. DELETE removes the key.
- **core/plugin/PluginDescriptorService.java**: manifest validation (id regex, non-blank name, http(s) url). New plugins start enabled.
- **core/security/SecurityConfiguration.java**: GETs require READ, writes to objects and data require EDIT, manifest upload requires PLUGIN_MANAGEMENT.
- **core/security/JwtTokenProvider.java, JwtAuthenticationFilter.java, user/Permission.java**: the claim holds plain `"READ"`/`"EDIT"`/`"PLUGIN_MANAGEMENT"`. The `PERMISSION_` prefix is added server-side only.
- **product/ProductResponse.java, ProductService.java, DbProductQueryService.java**: `pluginData` is returned as the full JSONB map.
- **resources/application.properties**: CORS `http://localhost:*` (not needed; RPC goes through postMessage).
- **db/changelog/2026/008-create-users-table.yaml**: dev users viewer [READ], editor [READ, EDIT], admin [READ, EDIT, PLUGIN_MANAGEMENT].

**Reference plugins**
- **plugins/warehouse/**: the template. `WarehousePage.tsx` (CRUD over objects, `tc-table`, `crypto.randomUUID`), `ProductStockTab.tsx` (load pattern), `main.tsx`, `vite-env.d.ts`, `manifest.json`.
- **plugins/box-size/src/domain.ts**: `{length, width, height}` in cm, read with null tolerance. `ProductBoxTab.tsx` is the getData/setData/removeData save flow with a 2 s "Saved!" message.
- **plugins/ai-description/src/pages/product-tab.tsx** (~lines 27–34): the `canEdit` JWT decode.
- **src/main/frontend/public/assets/plugin-ui.css** (200 lines): the `tc-*` classes.
- **start-dev.sh / start-dev.ps1**: run `npm run dev` in every `plugins/*/` that has a package.json. They pick up the new plugin automatically but do not register it.

---

## Current Functionality

### Key Components/Functions

- **PluginFrame**: mounts each extension point as an iframe and sets the context string once per mount.
- **PluginMessageHandler**: the RPC gateway. Messages `getToken`, `getProduct`, `getProducts`, `getPlugins`, `getData`/`setData`/`removeData`, `objectsList`/`objectsListByEntity`/`objectsGet`/`objectsSave`/`objectsDelete`, `pluginFetch` (`/api/` only), `filterChange`.
- **resolveIcon**: maps the manifest `icon` to a lucide component. The dynamic branch is effectively dead code (the blocker).
- **PluginObjectService / PluginDataService**: storage for plugin-owned objects and per-product data, scoped by the registry `pluginId`.

### Data Flow

1. The host renders an iframe at `http://localhost:3010/<path>#<context>`. The SDK (`/assets/plugin-sdk.js`) parses the context into `thisPlugin`.
2. Plugin → `postMessage` → host handler (origin, source and prefix checks) → `api/client.ts` with the user's Bearer token → Spring REST → SecurityConfiguration (READ/EDIT) → service (`findEnabledOrThrow`) → JPA/jOOQ.
3. Shipping data:
   - Carriers and methods: `objects` of type `carrier` / `shipping-method`, global with no entity binding.
   - Per-product opt-out: `thisPlugin.setData(productId, {disabledMethodIds})` stored under `products.plugin_data.shipping`.
   - Box dimensions: `hostApp.getProduct(productId)` → `pluginData["box-size"]`, compared with the method's max L×W×H. The tab shows a warning only.

---

## Dependencies

### Imports (what the plugin depends on)
- Host plugin SDK (`/assets/plugin-sdk.js`) and `plugin-ui.css`, served by the host at :8080.
- `plugins/sdk.ts` types (imported from `../../../sdk` in pages and `../../sdk` in `src/domain.ts`).
- react / react-dom ^19.2.4, react-router-dom ^7.13.2, vite ^8.0.1, @vitejs/plugin-react ^6.0.1, typescript ~5.9.3.
- The box-size plugin's data shape (soft dependency through the product's pluginData).

### Consumers (what depends on this)
- **Host sidebar / router**: render the `menu.main` entry and route.
- **ProductDetailPage**: renders the `product.detail.tabs` iframe.
- **Icons.tsx `ICON_MAP`**: one added entry, used by every `menu.main` icon render.

**Consumer Count**: 3 host touchpoints, of which only Icons.tsx is modified.
**Impact Scope**: Low. The work is additive and isolated in a new plugin directory. The single host edit adds a map entry and does not change existing icons.

---

## Test Coverage

### Test Files
- Host: Vitest in `src/main/frontend/src/test/`. No test exercises `resolveIcon` with an unmapped name.
- Backend: integration tests cover the plugin object and data APIs (TestContainers), and nothing changes there.
- Reference plugins: warehouse and box-size have none. ai-description uses Jest 29 + ts-jest (`src/__tests__/domain.test.ts`).

### Coverage Assessment
- **Test count for the new code**: 0 today.
- **Gaps**: plugin domain logic (`fitsLimits`, mappers, disabled-ids toggling) and the `truck` icon resolution.
- **Suggested**: one host Vitest case asserting `resolveIcon("truck") === Truck`. Optionally add Vitest to the shipping plugin for pure domain helpers (2–6 tests), or rely on manual and E2E verification, which is consistent with the sibling Vite plugins.

---

## Coding Patterns

### Naming Conventions
- **Components**: PascalCase pages with a role suffix (`WarehousePage`, `ProductStockTab`, `ProductBoxBadge`) → `ShippingConfigPage`, `ProductShippingTab`.
- **Functions**: `toX` mappers in `domain.ts` (`toWarehouse`, `toBoxDimensions`), `handleX` handlers, `async function load()` + `void load()`.
- **Files**: `plugins/<id>/`, npm package named `<id>-plugin`, manifest version matching package.json.

### Architecture Patterns
- **Style**: functional React with hooks, no state library.
- **State management**: local `useState`. Load state starts `true`. `error` is cleared before each action. A `saving` flag disables buttons. A 2 s "saved" flash.
- **UI**: root `<div className="tc-plugin" style={{padding:"1rem"}}>`. Tabs use `1.5rem` padding with `<h3 style={{margin:"0 0 1rem"}}>`. Errors render in `<p className="tc-error">`. Inline style only for layout.
- **Errors**: `catch (err) { setError(err instanceof Error ? err.message : "Failed to …") }`. Never swallow (the existing box-size and warehouse badge swallows are anti-patterns, tolerated only in tiny info badges).
- **TS constraints**: `verbatimModuleSyntax` requires `import type`. `erasableSyntaxOnly` rules out enums and parameter properties.
- **Anti-patterns to avoid**:
  - inline hex colours (box-size `#334155`)
  - sequential awaits in loops (use `Promise.all`)
  - `Promise.all().finally` with error setters that overwrite each other
  - `as unknown as` casts
  - passing async handlers directly to `onClick`

---

## Complexity Assessment

| Factor | Value | Level |
|--------|-------|-------|
| File Size | ~11 new small files (~2 substantive pages, each likely 150–300 lines) + 1 one-line host edit | Medium |
| Dependencies | ~6 npm deps + host SDK | Medium |
| Consumers | 3 host touchpoints (1 modified) | Low |
| Test Coverage | 0 existing for the new code; reference plugins untested | High (gap) |

### Overall: Moderate
The work is mostly scaffolding plus two CRUD/form pages over well-understood APIs. The complexity is in correct integration (permissions, cross-plugin data read, warning logic), not in new architecture.

---

## Key Findings

### Strengths
- The objects API accepts arbitrary `objectType`, so `carrier` and `shipping-method` need no backend work.
- `setData` preserves other plugins' namespaces. `ProductResponse` exposes all namespaces, which makes the box-size read trivial.
- Security is centralised and already enforces EDIT on writes. A viewer write fails safely with 403.
- `start-dev` scripts pick up the new plugin automatically.

### Concerns
- The `truck` icon blocker in `resolveIcon`, which no test catches.
- The number `productId`, which contradicts the SDK types.
- Non-idempotent delete (404), opaque status-text errors, and the 401 redirect of the whole host.
- Registration is manual and needs an admin token. A dependency on box-size being registered as `box-size`.
- No shared `canEdit` helper, and the existing decode skips base64url handling.

### Opportunities
- Add a small `canEdit` / `decodeJwtPayload` helper local to the plugin with correct base64url handling.
- Keep `domain.ts` pure (`toCarrier`, `toShippingMethod`, `toBoxDimensions`, `exceedsLimits`) so it is easy to test.
- Fix the doc inaccuracy (`../../sdk` vs `../../../sdk` for pages) while updating plugin docs.

---

## Impact Assessment

- **Primary changes**: new `plugins/shipping/**`, plus `src/main/frontend/src/components/shared/Icons.tsx` (`ICON_MAP` + `Truck` import).
- **Related changes**: `.maister/docs/standards/plugins/plugin-development.md`, `CLAUDE.md`, `README.md`, `plugins/CLAUDE.md`.
- **Test updates**: a host Vitest case for `resolveIcon("truck")`; optional plugin domain tests. No backend test changes.

### Risk Level: Low-Medium
The change is additive and isolated, with no backend or schema changes. Risk comes from integration details that fail silently or confusingly: the icon fallback, `productId` type mismatches in comparisons and URLs, missing manifest registration showing up as 404s, an origin mismatch silently dropping RPC, and delete 404s.

---

## Recommendations

This is a **new capability built on existing infrastructure**.

1. **Scaffold from warehouse**: copy `index.html` (title "Shipping Plugin"), `package.json` (name `shipping-plugin`, same deps and scripts), `tsconfig.json` verbatim, `vite-env.d.ts` and `main.tsx` (StrictMode + BrowserRouter + Routes). Set `vite.config.ts` to `server: { port: 3010, strictPort: true }`.
2. **Manifest**: id `shipping`, url `http://localhost:3010`, `menu.main` (path not `/detail` or `/edit`, icon `truck`, priority 110), `product.detail.tabs` (priority 80). Document the registration command (`PUT /api/plugins/shipping/manifest` with an admin token) in the plugin README or the `plugins/CLAUDE.md` entry.
3. **Host icon fix**: `import { Truck } from "lucide-react"` and add `truck: Truck` to `ICON_MAP`. Add a Vitest assertion.
4. **domain.ts**:
   - Types `Carrier {id, name}` and `ShippingMethod {id, carrierId, name, active, maxLengthCm?, maxWidthCm?, maxHeightCm?}`.
   - `to*` mappers from `PluginObject`.
   - A local `toBoxDimensions` (null if any dimension is missing).
   - `exceedsLimits(box, method)` that compares only the limits that are set. Decide whether orientation-agnostic comparison (sorted dimensions) is wanted; the initial plan should settle this.
5. **ShippingConfigPage**: load carriers and methods with `Promise.all`. CRUD uses `crypto.randomUUID()` ids, a `tc-table`, and a delete button with `tc-ghost-button--danger`. Decide how deleting a carrier treats its methods (block it, or cascade-delete in parallel), and tolerate a 404 on delete. Hide write controls when `!canEdit`.
6. **ProductShippingTab**:
   - `const productId = String(sdk.thisPlugin.productId ?? "")`, with the guard "No product context available."
   - Load in parallel: the methods list, `thisPlugin.getData(productId)` (`{disabledMethodIds}`, defaulting to `[]`) and `hostApp.getProduct(productId)` for `pluginData?.["box-size"]`.
   - One checkbox per active method, inside `label.tc-flex`.
   - For each enabled method whose limits the box exceeds, show a warning with `tc-badge--danger`. The method stays enabled.
   - Save with `setData` (or `removeData` when the list is empty, if preferred), with saving/saved flags. Hide or disable inputs when `!canEdit`.
7. **canEdit**: `getToken()`, then decode the payload with base64url → base64 plus padding, then `permissions.includes("EDIT")`. Wrap in try/catch with a default of false.
8. **Docs**: reserve 3010+ for business modules in the standard and in root `CLAUDE.md`, add the plugin to the `README.md` tree and install steps, and add a Reference Plugins entry in `plugins/CLAUDE.md`.
9. **Verification**:
   - As editor: create a carrier and a method with limits, give a product a box-size larger than those limits, open the tab, confirm the warning, toggle the method off, save and reload.
   - As viewer: confirm the write controls are hidden.
   - Confirm the sidebar shows the Truck icon.

---

## Next Steps

Invoke the gap-analyzer and compare this current-state analysis with `analysis/initial-plan.md`. Specifically, confirm:
- the icon-map host change is accepted as the only host modification
- how deleting a carrier treats its methods
- whether dimension comparison is orientation-agnostic
- whether to add Vitest tests to the plugin
- the manifest registration and documentation steps
