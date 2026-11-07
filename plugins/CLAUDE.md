# Plugin Development Guide

Plugins are standalone web apps that run in iframes and communicate with the host via postMessage RPC. The host provides a Plugin SDK served at `/assets/plugin-sdk.js`.

## Project Structure

```
plugins/
  sdk.ts                # Shared SDK type declarations — all plugins import from here
  my-plugin/
    index.html          # Load SDK + plugin-ui.css in <head>, app entry in <body>
    manifest.json       # Plugin identity + extension points
    package.json
    vite.config.ts
    src/
      main.tsx          # Router with routes matching manifest paths
      domain.ts         # Plugin-specific domain types and mappers
      pages/            # One component per extension point path
```

## File Organization

- **`plugins/sdk.ts`** — Shared SDK types (`PluginSDKType`, `PluginContext`, `PluginObject`) and `getSDK()` helper. All plugins import from here. Never duplicate this file into individual plugins.
- **`src/domain.ts`** — Plugin-specific domain interfaces and mapper functions (e.g., `Warehouse`, `toWarehouse`). Keep domain logic separate from SDK types.
- **`src/pages/`** — One component per extension point path. No `sdk.ts` in individual plugins.

**Import conventions:**
```typescript
// SDK — always from shared
import { getSDK } from "../../sdk";
import type { PluginObject } from "../../sdk";

// Domain — from plugin's own domain file
import { toWarehouse } from "../domain";
import type { Warehouse } from "../domain";
```

## Quick Start

1. Copy `plugins/warehouse/` as a template
2. Update `manifest.json` with your plugin's identity and extension points
3. Update routes in `src/main.tsx` to match manifest paths
4. Register with the host: `curl -X PUT http://localhost:8080/api/plugins/{pluginId}/manifest -H "Content-Type: application/json" -d @manifest.json`
5. Run dev server: `npm run dev`
6. Access via host sidebar or product detail tabs

## UI & Styling

Plugins must use the host's shared UI stylesheet to maintain visual consistency. Do not use inline styles for standard UI elements — use the provided CSS classes instead.

### Loading Host Styles

Add both the SDK and the UI stylesheet in your plugin's `<head>`:

```html
<script src="http://localhost:8080/assets/plugin-sdk.js"></script>
<link rel="stylesheet" href="http://localhost:8080/assets/plugin-ui.css" />
```

### Available CSS Classes

Wrap your plugin root in `tc-plugin` for base typography and color:

```html
<div class="tc-plugin">...</div>
```

**Buttons:**
- `tc-primary-button` — Primary action (matches the host app's `PrimaryButton` component)
- `tc-ghost-button` — Secondary/subtle action
- `tc-ghost-button tc-ghost-button--danger` — Destructive action (red text)

**Inputs:**
- `tc-input` — Text/number inputs
- `tc-select` — Dropdown selects

**Tables:**
- `tc-table` — Full table styling with blue uppercase headers, hover rows, borders
- Use `align="right"` on `<th>`/`<td>` for right-aligned columns
- `<tfoot>` gets automatic top border and bold text

**Badges:**
- `tc-badge tc-badge--success` — Green badge (e.g., "Product available")
- `tc-badge tc-badge--danger` — Red badge (e.g., "Product unavailable")

**Layout:**
- `tc-card` — White card with border and rounded corners
- `tc-section` — Section with bottom margin
- `tc-flex` — Flex row with gap
- `tc-error` — Red error text

### Example

```tsx
<div className="tc-plugin" style={{ padding: "1rem" }}>
  <h1>My Plugin</h1>
  <table className="tc-table">
    <thead><tr><th>Name</th><th align="right">Value</th></tr></thead>
    <tbody><tr><td>Item</td><td align="right">42</td></tr></tbody>
  </table>
  <button className="tc-primary-button">Save</button>
  <button className="tc-ghost-button tc-ghost-button--danger">Delete</button>
</div>
```

### Rules

- Always load `plugin-ui.css` — do not redefine button, table, or input styles inline
- Use `tc-primary-button` for all primary actions — it matches the host's `PrimaryButton` component exactly
- Wrap plugin root in `tc-plugin` for consistent typography
- Only use inline `style` for layout-specific concerns (padding, max-width, margins) that aren't covered by the shared classes

## Manifest

The manifest is the single source of truth. Upload it via `PUT /api/plugins/{pluginId}/manifest`.

```json
{
  "name": "My Plugin",
  "version": "1.0.0",
  "url": "http://localhost:3001",
  "description": "What this plugin does",
  "extensionPoints": [...]
}
```

**Rules:**
- `pluginId` (from the URL path) must match `^[a-zA-Z0-9_-]+$`
- `url` must be HTTP or HTTPS
- `name` is required and non-blank

## Extension Points

### `menu.main` -- Sidebar Navigation

Adds a menu item to the host sidebar. Clicking it renders your plugin full-page in an iframe.

```json
{ "type": "menu.main", "label": "My Plugin", "icon": "warehouse", "path": "/", "priority": 100 }
```

- `path` is appended to your plugin's base URL
- `icon` is a [Lucide icon](https://lucide.dev/icons/) name in kebab-case (e.g., `"warehouse"`, `"shopping-cart"`, `"bar-chart"`, `"settings"`). Falls back to `plug` if omitted or unrecognized.
- `priority` controls sort order (lower = higher in menu)
- Your page receives context: `RENDER{"pluginId":"...","pluginName":"...","hostOrigin":"..."}`

### `product.detail.tabs` -- Product Detail Tab

Adds a tab to the product detail view. Your iframe receives the `productId`.

```json
{ "type": "product.detail.tabs", "label": "My Tab", "path": "/product-tab", "priority": 50 }
```

- Context: `PRODUCT_DETAIL{"pluginId":"...","hostOrigin":"...","productId":"42"}`
- Read `productId` via `thisPlugin.productId`

### `product.list.filters` -- Product List Filter

Adds a native filter control to the product list page. The host renders the control automatically based on `filterType` -- no plugin iframe needed.

```json
{ "type": "product.list.filters", "label": "In Stock", "filterKey": "stock", "filterType": "boolean", "priority": 10 }
```

- `filterKey`: The key in your plugin's `pluginData` on the product (e.g., `"stock"` → queries `pluginData->'yourPluginId'->>'stock'`)
- `filterType`: Controls the rendered UI and query operator:
  - `"boolean"` → Switch toggle, queries with `bool` operator
  - `"string"` → Text input, queries with `eq` operator
  - `"number"` → Number input, queries with `eq` operator
- `label`: Display label for the filter control
- `priority`: Sort order (lower = further left)
- No `path` needed -- the host renders controls natively

### `product.detail.info` -- Product Detail Info

Renders a plugin iframe below the product details card. Your component receives the `productId` and can display any custom info (availability badges, ratings, etc.).

```json
{ "type": "product.detail.info", "label": "Availability", "path": "/product-availability", "priority": 10 }
```

- `path` is appended to your plugin's base URL
- Context: `PRODUCT_DETAIL{"pluginId":"...","hostOrigin":"...","productId":"42"}`
- Read `productId` via `thisPlugin.productId`
- Use `thisPlugin.getData(productId)` to read your plugin's data on the product
- Keep the component compact (~60px height) — it renders inline below product details
- `priority` controls sort order (lower = higher)

## SDK API

Load in your `index.html` `<head>`:

```html
<script src="http://localhost:8080/assets/plugin-sdk.js"></script>
```

Import the shared type declarations in your TypeScript code:

```typescript
import { getSDK } from "../../sdk";

const sdk = getSDK();
```

### `thisPlugin` -- Plugin-scoped operations

```typescript
const { thisPlugin } = PluginSDK;

// Context shortcuts (sync) — preferred over getContext()
thisPlugin.pluginId                         // This plugin's ID
thisPlugin.pluginName                       // This plugin's display name
thisPlugin.productId                        // Current product ID (product-scoped extensions only)

// Full context object (sync) — use shortcuts above instead
thisPlugin.getContext()
// Returns: { extensionPoint, pluginId, pluginName, hostOrigin, productId? }

// Product-specific plugin data (namespaced per plugin)
await thisPlugin.getData(productId)         // Get this plugin's data on a product
await thisPlugin.setData(productId, data)   // Replace this plugin's data on a product
await thisPlugin.removeData(productId)      // Remove this plugin's data from a product

// Custom objects (plugin-owned collections)
await thisPlugin.objects.list(objectType)                              // List all objects of a type
await thisPlugin.objects.list(objectType, options?)                    // With entity filter/JSONB filter
  // options: { entityType?, entityId?, filter? }
  // filter format: "jsonPath:operator:value" (operators: eq, gt, lt, exists, bool)
await thisPlugin.objects.listByEntity(entityType, entityId, options?)  // Cross-type: all objects for an entity
  // options: { filter? }
await thisPlugin.objects.get(objectType, objectId)                    // Get single object
await thisPlugin.objects.save(objectType, objectId, data, options?)   // Create or replace
  // options: { entityType?, entityId? }
  // Omitting entityType/entityId clears any existing entity binding
await thisPlugin.objects.delete(objectType, objectId)                 // Delete

// Note: Product list filters are now host-rendered from manifest metadata.
// No SDK call needed -- define filterKey/filterType in your manifest.
```

### `hostApp` -- Access host data

```typescript
const { hostApp } = PluginSDK;

await hostApp.getProducts({ category: 1, search: "foo", sort: "name,asc" })
await hostApp.getProduct(productId)
await hostApp.getPlugins()
await hostApp.fetch("/api/some-endpoint", { method: "POST", body: JSON.stringify(data) })
```

**`hostApp.fetch` restrictions:** Only `/api/` URLs allowed. Credentials stripped. Path traversal (`..`) rejected.

## Data Storage Patterns

### Plugin Data (per-product)

Use `thisPlugin.getData/setData` to attach data to a product. Each plugin's data is namespaced -- you only see your own.

```typescript
// Store stock info on product #5
await thisPlugin.setData("5", { inStock: true, quantity: 42 });

// Read it back
const data = await thisPlugin.getData("5");
// { inStock: true, quantity: 42 }
```

- `setData` replaces the entire data node (not a deep merge)
- Data is stored in the product's `pluginData` JSONB column under your pluginId key

### Custom Objects (plugin-owned collections)

Use `thisPlugin.objects` for plugin-managed data that isn't tied to a specific product.

```typescript
// Create a warehouse
await thisPlugin.objects.save("warehouse", "wh-1", { name: "Main", address: "123 St" });

// List all warehouses
const warehouses = await thisPlugin.objects.list("warehouse");

// Use composite IDs for relationships
await thisPlugin.objects.save("stock", `${productId}-${warehouseId}`, {
  productId, warehouseId, quantity: 10
});
```

- Objects have a unique constraint on `(pluginId, objectType, objectId)`
- Save is an upsert -- creates if new, replaces if exists
- Use descriptive `objectType` names (e.g., "warehouse", "stock", "config")
- Use composite objectIds for natural keys (e.g., `{productId}-{warehouseId}`)

### Entity Binding (connecting objects to entities)

Bind custom objects to main entities (Product, Category) for server-side filtering:

```typescript
// Save stock bound to a product
await thisPlugin.objects.save("stock", `${productId}-${warehouseId}`, {
  productId, warehouseId, quantity: 10
}, { entityType: "PRODUCT", entityId: productId });

// List stock for a specific product
const stocks = await thisPlugin.objects.list("stock", {
  entityType: "PRODUCT", entityId: productId
});

// List ALL objects for a product (any type)
const allForProduct = await thisPlugin.objects.listByEntity("PRODUCT", productId);

// Filter by JSONB data values
const inStock = await thisPlugin.objects.list("stock", {
  entityType: "PRODUCT", entityId: productId,
  filter: "quantity:gt:0"
});
```

- Entity types: `PRODUCT`, `CATEGORY`
- Entity binding is optional -- omit options to use objects without binding
- Saving without entity options clears any existing binding (explicit intent model)
- Both `entityType` and `entityId` must be provided together or both omitted
- Filter format: `jsonPath:operator:value` (operators: eq, gt, lt, exists, bool)

## Domain Types

Each plugin keeps its domain types in `src/domain.ts`. Import `PluginObject` from the shared SDK to write mapper functions:

```typescript
import type { PluginObject } from "../../sdk";

export interface MyItem {
  objectId: string;
  name: string;
}

export function toMyItem(obj: PluginObject): MyItem {
  return { objectId: obj.objectId, name: obj.data.name as string };
}
```

## Context and Routing

The host passes context to your plugin via the iframe's URL hash fragment. The SDK parses this automatically. Your plugin code should:

1. Use `thisPlugin.productId`, `thisPlugin.pluginId`, etc. to read context values
2. Set up routes matching the `path` fields in your manifest
3. Each route renders the UI for that extension point

```tsx
// main.tsx
<Routes>
  <Route path="/" element={<MainPage />} />              {/* menu.main path="/" */}
  <Route path="/product-tab" element={<ProductTab />} />  {/* product.detail.tabs path="/product-tab" */}
  {/* product.list.filters -- no route needed, host renders natively */}
</Routes>
```

## Error Handling

- All SDK methods return promises that reject on failure (10 second timeout)
- Always wrap SDK calls in try/catch
- Show errors to the user -- don't swallow them silently
- When running standalone (not in iframe), the SDK provides a fallback context but messages will time out

## Development Workflow

1. Start the host: `cd /path/to/project && ./mvnw spring-boot:run`
2. Start the host frontend (optional, for hot reload): `cd src/main/frontend && npm run dev`
3. Start your plugin: `cd plugins/my-plugin && npm run dev`
4. Register manifest once: `curl -X PUT http://localhost:8080/api/plugins/my-plugin/manifest -H "Content-Type: application/json" -d @manifest.json`
5. Access your plugin through the host app (sidebar, product tabs, etc.)
6. Re-register manifest after changes to extension points

## Reference Plugins

### Warehouse Plugin

`plugins/warehouse/` is a working example demonstrating:
- Warehouse CRUD via `thisPlugin.objects` (type: "warehouse")
- Stock tracking via `thisPlugin.objects` with composite keys (type: "stock")
- Product list from `hostApp.getProducts()`
- Product detail tab reading `productId` from context
- Host-rendered boolean filter via manifest `filterKey`/`filterType`
- Lucide icon (`"warehouse"`) in sidebar via manifest `icon` field
- Host UI stylesheet (`plugin-ui.css`) for consistent button, table, input, and badge styling
- Domain types in `src/domain.ts` separate from shared SDK

### Box Size Plugin

`plugins/box-size/` is a minimal example demonstrating:
- Per-product data via `thisPlugin.getData/setData` (no custom objects needed)
- Product detail tab (`product.detail.tabs`) with a form for editing box dimensions (L×W×H)
- Product detail info badge (`product.detail.info`) showing "Box: 120cm/12cm/48cm"
- Domain types and formatters in `src/domain.ts`
- Validation and error handling in the save flow

### Shipping Plugin

`plugins/shipping/` is a business-module example demonstrating:
- Dev server on port 3010 (`strictPort: true`). Port rule: existing plugins use 3001–3003, 3004–3009 are for small demo and utility plugins, and 3010+ is for business modules (shipping 3010, next business module 3011)
- System-wide catalogue via `thisPlugin.objects` (types `"carrier"` and `"shipping-method"`), keyed by `objectId` (a UUID), never the numeric `id`. Inline edit saves under the same `objectId`, so existing references survive; `carrierId` and opt-outs hold objectIds
- Per-product opt-out restrictions via `thisPlugin.getData/setData` as `{disabledMethodIds: string[]}`: every active method is available unless unticked, and `removeData` runs when the list is empty
- Cross-plugin read: the product tab reads box-size dimensions from `hostApp.getProduct(productId)` (`pluginData["box-size"]`) for a rotation-aware size warning, and re-fetches the product and both lists on window `focus` / `visibilitychange` while keeping unsaved ticks
- Write controls gated by a local `canEdit(token)` that base64url-decodes the JWT from `hostApp.getToken()` and checks the `permissions` claim for `EDIT` (the backend still enforces EDIT with 403)
- Sidebar page (`menu.main`, icon `truck`) for carriers/methods and a product detail tab (`product.detail.tabs`) for restrictions
- Manual registration with a `PERMISSION_PLUGIN_MANAGEMENT` token: `curl -X PUT http://localhost:8080/api/plugins/shipping/manifest -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d @manifest.json`
