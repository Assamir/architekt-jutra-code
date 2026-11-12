## Plugin Development

Read `plugins/CLAUDE.md` for the full manifest format, extension point reference and SDK API. These standards are the conventions every plugin must follow.

### Plugin Structure and Shared SDK Imports

Each plugin is a standalone npm project in `plugins/<id>/`: `package.json` named `<id>-plugin`, `private: true`, version matching the manifest version (Vite plugins also set `type: module`). Layout: `manifest.json`, `src/main.tsx` (router), `src/domain.ts` (plugin domain interfaces and `toX(obj: PluginObject)` mappers), `src/pages/` (one component per extension-point path). Start a new plugin by copying `plugins/warehouse/`.

- Import SDK types and `getSDK()` from the shared `plugins/sdk.ts` via relative import (`../../sdk`); never keep a local `sdk.ts` copy. Next.js plugins need `experimental.externalDir` (ai-description maps it as `@sdk` in tsconfig paths).
- Every plugin must define a `dev` script: `start-dev.sh`/`.ps1` runs `npm run dev` in each `plugins/*/` that has a `package.json`.
- Pin the same React 19 / Vite / TypeScript (`~5.9.3`) versions as the host. `node_modules` is gitignored.

```typescript
import { getSDK } from "../../sdk";
import type { PluginObject } from "../../sdk";
import { toWarehouse } from "../domain";
```

Source: plugins/CLAUDE.md, plugins/*/package.json, start-dev.sh, plugins/ai-description/next.config.js, plugins/warehouse/src/pages/WarehousePage.tsx

### Mandatory plugin-ui.css with tc-* Classes

Every plugin loads the host's `plugin-ui.css` (and `plugin-sdk.js`) in `<head>` and styles standard UI with the shared `tc-*` classes so it matches the host: root wrapper `tc-plugin`, `tc-primary-button` for all primary actions (matches the host `PrimaryButton`), `tc-ghost-button` / `tc-ghost-button--danger`, `tc-input`, `tc-select`, `tc-table`, `tc-badge--success` / `--danger`, `tc-card`, `tc-section`, `tc-flex`, `tc-error`. Never redefine button, table or input styles inline; inline `style` is only for layout (padding, margins, max-width). Small `product.detail.info` badges may skip the `tc-plugin` root.

```tsx
<link rel="stylesheet" href="http://localhost:8080/assets/plugin-ui.css" />
<div className="tc-plugin" style={{ padding: "1rem" }}>
  <button className="tc-primary-button">Save</button>
</div>
```

Source: plugins/CLAUDE.md, CLAUDE.md, plugins/*/index.html, plugins/box-size/src/pages/ProductBoxTab.tsx

### Plugin Manifest Is the Single Source of Truth

The manifest (`name`, `version`, `url`, `description`, `extensionPoints`) defines the plugin and is uploaded with `PUT /api/plugins/{pluginId}/manifest` using a token with `PERMISSION_PLUGIN_MANAGEMENT`. `pluginId` must match `^[a-zA-Z0-9_-]+$`, `url` must be HTTP/HTTPS, `name` is required and non-blank. Re-register the manifest whenever extension points change, and keep routes in `src/main.tsx` matching the manifest `path` fields.

Source: plugins/CLAUDE.md, CLAUDE.md, plugins/*/manifest.json

### Plugin Storage Choice: Per-Product Data vs Custom Objects

- **Per-product data** (`thisPlugin.getData/setData`): values attached to one product, stored in `products.plugin_data -> {pluginId}`. `setData` replaces the whole node (no deep merge). Use it also for small denormalized summaries the host reads — `product.list.filters` (`filterKey`) and info badges.
- **Custom objects** (`thisPlugin.objects.list/save/delete`): plugin-owned collections. `save` upserts on `(pluginId, objectType, objectId)`; use descriptive `objectType` names and composite `objectId`s for natural keys (`{productId}-{warehouseId}`). Entity binding to PRODUCT/CATEGORY is optional, but `entityType` and `entityId` go together or not at all; saving without them clears an existing binding.
- JSONB filters use `jsonPath:op:value` with ops `eq`, `gt`, `lt`, `exists`, `bool`.

```typescript
await sdk.thisPlugin.objects.save("stock", `${productId}-${warehouseId}`, data,
  { entityType: "PRODUCT", entityId: productId });
await sdk.thisPlugin.setData(productId, { stock: totalQty > 0 }); // matches manifest filterKey "stock"
```

Source: plugins/CLAUDE.md, CLAUDE.md, plugins/warehouse/src/pages/WarehousePage.tsx, plugins/warehouse/manifest.json

### Fixed Dev Ports

Every service has a fixed, unique local port: host 8080, MCP server 8081 (management 9081), warehouse 3001, box-size 3002, ai-description 3003, shipping 3010, ai-offer-analysis 3011. Port rule: existing plugins use 3001–3003, 3004–3009 are for small demo and utility plugins, and 3010+ is for business modules (shipping 3010, ai-offer-analysis 3011, next business module 3012). A new plugin takes the next free port in its range and must always run there, because the manifest `url` is `http://localhost:<port>`. Vite plugins set `server.port` with `strictPort: true` so a clash fails instead of silently moving; Next.js plugins pass `-p <port>` to both `dev` and `start`. Avoid ports used by Compose services (3100 Langfuse, 4000 LiteLLM).

```typescript
server: { port: 3012, strictPort: true } // new business-module plugin vite.config.ts
```

Source: plugins/*/vite.config.ts, plugins/ai-description/package.json, plugins/*/manifest.json, CLAUDE.md, compose.yml

### Extension Point Conventions

- `menu.main`: `icon` is a kebab-case Lucide icon name (falls back to `plug`).
- `priority`: lower values sort higher / further left, for every extension point.
- `product.detail.tabs` and `product.detail.info`: the `path` is appended to the plugin URL; read the product via `thisPlugin.productId`. Keep `product.detail.info` compact (~60px) — it renders inline.
- `product.list.filters`: no `path` or iframe; the host renders it natively from `filterKey` (a key in the plugin's pluginData) and `filterType` (boolean→bool, string→eq, number→eq).

```json
{ "type": "product.list.filters", "label": "In Stock", "filterKey": "stock", "filterType": "boolean", "priority": 10 }
```

Source: plugins/CLAUDE.md, CLAUDE.md

### Plugin SDK Usage and Error Handling

Read context with the sync shortcuts `thisPlugin.pluginId`, `pluginName`, `productId` instead of `getContext()`. `hostApp.fetch` only allows `/api/` URLs, strips credentials and rejects path traversal. SDK promises reject on failure or after a 10s timeout, so wrap every SDK call in try/catch and show the error to the user — never swallow it.

```typescript
try { await sdk.thisPlugin.setData(productId, dims); }
catch (e) { setError(String(e)); }
```

Source: plugins/CLAUDE.md, plugins/*/src/pages/*.tsx
