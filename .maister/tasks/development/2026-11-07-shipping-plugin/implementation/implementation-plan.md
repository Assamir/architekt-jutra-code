# Implementation Plan: Shipping Plugin (`plugins/shipping`, port 3010)

## TL;DR
7 task groups, 44 steps, 19 automated tests (16 plugin domain + 3 host icon).
Plugin chain: Group 1 (scaffold + size-check domain) → Group 2 (mappers + canEdit) → Group 3 (ShippingConfigPage) and Group 4 (ProductShippingTab). Groups 3 and 4 share `src/main.tsx`, so they run one after the other.
Group 5 (host `truck` icon) and Group 6 (docs) depend on nothing. Group 7 (test review + lint gate) runs last.
Complexity: medium. There are no backend changes and about 20 files, mostly new.

## Key Decisions
- Scaffold is merged with the size-check half of `domain.ts` (Group 1). This gives every plugin group real test-first content, and the spec's fixed 16 tests split cleanly into two groups of 8.
- `src/main.tsx` starts with an empty `<Routes>` in Group 1, and each page group adds its own `<Route>`. No stub pages are needed, and the shared file makes the executor serialize Groups 3 and 4.
- UI groups (3, 4) write no component tests. The spec rules them out (consistent with sibling Vite plugins), so their first step defines a walkthrough checklist, and verification is `npm run build` (`tsc -b`) plus the browser walkthrough.
- The Test Review group (7) adds no tests. The spec fixes the counts (16 plugin, 3 host), so Group 7 reviews coverage, runs every feature test, the build and the lint gate, and reports gaps instead of writing tests.
- Mockup helper classes (`muted`, `boxline`, `hint`, `editrow`, `row-off`, …) map to plain elements, `tc-*` classes and inline layout styles. `plugin-ui.css` does not change.

## Open Questions / Risks
- Vitest/Vite peer range: the host already pairs `vitest ^3.2.4` with Vite 8. If `npm install` in `plugins/shipping` fails with ERESOLVE, align the version with the host's lockfile, not with `--force`.
- Reload on focus probably will not fire on host tab switches (iframes stay mounted). This is accepted by the spec; it must be checked in the Group 4 walkthrough.
- Root `CLAUDE.md` has uncommitted user edits. Group 6 must append to line 39 only and must not reformat the file.
- Manual manifest registration (`PUT /api/plugins/shipping/manifest` with an admin token) is required before the browser walkthrough of Groups 3 and 4.
- The lint baseline (16 problems, 14 errors) must be measured before Group 5 starts, so that a later count can be compared with it.

## Overview
Total Steps: 44
Task Groups: 7
Expected Tests: 19 (plugin `domain.test.ts`: 8 + 8; host `icons.test.ts`: 3). Group 7 adds none, because the spec fixes the counts.

## Implementation Steps

### Task Group 1: Plugin Scaffold & Size-Check Domain
**Dependencies:** None
**Files to Modify:** plugins/shipping/package.json, plugins/shipping/package-lock.json, plugins/shipping/index.html, plugins/shipping/manifest.json, plugins/shipping/tsconfig.json, plugins/shipping/vite.config.ts, plugins/shipping/src/vite-env.d.ts, plugins/shipping/src/main.tsx, plugins/shipping/src/domain.ts, plugins/shipping/src/domain.test.ts
**Estimated Steps:** 6

- [x] 1.0 Complete the plugin scaffold and the size-check half of the domain
  - [x] 1.1 Create the npm project skeleton by copying from `plugins/warehouse`
    - `package.json`: name `shipping-plugin`, private, `type: module`. Scripts: `dev` (`vite`), `build` (`tsc -b && vite build`), `preview`, `test` (`vitest run`). Warehouse deps plus devDependency `vitest` (`^3.2.4`, same as the host).
    - `index.html` loads `/assets/plugin-sdk.js` and `plugin-ui.css` from the host, the same way as warehouse.
    - `tsconfig.json` is the warehouse copy (`include: ["src"]`, strict, `verbatimModuleSyntax`, `erasableSyntaxOnly`). `src/vite-env.d.ts` is copied too.
    - `vite.config.ts`: `server: { port: 3010, strictPort: true }`, with no Vitest `globals`.
    - `src/main.tsx`: StrictMode + BrowserRouter + an empty `<Routes>` (Groups 3 and 4 add the routes).
  - [x] 1.2 Write `manifest.json` (req 25)
    - `name` "Shipping", `version` "1.0.0", `description` "Carriers, delivery methods and per-product shipping restrictions", `url` `http://localhost:3010`
    - `menu.main`: label "Shipping", icon `truck`, path `/`, priority 110
    - `product.detail.tabs`: label "Shipping", path `/product-shipping`, priority 80
  - [x] 1.3 Run `npm install` in `plugins/shipping` and confirm the lockfile is created
  - [x] 1.4 Write the 8 size-check tests in `src/domain.test.ts` (test first, with explicit `import { describe, it, expect } from "vitest"`)
    - `sizeCheck`: fits; too large; fits only when rotated; partial limits (a missing limit is unlimited, e.g. box 120×12×48 against L 130 only → fits); no limits → `no-limits`; null box → `no-dimensions`
    - Spec example: box 120×12×48 against limits 64×38×41 → `too-large`
    - `toBoxDimensions`: complete `pluginData["box-size"]` → dimensions; missing or partial / non-numeric → `null`
  - [x] 1.5 Implement the types and size-check helpers in `src/domain.ts`
    - Types `BoxDimensions {length,width,height}` and `ShippingMethod` (enough for the limits: `maxLengthCm?`, `maxWidthCm?`, `maxHeightCm?`), plus `SizeCheckResult = "no-dimensions" | "no-limits" | "fits" | "too-large"` (a string union, no enum)
    - `toBoxDimensions(product)`: reads `product.pluginData?.["box-size"]` from `unknown`. It narrows without `as unknown as` casts and returns `null` unless all three values are finite and > 0. The shape is redeclared locally, never imported from box-size.
    - `sizeCheck(box, method)`: precedence `no-dimensions` (box null) → `no-limits` (all three limits missing) → sorted comparison. Box dimensions are sorted descending. The limits are sorted descending with each missing limit as `Infinity`. The box fits when every box value is ≤ the limit at the same position.
  - [x] 1.6 Ensure the size-check tests pass
    - Run ONLY `npm test` in `plugins/shipping` (8 tests)

**Acceptance Criteria:**
- The 8 size-check tests pass
- `manifest.json` matches req 25 exactly; `vite.config.ts` has port 3010 with `strictPort: true`
- `domain.ts` has no enums and no `as unknown as` casts, and imports `../../sdk` for SDK types only

### Task Group 2: Domain Mappers & Edit Permission
**Dependencies:** 1
**Files to Modify:** plugins/shipping/src/domain.ts, plugins/shipping/src/domain.test.ts
**Estimated Steps:** 5

- [x] 2.0 Complete the mappers, pruning and `canEdit` in `domain.ts`
  - [x] 2.1 Write the 8 mapper and permission tests (append to `src/domain.test.ts`)
    - `toCarrier` / `toShippingMethod` default `active` to true unless it is exactly `false`
    - `toShippingMethod` drops invalid limits (0, negative, NaN, strings) and keeps finite positive ones
    - Id from `objectId`: `{id: "42", objectId: "uuid-…"}` maps to `id === "uuid-…"` (guards M1)
    - `pruneDisabledIds` drops unknown ids and keeps ids of inactive methods
    - `canEdit`: an EDIT token → true; a READ-only token → false; a payload whose base64url contains `-` / `_` decodes correctly; a malformed or `null` token → false
  - [x] 2.2 Implement the `Carrier` / `ShippingMethod` types and mappers
    - `Carrier {id, name, active}`, `ShippingMethod {id, carrierId, name, active, maxLengthCm?, maxWidthCm?, maxHeightCm?}`
    - `toCarrier(obj: PluginObject)` and `toShippingMethod(obj: PluginObject)` set `id` from `obj.objectId`, never `obj.id` (the numeric DB key). They narrow `obj.data` from unknown and omit invalid limits.
    - Reuse: the `toX` mapper pattern from `plugins/warehouse/src/domain.ts`
  - [x] 2.3 Implement `toDisabledMethodIds(data)`, `pruneDisabledIds(ids, methods)` and `canEdit(token)`
    - `toDisabledMethodIds`: the string entries of `data?.disabledMethodIds`, else `[]`
    - `pruneDisabledIds`: keeps the ids present in `methods` (active or not)
    - `canEdit`: split the JWT, base64url → base64 (`-`→`+`, `_`→`/`, pad with `=`), `atob`, `JSON.parse`, then `Array.isArray(permissions) && permissions.includes("EDIT")`. Any failure returns false. The decoding is copied from `src/main/frontend/src/auth/AuthContext.tsx` `decodeJwtPayload` and the flow from `plugins/ai-description/src/pages/product-tab.tsx`.
  - [x] 2.4 Check that every exported helper will have a caller in Group 3 or 4 (minimal-implementation standard)
  - [x] 2.5 Ensure the domain tests pass
    - Run ONLY `npm test` in `plugins/shipping` (16 tests total in `domain.test.ts`)

**Acceptance Criteria:**
- All 16 domain tests pass
- No mapper reads `PluginObject.id`
- `canEdit` never throws

### Task Group 3: Shipping Configuration Page (menu.main)
**Dependencies:** 2
**Files to Modify:** plugins/shipping/src/pages/ShippingConfigPage.tsx, plugins/shipping/src/main.tsx
**Visual References:**
- mockup: analysis/design-context/mockups/shipping-configuration-editor.html
  element: screen:shipping-config
  locator: plugin frame, lines 418–429 (`<div class="tc-plugin">` through the methods `tc-table`)
  acceptance: root `tc-plugin` with padding 1rem and max-width 900px; `h1` "Shipping"; two `tc-section`s in the order Carriers, then Delivery methods, each with a `tc-flex` add form above a `tc-table`. Carrier columns are Name / Methods / Status / actions. Method columns are Carrier / Method / Max size (L×W×H) / Status / actions. The Status cell holds an "Active" checkbox, plus a `tc-badge` "Carrier inactive" when the carrier is inactive or missing. Actions are Edit (`tc-ghost-button`) and Delete (`tc-ghost-button--danger`). The add carrier form is an input with placeholder "Carrier name (e.g. GLS)" and the button "Add carrier". The add method form, in order, is a carrier select ("-- Carrier --"), a name input, the label "Max (cm, optional):", L/W/H number inputs and "Add method".
- mockup: analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html
  element: screen:shipping-config-edit
  locator: plugin frame, lines 418–431 (the carrier edit row in the Carriers table, the `tc-error` under the Carriers add form, and the method edit row in the methods table)
  acceptance: a blocked carrier delete shows `tc-error` under the Carriers add form with the exact singular or plural sentence from req 5; the rest of the page stays interactive
- mockup: analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html
  element: component:inline-edit-row
  locator: `<tr class="editrow">` rows at lines 421 and 431
  acceptance: the carrier edit row has a `tc-input` name (prefilled), an Active checkbox, `tc-primary-button` "Save" and `tc-ghost-button` "Cancel". In the method edit row the cells switch, in column order, to a `tc-select` carrier, a `tc-input` name, L/W/H `tc-input`s inside a `tc-flex`, an Active checkbox, and Save / Cancel. No `editrow` background class is reproduced.
- mockup: analysis/design-context/mockups/shipping-configuration-read-only-viewer.html
  element: screen:shipping-config-readonly
  locator: plugin frame, lines 418–423
  acceptance: `<p>` "You have read-only access. Ask an editor to change carriers or delivery methods." directly under the `h1`; no add forms, Edit, Delete or checkboxes. Status shows `tc-badge tc-badge--success` "Active", a plain `tc-badge` "Inactive", or "Inactive (carrier)", following the rules of req 23.
**Estimated Steps:** 9

- [x] 3.0 Complete ShippingConfigPage
  - [x] 3.1 Define the walkthrough checklist (no component tests, per the spec)
    - List the states to check in the browser: editor list, add carrier and add method with each validation message, carrier and method inline edit (objectId unchanged, no duplicate object), only one edit row across both tables, a toggle during an open edit, carrier delete blocked (singular and plural), method delete, carrier-inactive badge, unknown carrier, viewer variant
  - [x] 3.2 Add the `/` route in `src/main.tsx` and create `ShippingConfigPage` (a named `export function`, no default export)
    - Load in parallel: `getToken` → `canEdit`, `objects.list("carrier")` → `toCarrier`, `objects.list("shipping-method")` → `toShippingMethod`
    - Show "Loading..." while loading and `tc-error` "Failed to load shipping data: <msg>" on failure
    - Sorting rule: carriers by name; methods by carrier display name ("Unknown carrier" when the carrier is missing), then by method name. All comparisons are case-insensitive.
    - Reuse: the load/list pattern from `plugins/warehouse/src/pages/WarehousePage.tsx`
  - [x] 3.3 Carriers section (reqs 1–3)
    - Methods count = the number of methods whose `carrierId` matches
    - Add: trim the name; when it is empty show "Carrier name is required."; otherwise `objects.save("carrier", crypto.randomUUID(), {name, active: true})`, then reload both lists
    - The Active toggle saves the full object immediately with the same objectId
  - [x] 3.4 Delivery methods section (reqs 6, 7, 9, 10)
    - Limits cell: `L × W × H cm` with `—` per missing limit, or "No limit" when all three are missing
    - The add form select lists only active carriers. Validation runs in order: "Select a carrier.", "Method name is required.", "Limits must be positive numbers." Empty limits are omitted from the saved object.
    - Status: the Active checkbox, plus `tc-badge` "Carrier inactive" whenever the carrier is inactive or missing
  - [x] 3.5 Inline edit for both tables (reqs 4, 8; component:inline-edit-row)
    - A single `editing` state `{kind: "carrier" | "shipping-method", id, draft}` allows at most one open row across both tables. Edit on another row replaces the draft; Cancel clears it.
    - Save validates like the add forms, writes the full object with `objects.save(type, sameObjectId, data)` (cleared limits drop out), reloads both lists and closes the edit
    - The method edit select lists active carriers plus the method's current carrier. When the carrier is missing, it starts at "-- Carrier --".
    - After any reload, close the edit if the edited id no longer exists; otherwise keep the draft
  - [x] 3.6 Delete (reqs 5, 11)
    - Carrier: re-list the methods first. If N > 0, show the singular or plural `Cannot delete "<name>": …` sentence in `tc-error` under the Carriers add form and do not delete. Otherwise delete and reload. No confirmation.
    - Method: `objects.delete("shipping-method", objectId)` for listed ids only, then reload
  - [x] 3.7 Permissions and errors (reqs 22–24)
    - Render the write controls only when `canEdit` is true. The viewer variant follows req 23, including the read-only notice and the three status labels.
    - Wrap every SDK call in try/catch and show `tc-error` near the section as `<context>: <err.message.trim() || "Unknown error">`. Disable buttons while a mutation runs. Wrap async handlers in `void`.
  - [x] 3.8 Check the page against all four visual references (layout, classes, copy, field order) and fix any differences
  - [x] 3.9 Ensure the plugin still builds and its tests pass
    - Run `npm run build` (`tsc -b` + vite) and `npm test` (16 domain tests) in `plugins/shipping`

**Acceptance Criteria:**
- `npm run build` passes; the 16 domain tests still pass
- Inline edit and toggles keep the objectId (no duplicate objects); at most one edit row is open at a time
- Implementation matches each `acceptance` criterion declared in Visual References

### Task Group 4: Product Shipping Tab (product.detail.tabs)
**Dependencies:** 2
**Files to Modify:** plugins/shipping/src/pages/ProductShippingTab.tsx, plugins/shipping/src/main.tsx
**Visual References:**
- mockup: analysis/design-context/mockups/product-shipping-tab-editor.html
  element: screen:product-shipping-tab
  locator: plugin frame, line 420 (`<div class="tc-plugin" style="padding:1.5rem">` through the footer)
  acceptance: root `tc-plugin` with padding 1.5rem; `h3` "Delivery methods for this product" with margin `0 0 1rem`; box line `<strong>Box:</strong> L × W × H cm (from Box Size)`; a `tc-table` with columns Available / Carrier / Method / Max (cm) / Size check; native checkboxes with aria-label `Available: <name>`; a footer `tc-flex` (margin-top 1rem) with the `tc-primary-button` "Save" and the counter "N of M methods disabled for this product"
- mockup: analysis/design-context/mockups/product-shipping-tab-editor.html
  element: component:size-check-badge
  locator: the Size check column of the table on line 420
  acceptance: `tc-badge tc-badge--success` "Fits"; `tc-badge tc-badge--danger` "Too large"; `tc-badge` "Inactive" (with the checkbox disabled); plain text "No limits" and "No dimensions"; precedence Inactive > No dimensions > No limits > Fits / Too large
- mockup: analysis/design-context/mockups/product-shipping-tab-too-large-method-still-enabled.html
  element: screen:product-shipping-tab-warning
  locator: the `tc-error` hint paragraph between the box line and the table, line 420
  acceptance: `tc-error` placed above the table that names every active, ticked, too-large method in table order, using the exact singular or plural sentence from req 19 (the rule decides which names appear, not the mockup's sample text)
- mockup: analysis/design-context/mockups/product-shipping-tab-no-box-dimensions.html
  element: screen:product-shipping-tab-nodims
  locator: the box line and the Size check column, line 420
  acceptance: the line "No box dimensions — size check unavailable. Set them in the Box Size tab."; every active row shows "No dimensions"; no hint
- mockup: analysis/design-context/mockups/product-shipping-tab-read-only-viewer.html
  element: screen:product-shipping-tab-readonly
  locator: the read-only notice and the table, line 420
  acceptance: "Read-only: you can see which methods are available but not change them."; all checkboxes disabled; the hint is still shown; no Save and no counter
- mockup: analysis/design-context/mockups/product-shipping-tab-empty-state.html
  element: screen:product-shipping-tab-empty
  locator: the box line and the empty message, line 420
  acceptance: the box line plus "No delivery methods configured. Add them in the Shipping section."; no table, no Save, and no read-only notice for viewers
**Estimated Steps:** 9

- [x] 4.0 Complete ProductShippingTab
  - [x] 4.1 Define the walkthrough checklist (no component tests, per the spec)
    - States: editor (Fits / Too large / No limits / Inactive), warning hint singular and plural (with box 120×12×48 against InPost Kurier and Paczkomat, Kurier sorts first), no dimensions, viewer, empty, no product context, Save → "Saved!" for 2 s, re-tick all → data removed, deactivate and reactivate a disabled method, reload on focus keeping unsaved ticks
  - [x] 4.2 Add the `/product-shipping` route in `src/main.tsx` and create `ProductShippingTab` (a named export)
    - `productId = String(sdk.thisPlugin.productId ?? "")`; when it is empty, show "No product context available." (req 12)
    - Initial load with `Promise.all`: `getToken` → `canEdit`, the carrier and method lists, `getData(productId)` → `toDisabledMethodIds`, and `hostApp.getProduct(productId)` → `toBoxDimensions`. Show "Loading..." while loading and `tc-error` on failure. The loaded ids initialise the selection.
    - Reuse: the save flow from `plugins/box-size/src/pages/ProductBoxTab.tsx`, without swallowing load errors
  - [x] 4.3 Box line, empty state and table (reqs 14–17)
    - Box line text per req 14. When there are no methods, show only the box line and the empty message (req 15).
    - Rows follow the sorting rule. Max (cm) shows `L × W × H` with `—` per missing limit, or a single `—` when the method has no limits.
    - A row is checked unless its id is in the selection; toggling only changes local state
  - [x] 4.4 Size check badge and hint (reqs 18, 19)
    - Effective active = `method.active && carrier?.active === true`. An inactive row shows a single "Inactive" badge with its checkbox disabled, and its stored state is preserved.
    - Otherwise the row shows the `sizeCheck(box, method)` result as the badge or text
    - The hint is derived from the selection plus the current data and is shown to viewers too
  - [x] 4.5 Footer and Save (reqs 20, 21)
    - Counter: N = selected ids that exist in the method list (inactive ones included), M = method count
    - Save: `pruneDisabledIds`. A non-empty list goes to `setData(productId, {disabledMethodIds})`, an empty one to `removeData(productId)`. The button reads "Saved!" for 2 s, the selection resets to the pruned list, and errors show `tc-error` "Failed to save: …". The button is disabled while saving.
  - [x] 4.6 Reload on focus (req 28)
    - A `useEffect` registers a window `focus` listener and a `document` `visibilitychange` listener (refresh only when `visibilityState === "visible"`), and removes both on unmount
    - Refresh re-fetches the product, carriers and methods in parallel, without "Loading..." and without touching the selection. It is skipped while a refresh or a Save is in flight (a ref guard). On failure it shows "Failed to refresh shipping data: …" and keeps the old data.
  - [x] 4.7 Permissions and errors (reqs 22–24)
    - The viewer variant shows the read-only notice, disabled checkboxes, and no Save or counter. The empty state has no notice.
    - Every SDK call sits in try/catch with a `<context>: <trimmed message || "Unknown error">` message. Async handlers are wrapped in `void`.
  - [x] 4.8 Check the tab against all six visual references and fix any differences
  - [x] 4.9 Ensure the plugin still builds and its tests pass
    - Run `npm run build` and `npm test` in `plugins/shipping`

**Acceptance Criteria:**
- `npm run build` passes; the 16 domain tests still pass
- The selection survives a refresh and is reset only on the initial load and after Save
- Implementation matches each `acceptance` criterion declared in Visual References

### Task Group 5: Host Icon Map (`truck`)
**Dependencies:** None
**Files to Modify:** src/main/frontend/src/components/shared/Icons.tsx, src/main/frontend/src/test/icons.test.ts
**Estimated Steps:** 5

- [x] 5.0 Complete the host icon change
  - [x] 5.1 Record the lint baseline before any edit: `npx eslint .` in `src/main/frontend`, expecting 16 problems (14 errors, 2 warnings)
  - [x] 5.2 Write 3 tests in `src/test/icons.test.ts`, inside a `describe("resolveIcon")` block with explicit `import { describe, it, expect } from "vitest"`
    - `resolveIcon("truck")` returns `Truck`
    - `resolveIcon("warehouse")` returns `Warehouse`
    - an unknown name falls back to `Plug`
  - [x] 5.3 Add `Truck` to the `lucide-react` named import and `truck: Truck` to `ICON_MAP` in `Icons.tsx`. Nothing else changes; the pre-existing `Icons.tsx:26:17` error stays as it is.
  - [x] 5.4 Ensure the icon tests pass: `npx vitest run src/test/icons.test.ts` (3 tests only)
  - [x] 5.5 Lint check: `npx eslint src/components/shared/Icons.tsx src/test/icons.test.ts` reports only the pre-existing `Icons.tsx:26:17`, and `npx eslint .` still reports 16 problems or fewer

**Acceptance Criteria:**
- The 3 icon tests pass
- No new lint problems

### Task Group 6: Documentation (port rule + shipping plugin)
**Dependencies:** None
**Files to Modify:** .maister/docs/standards/plugins/plugin-development.md, .maister/docs/INDEX.md, .maister/docs/project/tech-stack.md, plugins/CLAUDE.md, CLAUDE.md, README.md
**Estimated Steps:** 5

- [x] 6.0 Complete the doc updates (req 27). Use the same port-rule wording everywhere: existing plugins 3001–3003, 3004–3009 for small demo and utility plugins, 3010+ for business modules (shipping 3010, next business module 3011)
  - [x] 6.1 Write the verification checks first: list `grep` checks that `3010` and the port rule appear in all six files and that `port: 3004` no longer appears in `plugin-development.md`
  - [x] 6.2 Update `plugin-development.md` (Fixed Dev Ports prose ~l.56: add shipping 3010 and the rule; code sample ~l.59: `port: 3004` → `port: 3011`) and `.maister/docs/INDEX.md` (the Plugin Development port list ~l.109)
  - [x] 6.3 Update `tech-stack.md` (add `shipping` (Vite + React, :3010) to the plugin list ~l.44–46) and `README.md` (`shipping/` in the plugin tree ~l.18; `(cd plugins/shipping && npm install)` in Quick Start ~l.71)
  - [x] 6.4 Add a "Shipping Plugin" entry to `plugins/CLAUDE.md` under Reference Plugins. Cover: the port and the port rule; storage (objects `carrier` / `shipping-method` keyed by objectId, pluginData `disabledMethodIds`); the box-size read via `hostApp.getProduct` with reload on focus; `canEdit`; the manifest registration command. Append `shipping` (:3010, Vite) to the Plugins line (~l.39) of the root `CLAUDE.md` only, leaving the user's uncommitted edits intact.
  - [x] 6.5 Run the 6.1 checks and confirm `git diff CLAUDE.md` shows only the one-line addition on top of the user's edits

**Acceptance Criteria:**
- All six docs mention shipping on 3010 and state the same port rule
- Root `CLAUDE.md` has a single-line change; no week7–week10/ or tools/ files are touched

### Task Group 7: Test Review & Gap Analysis
**Dependencies:** 1, 2, 3, 4, 5, 6
**Files to Modify:** None
**Estimated Steps:** 5

- [x] 7.0 Review and fill critical gaps
  - [x] 7.1 Review the 19 existing tests (16 in `plugins/shipping/src/domain.test.ts`, 3 in `src/main/frontend/src/test/icons.test.ts`) against reqs 18, 21, 22 and the M1 objectId guard
  - [x] 7.2 Analyze gaps for THIS feature only. The spec fixes the test counts (L8), so write no additional tests; record any uncovered critical behavior for the verification phase
  - [x] 7.3 Run the feature tests: `npm test` in `plugins/shipping` (16) and `npx vitest run src/test/icons.test.ts` in `src/main/frontend` (3)
  - [x] 7.4 Run `npm run build` in `plugins/shipping` and `npx eslint .` in `src/main/frontend` (16 problems or fewer)
  - [x] 7.5 Confirm the `strictPort` behavior: a second `npm run dev` on 3010 fails instead of moving to another port

**Acceptance Criteria:**
- All 19 feature tests pass
- No more than 10 additional tests are added (planned: 0)
- The plugin build passes and the lint count is no higher than the baseline

## Execution Order

1. Task Group 1: Plugin Scaffold & Size-Check Domain (6 steps)
2. Task Group 2: Domain Mappers & Edit Permission (5 steps, depends on 1)
3. Task Group 3: Shipping Configuration Page (9 steps, depends on 2; shares `src/main.tsx` with Group 4)
4. Task Group 4: Product Shipping Tab (9 steps, depends on 2; shares `src/main.tsx` with Group 3)
5. Task Group 5: Host Icon Map (5 steps, no dependencies)
6. Task Group 6: Documentation (5 steps, no dependencies)
7. Task Group 7: Test Review & Gap Analysis (5 steps, depends on 1–6)

## Standards Compliance

Follow standards from `.maister/docs/standards/`:
- global/: `minimal-implementation.md` (every domain helper has a caller, no stubs), `commenting.md`, `error-handling.md` (specific messages, validation before writes), `conventions.md` (unique fixed ports, course directories untouched)
- plugins/: `plugin-development.md` (structure, the shared `sdk.ts` import, mandatory `tc-*`, manifest, `strictPort`, try/catch for every SDK call)
- backend/: `plugin-auth.md` (`hostApp.getToken()`, permissions from the JWT claim)
- frontend/: `components.md` (named exports, `import type`), `css.md` (no inline hex), `tooling.md` (strict TS, no enums, `tsc -b` gate), `accessibility.md` (aria-labels, real labels and buttons)
- testing/: `frontend-testing.md` (host test in `src/test/`, describe named after the feature, explicit vitest imports)

## Notes

- Test-driven: each group with logic starts with its tests (domain 1.4 and 2.1, host 5.2); the UI groups start with a walkthrough checklist
- Run incrementally: run only the plugin's `npm test` or the single host test file, never the full host suite
- Mark progress: check off steps as they are completed
- Reuse first: copy patterns from warehouse, box-size, ai-description and AuthContext; only `plugins/sdk.ts` is imported across plugins
- No commits; Maven is not needed
