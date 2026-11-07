# E2E Verification Report

## TL;DR
Verdict: **GO WITH CAVEATS**. 23 of 23 scenarios passed: 19 cleanly and 4 with issues. There were 0 failures and 0 console errors.
All config-page rules (validation copy, inline edit with an unchanged objectId, one edit row at a time, blocked-delete sentences, busy-disabled Cancel) and all product-tab rules (badges, plural and singular hint, Save/"Saved!", opt-out persistence, removeData on an empty list, inactive opt-out preservation) work against the live stack.
Main caveat: the sidebar "Shipping" entry with the Truck icon shows **only for users with PLUGIN_MANAGEMENT**. Editors and viewers have no sidebar entry, because the host `Sidebar.tsx` gates plugin menu items. This is pre-existing host behaviour, not part of this change.
Reload-on-focus works only after the first click inside the iframe; switching host tabs does not trigger it. This was predicted in the spec, and unsaved ticks survive.

## Key Decisions
- Roles were switched by logging in from inside the page (`fetch('/api/auth/login')` then `localStorage.auth_token`). Tokens never left the browser.
- box-size was **not** registered at the start (`GET /api/plugins` listed only `shipping`), so it was registered from `plugins/box-size/manifest.json` (PUT 200).
- Test data was created through the UI as editor: carriers, methods, the box and the opt-outs. Pocztex Kurier needed a short reactivation of Poczta Polska, because the add form lists only active carriers (req 7).
- Spec-sanctioned mockup differences are treated as accepted, not as defects: `row-off` greying, `muted` helper classes, row order, and the hint naming every ticked too-large method.

## Open Questions / Risks
- **Major (pre-existing host)**: editors, the intended users of the config page, cannot reach Shipping from the sidebar; it works only by direct URL `/plugins/shipping/`. Decide whether `Sidebar.tsx` should show plugin `menu.main` items to non-admins (follow-up outside this task).
- **Minor**: the box line stays stale after a Box Size change until the user clicks inside the Shipping iframe (spec risk confirmed).
- **Minor (pre-existing CSS)**: every `<p class="tc-error">` renders grey, because `.tc-plugin p` outranks `.tc-error`. The too-large hint and validation errors are therefore not red, in this and every other plugin.

## 1. Identifier
- **Task**: 2026-11-07-shipping-plugin
- **Task path**: C:\code\_repos\architekt-jutra\architekt-jutra-code\.maister\tasks\development\2026-11-07-shipping-plugin
- **Spec**: C:\code\_repos\architekt-jutra\architekt-jutra-code\.maister\tasks\development\2026-11-07-shipping-plugin\implementation\spec.md
- **Date**: 2026-11-07
- **Git ref**: 00e2c24 (master, with uncommitted shipping-plugin working-tree changes)
- **Tester**: e2e-test-verifier (maister)

## 2. Test Environment
| Field | Value |
|---|---|
| Base URL | http://localhost:5173 (host Vite dev server, `/api` proxied to :8080) |
| Browser | Playwright MCP Chromium (headed default) |
| Viewport | 1440×900 |
| Auth context | admin (sidebar check), editor (READ, EDIT), viewer (READ). Dev passwords admin123 / editor123 / viewer123 all valid |
| Test data | Live dev DB. 4 carriers and 6 methods created through the UI; product 1 "LG C4 65" OLED evo 4K Smart TV" with box 120×12×48 (also 60×40×30 and none) |

Plugins: shipping dev server on :3010 (started for this run), box-size on :3002 (already running). Backend on :8080 (already running).

## 3. Executive Summary
**Verdict**: ⚠️ GO WITH CAVEATS

| Metric | Count |
|---|---|
| Scenarios planned | 23 |
| Scenarios executed | 23 |
| Passed | 23 |
| Failed | 0 |
| Blocked | 0 |
| Pass rate | 100% |
| Critical issues | 0 |
| Major issues | 1 |
| Minor issues | 3 |
| Cosmetic issues | 0 |

The shipping plugin behaves as specified across both checklists. On the config page: 12 scenarios from Group 3's 14-item walkthrough. On the product tab: 11 scenarios from Group 4's 12-item walkthrough. Every exact copy string in requirements 2, 5, 7, 14, 15, 19, 20 and 23 matched verbatim.

Persistence was verified independently over REST:
- Inline edits keep the same `objectId`, with no duplicate objects.
- `disabledMethodIds` hold method objectIds.
- An empty selection issues `DELETE /api/plugins/shipping/products/1/data`.

The single Major finding is a pre-existing host gate: plugin menu items are shown only with PLUGIN_MANAGEMENT. The Truck icon itself renders correctly. Three Minor findings are either documented risks or pre-existing CSS and browser behaviour, so none blocks merge.

## 4. Verification Scenarios

### 4.1 Sidebar shows "Shipping" with the Truck icon — ⚠️ Passed with issues
- **User story / acceptance criterion**: Success Criteria ("sidebar shows Shipping with the Truck icon"); req 25, 26
- **Preconditions**: shipping manifest registered (PUT 200); :5173 serving the working-tree `Icons.tsx`

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Log in as admin, open /products | Sidebar lists "Shipping" | Link "Shipping" → `/plugins/shipping/` | ✅ |
| 2 | Inspect the icon | `lucide-truck` (not plug) | `lucide lucide-truck` | ✅ |
| 3 | Log in as editor / viewer | Sidebar lists "Shipping" | Only Products, Carbon Footprint, Categories | ⚠️ |

- **Issues observed**: §5.2 #1
- **Evidence**: `screenshots/01-sidebar-shipping-truck-admin.png`, `screenshots/03-config-editor.png` (editor sidebar without Shipping)
- **Acceptance criteria checklist**:
  - [x] Truck icon, not Plug
  - [ ] Visible to every role that can use the page (admin only)

### 4.2 Add carrier and validation — ✅ Passed
- **User story / acceptance criterion**: req 2
- **Preconditions**: editor, no carriers

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Click "Add carrier" with an empty name | "Carrier name is required." | Exact text in `tc-error` | ✅ |
| 2 | Name "   " (spaces only) | Same error, nothing saved | Same error; 1 carrier in list (unchanged) | ✅ |
| 3 | Add DHL, "  DPD  ", InPost, Poczta Polska | Trimmed, active, UUID objectIds | Stored `DPD` trimmed; 4 UUID objectIds; input cleared | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/02-carrier-name-required.png`
- **Acceptance criteria checklist**:
  - [x] Placeholder "Carrier name (e.g. GLS)"
  - [x] Trim + required validation
  - [x] New carriers `active: true`, objectId = UUID

### 4.3 Carrier active toggle — ✅ Passed
- **User story / acceptance criterion**: req 3
- **Preconditions**: Poczta Polska active

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Untick "Active: Poczta Polska" | Saved immediately with the same objectId | REST shows `2006b873…` `{active:false}`; no new object | ✅ |
| 2 | Method add-form carrier select | Inactive carrier excluded | Options: -- Carrier --, DHL, DPD, InPost | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/03-config-editor.png`
- **Acceptance criteria checklist**:
  - [x] Immediate save, same objectId

### 4.4 Carrier inline edit keeps objectId — ✅ Passed
- **User story / acceptance criterion**: req 4; Success Criteria (objectId unchanged, no duplicate)
- **Preconditions**: carrier DHL `80a8fc2b-…`

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Edit DHL, clear the name, Save | "Carrier name is required." | Exact text | ✅ |
| 2 | Rename to "DHL Express", Save | Same objectId, edit closes, both lists reload | `80a8fc2b-…=DHL Express`; still 4 carriers; methods table shows "DHL Express" | ✅ |
| 3 | Rename back to "DHL" | Same objectId | Same objectId | ✅ |
| 4 | `GET /api/plugins/shipping/objects/carrier` | 4 objects, no duplicates | 4 objects (ids 1–4) | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/04-config-carrier-inline-edit.png`
- **Acceptance criteria checklist**:
  - [x] Same objectId on save
  - [x] Validation as in the add form
  - [x] Save reloads and closes the edit

### 4.5 One edit row at a time, Cancel, toggle during edit — ✅ Passed
- **User story / acceptance criterion**: req 4, req 8
- **Preconditions**: editor, data seeded

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Edit DPD carrier (draft "DPD draft"), then Edit "DPD Classic" method | Carrier draft discarded, only the method row in edit | Carrier inputs 0, method inputs 1; DPD row shows "DPD" | ✅ |
| 2 | Method edit: type H=999, Cancel | Draft discarded | Row unchanged; reopened H empty (stored value) | ✅ |
| 3 | Carrier edit open, toggle "Active: DHL Kurier" twice | Lists reload, draft kept | Draft "DPD draft xyz" survived both toggles | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/05-config-method-inline-edit.png`
- **Acceptance criteria checklist**:
  - [x] Only one row in edit mode across both tables
  - [x] Cancel discards the draft
  - [x] Immediate toggles keep the open draft

### 4.6 Carrier delete: blocked (singular/plural) and allowed — ✅ Passed
- **User story / acceptance criterion**: req 5
- **Preconditions**: DPD has 1 method, InPost has 2

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Delete DPD | `Cannot delete "DPD": it still has 1 delivery method. Delete or move it first.` | Exact | ✅ |
| 2 | Delete InPost | `Cannot delete "InPost": it still has 2 delivery methods. Delete or move them first.` | Exact; carrier count still 4 | ✅ |
| 3 | Add "GLS" (0 methods), Delete | Deleted without a prompt, list reloads | Carriers back to DHL, DPD, InPost, Poczta Polska; no dialog | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/06-config-edit-and-blocked-delete.png`
- **Acceptance criteria checklist**:
  - [x] Singular sentence
  - [x] Plural sentence
  - [x] No confirmation prompt

### 4.7 Add method and validation — ⚠️ Passed with issues
- **User story / acceptance criterion**: req 7
- **Preconditions**: 3 active carriers

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Add with no carrier | "Select a carrier." | Exact | ✅ |
| 2 | Carrier DHL, name "  " | "Method name is required." | Exact | ✅ |
| 3 | L = -5 | "Limits must be positive numbers." | Exact | ✅ |
| 4 | L = 0 | "Limits must be positive numbers." | Exact | ✅ |
| 5 | L = "1e999" | Error (not finite) | Chrome sanitised the value to "", so the method was saved with **no** length limit and no error | ⚠️ |
| 6 | Add the 6 methods (limits as per brief; DHL Kurier none) | Stored with carrier objectId; empty limits omitted | REST: DHL Kurier has no `max*` keys; others store all three; `carrierId` = carrier objectId | ✅ |

- **Issues observed**: §5.3 #1
- **Evidence**: `screenshots/03-config-editor.png`, `screenshots/03b-config-editor-frame.png`
- **Acceptance criteria checklist**:
  - [x] aria-labels "Max length cm" / "Max width cm" / "Max height cm"
  - [x] Label "Max (cm, optional):"
  - [x] All three validation messages
  - [ ] Every filled limit validated (a browser-sanitised invalid entry bypasses validation)

### 4.8 Method inline edit keeps objectId; cleared limits drop out — ✅ Passed
- **User story / acceptance criterion**: req 8
- **Preconditions**: DPD Classic `a9e3fc6b-…` 175×80×60

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Edit row | Carrier select, name, L×W×H inputs, Active, Save/Cancel | As expected (`tc-select`, `tc-input`, × separators) | ✅ |
| 2 | Clear H, Save | Same objectId, `maxHeightCm` removed | Same objectId; data has no `maxHeightCm`; row "175 × 80 × — cm" | ✅ |
| 3 | Restore H = 60 | Same objectId | "175 × 80 × 60 cm"; 6 methods total | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/05-config-method-inline-edit.png`
- **Acceptance criteria checklist**:
  - [x] Same objectId
  - [x] Full object written, cleared limits dropped

### 4.9 Method list: sorting, limits format, "Carrier inactive" — ✅ Passed
- **User story / acceptance criterion**: req 6, req 10, Sorting rule
- **Preconditions**: 6 methods, Poczta Polska inactive

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Read table order | Carrier, then method (case-insensitive) | DHL Kurier, DHL Paleta, DPD Classic, InPost Kurier, InPost Paczkomat, Pocztex Kurier | ✅ |
| 2 | Limits column | "No limit" / "L × W × H cm" | "No limit", "120 × 80 × 180 cm", … | ✅ |
| 3 | Pocztex Kurier status | Own Active checkbox + `tc-badge` "Carrier inactive" | Both present | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/03b-config-editor-frame.png`
- **Acceptance criteria checklist**:
  - [x] Sorting rule
  - [x] Limits formatting
  - [x] Carrier inactive badge

### 4.10 Method delete — ✅ Passed
- **User story / acceptance criterion**: req 11
- **Preconditions**: "Test" method exists (created by the 1e999 case)

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Delete "Test" | Removed without a prompt, list reloads | Method count 1 → 0, no dialog | ✅ |
| 2 | Delete all 6 methods (empty-state setup) | All removed | 0 methods, no `tc-error` | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/19-product-tab-empty-editor.png`
- **Acceptance criteria checklist**:
  - [x] Delete without confirmation

### 4.11 Controls disabled while busy (Cancel included) — ✅ Passed
- **User story / acceptance criterion**: req 24 ("Buttons are disabled while a mutation runs"); verification fix #5
- **Preconditions**: route delaying `PUT /api/plugins/shipping/objects/**` by 2.5 s

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Edit carrier, Save (slow PUT) | Save and Cancel disabled | Save disabled, **Cancel disabled** | ✅ |
| 2 | Other controls during the mutation | Disabled | "Add carrier" disabled, Active toggles disabled | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/07-config-busy-cancel-disabled.png`
- **Acceptance criteria checklist**:
  - [x] Cancel disabled while busy (observable)

### 4.12 Config page as viewer — ✅ Passed
- **User story / acceptance criterion**: req 22, req 23
- **Preconditions**: viewer; DHL Paleta set inactive for coverage, Poczta Polska inactive

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open /plugins/shipping/ | Read-only notice | "You have read-only access. Ask an editor to change carriers or delivery methods." | ✅ |
| 2 | Count controls | No buttons, inputs, selects | 0 buttons, 0 inputs/selects | ✅ |
| 3 | Status badges | Active (success) / Inactive / Inactive (carrier) | Carriers: Active×3, Inactive; methods: DHL Paleta "Inactive", Pocztex Kurier "Inactive (carrier)", others Active | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/08-config-viewer.png`
- **Acceptance criteria checklist**:
  - [x] Notice shown
  - [x] No add/edit/delete/toggle controls
  - [x] Three status variants correct

### 4.13 Product tab loads (no box yet) — ✅ Passed
- **User story / acceptance criterion**: req 12, 13, 14, 18
- **Preconditions**: editor; product 1 without box-size data

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open product 1 | Tabs include "Shipping" | Details, Box Size, Shipping; iframe `http://localhost:3010/product-shipping` | ✅ |
| 2 | Shipping tab | Heading + no-box line | "Delivery methods for this product"; "No box dimensions — size check unavailable. Set them in the Box Size tab." | ✅ |
| 3 | Badges | "No dimensions" for active rows (even no-limit), "Inactive" for Pocztex | As expected; no hint | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/09-product-tab-nodims.png`, `screenshots/09-product-tab-nodims-initial-host.png`
- **Acceptance criteria checklist**:
  - [x] Badge precedence Inactive > No dimensions > No limits

### 4.14 Box line, badges and sorting with box 120×12×48 — ✅ Passed
- **User story / acceptance criterion**: req 14, 16, 18; Success Criteria (InPost)
- **Preconditions**: box 120×12×48 saved in the Box Size tab (button showed "Saved!")

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Box line | "Box: 120 × 12 × 48 cm (from Box Size)" | Exact | ✅ |
| 2 | Badges | No limits / Fits / Fits / Too large / Too large / Inactive | DHL Kurier plain "No limits"; Paleta, DPD `tc-badge--success` Fits; InPost Kurier, Paczkomat `tc-badge--danger` Too large; Pocztex `tc-badge` Inactive | ✅ |
| 3 | Row order | Kurier before Paczkomat | Kurier before Paczkomat | ✅ |
| 4 | Inactive row checkbox | Disabled | Pocztex checkbox disabled | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/11-product-tab-warning-plural.png`
- **Acceptance criteria checklist**:
  - [x] Box line format
  - [x] All badge variants and classes
  - [x] Aria-labels "Available: <name>"

### 4.15 Too-large hint: plural, then singular — ⚠️ Passed with issues
- **User story / acceptance criterion**: req 19
- **Preconditions**: state of 4.14, both InPost ticked

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Read the hint | `"InPost Kurier", "InPost Paczkomat" are still available although the box exceeds their limits. Consider unticking them.` | Exact, in `p.tc-error` | ✅ |
| 2 | Untick InPost Kurier (unsaved) | `"InPost Paczkomat" is still available although the box exceeds its limits. Consider unticking it.`; counter 1 of 6 | Exact; "1 of 6 methods disabled for this product" | ✅ |
| 3 | Hint colour | Error styling | Rendered grey (#64748B): `.tc-plugin p` overrides `.tc-error` | ⚠️ |

- **Issues observed**: §5.3 #2
- **Evidence**: `screenshots/11-product-tab-warning-plural.png`, `screenshots/12-product-tab-editor-singular.png`
- **Acceptance criteria checklist**:
  - [x] Plural sentence names both, in table order
  - [x] Singular sentence after untick
  - [x] Recomputed from unsaved state

### 4.16 Save, "Saved!", counter, persistence — ✅ Passed
- **User story / acceptance criterion**: req 20, 21; Success Criteria
- **Preconditions**: InPost Kurier unticked (unsaved)

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Click Save | Label "Saved!" for 2 s | "Saved!" at +0.5 s, "Save" at +2.7 s | ✅ |
| 2 | `GET /api/plugins/shipping/products/1/data` | `{disabledMethodIds:[<objectId>]}` | `200 {"disabledMethodIds":["933de468-…"]}` (InPost Kurier objectId) | ✅ |
| 3 | Reload page, open Shipping tab | Kurier still unticked, 1 of 6 | Kurier unticked; "1 of 6 methods disabled for this product" | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/13-product-tab-saved.png`
- **Acceptance criteria checklist**:
  - [x] setData with objectIds
  - [x] Saved! flash for 2 s
  - [x] Persists across reload

### 4.17 Re-tick everything and save removes the data — ✅ Passed
- **User story / acceptance criterion**: req 21; Success Criteria
- **Preconditions**: 2 methods unticked

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Tick all, Save | `removeData` | Network: `DELETE /api/plugins/shipping/products/1/data` | ✅ |
| 2 | Product `pluginData` | No `shipping` key | `{"box-size":{…}}` only; data endpoint returns `{}` | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/16-reload-focus-after-click.png` (state before re-tick)
- **Acceptance criteria checklist**:
  - [x] Empty list → removeData

### 4.18 Disabled method: rename/limits edit, deactivate, reactivate — ✅ Passed
- **User story / acceptance criterion**: Key Decisions (opt-out kept), req 18, req 21; Success Criteria
- **Preconditions**: InPost Kurier saved as disabled on product 1

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Config: rename to "InPost Kurier 2", H 35 → 36, then deactivate | Same objectId | `933de468-…` unchanged, `active:false`, H 36 | ✅ |
| 2 | Product tab | "Inactive" badge, unticked, disabled, 1 of 6 | As expected | ✅ |
| 3 | Save while inactive | Inactive id kept by prune | Data still `["933de468-…"]` | ✅ |
| 4 | Reactivate, restore name/H | Opt-out re-applies | Unticked, enabled, "Too large", 1 of 6 | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/14-product-tab-disabled-method-deactivated.png`
- **Acceptance criteria checklist**:
  - [x] Rename/limits edit keeps the opt-out
  - [x] Deactivation preserves the unticked state
  - [x] Reactivation re-applies it

### 4.19 No box dimensions after Remove in Box Size — ✅ Passed
- **User story / acceptance criterion**: req 14, 18, 28
- **Preconditions**: box 120×12×48, Kurier disabled

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Box Size → Remove, back to Shipping | (refresh pending until focus) | Still showed the old box line | ✅ |
| 2 | Click inside the iframe | No-box line, every active row "No dimensions", no hint | As expected; 0 `tc-error`; 1 of 6 | ✅ |

- **Issues observed**: _None observed._ (trigger behaviour covered in 4.20)
- **Evidence**: `screenshots/18-product-tab-nodims-after-remove.png`
- **Acceptance criteria checklist**:
  - [x] "No dimensions" rows, no hint

### 4.20 Reload on focus keeps unsaved ticks — ⚠️ Passed with issues
- **User story / acceptance criterion**: req 28; Open Questions (reload trigger)
- **Preconditions**: box 120×12×48; DPD Classic unticked **without saving** (2 of 6)

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Box Size: set 60×40×30, Save, switch to the Shipping tab | Record whether the switch refreshes | **No refresh on tab switch**: line still "120 × 12 × 48" after 2.5 s | ⚠️ |
| 2 | Click inside the Shipping iframe | Refresh | "Box: 60 × 40 × 30 cm (from Box Size)"; badges recomputed (InPost both Fits); hint gone | ✅ |
| 3 | Unsaved selection | Survives | DPD Classic still unticked; counter 2 of 6 | ✅ |
| 4 | Loading state during refresh | None | No "Loading..." seen | ✅ |

- **Issues observed**: §5.3 #3
- **Evidence**: `screenshots/15-reload-focus-after-switch.png`, `screenshots/16-reload-focus-after-click.png`, `screenshots/10-product-tab-after-switch-before-click.png`
- **Acceptance criteria checklist**:
  - [x] Refresh on iframe focus
  - [x] Unsaved ticks survive
  - [ ] Refresh on host tab switch (not triggered; accepted per spec risk)

### 4.21 Product tab as viewer — ✅ Passed
- **User story / acceptance criterion**: req 22, 23
- **Preconditions**: viewer; box 120×12×48; InPost Kurier disabled

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Open the Shipping tab | Read-only line | "Read-only: you can see which methods are available but not change them." | ✅ |
| 2 | Checkboxes | All disabled | 6/6 disabled | ✅ |
| 3 | Hint | Still shown | Singular hint for InPost Paczkomat | ✅ |
| 4 | Save/counter | Absent | 0 buttons, no counter | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/17-product-tab-viewer.png`
- **Acceptance criteria checklist**:
  - [x] Disabled checkboxes, hint shown, no Save or counter

### 4.22 Empty state (editor and viewer) — ✅ Passed
- **User story / acceptance criterion**: req 15
- **Preconditions**: all methods deleted through the UI; box 120×12×48

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Editor: Shipping tab | Box line + "No delivery methods configured. Add them in the Shipping section.", no table/Save | Exact; 0 tables/buttons | ✅ |
| 2 | Viewer: Shipping tab | Same, no read-only notice | Exact, no notice | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/19-product-tab-empty-editor.png`, `screenshots/20-product-tab-empty-viewer.png`
- **Acceptance criteria checklist**:
  - [x] Empty hint
  - [x] No viewer notice in the empty state

### 4.23 Plugin served from :3010 with strictPort — ✅ Passed
- **User story / acceptance criterion**: req 25; Success Criteria
- **Preconditions**: shipping dev server running

| # | Action | Expected | Actual | Status |
|---|---|---|---|---|
| 1 | Inspect the iframe src | `http://localhost:3010` | `http://localhost:3010/#RENDER…` and `/product-shipping#PRODUCT_DETAIL…` | ✅ |
| 2 | Start a second `vite` | Fails | "Error: Port 3010 is already in use" | ✅ |
| 3 | Registered manifest | name, version, url, extension points | GET /api/plugins: Shipping 1.0.0, url http://localhost:3010, menu.main (truck, /, 110) + product.detail.tabs (/product-shipping, 80) | ✅ |

- **Issues observed**: _None observed._
- **Evidence**: `screenshots/09-product-tab-nodims-initial-host.png`
- **Acceptance criteria checklist**:
  - [x] Port 3010, strictPort
  - [x] Manifest fields

## 5. Discrepancies

### 5.1 Critical
_None observed._

### 5.2 Major
**#1 Sidebar "Shipping" entry is hidden from editors and viewers**
- **Spec requirement**: Success Criteria: "After manifest registration … the sidebar shows 'Shipping' with the Truck icon". Key Decisions: "Admin means `PERMISSION_EDIT`", so editors are the page's users.
- **Expected**: An editor (READ, EDIT) sees "Shipping" in the sidebar.
- **Actual**: Only admin (PLUGIN_MANAGEMENT) sees it. `src/main/frontend/src/components/layout/Sidebar.tsx` renders `{hasPluginManagement && <PluginMenuItems />}`. This was committed in ba41674 and is not part of this change. The page itself works for editors via `/plugins/shipping/`.
- **Evidence**: `screenshots/01-sidebar-shipping-truck-admin.png` (admin, Truck shown), `screenshots/03-config-editor.png` (editor, no entry)
- **Root cause hypothesis**: The host treats every plugin menu item as an admin feature, a design choice that predates business plugins.
- **User impact**: Editors cannot discover the Shipping configuration page without a direct link.
- **Recommended fix**: A follow-up host change to show `menu.main` items to all authenticated users (or gate them per manifest), plus a spec/doc note in the meantime. It is out of scope for this plugin task.
- **Workaround**: Navigate to `http://localhost:8080/plugins/shipping/` (or :5173 in dev) directly, or bookmark it.

### 5.3 Minor
**#1 Out-of-range number input is silently saved as "no limit"**
- **Spec requirement**: req 7: "each filled limit must be a finite number > 0 ('Limits must be positive numbers.')"
- **Expected**: The validation error for a non-finite entry such as `1e999`.
- **Actual**: Chrome sanitises a `type="number"` value it cannot parse (here `1e999`) to `""`. The plugin then sees an empty field, omits the limit, and saves the method with no length limit and no message.
- **Evidence**: The method "Test" was stored as `{"name":"Test","active":true,"carrierId":"80a8fc2b-…"}` (no `maxLengthCm`). It was observed via `objects.list` and then deleted.
- **Root cause hypothesis**: `parseLimits` reads `e.target.value`, which the browser has already emptied for bad input. `validity.badInput` is not consulted.
- **User impact**: A mistyped limit silently becomes "unlimited", and the size check then says "Fits"/"No limits" for that dimension.
- **Recommended fix**: Treat `input.validity.badInput` as invalid, or use `type="text" inputMode="decimal"` so the raw text reaches `parseLimits`.
- **Workaround**: Check the Max size column after saving; edit inline if it shows "—" or "No limit" unexpectedly.

**#2 `tc-error` paragraphs render grey, not red**
- **Spec requirement**: req 19 and req 24 (errors and the hint shown in `tc-error`); Visual Design (`tc-error` hint).
- **Expected**: `.tc-error` red (#DC2626).
- **Actual**: `.tc-plugin p { color:#64748B }` (specificity 0,1,1) outranks `.tc-error { color:#DC2626 }` (0,1,0). Every `<p class="tc-error">` is grey: the hint, validation errors and the blocked-delete message. The mockups render the same, and warehouse and box-size use the same pattern.
- **Evidence**: `screenshots/12-product-tab-editor-singular.png`, `screenshots/02-carrier-name-required.png`
- **Root cause hypothesis**: A pre-existing CSS specificity bug in the host `src/main/frontend/public/assets/plugin-ui.css`, which is out of scope for this task.
- **User impact**: Warnings and errors are less noticeable.
- **Recommended fix**: A host follow-up: `.tc-plugin .tc-error` or `p.tc-error` in plugin-ui.css.
- **Workaround**: None needed; the text is still shown.

**#3 Reload on focus does not fire on host tab switch**
- **Spec requirement**: req 28; Open Questions ("refresh then runs on the first click inside the Shipping tab").
- **Expected**: Per req 28, a refresh on iframe `focus` or `visibilitychange`. The spec predicted that a host tab switch would trigger neither.
- **Actual**: Confirmed. After a Box Size change and a switch back, the box line stays stale until the first click inside the iframe, and then refreshes correctly with unsaved ticks kept.
- **Evidence**: `screenshots/15-reload-focus-after-switch.png` (stale 120×12×48), `screenshots/16-reload-focus-after-click.png` (60×40×30)
- **Root cause hypothesis**: The host hides tab iframes with CSS `display`. Switching sends no focus or visibility events into the iframe.
- **User impact**: Badges and the hint can be stale until the user interacts. Saving is unaffected, because Save prunes against the current methods.
- **Recommended fix**: Accepted per spec. An optional future host change could send a postMessage on tab activation.
- **Workaround**: Click anywhere inside the Shipping tab.

### 5.4 Cosmetic
_None observed._

## 6. Console & Network Errors
| Source (file:line) | Message | Frequency | Severity | Impact |
|---|---|---|---|---|
| react-dom_client.js:6804 (host) | "An iframe which has both allow-scripts and allow-same-origin for its sandbox attribute can escape its sandboxing." | Once per iframe mount | Minor | Pre-existing host iframe sandbox warning, not shipping-specific |

No console errors (0 across the session). No failed (non-2xx) network requests from the shipping plugin were observed; blocked carrier deletes are resolved client-side and issue no DELETE.

## 7. Spec Alignment
- **Fully implemented**: reqs 1–6, 8–25, 28 (with the accepted trigger caveat); sorting rule; objectId-as-domain-id; opt-out model with removeData; badge precedence; inactive opt-out preservation; viewer variants; strictPort on 3010; manifest fields; `truck` icon mapping.
- **Partially implemented**: req 7: validation is bypassed for input the browser sanitises to empty (§5.3 #1).
- **Not implemented**: _None._
- **Extra (unspecified) behavior**: The blocked-delete `tc-error` stays visible after opening an edit row; it is cleared on the next carrier mutation. Harmless, and it matches the mockup combination.

## 8. Variances from Plan
- box-size was not registered in the backend, so it was registered from `plugins/box-size/manifest.json` (PUT 200).
- After the first login, plugins were not in context until a page reload: `PluginProvider` fetches before login. A reload fixed it. This is host behaviour.
- Immediately after the dev servers started, the first Playwright pointer clicks inside the iframe did not trigger React handlers. Shipping's Vite was re-optimising dependencies ("lockfile has changed"). Later clicks worked consistently, so this was treated as an environment artefact; no data was affected.
- Roles were switched by an in-page login fetch rather than the login form.
- Pocztex Kurier was added by briefly reactivating Poczta Polska, because the add form lists only active carriers (by design).
- DHL Paleta was temporarily deactivated to show all three viewer status variants, then reactivated.
- Screenshots `06` and `05` are element captures clipped at the iframe viewport height; the lower table rows are cut off.
- The "14-item" and "12-item" walkthrough lists live in group reports that are not in the task directory. Scenarios were derived from spec reqs 1–28, the Success Criteria and the brief's key checks.

## 9. Evaluation Against Exit Criteria
| Criterion (from spec) | Status | Evidence |
|---|---|---|
| Vite serves the plugin on :3010, and a second instance fails because of `strictPort` | ✅ | §4.23 |
| After manifest registration, the sidebar shows "Shipping" with the Truck icon, and the product detail page shows a "Shipping" tab | ⚠️ | Admin only (§5.2 #1); tab ✅ §4.13 |
| Carriers and methods can be added, edited inline (objectId unchanged, no duplicate), toggled and deleted | ✅ | §4.2–4.4, 4.7–4.10 |
| Only one row is in edit mode at a time across both tables | ✅ | §4.5 |
| Deleting a carrier that has methods is blocked with the singular or plural message | ✅ | §4.6 |
| Box 120×12×48: InPost Kurier/Paczkomat "Too large", Kurier before Paczkomat, plural hint exact | ✅ | §4.14, 4.15 |
| Unticking one leaves the singular hint naming the other | ✅ | §4.15 |
| Untick + Save + reload keeps it; GET data returns objectIds; "Saved!" for 2 s | ✅ | §4.16 |
| Re-ticking everything and saving removes the data | ✅ | §4.17 |
| Renaming or editing the limits of a disabled method keeps it disabled | ✅ | §4.18 |
| Deactivating a disabled method shows "Inactive", unticked preserved; reactivating re-applies | ✅ | §4.18 |
| After a Box Size change, returning and focusing updates the box line and badges; unsaved ticks kept | ✅ | §4.20 (on focus/click) |
| Viewer: no write controls, product checkboxes disabled, read-only notices (not in the empty state) | ✅ | §4.12, 4.21, 4.22 |
| All 8 screens and 2 components match the mockups in layout, components and states (rules win) | ✅ | `visual-fidelity.md`: 9 match, 1 minor deviation, 0 drift |

## 10. Recommendations
- **Must fix before merge**: _None._
- **Should fix soon**: §5.2 #1 (host sidebar gating, separate host task); §5.3 #1 (detect `validity.badInput` on limit inputs).
- **Nice-to-have**: §5.3 #2 (plugin-ui.css `tc-error` specificity, host-wide); §5.3 #3 (host postMessage on tab activation to trigger the refresh).

## 11. Artifacts
- **Screenshots**: `verification/screenshots/` (30 files: 22 implementation captures + 8 rendered mockups `mockup-*.png`)
- **Visual-fidelity report**: `verification/visual-fidelity.md`
- **Console log dump**: inline in §6

## 12. Conclusion
Verdict: ⚠️ GO WITH CAVEATS. Every specified shipping-plugin behaviour was observed working end to end against the live backend, with exact copy, correct objectId handling and correct pluginData persistence. The one Major finding is a pre-existing host sidebar gate that hides the menu entry from editors, and the three Minor findings are a browser input edge case, pre-existing CSS, and a documented trigger limitation. Recommendation: **merge**, and open follow-ups for the host sidebar gating and limit-input `badInput` handling.
