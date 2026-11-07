# Plan: Shipping plugin (`shipping`, port 3010)

## Context
aj needs a logistics module delivered as a microkernel plugin:
1. **Global configuration**: manage carriers (DHL, DPD, InPost, Poczta Polska…) and their delivery methods for the whole system.
2. **Per-product availability**: an administrator can turn off a delivery method for a specific product, for example when the product is too big for it.
3. **Deployment**: the dev server runs on **port 3010**, kept apart from the ports of the modules that come next.

Decisions confirmed with the user:
- **Opt-out model**: every active method is available by default, and the admin turns methods off per product.
- Methods carry optional **max L×W×H (cm)**. The product tab reads the box-size plugin's dimensions and **warns, without turning anything off**, when a method's limits are exceeded.
- **Admin means `PERMISSION_EDIT`**. The backend already enforces this on plugin object and data writes, so **no backend changes**. The UI hides write controls for other users, per `standards/backend/plugin-auth.md`.
- Port 3010 is fixed with `strictPort`. Update the docs so new business modules use ports from 3010 up.
- No weight limit: products and box-size carry no weight, so it could never be checked (minimal-implementation standard).

Execution: once plan mode is off, this runs through the `/maister:development` workflow (task dir `.maister/tasks/development/2026-11-07-shipping-plugin/`, with its gates). This plan is its starting brief.

## Data model (no migrations; existing plugin storage)
| What | Storage | Shape |
|---|---|---|
| Carrier | `objects`, type `carrier`, id = UUID | `{ name, active }` |
| Delivery method | `objects`, type `shipping-method`, id = UUID | `{ carrierId, name, active, maxLengthCm?, maxWidthCm?, maxHeightCm? }` |
| Per-product restriction | `thisPlugin.setData(productId, …)` | `{ disabledMethodIds: string[] }` (`removeData` when empty) |

- Fit check: sort the box dimensions and the method limits in descending order and compare them pair by pair, so a box may be rotated. A missing limit means no limit. A missing box-size record means "dimensions unknown, no warning".
- Box dimensions come from `hostApp.getProduct(productId)` → `pluginData["box-size"]`. `ProductResponse` exposes every plugin's data; `src/main/java/pl/devstyle/aj/product/ProductResponse.java:17`.
- Deleting a carrier that still has methods is blocked with an error. Deleting a method leaves stale ids in product data; reads ignore unknown ids, and the next save drops them.
- Inactive methods/carriers are shown greyed out in the product tab and count as unavailable.

## Files to create: `plugins/shipping/` (copy the `box-size` / `warehouse` structure)
- `package.json` (`shipping-plugin`, scripts `dev`/`build`/`preview`, same deps as `plugins/box-size/package.json`), `tsconfig.json`, `index.html` (SDK + `plugin-ui.css` like `plugins/box-size/index.html`)
- `vite.config.ts`: `server: { port: 3010, strictPort: true }`
- `manifest.json`: `url: http://localhost:3010`; extension points:
  - `menu.main`: label "Shipping", icon `truck`, path `/`, priority ~110
  - `product.detail.tabs`: label "Shipping", path `/product-shipping`, priority 70
- `src/main.tsx`: routes `/` → `ShippingConfigPage`, `/product-shipping` → `ProductShippingTab`
- `src/domain.ts`: `Carrier`, `ShippingMethod`, `ProductShippingData`, mappers `toCarrier`/`toShippingMethod` (pattern: `plugins/warehouse/src/domain.ts`), `toProductShippingData`, `exceedsLimits(box, method)`, plus `canEdit(token)` (JWT `permissions` includes `EDIT`). Reuse the `BoxDimensions` shape from `plugins/box-size/src/domain.ts` by redeclaring the type locally; do not import across plugins.
- `src/pages/ShippingConfigPage.tsx` (pattern: `plugins/warehouse/src/pages/WarehousePage.tsx`):
  - Carriers section: table (name, active toggle, delete) plus an add form.
  - Methods section: table (carrier, name, limits, active, delete) plus an add form (carrier select, name, 3 optional number inputs). Validate that limits are positive numbers.
  - Write controls only when `canEdit`. try/catch around every SDK call, with errors shown in `tc-error`.
- `src/pages/ProductShippingTab.tsx` (pattern: `plugins/box-size/src/pages/ProductBoxTab.tsx`):
  - Loads methods, carriers, own data and the product (for box-size).
  - Table with one row per method: carrier, method, a checkbox "available", and a status badge (`tc-badge--success` "Fits" / `tc-badge--danger` "Too large" / plain "No dimensions").
  - A hint line when exceeding methods are still enabled. Save writes `disabledMethodIds`, and an all-enabled save calls `removeData`.
  - Read-only users see the table with the checkboxes disabled.
- Styling only with `tc-*` classes; inline styles only for layout.

## Files to modify
- `.maister/docs/standards/plugins/plugin-development.md`: in the fixed dev ports list, replace "next plugin 3004" with "`shipping` 3010; further business modules from 3010 up (3011, …)".
- `.maister/docs/INDEX.md`: update the Plugin Development line's port list to match.
- `plugins/CLAUDE.md`: add a "Shipping Plugin" entry under Reference Plugins (opt-out restrictions, reading another plugin's pluginData via `hostApp.getProduct`).
- Root `CLAUDE.md`: add `shipping` (:3010) to the Plugins line. It already shows uncommitted user edits, so append only.
- `start-dev.sh/.ps1`: no change needed; they start every `plugins/*` folder that has a `package.json`.

## Verification
1. `cd plugins/shipping && npm install && npm run build` checks types (`tsc -b`) and the build.
2. Start the stack (`./start-dev.ps1`); confirm Vite listens on :3010, and that a second instance fails because of strictPort.
3. Register: `PUT /api/plugins/shipping/manifest` with an admin token (`PERMISSION_PLUGIN_MANAGEMENT`).
4. In the browser (Playwright / Chrome) at http://localhost:8080:
   - Sidebar "Shipping": add DHL, DPD, InPost and Poczta Polska. Add "InPost Paczkomat" with limits 64×38×41, and a DHL courier with no limits. Toggle active; check that deleting a carrier that has methods is blocked.
   - Product with a box-size of 120×12×48: the Shipping tab marks Paczkomat "Too large". Untick it, save, reload, and confirm it persists (`GET /api/plugins/shipping/products/{id}/data`).
   - Log in as a read-only user: no add/delete/save controls appear, and the checkboxes are disabled.
5. `./mvnw test` is not needed (no backend changes); `npm run lint` in the host is unaffected.
