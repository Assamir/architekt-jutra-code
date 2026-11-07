# Design Context Index

## TL;DR
8 HTML mockup screens for the shipping plugin: 3 variants of the Shipping configuration page (editor, inline edit + blocked delete, read-only) and 5 of the product Shipping tab (editor, too-large warning, no box dimensions, read-only, empty state). All use host plugin-ui.css tc-* classes.

| ID | Type | Source | Description |
|----|------|--------|-------------|
| screen:shipping-config | screen | analysis/design-context/mockups/shipping-configuration-editor.html | menu.main Shipping page: carriers + delivery methods tables with add forms, active toggles, edit/delete |
| screen:shipping-config-edit | screen | analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html | Inline edit rows (same id) and tc-error when deleting a carrier with methods |
| screen:shipping-config-readonly | screen | analysis/design-context/mockups/shipping-configuration-read-only-viewer.html | Viewer without EDIT: tables only, status badges, no controls |
| screen:product-shipping-tab | screen | analysis/design-context/mockups/product-shipping-tab-editor.html | product.detail.tabs Shipping: box line, availability checkboxes, size-check badges, Save |
| screen:product-shipping-tab-warning | screen | analysis/design-context/mockups/product-shipping-tab-too-large-method-still-enabled.html | Hint when a too-large method is still enabled (warn only) |
| screen:product-shipping-tab-nodims | screen | analysis/design-context/mockups/product-shipping-tab-no-box-dimensions.html | No box-size data: "No dimensions" badges, no warnings |
| screen:product-shipping-tab-readonly | screen | analysis/design-context/mockups/product-shipping-tab-read-only-viewer.html | Viewer: disabled checkboxes, no Save |
| screen:product-shipping-tab-empty | screen | analysis/design-context/mockups/product-shipping-tab-empty-state.html | Empty state hint "No delivery methods configured…" |
| component:size-check-badge | component | analysis/design-context/mockups/product-shipping-tab-editor.html | tc-badge--success Fits / tc-badge--danger Too large / tc-badge Inactive / muted No limits, No dimensions |
| component:inline-edit-row | component | analysis/design-context/mockups/shipping-configuration-inline-edit-and-blocked-delete.html | Table row switching to tc-input/tc-select with Save/Cancel |
