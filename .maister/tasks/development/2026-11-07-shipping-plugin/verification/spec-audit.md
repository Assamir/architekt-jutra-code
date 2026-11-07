# Spec Audit: Shipping Plugin (pre-implementation)

## TL;DR
Verdict: **Mostly Compliant (pass-with-concerns)**. The spec is complete against requirements.md and scope-clarifications.md, and nearly every claim about host behaviour checks out against the code. The data model and backend needs no changes.
Counts: Critical 0, High 1, Medium 3, Low 8.
Main concerns: (1) the "host `npm run lint` passes" success criterion cannot be met, because lint already fails with 14 errors, one of them in `Icons.tsx`; (2) the spec never says that domain ids must come from `PluginObject.objectId`; (3) the product-tab iframe is never reloaded, so box changes made in the Box Size tab don't show up.

## Key Decisions
- Lint baseline rated High, not Critical. It blocks a stated success criterion and invites out-of-scope edits, but it does not affect the feature.
- objectId-vs-id rated Medium. The warehouse pattern the spec says to copy already uses `objectId`. Getting it wrong would still quietly corrupt the opt-out data and create duplicate objects.
- Mockup-vs-rule differences (hint wording, method order) rated Medium as one finding. The spec already says the rule wins, but a success criterion still asks for "match mockups in wording".
- User-approved decisions (opt-out model, warn-only rotation check, EDIT = admin, port 3010, and the rest) were not reopened. None is contradictory or unimplementable.

## Open Questions / Risks
- Should the product Shipping tab refresh box dimensions after the user edits the Box Size tab? Options: a reload-on-focus, a "Refresh" control, or a documented limitation.
- Should the docs keep 3004–3009 for non-business plugins, or should every new plugin now start at 3011?
- What is the exact plural wording of the warning hint ("exceeds its/their limits. Consider unticking it/them.")?
- Error text will probably read like `403 ` with an empty status text, because embedded Tomcat sends no reason phrase. This is acceptable, but the messages are terse.

---

## 1. Claims verified against the codebase (no issue)

| Spec claim | Evidence |
|---|---|
| SDK calls proxy to REST with user JWT; origin checked exactly against manifest URL | `src/main/frontend/src/plugins/PluginMessageHandler.ts:173-179` (origin), `:99-156` (routes), `src/main/frontend/src/api/client.ts:21-28` (Bearer) |
| Errors arrive as `"<status> <statusText>"`; 401 redirects host | `api/client.ts:7` (`super(\`${status} ${statusText}\`)`), `:36-41` |
| `getData` returns `{}` when no data; `setData` replaces node; `removeData` 204 | `core/plugin/PluginDataService.java:73-81`, `:96`, `PluginDataController.java:39-43` |
| `objects.save` is an upsert by `(pluginId, objectType, objectId)` that replaces data | `PluginObjectService.java:157-172` |
| Delete of missing object → 404 | `PluginObjectService.java:177-178` (`EntityNotFoundException`) |
| READ on GETs, EDIT on writes for objects and data | `SecurityConfiguration.java:114-115`, `:124-127`; manifest PUT needs PLUGIN_MANAGEMENT `:130` |
| JWT `permissions` claim holds bare `"EDIT"` / `"READ"` | `JwtTokenProvider.java:38`, `JwtAuthenticationFilter.java:29-31` (prefixes `PERMISSION_`), `db/changelog/2026/008-create-users-table.yaml:120-140` |
| `productId` arrives as a number | `pages/ProductDetailPage.tsx:193,214` (`contextData={{ productId: product.id }}`), `plugin-sdk/context.ts:11-23` (no coercion); `sdk.ts:15,58` typed `string` |
| `ProductResponse.pluginData` exposes other plugins' data (box-size) | `product/ProductResponse.java:17,31` |
| box-size shape `{length,width,height}`, null tolerance | `plugins/box-size/src/domain.ts:1-14`, `ProductBoxTab.tsx:54-55` |
| box-size save-flow pattern and 2 s flash; it swallows load errors | `plugins/box-size/src/pages/ProductBoxTab.tsx:33-35`, `:56-57` |
| ai-description `canEdit` uses plain `atob` (not base64url-safe) | `plugins/ai-description/src/pages/product-tab.tsx:27-33` |
| Correct base64url decode lives in non-exported `decodeJwtPayload` | `src/main/frontend/src/auth/AuthContext.tsx:14-24` |
| Unpadded base64 works with `atob` in Node (plugin tests) | Ran `atob('eyJhIjoxfQ')` on Node v24.19.0 and got `{"a":1}` |
| `ICON_MAP` lacks truck. Dynamic lookup falls back to Plug because lucide icons are `forwardRef` objects, not functions | `components/shared/Icons.tsx:18-24`, `:37-38`; `typeof require('lucide-react').Truck === "object"` |
| `Truck` exists in installed lucide-react | `node_modules/lucide-react/dist/lucide-react.d.ts:20401` |
| No host test covers `resolveIcon` | grep of `src/main/frontend/src/test/` finds no match |
| Host Vitest is ^3.2.4 and works alongside vite 8 / plugin-react 6 | `src/main/frontend/package.json` devDeps; vitest bundles a nested vite (`node_modules/vitest/node_modules/vite`); ran `npx vitest run src/test/footprint-helpers.test.ts`, 8 passed |
| Warehouse scaffold (strict tsconfig, StrictMode + BrowserRouter, strictPort) | `plugins/warehouse/{package.json,tsconfig.json,vite.config.ts,index.html,src/main.tsx,manifest.json}` |
| `start-dev` picks up any plugin with package.json | `start-dev.sh:33-37`, `start-dev.ps1:47` |
| All referenced `tc-*` classes exist; no checkbox or muted class exists | `src/main/frontend/public/assets/plugin-ui.css` (each class grep-matched) |
| Doc line refs | `plugin-development.md:56` (ports prose), `:59` (`port: 3004`); `INDEX.md:109`; `CLAUDE.md:39`; `README.md:18`, `:71`; `plugins/CLAUDE.md:376` Reference Plugins |
| Sidebar resolves manifest icon via `resolveIcon` | `components/layout/Sidebar.tsx:58` |
| Fit maths in mockups is consistent with the sorted-dimension rule | Box 120×12×48 sorts to (120,48,12). Paczkomat (64,41,38) and InPost Kurier (80,50,35) are too large; Paleta (180,120,80) and DPD (175,80,60) fit; Pocztex is too large but shown as Inactive by precedence |

Requirements coverage: every item in `analysis/requirements.md` §Functional requirements 1–10 and every bullet in `analysis/scope-clarifications.md` maps to spec requirements 1–27. Nothing was dropped.

---

## 2. High

### H1. Success criterion "host `npm run lint` passes" cannot be met. Lint already fails, including in `Icons.tsx`.
- **Spec reference**: Testing Approach, "The host `npm run lint` must still pass after the Icons.tsx edit". Success Criteria, "The host `npx vitest run src/test/icons.test.ts` and `npm run lint` pass". Req 26, "Nothing else changes."
- **Evidence**: `npx eslint .` in `src/main/frontend` reports **16 problems (14 errors, 2 warnings)** on the current tree:
  - `src/components/shared/Icons.tsx:26:17` `react-refresh/only-export-components` (the `resolveIcon` export);
  - `src/auth/AuthContext.tsx:87`, `src/plugins/PluginContext.tsx:107`, `src/router.tsx:20` (react-refresh);
  - `src/hooks/useFootprintComparison.ts:71`, `src/pages/CarbonFootprintLandingPage.tsx:29` (set-state-in-effect);
  - `src/test/footprint-api.test.ts:136`, `src/test/plugin-sdk-auth.test.ts:39`, `src/test/plugin-sdk.test.ts:86,108`, `src/test/plugins.test.tsx:163,194,243,286`.
- **Category**: Unimplementable as written / incorrect assumption.
- **Severity**: High. The gate cannot pass without touching about 10 unrelated files and the very `Icons.tsx` export that req 26 freezes. An implementer or verifier will either report a false failure or widen the scope.
- **Recommendation**: Reword the criterion to "no new lint problems". For example, `npx eslint src/components/shared/Icons.tsx src/test/icons.test.ts` reports only the pre-existing `Icons.tsx:26` error, and the total count stays at 16. Record the baseline in the spec.

---

## 3. Medium

### M1. The spec does not say which `PluginObject` field holds the domain id. `sdk.ts` mistypes the numeric DB id as `id: string`.
- **Spec reference**: Data model table ("UUID id"); req 2/7 ("get the id `crypto.randomUUID()`"); req 11/21 (ids in `disabledMethodIds`); `toCarrier` / `toShippingMethod` mappers.
- **Evidence**:
  - `plugins/sdk.ts:20-23` declares `id: string; ... objectId: string`.
  - The backend returns `id` as the `Long` DB primary key: `core/plugin/PluginObjectResponse.java:188,191`.
  - The UUID passed to `objects.save` is `objectId` (`PluginObjectService.java:160-165`).
  - The warehouse mapper correctly uses `obj.objectId` (`plugins/warehouse/src/domain.ts:24,29`).
- **Gap**: An implementer who writes `id: obj.id` would type-check cleanly, because the type is wrong, and would then:
  - store numeric DB ids in `disabledMethodIds` and `carrierId`;
  - have inline-edit `objects.save(type, "<numeric>")` create new objects instead of updating the existing ones;
  - make delete return 404.

  That silently breaks the core "same id" decision.
- **Severity**: Medium. Copying the warehouse pattern makes the mistake unlikely, but its impact is high and the type system will not catch it.
- **Recommendation**: State explicitly that domain `id = PluginObject.objectId`, never `PluginObject.id`. Add a mapper test asserting that `toCarrier({id: "42", objectId: "uuid-…"}).id === "uuid-…"`.

### M2. Product Shipping tab data goes stale. Tab iframes are mounted once and kept alive, so box changes never reach the tab.
- **Spec reference**: Req 13 (load once), req 14 ("No box dimensions — … Set them in the Box Size tab.").
- **Evidence**:
  - `pages/ProductDetailPage.tsx:201-218` renders every plugin tab at once and switches only `display` between `block` and `none`, with the comment "render all, show/hide with CSS to preserve iframe state".
  - `plugins/PluginFrame.tsx:44-56` sets `src` only once.
  - box-size writes are in a different iframe (`ProductBoxTab.tsx:55`). The host sends no cross-plugin change notification (`PluginMessageHandler.ts`, no broadcast).
- **Gap**: The spec's own empty-box message tells the editor to set dimensions in the Box Size tab. Back on the Shipping tab, the box line, badges and hint still show the stale "No dimensions" until a full page reload. Methods added on the config page in another view behave the same way while the product page stays open.
- **Category**: Missing requirement (state refresh).
- **Severity**: Medium. It affects the main journey, the workaround is a page reload, and no data is lost.
- **Recommendation**: Pick one and write it down:
  - (a) re-fetch the product and lists on the iframe window's `focus` event;
  - (b) add a small `tc-ghost-button` "Refresh" control;
  - (c) document it as a known limitation and adjust the hint text.

  Option (a) adds no UI and does not change the mockups.

### M3. The "match mockups in wording" success criterion conflicts with the spec's rules on mockup data, and the plural hint text is unspecified.
- **Spec reference**: Open Questions bullet 2 (rule wins); req 6/16 (sort by carrier name, then method name); req 19 (hint wording, plural "with 'are'"); Success Criteria ("All 8 screens and 2 components match their mockups in structure and wording").
- **Evidence**:
  - **Hint**: with box 120×12×48, InPost Kurier (80×50×35) is ticked and too large in `product-shipping-tab-editor.html`, `…-too-large-method-still-enabled.html` and `…-read-only-viewer.html`. The rule therefore must name it, but all three mockups name only "InPost Paczkomat", and in the editor and viewer mockups Paczkomat is *unticked*. Under the rule, the warning screen must read `"InPost Paczkomat", "InPost Kurier" are …` and the editor and viewer screens must name only "InPost Kurier".
  - **Plural text**: "comma-separated with 'are'" leaves open whether the rest becomes "exceeds their limits. Consider unticking them." or keeps "its … it".
  - **Sort**: every mockup lists "InPost Paczkomat" before "InPost Kurier", which is not alphabetical. That contradicts req 6 and req 16.
  - **Success criterion**: the criterion "InPost Paczkomat shows Too large and the hint names it" holds only while Paczkomat is ticked.
- **Category**: Contradictory (spec vs. its own binding mockups).
- **Severity**: Medium. A verifier comparing screens word for word against the mockups will flag correct behaviour.
- **Recommendation**: Change the criterion to "match in structure and copy templates; row order and hint content follow req 6/16/19". Spell out the exact plural sentence, for example `"A", "B" are still available although the box exceeds their limits. Consider unticking them.`

---

## 4. Low

### L1. The port rule in the docs is ambiguous for non-business plugins
- **Spec**: Req 27 says "new business modules take the next free port from 3010 up, currently 3011", and the code sample changes to 3011.
- **Evidence**: The current rule is "A new plugin takes the next free port in the 3001+ range (currently 3004)" (`plugin-development.md:56`; `INDEX.md:109` "next plugin 3004").
- **Gap**: The spec doesn't say whether 3004–3009 stays available for generic or demo plugins, or whether every new plugin now starts at 3011.
- **Ask**: Which rule should the docs state?

### L2. Other docs listing plugin ports are not updated
- **Evidence**: `.maister/docs/project/tech-stack.md:44-46` lists the plugins with their ports, and `INDEX.md` summarises the tech stack the same way. `architecture.md:49` is known to be stale.
- **Gap**: The success criterion "All five docs reflect shipping" will pass while these docs still omit shipping.
- **Recommendation**: Either add `tech-stack.md` to the update list or exclude it explicitly.

### L3. Pluralisation in the blocked-delete message
- **Spec**: Req 43 uses the template `it still has <N> delivery methods`.
- **Gap**: With N = 1 this produces "1 delivery methods". The mockup only shows N = 2.
- **Recommendation**: Specify the singular form, "1 delivery method".

### L4. Edge states are undefined
- **Missing or deleted carrier**: req 13 and Effective-active treat it as inactive, but the spec doesn't say what the Carrier column shows (e.g. `—`), how such a method sorts, or whether the "Carrier inactive" badge appears.
- **Viewer status**: when the method *and* its carrier are both inactive, req 23 doesn't say whether the status reads "Inactive" or "Inactive (carrier)".
- **Partial limits in the product tab**: the "Max (cm)" column is unspecified. Req 6 covers this case for the config page only.
- **Empty state for viewers**: req 15 doesn't say whether the read-only notice is shown.

### L5. Inline-edit interaction details
- **Edit on another row**: when a second row's "Edit" is clicked while a row is already being edited, the spec doesn't say whether the open draft is discarded (req 4, "one row at a time").
- **Toggles during an edit**: an immediate toggle on another row triggers "reload both lists" (Technical Approach, Writes). The spec doesn't say whether that keeps the open draft.
- **"Saved!" placement**: box-size swaps the button label (`ProductBoxTab.tsx:106`), but req 21 doesn't say where the text appears.
- **Delete confirmation**: the spec has none. Deleting a method permanently drops its per-product opt-outs.

### L6. Manifest top-level fields are not specified
- **Spec**: Req 25 specifies only `url` and the extension points.
- **Evidence**: The backend rejects a blank `name` (`PluginDescriptorService.java:27-29`). Warehouse also sets `version` and `description` (`plugins/warehouse/manifest.json`).
- **Recommendation**: State `name` (e.g. "Shipping"), `version` "1.0.0" and a description.

### L7. Error messages will be terse
- **Evidence**: `ApiError` builds the message from `statusText` (`api/client.ts:7`). Spring Boot's embedded Tomcat does not send an HTTP reason phrase, so `statusText` is probably `""` and users would see e.g. `403 ` or `404 `. This is inferred from Tomcat's defaults and was not observed at runtime.
- **Spec**: Req 24 allows "a specific fallback", but only for a missing message.
- **Recommendation (optional)**: Prefix the message with context, e.g. "Failed to save carrier: 403".

### L8. Plugin test wiring and test count
- **Wiring**: Plugin `tsconfig.json` has `include: ["src"]` and no `types` (`plugins/warehouse/tsconfig.json`). `npm run build` (`tsc -b`) will therefore type-check `src/domain.test.ts`.
  - If the tests use Vitest globals, the build fails.
  - Host tests import explicitly (`src/test/footprint-helpers.test.ts:1`), but the spec's standards line cites "Vitest globals".
  - **Recommendation**: Specify explicit `import { describe, it, expect } from "vitest"` in the plugin tests, with no `globals` config.
- **Count**: "About 6-8 tests" conflicts with the roughly 14 enumerated cases (6 sizeCheck, mappers, toBoxDimensions, prune, 4 canEdit). Allow `it.each` or raise the number.

### Observation (no severity)
The product tab loads its own data with `getData`, although `getProduct` already returns `pluginData.shipping` (`ProductResponse.java:17`). This costs one extra round trip and is harmless. Keep it if the intent is to stay on the documented SDK path.

---

## 5. Clarification Needed
1. M2: should the product tab refresh (on focus or with a button), or should staleness be documented?
2. M3: what is the exact plural hint sentence, and does the "match mockups" criterion exempt row order and hint content?
3. L1: are ports 3004–3009 still reserved for generic plugins?
4. L4: what should a method show when its carrier is missing, and what status should the viewer see when both the method and its carrier are inactive?

## 6. Extra Features
None. The spec adds nothing beyond requirements.md and scope-clarifications.md.

## 7. Recommendations (priority order)
1. Rewrite the lint success criterion as "no new lint problems" and record the baseline of 16 (H1).
2. Add "domain id = `PluginObject.objectId`" to the Data model and the mapper tests (M1).
3. Decide on product-tab refresh behaviour (M2).
4. Reconcile the hint and sort rules with the mockup-fidelity criterion and spell out the plural sentence (M3).
5. Resolve the Low items inline when the implementation plan is written, specifically singular wording, manifest name, explicit vitest imports, edge-state display and the docs port wording.
