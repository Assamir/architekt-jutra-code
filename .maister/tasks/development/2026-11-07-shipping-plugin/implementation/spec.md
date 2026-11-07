# Specification: Shipping Plugin (`plugins/shipping`, port 3010)

## TL;DR
New iframe plugin `plugins/shipping` (Vite + React, port 3010, `strictPort`). It stores carriers and delivery methods as plugin objects, keyed by `PluginObject.objectId` (a UUID). Each product's opt-outs go in pluginData `{disabledMethodIds}`, so the backend does not change.
A sidebar "Shipping" page manages carriers and methods: add, edit inline with the same id, toggle active, delete. A product "Shipping" tab lets editors untick methods, warned by a rotation-aware size check against box-size dimensions. The tab reloads box and method data on focus and keeps unsaved ticks.
The only host code change is `truck: Truck` in `ICON_MAP`, with a Vitest test, and it must add no lint problems to the existing baseline of 16. Six docs record the new port rule: 3004–3009 for demo and utility plugins, 3010 and up for business modules.

## Key Decisions
- Opt-out model in pluginData `{disabledMethodIds: string[]}`. Every active method is available unless unticked, and `removeData` runs when the list is empty, which keeps product JSONB minimal.
- The domain id of a Carrier or ShippingMethod is `PluginObject.objectId` (the UUID), never `PluginObject.id`, which is the numeric DB key. `carrierId` and `disabledMethodIds` hold objectIds. `plugins/sdk.ts` types `id` as `string`, so the compiler will not catch the mistake; a mapper test guards it.
- Inline edit saves with `objects.save` and the same id. Delete+recreate would change the UUID and silently drop per-product opt-outs.
- Warn only, never auto-disable. The size check sorts dimensions, so the box may be rotated, and a missing limit means unlimited. The editor stays in control.
- Badge precedence is Inactive > No dimensions > No limits > Fits / Too large. This matches the mockups: an inactive method shows "Inactive" even when it has limits, and with no box every row shows "No dimensions".
- A method counts as inactive when its own `active` is false or its carrier is inactive or missing. Its stored opt-out is kept and re-applies on reactivation.
- Reload on focus: the product tab re-fetches the product and both lists when its window gains focus or its document becomes visible. It keeps the in-progress checkbox selection, which is reset from stored data only on the initial load and after Save.
- Admin means `PERMISSION_EDIT`. The UI is gated by a local `canEdit(token)` with correct base64url decoding, and the backend already enforces EDIT with 403.
- Carrier delete is blocked while methods reference the carrier. The guard re-lists methods before deleting, so it never acts on stale state.
- Delete asks for no confirmation, consistent with the warehouse plugin.
- Carrier selects list active carriers. The edit-row select also includes the method's current carrier so its value is always representable. This follows the add-form mockup, which omits the inactive carrier.
- Only one row is in edit mode at a time, across both config tables. Opening another row's edit discards the open draft.
- Lint gate is "no new problems". The current baseline is 16 problems (14 errors), including the pre-existing `Icons.tsx:26:17` `react-refresh/only-export-components`, which is out of scope.
- Port rule: 3004–3009 stay available for small demo and utility plugins, and 3010 and up is reserved for business modules (shipping 3010, the next business module 3011).
- Mockups are binding for layout, components and states. Where mockup sample data or wording conflicts with a rule in this spec (row order, hint content), the rule wins.
- Patterns are copied, not imported across plugins: the scaffold from `warehouse`, the save flow from `box-size`, and `canEdit` from `ai-description` with the decoding fixed. Only `plugins/sdk.ts` is shared, per the plugin standard.
- Mockup-only helper classes (`muted`, `cb`, `row-off`, `boxline`, `hint`, `empty`, `editrow`, `limits`) are not reproduced. Plain elements, `tc-*` classes and inline layout styles replace them, and `plugin-ui.css` does not change.

## Open Questions / Risks
- Manifest registration is manual (`PUT /api/plugins/shipping/manifest` with an admin token). Size warnings work only when box-size is registered under the id `box-size`.
- Reload-on-focus trigger: the host keeps tab iframes mounted and switches them with `display`. Switching host tabs therefore probably fires neither `focus` nor `visibilitychange` inside the iframe; the iframe document's visibility follows the top-level page. The refresh then runs on the first click inside the Shipping tab, or when the browser tab becomes visible again. This is acceptable because unsaved ticks are kept, but the box line can stay stale until that first interaction. Check this in the browser walkthrough.
- Concurrent editors can still overwrite each other, because `objects.save` replaces the whole object and there is no versioning. This is acceptable at this scale.
- Error messages are terse. `ApiError` builds them from `statusText`, which embedded Tomcat probably leaves empty, so users see a contextual prefix plus e.g. `403` (see requirement 24).
- Follow-ups, out of scope: showing restrictions on the product list or detail info, MCP exposure, and checkout consumers. `plugins/CLAUDE.md` also gives an inaccurate `../../sdk` import path for pages; that is noted but not fixed here.
- Resolved: the lint baseline (H1), objectId vs id (M1), the stale product tab (M2), mockup conflicts and hint wording (M3), the port rule (L1), and the edge states (L3–L8). See Audit Resolution.

## Goal
Let editors maintain a system-wide catalogue of carriers and delivery methods, and turn individual methods off per product, warned when the product's box exceeds a method's size limits, all as an isolated plugin on port 3010.

## User Stories
- As an editor, I want to add carriers (DHL, DPD, InPost, Poczta Polska…) and their delivery methods with optional max L×W×H, so that the system knows which shipping options exist.
- As an editor, I want to rename carriers and edit a method's carrier, name and limits in place, so that typos and limit changes don't wipe existing product restrictions.
- As an editor, I want to deactivate a carrier or method without deleting it, so that I can suspend it temporarily and keep per-product settings.
- As an editor, I want to untick delivery methods on a product's Shipping tab and see which methods its box is too large for, so that unsuitable methods are not offered for that product.
- As a viewer, I want to see the carriers, methods and per-product availability read-only, so that I know the shipping setup without being able to change it.

## Core Requirements

### Sorting rule (used by requirements 1, 6, 16 and 19)
Carriers sort by name. Methods sort by carrier display name, then by method name. All comparisons are case-insensitive. A method whose carrier is missing sorts under the display name "Unknown carrier". Mockup row order is sample data and does not override this rule.

### Carriers (ShippingConfigPage, "Carriers" section)
1. List carriers sorted by name, with columns Name, Methods (the count of methods referencing the carrier), Status and actions.
2. Add a carrier: a text input ("Carrier name (e.g. GLS)") and an "Add carrier" button. The name is trimmed and must not be empty ("Carrier name is required."). New carriers are `active: true` and get the objectId `crypto.randomUUID()`.
3. Toggle active with an "Active" checkbox in the Status column. The change saves immediately with the full object and the same objectId.
4. Inline edit: "Edit" turns the row into a name input plus an Active checkbox with "Save" / "Cancel". Save keeps the objectId, and the name is validated as in the add form. Only one row is in edit mode at a time, across both the Carriers and Delivery methods tables. Clicking "Edit" on another row discards the open draft without asking. "Cancel" discards the draft. A successful save reloads both lists and closes the edit. Immediate toggles on other rows during an edit reload the lists but keep the open draft, unless the edited object no longer exists after the reload, in which case the edit closes.
5. Delete: re-list methods first. If any method references the carrier, nothing is deleted and the Carriers section shows `tc-error` with `Cannot delete "<name>": it still has 1 delivery method. Delete or move it first.` when N = 1, or `Cannot delete "<name>": it still has <N> delivery methods. Delete or move them first.` when N > 1. Otherwise the carrier is deleted without a confirmation prompt and the list is reloaded.

### Delivery methods (ShippingConfigPage, "Delivery methods" section)
6. List methods sorted by the sorting rule, with columns Carrier, Method, Max size (L×W×H), Status and actions. Limits render as `L × W × H cm`, with `—` for a missing single limit, or "No limit" when all three are missing. A method whose carrier is missing shows "Unknown carrier" in the Carrier column.
7. Add a method: a carrier select ("-- Carrier --" plus active carriers), a name input, the label "Max (cm, optional):", three number inputs L/W/H with aria-labels "Max length cm", "Max width cm" and "Max height cm", and an "Add method" button. Validation: a carrier is required ("Select a carrier."), the name is trimmed and required ("Method name is required."), and each filled limit must be a finite number > 0 ("Limits must be positive numbers."). Empty limits are omitted from the stored object. New methods are `active: true` and get a UUID objectId. `carrierId` stores the carrier's objectId.
8. Inline edit: the row becomes a carrier select, name input, L/W/H inputs and an Active checkbox, with Save / Cancel. Save uses the same objectId, the same validation, and writes the full object, so cleared limits drop out. The edit-mode rules of requirement 4 apply. When the method's carrier is missing, the select starts at "-- Carrier --", and a carrier must be chosen before saving.
9. Toggle active with the "Active" checkbox, saved immediately with the same objectId.
10. When the method's carrier is inactive or missing, the Status cell shows the method's own Active checkbox (editors) and also `tc-badge` "Carrier inactive". This applies whether the method itself is active or not.
11. Delete removes the method without a confirmation prompt (listed ids only) and reloads. Stale ids in product data are ignored on read and pruned on the next product save.

### Product Shipping tab (ProductShippingTab)
12. Normalise the product id with `String(sdk.thisPlugin.productId ?? "")`. If it is empty, show "No product context available."
13. Initial load in parallel: token (for `canEdit`), carriers, methods, own data (`getData`) and the product (`hostApp.getProduct`). Show "Loading..." while loading and `tc-error` on failure. The loaded `disabledMethodIds` initialise the checkbox selection.
14. The heading is "Delivery methods for this product". The box line reads `Box: L × W × H cm (from Box Size)` from `pluginData["box-size"]`. When the box is missing or incomplete, it reads "No box dimensions — size check unavailable. Set them in the Box Size tab." The hint stays, because requirement 28 refreshes the box after the user returns to the tab.
15. Empty state: when no methods exist, show the box line plus "No delivery methods configured. Add them in the Shipping section.", with no table and no Save. Viewers see the same empty state and no read-only notice.
16. Table with one row per method, sorted by the sorting rule. Columns:
    - Available: a native checkbox with aria-label `Available: <method name>`.
    - Carrier: the carrier name, or "Unknown carrier" when the carrier is missing.
    - Method.
    - Max (cm): `L × W × H`, with `—` for each missing dimension, or a single `—` when the method has no limits.
    - Size check.
17. A checkbox is checked unless the method id is in `disabledMethodIds`. Changes stay local until Save.
18. Size check badge (component:size-check-badge), in order of precedence:
    - Inactive method (its own `active` is false, or its carrier is inactive or missing): one `tc-badge` "Inactive", even when both the method and its carrier are inactive. The checkbox is disabled and the stored opt-out state is preserved.
    - Box missing: plain text "No dimensions".
    - Method has no limits: plain text "No limits".
    - Box fits: `tc-badge tc-badge--success` "Fits".
    - Box exceeds the limits: `tc-badge tc-badge--danger` "Too large".
19. Warning hint above the table, in `tc-error`, when one or more active, currently ticked methods are "Too large". Names are quoted, comma-separated, in table order. Exact sentences:
    - One method: `"<name>" is still available although the box exceeds its limits. Consider unticking it.`
    - Several methods: `"<name1>", "<name2>" are still available although the box exceeds their limits. Consider unticking them.`

    The hint recomputes from the current unsaved checkbox state and from refreshed data, and viewers see it too. Mockup hint text is sample data; this rule decides which names appear.
20. Editor footer: a "Save" button (`tc-primary-button`, disabled while saving) and the counter "N of M methods disabled for this product". N counts disabled ids that exist in the current method list, inactive ones included. M is the total number of methods.
21. Save prunes `disabledMethodIds` to ids of methods that still exist, keeping inactive ones. A non-empty list goes to `setData(productId, {disabledMethodIds})`; an empty one goes to `removeData(productId)`. On success, the Save button's label changes to "Saved!" for 2 s, and the selection is reset to the saved, pruned list. On failure, `tc-error`.

### Product tab refresh
28. Reload on focus. While the tab is shown, it listens for the iframe window's `focus` event and for `visibilitychange` to `visible`. On either event it re-fetches the product (box-size dimensions), carriers and methods in parallel. It does not re-fetch its own data and does not change the in-progress checkbox selection; the selection is reset from stored data only on the initial load (requirement 13) and after Save (requirement 21). Rules:
    - No "Loading..." state is shown during a refresh. The current data stays visible until the new data arrives.
    - A refresh does not start while another refresh or a Save is in flight.
    - A refresh failure shows `tc-error` with the prefix "Failed to refresh shipping data:" and keeps the previously loaded data.
    - Methods that appear after a refresh are ticked unless their id is in the selection. Ids of methods that disappeared stay in the selection and are pruned on Save.
    - The box line, badges, hint and counter all derive from the refreshed data.
    - The listeners are removed when the component unmounts.

### Permissions, errors, runtime
22. Write controls (add forms, Edit / Delete / Save buttons, active checkboxes on the config page, enabled product checkboxes, the product Save button and counter) render only when `canEdit` is true, meaning the JWT `permissions` claim contains "EDIT".
23. Viewer variants:
    - Config page: the line "You have read-only access. Ask an editor to change carriers or delivery methods." Status cells show `tc-badge--success` "Active", plain `tc-badge` "Inactive", or "Inactive (carrier)". A method that is itself inactive shows "Inactive", including when its carrier is also inactive. An active method whose carrier is inactive or missing shows "Inactive (carrier)".
    - Product tab: the line "Read-only: you can see which methods are available but not change them." All checkboxes are disabled, with no Save and no counter. In the empty state, the notice is not shown (requirement 15).
24. Every SDK call is wrapped in try/catch, and the error is shown in `tc-error` near the affected section. No error is swallowed. The message is `<context>: <err.message>`, where the context names the action, for example "Failed to load shipping data", "Failed to save", "Failed to save carrier", "Failed to delete method" or "Failed to refresh shipping data". `err.message` is shown as is but trimmed, because it may be as terse as `403 ` with an empty status text; this gives e.g. "Failed to save: 403". When the message is empty, the fallback is "Unknown error". Buttons are disabled while a mutation runs.
25. Dev server on port 3010 with `strictPort: true`. Manifest top-level fields: `name` "Shipping", `version` "1.0.0", `description` "Carriers, delivery methods and per-product shipping restrictions", and `url` exactly `http://localhost:3010`. Extension points:
    - `menu.main`: label "Shipping", icon `truck`, path `/`, priority 110.
    - `product.detail.tabs`: label "Shipping", path `/product-shipping`, priority 80.

### Host and docs
26. `src/main/frontend/src/components/shared/Icons.tsx`: import `Truck` and add `truck: Truck` to `ICON_MAP`. Nothing else changes. The pre-existing `Icons.tsx:26:17` `react-refresh/only-export-components` lint error stays as it is.
27. Docs updates. The port rule wording is the same everywhere: existing plugins use 3001–3003, 3004–3009 stay available for small demo and utility plugins, and 3010 and up is reserved for business modules (shipping 3010, the next business module 3011).
    - `.maister/docs/standards/plugins/plugin-development.md`: the Fixed Dev Ports prose (line 56) adds `shipping` 3010 and states the port rule. The code sample on line 59 changes from `port: 3004` to `port: 3011`.
    - `.maister/docs/INDEX.md`: the Plugin Development port list on line 109 changes to match, e.g. "demo/utility plugins 3004–3009, shipping 3010, next business module 3011".
    - `.maister/docs/project/tech-stack.md`: add `shipping` (Vite + React, :3010) to the plugin list with ports (~lines 44–46).
    - `plugins/CLAUDE.md`: a "Shipping Plugin" entry under Reference Plugins covering the port and the port rule, storage (objects `carrier` / `shipping-method` keyed by objectId, pluginData `disabledMethodIds`), the cross-plugin box-size read via `hostApp.getProduct` with reload on focus, `canEdit`, and the manifest registration command.
    - Root `CLAUDE.md`: append `shipping` (:3010, Vite) to the Plugins line (line 39) only. The file has uncommitted user edits.
    - `README.md`: add `shipping/` to the plugin tree (~line 18) and `(cd plugins/shipping && npm install)` to Quick Start (~line 71).

## Visual Design
Mockups in `analysis/design-context/` are binding inputs. The implementation-planner will attach `Visual References` to UI task groups. Fidelity: hi-fi HTML rendered with the host's `plugin-ui.css`. The implementation matches the mockups in layout, components and states. Where mockup sample data or wording conflicts with a rule in this spec, the rule wins. The known conflicts are row order (the mockups list "InPost Paczkomat" before "InPost Kurier"; the sorting rule puts Kurier first) and hint content (the mockups name only "InPost Paczkomat", while requirement 19 names every ticked, active, too-large method). The mockup-local helper classes are realised with plain elements and inline layout only.

| ID | Mockup | Component / state | Key elements |
|----|--------|-------------------|--------------|
| screen:shipping-config | `analysis/design-context/mockups/shipping-configuration-editor.html` | ShippingConfigPage, editor | Root `tc-plugin` (padding 1rem, max-width 900px), `h1` "Shipping", two `tc-section`s (Carriers, Delivery methods), `tc-flex` add forms, `tc-table`s, Active checkboxes, Edit (`tc-ghost-button`) / Delete (`tc-ghost-button--danger`), "Carrier inactive" badge |
| screen:shipping-config-edit | `analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html` | ShippingConfigPage, inline edit + blocked delete | Carrier edit row (name input, Active, Save / Cancel); method edit row (carrier select, name, L/W/H inputs in `tc-flex`, Active, Save / Cancel); `tc-error` under the Carriers add form for a blocked delete |
| screen:shipping-config-readonly | `analysis/design-context/mockups/shipping-configuration-read-only-viewer.html` | ShippingConfigPage, viewer | Read-only notice line; no forms or actions; status badges Active / Inactive / Inactive (carrier) |
| screen:product-shipping-tab | `analysis/design-context/mockups/product-shipping-tab-editor.html` | ProductShippingTab, editor | Root `tc-plugin` (padding 1.5rem), `h3` heading (margin 0 0 1rem), box line, checkbox table, size-check badges, footer `tc-flex` (margin-top 1rem) with Save + counter |
| screen:product-shipping-tab-warning | `analysis/design-context/mockups/product-shipping-tab-too-large-method-still-enabled.html` | ProductShippingTab, warning | `tc-error` hint above the table naming the available too-large methods |
| screen:product-shipping-tab-nodims | `analysis/design-context/mockups/product-shipping-tab-no-box-dimensions.html` | ProductShippingTab, no box | "No box dimensions…" line; every active row shows "No dimensions"; no hint |
| screen:product-shipping-tab-readonly | `analysis/design-context/mockups/product-shipping-tab-read-only-viewer.html` | ProductShippingTab, viewer | Read-only notice; all checkboxes disabled; hint still shown; no Save or counter |
| screen:product-shipping-tab-empty | `analysis/design-context/mockups/product-shipping-tab-empty-state.html` | ProductShippingTab, empty | Box line + "No delivery methods configured. Add them in the Shipping section." |
| component:size-check-badge | `analysis/design-context/mockups/product-shipping-tab-editor.html` | Size check cell | `tc-badge--success` Fits / `tc-badge--danger` Too large / `tc-badge` Inactive / plain text No limits and No dimensions |
| component:inline-edit-row | `analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html` | Edit row in both config tables | Cells switch to `tc-input` / `tc-select` with `tc-primary-button` Save + `tc-ghost-button` Cancel |

The mockups use a `row-off` greyed-row style for inactive rows. It is not reproduced, because there is no `tc-*` class for it and inline hex is forbidden. The Inactive / Carrier inactive badges carry that state instead.

## Reusable Components

### Existing Code to Leverage
- `plugins/warehouse/index.html`, `package.json`, `tsconfig.json`, `vite.config.ts`, `src/vite-env.d.ts`, `src/main.tsx` provide the scaffold to copy: SDK + `plugin-ui.css` loading, deps and scripts, strict TS config, and StrictMode + BrowserRouter + Routes.
- `plugins/warehouse/manifest.json` is the manifest shape to copy (`name`, `version`, `description`, `url`, `extensionPoints`).
- `plugins/warehouse/src/pages/WarehousePage.tsx` provides the objects CRUD page pattern: `Promise.all` load, `crypto.randomUUID()` ids, `tc-table`, `tc-ghost-button--danger`, and delete without confirmation.
- `plugins/warehouse/src/domain.ts` provides the `toX(obj: PluginObject)` mapper pattern, which takes the domain id from `obj.objectId`.
- `plugins/box-size/src/pages/ProductBoxTab.tsx` provides the per-product getData / setData / removeData save flow, the saving flag and the 2 s "Saved!" button-label flash. Unlike that file, the shipping tab does not swallow load errors.
- `plugins/box-size/src/domain.ts` defines the `BoxDimensions` shape `{length, width, height}` and the `toBoxDimensions` null tolerance. Both are redeclared locally, never imported across plugins.
- `plugins/ai-description/src/pages/product-tab.tsx` (~l.27–34) shows the `canEdit` flow via `hostApp.getToken()` and `permissions.includes("EDIT")`.
- `src/main/frontend/src/auth/AuthContext.tsx` `decodeJwtPayload` (l.14) is the correct base64url → base64 conversion to copy into `canEdit`. It is not exported, so it is copied.
- `plugins/sdk.ts` provides `getSDK` and `PluginObject`, imported from pages as `../../../sdk` and from `src/domain.ts` as `../../sdk`. Its `PluginObject.id` is typed `string` but holds the numeric DB key (`core/plugin/PluginObjectResponse.java`); mappers ignore it.
- `src/main/frontend/public/assets/plugin-ui.css` provides the `tc-*` classes: tc-plugin, tc-section, tc-table, tc-flex, tc-input, tc-select, tc-primary-button, tc-ghost-button(--danger), tc-badge(--success / --danger), tc-error.
- Host Vitest (`src/main/frontend/src/test/`, vitest ^3.2.4) provides the infrastructure for the icon test. The plugin uses the same vitest version. Host tests import `describe` / `it` / `expect` explicitly (e.g. `src/test/footprint-helpers.test.ts:1`).
- No backend code is involved. The existing `PluginObjectController`/`PluginObjectService`, `PluginDataController`/`PluginDataService`, `ProductResponse.pluginData` and the EDIT matchers in `SecurityConfiguration` already cover everything.

### New Components Required
- **`plugins/shipping/` scaffold**: `index.html`, `manifest.json`, `package.json` (`shipping-plugin`, warehouse deps plus `vitest` devDependency and a `test` script), `tsconfig.json` (`include: ["src"]`, as warehouse), `vite.config.ts` (3010, strictPort, no Vitest `globals`), `src/vite-env.d.ts`, `src/main.tsx` (routes `/` and `/product-shipping`). These are new because each plugin is an independent npm project.
- **`src/domain.ts`**: types `Carrier`, `ShippingMethod`, `BoxDimensions`, plus pure helpers, each with a caller:
  - `toCarrier` and `toShippingMethod`: mappers that set the domain `id` from `PluginObject.objectId`, never `PluginObject.id`. They default `active` to true unless it is `false`, and keep only finite positive limits.
  - `toBoxDimensions(product)`: reads `pluginData["box-size"]` and returns null if any dimension is missing or invalid.
  - `toDisabledMethodIds(data)`: returns the string entries, or `[]`.
  - `sizeCheck(box, method)`: returns no-dimensions / no-limits / fits / too-large, using the sorted-dimension comparison where a missing limit is unlimited.
  - `pruneDisabledIds(ids, methods)`.
  - `canEdit(token)`.

  These are new because the shipping-specific logic does not exist anywhere. Keeping it pure makes it testable.
- **`src/pages/ShippingConfigPage.tsx`** (menu.main) and **`src/pages/ProductShippingTab.tsx`** (product.detail.tabs): new UI with no existing equivalent. Patterns are copied as listed above. The reload-on-focus listener lives in `ProductShippingTab`; no host or sibling plugin has one to reuse.
- **`src/domain.test.ts`**: plugin Vitest tests. The sibling Vite plugins have no tests to extend.
- **`src/main/frontend/src/test/icons.test.ts`**: a host test for `resolveIcon`. No test covers it today.

## Technical Approach
- **Data model.** No migrations; the plugin uses existing plugin storage.

  | Entity | Storage | Domain id | Shape |
  |---|---|---|---|
  | Carrier | objects type `carrier` | `PluginObject.objectId` (UUID) | `{name, active}` |
  | Delivery method | objects type `shipping-method` | `PluginObject.objectId` (UUID) | `{carrierId, name, active, maxLengthCm?, maxWidthCm?, maxHeightCm?}` |
  | Product restriction | pluginData `products.plugin_data.shipping` | (product id) | `{disabledMethodIds: string[]}` |

  `PluginObject.id` is the numeric DB primary key and is never used as a domain id. `carrierId` holds a carrier objectId, and `disabledMethodIds` hold method objectIds. All `objects.save` and `objects.delete` calls pass the objectId. Using the numeric id would create duplicate objects on edit, make deletes return 404, and corrupt opt-outs.
- **Flow.** The iframe calls the SDK, which sends a postMessage to the host `PluginMessageHandler`. The handler checks the exact origin `http://localhost:3010` and calls the REST API with the user's JWT. The backend enforces READ on GETs and EDIT on writes.
- **Writes.** Every config mutation calls `objects.save(type, objectId, fullData)`, then reloads both lists, so the UI always reflects the server. Edits and toggles reuse the existing objectId.
- **Fit check.** Sort the three box dimensions in descending order. Build the method's limit triple with each missing limit replaced by +∞, then sort it in descending order. The box fits when every sorted box dimension is ≤ the limit at the same position. A missing limit therefore never constrains, and the comparison still allows any rotation against the limits that are set. When all three limits are missing, the result is "no-limits" regardless of the box. Example: box 120×12×48 sorts to (120, 48, 12). Limits 64×38×41 sort to (64, 41, 38) and give too-large; limits L 130 and the rest missing sort to (∞, ∞, 130) and give fits.
- **Effective active.** `method.active && carrier?.active === true`. It is computed in the pages from the carrier lookup, and drives both the badges and the disabled checkboxes.
- **Product tab state.** The disabled-id selection is set from stored data on the initial load and after a successful Save, and is otherwise only changed by checkbox toggles. Refreshes (requirement 28) replace the product, carrier and method data but never the selection. The hint and counter derive from the selection plus the latest data. Save writes the pruned selection.
- **Async rules.** Use `Promise.all` for parallel loads. Do not pass async handlers to `onClick` or event listeners directly; wrap them with `void`. Use no `as unknown as` casts; the mappers narrow `unknown` input instead.
- **Runtime pitfalls.**
  - `productId` arrives as a number, so it goes through `String()`.
  - Avoid `/detail` and `/edit` as menu paths.
  - Errors arrive as `"<status> <statusText>"`, often with an empty status text; see requirement 24 for display.
  - A 401 redirects the whole host.
  - Delete only ids from the last listing, because deleting a missing object returns 404.
  - Tab iframes stay mounted and are hidden with CSS, so the product tab must refresh itself (requirement 28).
- **Registration (manual, documented).** Run `PUT /api/plugins/shipping/manifest` with a `PERMISSION_PLUGIN_MANAGEMENT` token. `start-dev` picks the plugin up automatically.

## Implementation Guidance

### Testing Approach
- Write 2-8 focused tests per implementation step group. Test verification runs only the new tests, not the entire suite.
- **Plugin domain tests** (`plugins/shipping/src/domain.test.ts`, run with `npm test` / `vitest run`). The file imports `describe`, `it` and `expect` from `"vitest"` explicitly and does not rely on globals, because `tsconfig.json` includes `src` and `tsc -b` type-checks the test file. 16 tests, split into two step groups of 8:
  - Size-check group (8): `sizeCheck` fits; too large; fits only when rotated; partial limits (missing treated as unlimited); no limits; missing box. `toBoxDimensions` with complete data; with missing or partial box-size data.
  - Mapper and permission group (8): `toCarrier` / `toShippingMethod` default `active` to true; drop invalid limits; take `id` from `objectId` and not from the numeric `id` (e.g. `{id: "42", objectId: "uuid-…"}` maps to `"uuid-…"`). `pruneDisabledIds` drops unknown ids and keeps inactive ones. `canEdit` with an EDIT token; a READ-only token; a base64url payload containing `-` / `_`; a malformed or null token returning false.
- **Host icon test** (`src/main/frontend/src/test/icons.test.ts`, 3 tests): `resolveIcon("truck")` returns `Truck`, `resolveIcon("warehouse")` still returns `Warehouse`, and an unknown name falls back to `Plug`.
- **Pages** are verified by `npm run build` (`tsc -b`, which must pass for the whole plugin including the test file) and a browser walkthrough of the 8 mockup states as an editor and as a viewer. The walkthrough also covers reload on focus: set box dimensions in the Box Size tab, return to Shipping, click inside it, and check that the box line updates while unsaved ticks stay. No component tests, consistent with the sibling Vite plugins.
- The backend test suite is not needed (no backend changes).
- **Lint gate.** No new host lint problems. The baseline on the current tree is 16 problems (14 errors, 2 warnings) from `npx eslint .` in `src/main/frontend`. It includes `src/components/shared/Icons.tsx:26:17` `react-refresh/only-export-components`, which is pre-existing and out of scope. After the change the total stays at 16, and `npx eslint src/components/shared/Icons.tsx src/test/icons.test.ts` reports only that pre-existing error.

### Standards Compliance
- `standards/plugins/plugin-development.md`:
  - Structure (`<id>-plugin`, `dev` script, `src/domain.ts`, `src/pages/`) and the shared `sdk.ts` import.
  - Mandatory `plugin-ui.css` / `tc-*`, with inline style for layout only.
  - Manifest as the source of truth; fixed port with `strictPort`; Lucide kebab-case icon.
  - try/catch around every SDK call, with errors surfaced.
- `standards/backend/plugin-auth.md`: browser auth via `hostApp.getToken()` and a permissions check from the JWT claim.
- `standards/frontend/components.md`: named `export function` components, no default exports, `import type` for types.
- `standards/frontend/css.md`: no inline hex colours.
- `standards/frontend/tooling.md`: strict TS, `verbatimModuleSyntax`, `erasableSyntaxOnly` (so no enums; use string-literal unions for the size-check result), and a type-check-gated build.
- `standards/frontend/accessibility.md`: aria-labels on checkboxes and limit inputs, real `<label>`/`<button>` elements, keyboard-operable controls.
- `standards/testing/frontend-testing.md`: the host test lives in `src/main/frontend/src/test/` with a describe block named after the feature. Both host and plugin tests import `describe` / `it` / `expect` from `"vitest"` explicitly.
- `standards/global/minimal-implementation.md`: no weight, no seed data, no list badges or filters, no speculative helpers; every domain function has a caller.
- `standards/global/error-handling.md`: specific user messages, fail-fast validation before writes.
- `standards/global/conventions.md`: fixed unique dev ports, and course directories are left alone.

## Out of Scope
- Backend, schema, security or SDK changes, and `plugin-ui.css` changes. This includes correcting the `PluginObject.id` type in `plugins/sdk.ts`.
- Weight limits, because products and box-size carry no weight.
- Seed or default carriers.
- Restriction visibility outside the product tab (`product.detail.info` badge, `product.list.filters`), MCP tools, and checkout or order consumers.
- Auto-disabling methods based on size.
- Bulk per-product operations, and a cascade delete of a carrier's methods.
- Delete confirmation dialogs.
- A host-side cross-plugin change notification or iframe reload on tab switch.
- Fixing the `resolveIcon` dynamic-lookup fallback in general; only the map entry is added.
- Fixing the pre-existing host lint problems, including `Icons.tsx:26:17`.
- Correcting the `../../sdk` page import path in `plugins/CLAUDE.md`.

## Success Criteria
- `cd plugins/shipping && npm install && npm run build` succeeds (`tsc -b` passes, including `src/domain.test.ts`), and `npm test` passes all 16 domain tests.
- The host `npx vitest run src/test/icons.test.ts` passes (3 tests). No new lint problems are introduced: the baseline is 16 problems (14 errors) on the current tree, including `Icons.tsx:26:17` `react-refresh/only-export-components`, which is pre-existing and out of scope.
- Vite serves the plugin on :3010, and a second instance fails because of `strictPort`.
- After manifest registration (name "Shipping", version "1.0.0"), the sidebar shows "Shipping" with the Truck icon, and the product detail page shows a "Shipping" tab.
- As editor:
  - Carriers and methods can be added, edited inline (with the objectId unchanged and no duplicate object created), toggled and deleted.
  - Only one row is in edit mode at a time across both tables.
  - Deleting a carrier that has methods is blocked with the specified singular or plural message.
  - With box 120×12×48 and both InPost methods ticked, "InPost Kurier" (80×50×35) and "InPost Paczkomat" (64×38×41) show "Too large", rows follow the sorting rule (Kurier before Paczkomat), and the hint reads `"InPost Kurier", "InPost Paczkomat" are still available although the box exceeds their limits. Consider unticking them.`
  - Unticking one of them leaves the singular hint naming only the other one.
  - Unticking a method and saving, then reloading, keeps it unticked (`GET /api/plugins/shipping/products/{id}/data` returns `{disabledMethodIds:[…]}` holding objectIds). The Save button shows "Saved!" for 2 s.
  - Re-ticking everything and saving removes the data.
  - Renaming or editing the limits of a disabled method keeps it disabled on the product.
  - Deactivating a disabled method shows "Inactive" with its unticked state preserved, and reactivating it re-applies the opt-out.
  - After box dimensions are set in the Box Size tab, returning to the Shipping tab and focusing it updates the box line and badges, and unsaved ticks are kept.
- As viewer: no add, edit, delete, toggle or save controls appear, product checkboxes are disabled, and the read-only notices are shown (except in the product-tab empty state).
- All 8 screens and 2 components match their mockups in layout, components and states. Where mockup sample data or wording conflicts with the spec rules (row order, hint content), the rules win.
- All six docs reflect `shipping` on 3010 and the port rule: 3004–3009 for demo and utility plugins, 3010 and up for business modules (next 3011).

## Audit Resolution
Source: `verification/spec-audit.md`.

| ID | Finding | Resolution |
|----|---------|------------|
| H1 | The "host `npm run lint` passes" criterion cannot be met | Replaced with "no new lint problems". The baseline of 16 problems (14 errors), including the pre-existing `Icons.tsx:26:17`, is recorded in Testing Approach and Success Criteria. The plugin must pass `tsc -b`. |
| M1 | The domain id field is unspecified, and `sdk.ts` mistypes the numeric id | Key Decisions, the data model table (Domain id column), the reqs 2/7 and the mappers now state that id = `PluginObject.objectId` and never `PluginObject.id`. A mapper test was added. |
| M2 | The product tab goes stale (iframes are kept alive) | Reload on focus: req 28 adds window `focus` / `visibilitychange` refresh of the product and both lists, and keeps unsaved ticks. The Box Size hint is kept. The trigger caveat is listed under Risks. |
| M3 | The "match mockups in wording" criterion conflicts with the rules, and the plural hint is unspecified | The criterion now reads "layout, components and states; rules win". There is an explicit case-insensitive sorting rule, and exact singular and plural hint sentences in req 19. The InPost success criterion is restated. |
| L1 | Ambiguous port rule for non-business plugins | 3004–3009 for demo and utility plugins, 3010+ for business modules (shipping 3010, next 3011). The sample uses 3011. The wording is in req 27 for every doc. |
| L2 | Other docs listing ports are not updated | `.maister/docs/project/tech-stack.md` (~l.44–46) is added. The docs count is now six. |
| L3 | Pluralisation of the blocked-delete message | Req 5 gives both the singular and the plural sentence. |
| L4 | Edge states are undefined | A missing carrier shows "Unknown carrier", counts as inactive and shows the "Inactive" badge (reqs 6, 10, 16, 18). A method with an inactive carrier shows a single "Inactive" on the product tab; the config page shows the method toggle plus "Carrier inactive", and viewers see "Inactive" or "Inactive (carrier)" (req 23). Partial limits use `—` per dimension, and missing limits count as +∞ before sorting (req 16, Fit check). The viewer empty state shows no read-only notice (req 15). |
| L5 | Inline-edit interaction details | One edit row across both tables; another Edit discards the draft; save closes the edit; toggles keep the draft (req 4). "Saved!" replaces the Save label for 2 s (req 21). No delete confirmation, as in warehouse (reqs 5, 11). |
| L6 | Manifest top-level fields are unspecified | Req 25: name "Shipping", version "1.0.0", description "Carriers, delivery methods and per-product shipping restrictions". |
| L7 | Terse error messages | Req 24: a contextual prefix plus the trimmed `err.message` as is (e.g. "Failed to save: 403"), with the fallback "Unknown error". |
| L8 | Plugin test wiring and test count | Explicit `vitest` imports with no globals (`tsc -b` checks the test file). The count is fixed at 16 tests in two groups of 8. |
