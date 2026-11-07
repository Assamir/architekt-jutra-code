# Visual Fidelity Report

**Mode**: Report-only (does NOT gate completion)
**Comparison**: LLM-judged structural match (not pixel-perfect), plugin frame regions only (`.tc-plugin` root)
**Source**: analysis/design-context/INDEX.md
**Captured**: verification/screenshots/ (implementation captures, plus `mockup-*.png` renders of each mockup's plugin frame)

## TL;DR
Fidelity is high. Of 10 design references (8 screens, 2 components), 9 match structurally, 1 deviates in a minor way, and none drifts.
The one deviation, `screen:shipping-config-edit`, is required by the spec: the mockup shows a carrier row and a method row in edit mode at the same time, while req 4 allows only one edit row.
Every other difference is either sample data/ordering the spec overrides (InPost Kurier before Paczkomat, a hint naming every ticked too-large method) or a mockup-only helper class the spec chose not to reproduce (`row-off` greying, `muted`).

## Key Decisions
- Spec-sanctioned differences are counted as a **match**, not a deviation: row order from the sorting rule, hint content from req 19, `row-off`/`muted` not reproduced (Visual Design and Key Decisions in spec.md). They are still noted per screen.
- Mockups were rendered in the same Playwright browser, and their `.tc-plugin` region was captured, so the comparison is like-for-like at the frame level.

## Open Questions / Risks
- Inactive rows are not visually de-emphasised. The mockups grey them with `row-off`; the implementation relies on the "Inactive" / "Carrier inactive" badges, as the spec decided. Design may want a `tc-*` class for this in plugin-ui.css later.
- Both the mockups and the implementation render `<p class="tc-error">` grey, because of `.tc-plugin p` specificity in plugin-ui.css. Visually they match, but neither shows red (see e2e report §5.3 #2).

## Summary
- Total screens compared: 10 (8 screens + 2 components)
- Match (✓): 9
- Minor deviation (⚠): 1
- Substantive drift (✗): 0

## Per-Screen Comparison

### screen:shipping-config (✓ Match)
- Mockup: analysis/design-context/mockups/shipping-configuration-editor.html (`screenshots/mockup-shipping-configuration-editor.png`)
- Screenshot: verification/screenshots/03b-config-editor-frame.png (also `03-config-editor.png`)
- Layout: `h1` "Shipping" followed by two `tc-section`s (Carriers, Delivery methods), each with a `tc-flex` add form above a `tc-table`. Matches.
- Field order: carrier input → "Add carrier"; method form: carrier select → method name → "Max (cm, optional):" → L, W, H → "Add method". Matches.
- Columns: Name / Methods / Status / actions; Carrier / Method / Max size (L×W×H) / Status / actions. Matches.
- Primary actions: Edit (`tc-ghost-button`) and Delete (`tc-ghost-button--danger`) per row; Active checkboxes; "Carrier inactive" badge on Pocztex Kurier. Matches.
- Notes: rows sorted per the rule (mockup lists Paczkomat before Kurier); Poczta Polska row not greyed (`row-off` deliberately omitted).

### screen:shipping-config-edit (⚠ Minor Deviation)
- Mockup: analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html (`screenshots/mockup-shipping-configuration-inline-edit-and-blocked-delete.png`)
- Screenshot: verification/screenshots/06-config-edit-and-blocked-delete.png, verification/screenshots/05-config-method-inline-edit.png, verification/screenshots/04-config-carrier-inline-edit.png
- Layout: blocked-delete `tc-error` sits directly under the Carriers add form, above the table. Matches.
- Carrier edit row: name `tc-input`, count, Active checkbox, `tc-primary-button` Save + `tc-ghost-button` Cancel. Matches.
- Method edit row: `tc-select` carrier, name input, L × W × H inputs in `tc-flex` with "×" separators, Active, Save/Cancel. Matches.
- Deviation: the mockup shows a carrier edit row and a method edit row **at the same time**. The implementation allows one edit row across both tables (req 4), so the two states were captured separately.
- Impact: none functionally. This is an intentional spec rule, not an implementation defect.
- Recommendation: update the mockup annotation to show one edit row, or accept as is.

### screen:shipping-config-readonly (✓ Match)
- Mockup: analysis/design-context/mockups/shipping-configuration-read-only-viewer.html (`screenshots/mockup-shipping-configuration-read-only-viewer.png`)
- Screenshot: verification/screenshots/08-config-viewer.png
- Layout: read-only notice under `h1`, then both tables with no add forms and an empty actions column. Matches.
- Status badges: `tc-badge--success` "Active", `tc-badge` "Inactive", `tc-badge` "Inactive (carrier)". Matches.
- Notes: DHL Paleta was deliberately inactive during capture (to show "Inactive" on a method), whereas the mockup has it Active. The order follows the sorting rule.

### screen:product-shipping-tab (✓ Match)
- Mockup: analysis/design-context/mockups/product-shipping-tab-editor.html (`screenshots/mockup-product-shipping-tab-editor.png`)
- Screenshot: verification/screenshots/12-product-tab-editor-singular.png
- Layout: `tc-plugin` (padding 1.5rem) → `h3` heading → box line → hint → table → footer `tc-flex` with Save and counter. Matches.
- Columns: Available / Carrier / Method / Max (cm) / Size check. Matches.
- State: 1 of 6 disabled, singular hint "InPost Paczkomat", Pocztex Kurier disabled with "Inactive". Matches.
- Notes: the unticked row is InPost Kurier (the mockup unticks Paczkomat while naming Paczkomat in the hint, a sample-data inconsistency). Order Kurier before Paczkomat per the rule. "(from Box Size)" and the counter are plain text, not `muted`.

### screen:product-shipping-tab-warning (✓ Match)
- Mockup: analysis/design-context/mockups/product-shipping-tab-too-large-method-still-enabled.html (`screenshots/mockup-product-shipping-tab-too-large-method-still-enabled.png`)
- Screenshot: verification/screenshots/11-product-tab-warning-plural.png
- Layout: `tc-error` hint above the table, all methods ticked, 0 of 6. Matches.
- Notes: the hint names both InPost methods (plural sentence) per req 19; the mockup names only Paczkomat (sample text the spec overrides).

### screen:product-shipping-tab-nodims (✓ Match)
- Mockup: analysis/design-context/mockups/product-shipping-tab-no-box-dimensions.html (`screenshots/mockup-product-shipping-tab-no-box-dimensions.png`)
- Screenshot: verification/screenshots/18-product-tab-nodims-after-remove.png (also `09-product-tab-nodims.png`)
- Layout: "No box dimensions — size check unavailable. Set them in the Box Size tab." with **Box Size** bold; every active row "No dimensions"; Pocztex "Inactive"; no hint; Save + "1 of 6". Matches.

### screen:product-shipping-tab-readonly (✓ Match)
- Mockup: analysis/design-context/mockups/product-shipping-tab-read-only-viewer.html (`screenshots/mockup-product-shipping-tab-read-only-viewer.png`)
- Screenshot: verification/screenshots/17-product-tab-viewer.png
- Layout: box line → read-only notice → hint → table with all checkboxes disabled; no footer. Matches, including the notice-before-hint order.

### screen:product-shipping-tab-empty (✓ Match)
- Mockup: analysis/design-context/mockups/product-shipping-tab-empty-state.html (`screenshots/mockup-product-shipping-tab-empty-state.png`)
- Screenshot: verification/screenshots/19-product-tab-empty-editor.png, verification/screenshots/20-product-tab-empty-viewer.png
- Layout: heading, box line, "No delivery methods configured. Add them in the Shipping section."; no table, no Save; the viewer shows no notice. Matches.

### component:size-check-badge (✓ Match)
- Mockup: analysis/design-context/mockups/product-shipping-tab-editor.html
- Screenshot: verification/screenshots/11-product-tab-warning-plural.png
- Variants: `tc-badge tc-badge--success` Fits, `tc-badge tc-badge--danger` Too large, `tc-badge` Inactive, plain text No limits / No dimensions. Classes verified in the DOM. Matches.

### component:inline-edit-row (✓ Match)
- Mockup: analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html
- Screenshot: verification/screenshots/04-config-carrier-inline-edit.png, verification/screenshots/05-config-method-inline-edit.png
- Cells switch to `tc-input` / `tc-select` with `tc-primary-button` Save and `tc-ghost-button` Cancel. Matches.
