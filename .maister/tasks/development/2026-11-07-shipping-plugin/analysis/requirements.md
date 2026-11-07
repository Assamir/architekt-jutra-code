# Requirements — Shipping Plugin

## TL;DR
New `plugins/shipping` iframe plugin (Vite + React, port 3010). Editors manage carriers and delivery methods on a sidebar page. On each product's Shipping tab they untick methods that don't suit that product, warned when box-size dimensions exceed a method's limits. The only host change is the `truck` ICON_MAP entry; the docs are updated to reserve ports 3010 and up. The 8 approved mockups are binding.

## Key Decisions
- Opt-out storage in pluginData `{disabledMethodIds}` — every active method is available unless the editor unticks it.
- Inline edit with the same object id — per-product opt-outs survive renames and limit changes.
- Warn-only size check with rotation (sorted-dimension comparison) — the editor stays in control.
- Admin = PERMISSION_EDIT, UI gating via JWT `permissions` with a base64url-safe decode — the backend already enforces this.
- Reuse by copying patterns (warehouse scaffold, box-size save flow, ai-description canEdit fixed per host AuthContext.decodeJwtPayload); import shared `plugins/sdk.ts`.

## Open Questions / Risks
- Manifest registration is manual (admin token); box-size must be registered as `box-size` for warnings.
- Follow-up (out of scope): restriction visibility on product list / detail info; consumers such as checkout or MCP.

## Initial description
Polish request: "Plugin systemu wysyłkowego" — (1) global configuration of carriers and delivery methods (DHL, DPD, InPost, Poczta, …) across the system; (2) per-product method availability: if a product doesn't fit a method's standard (e.g. too large), the administrator must be able to disable that method for it; (3) plugin served on port 3010, separate from future modules.

## Q&A (all rounds)
| # | Question | Answer |
|---|---|---|
| 1 | Restriction model | Opt-out list (all active methods available by default) |
| 2 | Size limits | Optional max L×W×H per method; product tab warns using box-size dims, never auto-disables |
| 3 | Administrator | PERMISSION_EDIT; UI hides write controls; no backend change |
| 4 | Port docs | 3010 + update standards/docs to reserve 3010+ for new business modules |
| 5 | Truck icon | Add `truck: Truck` to host ICON_MAP + Vitest case |
| 6 | Plugin tests | Vitest for domain.ts helpers |
| 7 | Seed data | None |
| 8 | Editing | Inline edit for methods (name, carrier, limits) and carriers (name), same id |
| 9 | Empty state | Hint "No delivery methods configured. Add them in the Shipping section." |
| 10 | Restriction visibility outside product | Out of scope |
| 11 | Inactive methods in product tab | Visible with "Inactive" badge, disabled checkbox, opt-out preserved |
| 12 | User journey | Editor configures on sidebar page, restricts per product on tab; viewer read-only; no other consumers |
| 13 | Reuse | Copy patterns; import shared sdk.ts |
| 14 | Visual assets | 8 approved mockups only |

## Personas & journey
- **Editor** (READ+EDIT): sidebar "Shipping" (truck icon) → add carriers → add methods with optional limits → toggle active / edit inline / delete (blocked for carriers with methods). Product detail → "Shipping" tab → sees box size + size-check badges → unticks methods → Save ("Saved!").
- **Viewer** (READ): same screens read-only; no add/edit/delete/toggle; disabled checkboxes; no Save.

## Similar features / reuse
- `plugins/warehouse` — scaffold (index.html, package.json, tsconfig, vite.config, vite-env.d.ts, main.tsx router), WarehousePage objects CRUD.
- `plugins/box-size/src/pages/ProductBoxTab.tsx` — getData/setData/removeData save flow; `plugins/box-size/src/domain.ts` `toBoxDimensions` (copy locally).
- `plugins/ai-description/src/pages/product-tab.tsx` — canEdit via `hostApp.getToken()`; fix base64url decoding as in `src/main/frontend/src/auth/AuthContext.tsx` `decodeJwtPayload`.
- `plugins/sdk.ts` — import `getSDK`, `PluginObject` (pages: `../../../sdk`).
- Host `src/main/frontend/src/components/shared/Icons.tsx` — ICON_MAP.

## Visual assets
`analysis/design-context/INDEX.md` — 8 screens (screen:shipping-config, -edit, -readonly; screen:product-shipping-tab, -warning, -nodims, -readonly, -empty) + components size-check-badge, inline-edit-row. Binding.

## Functional requirements
1. Carriers: list, add (name required, trimmed, non-empty), inline rename, toggle active, delete (blocked with tc-error if any method references it).
2. Methods: list (carrier, name, limits or "No limit", status), add (carrier required, name required, limits optional positive numbers), inline edit (carrier, name, limits, active), delete.
3. Method status shows "Carrier inactive" when its carrier is inactive.
4. Product tab: show box dims from `pluginData['box-size']` or "No box dimensions — size check unavailable"; one row per method; checkbox "Available" (checked unless id in disabledMethodIds); badges Fits / Too large / No limits / No dimensions / Inactive; inactive rows disabled with opt-out preserved; warning hint when any active, enabled method is too large; counter "N of M methods disabled"; Save → setData `{disabledMethodIds}` (pruned of non-existent ids, inactive kept) or removeData when empty; "Saved!" flash; empty-state hint when no methods exist.
5. Permission gating: editor controls only when JWT `permissions` contains "EDIT".
6. Errors from every SDK call shown via tc-error (no swallowed errors), loading states.
7. Port 3010 strictPort; manifest url `http://localhost:3010`; menu.main (Shipping, truck, `/`, 110), product.detail.tabs (Shipping, `/product-shipping`, 80).
8. Host: `truck: Truck` in ICON_MAP + Vitest test (truck resolves to Truck, warehouse unchanged, unknown falls back to Plug).
9. Plugin Vitest tests for domain helpers (mappers, fit check incl. rotation/missing limits/missing dims, canEdit incl. base64url, prune of disabled ids).
10. Docs: plugin-development.md (ports list + code sample), INDEX.md, plugins/CLAUDE.md (Reference Plugins entry), root CLAUDE.md, README.md.

## Scope boundaries
- In: plugin app, ICON_MAP line + test, docs.
- Out: backend changes, weight limits, seed carriers, product list filter / info badge, MCP tools, checkout consumers, plugin-ui.css changes.

## Technical considerations
- `productId` arrives as a number → `String()`.
- Manifest url origin exact; avoid `/detail`, `/edit` paths.
- Delete of missing object → 404; delete only listed ids.
- Errors arrive as "<status> <statusText>"; 401 redirects host.
- UUID object ids; object types `carrier`, `shipping-method`.
- No checkbox/muted classes in plugin-ui.css → native checkbox in label.tc-flex; muted via `<p>` / `tc-badge`.
