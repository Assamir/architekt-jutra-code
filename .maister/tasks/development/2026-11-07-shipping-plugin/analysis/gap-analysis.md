# Gap Analysis: Shipping plugin (`plugins/shipping`, port 3010)

## TL;DR
This is a new capability built on host infrastructure that already exists. The backend needs no changes. On the host, the only code change is a one-line `ICON_MAP` entry plus a Vitest case. The confirmed plan covers create, read and delete for carriers, methods and per-product restrictions. It does not cover editing anything except the `active` flag. Because opt-outs are keyed by method UUID, the only way to fix a method's limits or name is to delete it and recreate it, and that silently drops every product's opt-out for that method. This is the one critical decision. The other gaps are small: discoverability (no product-list signal and no empty-state link) and three doc spots the plan missed.

## Key Decisions
- **Characteristics are creates_new_entities + involves_data_operations + ui_heavy, with a light modifies_existing_code.** The only existing code touched is `Icons.tsx` (additive), so change_type is additive and compatibility is strict for the host.
- **The data lifecycle is assessed per stored entity: Carrier, ShippingMethod and ProductShippingData.** The backend supports every operation (`objects.save` upserts, `setData`/`removeData`), so any gaps are in the UI only.
- **Port 3010 is free.** `compose.yml` maps 5435, 5433, 8822, 9900, 9010, 9011, 6677, 3100, 5002, 5001, 5434 and 4000. MinIO's 9010 is a different number. No clash.
- **`product.detail.tabs` priority 80 is correct.** It follows warehouse 50, box-size 60 and ai-description 70, so the order is deterministic.
- **The confirmed decisions are not reopened.** They are opt-out, warn-only, block carrier delete, EDIT = admin, no weight, no seed data and Vitest for domain.ts. None conflicts with the code.

## Open Questions / Risks
- **Edit/rename gap (critical decision below).** Deleting and recreating a method to fix its limits changes its UUID. Existing per-product opt-outs then become stale and are dropped on the next save, and nothing warns the admin.
- **Doc spot the plan missed.** The code sample in `plugin-development.md` line 59 (`server: { port: 3004, strictPort: true }`) must change too, not only the prose on line 56. The README tree (line 18) and install steps (line 71) are listed in the codebase analysis but not in the initial plan's "Files to modify".
- **Restrictions can't be seen outside the product tab.** There is no product-list filter or badge, and the MCP server does not expose them. That is acceptable for the stated scope, but users can't find these restrictions.
- **No host test today.** No test exercises `resolveIcon`. Without the new Vitest case, a regression back to the Plug fallback would go unnoticed.
- **Runtime pitfalls, carried from Phase 1.** `productId` arrives as a number. Delete returns 404 on a missing object. The manifest must be registered manually with an admin token. The origin must be exactly `http://localhost:3010`. Base64url JWT decoding must be correct.

## Summary
- **Risk Level**: Low-Medium
- **Estimated Effort**: Medium. About 11 small new files, 2 substantive pages, 1 domain module with tests, a one-line host edit and 5 doc edits.
- **Detected Characteristics**: creates_new_entities, involves_data_operations, ui_heavy, modifies_existing_code (minimal)

## Task Characteristics
- Has reproducible defect: no. The `resolveIcon` forwardRef fallback is a latent host limitation. The task works around it by adding a map entry rather than fixing it.
- Modifies existing code: yes, minimally: `src/main/frontend/src/components/shared/Icons.tsx` (adds an import and a map entry) and the docs.
- Creates new entities: yes. A new plugin, plus object types `carrier` and `shipping-method` and the `shipping` pluginData namespace.
- Involves data operations: yes. CRUD on carriers and methods, and per-product read/write/remove.
- UI heavy: yes. A config page with two tables and forms, and a product tab with a checkbox table and status badges.

## Gaps Identified

### Missing Features (all new; nothing exists today)
- **The `plugins/shipping` plugin.** There are 3 plugin directories today: ai-description, box-size and warehouse. Shipping is not among them.
- **Carrier management.** `objects` type `carrier` `{name, active}`. The backend already accepts any `objectType` (`PluginObjectController`).
- **Delivery method management.** `objects` type `shipping-method` `{carrierId, name, active, max*Cm?}`.
- **Per-product opt-out.** `products.plugin_data.shipping = {disabledMethodIds}`. `PluginDataService` PUT replaces only the plugin's own key.
- **Fit warning.** Compares `pluginData["box-size"]` `{length,width,height}` (numbers, saved via `parseFloat` in `ProductBoxTab.tsx:44-55`) against the method limits.
- **Truck icon.** `ICON_MAP` (`Icons.tsx:18-24`) holds only package, layout-list, plug, warehouse and footprint (→Leaf). The dynamic branch checks `typeof icon === "function"`, which never matches lucide's forwardRef objects, so `truck` would resolve to Plug.

### Incomplete Features (in the confirmed plan)
- **Carrier UPDATE.** The plan lets the admin toggle `active` only. A carrier can't be renamed (for example to fix the typo "Poczta Polksa").
- **Method UPDATE.** The plan lets the admin toggle `active` only. Name, limits and carrier can't be edited. Fixing a limit means delete and recreate, which gives the method a new UUID, so every product's opt-out for it is lost.
- **Empty state in the product tab.** The plan doesn't say what the tab shows when no methods are configured.

### Behavioral Changes Needed (host)
- **`resolveIcon("truck")`.** Changes from Plug to Truck, and every other name keeps its current result.

## User Journey Impact Assessment

| Dimension | Current | After | Assessment |
|-----------|---------|-------|------------|
| Reachability (config) | n/a | Sidebar "Shipping" → `/plugins/shipping/` (menu.main, priority 110, after Warehouse 100) | ✅ |
| Reachability (per-product) | n/a | Product detail → "Shipping" tab (priority 80, last) | ✅ |
| Discoverability (config) | n/a | 9/10 with the Truck icon in the main nav. 7/10 if the icon falls back to Plug | ✅ (depends on the ICON_MAP fix) |
| Discoverability (restrictions) | n/a | 5/10: only visible after opening each product's tab. No list badge or filter | ⚠️ |
| Flow Integration | n/a | The admin configures carriers first, then products. A product tab with no methods is a dead end unless it shows a hint ("Configure delivery methods in Shipping") | ⚠️ (add an empty-state hint) |
| Multi-persona | n/a | Viewer [READ]: sees both pages read-only, with write controls hidden or disabled. Editor/admin [EDIT]: full access. The backend returns 403 on viewer writes (`SecurityConfiguration`) | ✅ |

## Data Lifecycle Analysis

The backend layer was verified in Phase 1 code reads: `PluginObjectController`/`PluginObjectService` (upsert, list ≤1000, get/delete with 404 on a missing object), `PluginDataController`/`PluginDataService` (GET `{}` when empty, PUT own key, DELETE key) and the SDK RPC in `PluginMessageHandler` (`objectsSave`, `objectsList`, `objectsDelete`, `getData`, `setData`, `removeData`, `getProduct`). The UI and Access layers don't exist yet. The status column grades the confirmed plan.

### Entity: Carrier (`objects` / `carrier`)
| Operation | Backend | UI (planned) | Access (planned) | Status |
|-----------|---------|--------------|------------------|--------|
| CREATE | `objectsSave` upsert | Add form on ShippingConfigPage | Sidebar → Shipping, EDIT only | ✅ |
| READ | `objectsList("carrier")` | Carriers table; carrier column and select in the methods section | Sidebar → Shipping | ✅ |
| UPDATE | `objectsSave` (full replace, same id) | Active toggle only | Same page | ⚠️ partial: no rename |
| DELETE | `objectsDelete` (404 if missing) | Delete button, blocked when methods reference the carrier | Same page | ✅ |

### Entity: ShippingMethod (`objects` / `shipping-method`)
| Operation | Backend | UI (planned) | Access (planned) | Status |
|-----------|---------|--------------|------------------|--------|
| CREATE | `objectsSave` | Add form (carrier select, name, 3 optional limits) | Sidebar → Shipping | ✅ |
| READ | `objectsList("shipping-method")` | Methods table; product tab rows | Sidebar plus product tab | ✅ |
| UPDATE | `objectsSave` | Active toggle only | Same page | ❌ name and limits not editable; recreating loses product opt-outs |
| DELETE | `objectsDelete` | Delete button; stale ids are ignored and pruned later | Same page | ✅ |

### Entity: ProductShippingData (`plugin_data.shipping`)
| Operation | Backend | UI (planned) | Access (planned) | Status |
|-----------|---------|--------------|------------------|--------|
| CREATE/UPDATE | `setData` | Checkbox table + Save | Product detail → Shipping tab, EDIT only | ✅ |
| READ | `getData` | Checkbox state + Fits / Too large / No dimensions badge | Product tab (all READ users) | ✅ |
| DELETE | `removeData` | Save when nothing is disabled | Product tab | ✅ |

**Completeness**: about 75%. One UPDATE operation is incomplete (ShippingMethod) and one is partial (Carrier rename). The backend already supports both, so closing the gap is UI-only and small.

**Orphaned Operations**: none in the strict sense. Every created item can be read, and everything displayed has an input. The UPDATE gap is the issue.

**Missing Touchpoints** (multi-touchpoint discovery for "shipping/delivery method" across host, plugins and MCP):
- **Product list (`product.list.filters` / `product.detail.info`).** There is no at-a-glance view of which products have restricted methods. This is high-value but outside the stated scope.
- **MCP server tools (`mcp-server/`).** Shipping restrictions are not exposed to agents. Nice-to-have and out of scope. `pluginData` already appears in the product responses MCP relays, so restrictions would show up there in raw form anyway.
- **Order/checkout.** No such module exists in aj, so this touchpoint does not apply.

## Integration Points
- **Host sidebar and router.** `menu.main` renders at `/plugins/shipping/` (the path `/` avoids the reserved `/detail` and `/edit`). The icon is resolved through `Icons.tsx` `resolveIcon`.
- **ProductDetailPage.** The `product.detail.tabs` iframe receives `contextData={{productId: product.id}}`, which is a number, so normalise it with `String()`.
- **PluginMessageHandler RPC.** It checks the origin exactly, so the manifest url must be `http://localhost:3010`.
- **Plugin storage APIs.** `objects` (`carrier`, `shipping-method`) and `thisPlugin.getData/setData/removeData`.
- **Cross-plugin read.** `hostApp.getProduct(id).pluginData["box-size"]`, a soft dependency on the box-size plugin being registered under id `box-size`.
- **Auth.** `hostApp.getToken()` → base64url decode → `permissions.includes("EDIT")`. The backend `SecurityConfiguration` enforces EDIT on writes.
- **Registration.** `PUT /api/plugins/shipping/manifest` needs `PERMISSION_PLUGIN_MANAGEMENT`.
- **Dev tooling.** `start-dev.sh/.ps1` already pick up any `plugins/*` folder with a `package.json`, so they need no change.

## Patterns to Follow
- `plugins/warehouse`: scaffold (`index.html`, `tsconfig.json`, `vite-env.d.ts`, `main.tsx`) and the objects CRUD page (`WarehousePage.tsx`, `crypto.randomUUID`, `tc-table`, `tc-ghost-button--danger`).
- `plugins/box-size/src/pages/ProductBoxTab.tsx`: getData/setData/removeData save flow with a 2 s "Saved!" message. `domain.ts` `toBoxDimensions` is redeclared locally, not imported.
- `plugins/ai-description/src/pages/product-tab.tsx`: the `canEdit` shape. Fix its base64url handling following `src/main/frontend/src/auth/AuthContext.tsx` `decodeJwtPayload`.
- `plugin-ui.css` classes available: tc-plugin, tc-section, tc-card, tc-table, tc-flex, tc-input, tc-select, tc-primary-button, tc-ghost-button(--danger), tc-badge(--success/--danger), tc-error. There is no checkbox, warning or muted class: use a native checkbox in `label.tc-flex`, show inactive items as plain text with an "Inactive" `tc-badge`, and use no inline hex.
- `ai-description/src/__tests__/domain.test.ts`: the shape of the pure-domain tests (Vitest instead of Jest here).

## Architectural Impact: Low
The work adds a new isolated plugin directory and a one-line additive host change. There are no schema, backend, security or SDK changes.

## Issues Requiring Decisions

### Critical (Must Decide Before Proceeding)
1. **Edit for delivery methods (and carrier rename) (`scope-update-method`).** The plan allows toggling `active` only. Fixing a method's limits or name means delete and recreate. That generates a new UUID, so every product that disabled the old method silently gets the new one enabled again, which defeats requirement (2).
   - Options:
     - A. Add inline edit (an Edit button that turns the row into inputs, then Save) for method name, carrier and limits, and for carrier name. It calls `objectsSave` with the same id. Estimated at about 40–60 lines per table.
     - B. Keep the limited scope and accept the delete/recreate data loss.
     - C. Edit only the method limits and name, not carriers.
   - Recommendation: A. The backend upsert already supports it, and without it the opt-out data isn't durable.

### Important (Should Decide)
1. **Empty-state hint in the product tab (`ux-empty-state`).** When no active methods exist, the tab would show an empty table with no way forward.
   - Options: A. Show "No delivery methods configured. Add them in the Shipping section." B. Show an empty table.
   - Default: A (one line of text and no navigation API needed).
2. **Product-list visibility of restrictions (`scope-list-touchpoint`).** Requirement (2) is satisfied in the tab, but an admin can't see which products have restrictions without opening each one.
   - Options: A. Out of scope (default). B. Add a `product.detail.info` badge such as "2 methods disabled". C. Add a `product.list.filters` entry.
   - Default: A. The request doesn't ask for it (minimal-implementation standard). Record it as a follow-up.
3. **How inactive methods appear in the product tab (`ux-inactive-methods`).** The plan says "greyed out and counted as unavailable", but `plugin-ui.css` has no muted or disabled class.
   - Options: A. Show the row with an "Inactive" `tc-badge` and a disabled checkbox that keeps the stored opt-out state. B. Hide inactive methods from the tab.
   - Default: A. It is transparent, needs no new CSS, and preserves the opt-out state if the method is reactivated.

## Recommendations
- Add to the spec's file list: `plugin-development.md` line 59 (code sample → 3010), `README.md` lines 18 and 71, and `.maister/docs/INDEX.md` (Plugin Development port list → "shipping 3010, further business modules 3011+").
- Keep `domain.ts` pure and cover these with Vitest:
  - `exceedsLimits`: rotation, partial limits, missing box
  - `toCarrier` / `toShippingMethod`
  - `toProductShippingData`, including stale-id pruning against the current method list
  - `canEdit`: base64url, malformed token → false
- Add the host Vitest case `resolveIcon("truck") === Truck`. Check that `resolveIcon("warehouse")` is unchanged.
- In ShippingConfigPage, validate limits as positive finite numbers, and require a non-empty name and a selected carrier.
- Treat a 404 on delete as success, and run the carrier-delete guard against the freshly loaded method list.
- Document manifest registration (curl with an admin token) in the `plugins/CLAUDE.md` Reference Plugins entry.

## Risk Assessment
- **Complexity Risk**: Low. Two CRUD-style pages and a small pure domain module.
- **Integration Risk**: Medium. Several of these failure modes are silent: the icon fallback, the origin match, the numeric `productId`, manual registration, and the box-size id dependency.
- **Regression Risk**: Low. The host change adds a map entry. The new plugin is isolated, `setData` doesn't touch other namespaces, and nothing changes in the backend.
