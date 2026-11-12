# Visual Coverage Matrix

Source: `analysis/design-context/INDEX.md`

| Screen/Component ID | Covered By Task Group(s) | Status |
|---------------------|--------------------------|--------|
| screen:analysis-tab-needs-review-editor | Group 5 (Plugin UI Pages: `product-tab.tsx`); helpers from Group 3 (Domain Logic) | ✅ |
| screen:analysis-tab-looks-good | Group 5 (`product-tab.tsx`, compact section layout) | ✅ |
| screen:analysis-tab-auto-running | Group 5 (`product-tab.tsx`, visibility-gated auto-run and running card) | ✅ |
| screen:analysis-tab-viewer-saved-result | Group 5 (`product-tab.tsx`, viewer stale note, no Re-analyze) | ✅ |
| screen:analysis-tab-viewer-no-analysis | Group 5 (`product-tab.tsx`, "Not analyzed yet" card) | ✅ |
| screen:analysis-tab-error | Group 5 (`product-tab.tsx`, error card and Retry); Group 4 (Analyze API Route, the sanitized `{error, code}` messages it shows) | ✅ |
| screen:info-badge-states | Group 5 (`product-badge.tsx`, 3 states and BroadcastChannel refresh); Group 1 (`manifest.json` `product.list.filters` entry for the host-native "AI: needs review" switch); Group 6 (seeded manifest JSONB) | ✅ |
| component:verdict-badge | Group 5 (rendering in tab and badge); Group 3 (`verdictBadgeClass`, `verdictLabel`, `priceBadgeLabel`, covered by domain test 9) | ✅ |
| component:analysis-section-card | Group 5 (`product-tab.tsx` section cards, per-section compact/detailed layout via `isCompactSection`) | ✅ |

## Uncovered Items

All 9 screens and components in `analysis/design-context/INDEX.md` are covered.

## Superseded Mockup Details (spec "Visual Design")

The spec marks some mockup values as illustrative. These are implemented the spec's way, not the mockup's:

- **Peer numbers.** Peers always come from the assigned category, with the product itself excluded.
- **Number grouping.** Numbers use `en-US` with 2 decimals instead of the mockups' thin-space grouping.
- **Error-state subtitle.** It appears only when there is a previous analysis.
- **Spinner.** The CSS spinner is replaced by a native indeterminate `<progress>`.
- **Inline hex colors.** The mockup's inline hex colors are not copied.
