# Visual Coverage Matrix

## TL;DR
All 10 design items in `analysis/design-context/INDEX.md` (8 screens, 2 components) are covered. Group 3 (ShippingConfigPage) covers the 3 config screens and `component:inline-edit-row`. Group 4 (ProductShippingTab) covers the 5 product-tab screens and `component:size-check-badge`. Nothing is uncovered.

## Key Decisions
- `component:size-check-badge` belongs to Group 4 (rendering), while its `sizeCheck` logic is tested in Group 1. The badge is UI, so Group 4 holds the visual contract.
- Mockup sample data (row order, hint names) does not bind the plan; the spec rules win. The acceptance criteria in the plan say so explicitly.

Source: `analysis/design-context/INDEX.md`

| Screen/Component ID | Covered By Task Group(s) | Status |
|---------------------|--------------------------|--------|
| screen:shipping-config | Group 3 (Shipping Configuration Page) | ✅ |
| screen:shipping-config-edit | Group 3 (Shipping Configuration Page) | ✅ |
| screen:shipping-config-readonly | Group 3 (Shipping Configuration Page) | ✅ |
| screen:product-shipping-tab | Group 4 (Product Shipping Tab) | ✅ |
| screen:product-shipping-tab-warning | Group 4 (Product Shipping Tab) | ✅ |
| screen:product-shipping-tab-nodims | Group 4 (Product Shipping Tab) | ✅ |
| screen:product-shipping-tab-readonly | Group 4 (Product Shipping Tab) | ✅ |
| screen:product-shipping-tab-empty | Group 4 (Product Shipping Tab) | ✅ |
| component:size-check-badge | Group 4 (Product Shipping Tab); logic in Group 1 (`sizeCheck`) | ✅ |
| component:inline-edit-row | Group 3 (Shipping Configuration Page) | ✅ |

## Uncovered Items

All screens covered (10/10).
