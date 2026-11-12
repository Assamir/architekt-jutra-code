# Visual Fidelity Report

**Mode**: Report-only (does NOT gate completion)
**Comparison**: LLM-judged structural match (not pixel-perfect)
**Source**: analysis/design-context/INDEX.md
**Captured**: verification/screenshots/ (`e2e-*` app captures; `e2e-mockup-*` renders of the source mockups)

## TL;DR
The implementation follows the 7 binding mockups closely. Layout regions, header composition, section cards, badge colour semantics and copy text all match.
Of the 9 design IDs: 8 match (✓), 1 has a minor deviation (⚠, a coverage gap only), and none show substantive drift (✗).
Every visible difference traces to an amendment in the spec: the native `<progress>` replaces the spinner, peer numbers are illustrative, LLM-dependent price layouts, the error-state header rule, and the "Priced as" line.
The only visual noise is the Next.js dev indicator overlapping the bottom-left of plugin iframes in dev mode.

## Key Decisions
- **What counted as a match.** A screen matches when its structure, copy and class semantics match. LLM-produced values (verdicts, numbers, issue texts) and the spec-declared illustrative values (peer numbers, thin-space grouping) were not compared.
- **Which screens were captured.** The needs-review and looks-good screens were judged on whichever stored record showed the layout. Vistara's price is FAIR (compact), so Skylink was used for the detailed price table.
- **Stubbed states.** The auto-running and error screens were captured with a stubbed `/api/analyze` (no LLM cost).

## Open Questions / Risks
- `screen:analysis-tab-viewer-saved-result`: the stale-note variant ("Product changed since this analysis…") was not reachable without editing a product, so that note is unverified visually.

## Summary
- Total screens compared: 9 (7 screens + 2 components)
- Match (✓): 8
- Minor deviation (⚠): 1
- Substantive drift (✗): 0

## Per-Screen Comparison

### screen:analysis-tab-needs-review-editor (✓ Match)
- Mockup: analysis/design-context/mockups/analysis-tab-needs-review-editor.html (render: `screenshots/e2e-mockup-analysis-tab-needs-review-editor.png`)
- Screenshot: `screenshots/e2e-10-editor-vistara-stored.png`, `screenshots/e2e-54-editor-tab-p16-price-detailed.png`
- **Layout and header.** Title and subtitle on the left; danger "Needs review" and the primary "Re-analyze" on the right. They match.
- **Section cards.** Each is a `tc-card tc-section` with an h4 and badges in a space-between row. They match.
- **Description.** POOR (danger) plus "Score 3/10" (plain), then "Issues" and "Suggestions" lists. Matches.
- **Category.** MISMATCH (danger), a table with "Assigned category" and "Suggested category" rows (the suggested value in bold), then the reasoning. Matches.
- **Price.** The detailed Metric | USD table with "Product price / Estimated market range / Catalog peers ({category}, {n}) min / median / max" plus the reasoning matches (Skylink). Vistara's price is FAIR, so its section is compact, which the per-section layout rule requires.
- **Spec-sanctioned differences.**
  - Peer values come from the assigned category (Smart Home 179.99 / 179.99 / 279.99), not the mockup's illustrative Televisions numbers.
  - Number grouping is `en-US` ("2,799.00", not "2 799.00").
  - A "Priced as" 12px line is added (deviation a).

### screen:analysis-tab-looks-good (✓ Match)
- Mockup: analysis/design-context/mockups/analysis-tab-looks-good.html (render: `screenshots/e2e-mockup-analysis-tab-looks-good.png`)
- Screenshot: `screenshots/e2e-52-editor-tab-p1.png`, `screenshots/e2e-03-editor-auto-run-result.png`
- Header: success "Looks good" plus Re-analyze. Matches.
- Compact sections:
  - Description GOOD shows the summary sentence.
  - Category FITS shows "Assigned: Televisions — {reasoning}".
  - Price FAIR shows "USD 1,799.99 vs market USD 1,300.00–2,200.00 · peers 1,299.99 / 1,749.99 / 2,199.99".
  - All match the mockup structure.
- Peers are the corrected values from the spec (self excluded: Televisions 2 → 1,299.99 / 1,749.99 / 2,199.99).
- The product-2 capture shows the rule-driven variant: a detailed ACCEPTABLE description section inside an overall "Looks good" result, as the spec states.

### screen:analysis-tab-auto-running (✓ Match)
- Mockup: analysis/design-context/mockups/analysis-tab-auto-running.html (render: `screenshots/e2e-mockup-analysis-tab-auto-running.png`)
- Screenshot: `screenshots/e2e-30-editor-auto-running-mocked.png` (auto, stubbed), `screenshots/e2e-11-editor-vistara-reanalyze-running.png` (manual, live)
- **Header.** Plain "Analyzing…" badge, with Re-analyze visible but `disabled` (rendered faded). Matches.
- **Running card.** Indeterminate `<progress aria-label="Analysis in progress">` (replaces the mockup spinner, per spec), "Analyzing this offer…", the 5–15 seconds helper, and a reason line. All match.
- **Reason line.** The auto-run on a never-analyzed product shows "Started automatically: this product has not been analyzed yet." (the mockup shows the content-changed variant). It is absent on the manual Re-analyze, per spec.
- **Subtitle.** Shown only when a previous analysis exists (Vistara 20:39), absent for product 3. Correct per the header rule.

### screen:analysis-tab-viewer-saved-result (⚠ Minor Deviation)
- Mockup: analysis/design-context/mockups/analysis-tab-viewer-saved-result.html (render: `screenshots/e2e-mockup-analysis-tab-viewer-saved-result.png`)
- Screenshot: `screenshots/e2e-41-viewer-vistara-tab.png`
- Saved result with header, danger badge and the same section cards as the editor, but no Re-analyze button. Matches.
- **Deviation (coverage, not drift).** The mockup's stale note "Product changed since this analysis. Only editors can run a new analysis." could not be shown, because the viewed analysis was current. Its presence for stale records is unverified in this run.
- Recommendation: check it once after an intentional product edit, viewing as `viewer` before an editor reopens the tab.

### screen:analysis-tab-viewer-no-analysis (✓ Match)
- Mockup: analysis/design-context/mockups/analysis-tab-viewer-no-analysis.html (render: `screenshots/e2e-mockup-analysis-tab-viewer-no-analysis.png`)
- Screenshot: `screenshots/e2e-42-viewer-not-analyzed-tab.png`
- Title "AI Offer Analysis" plus a single `tc-card` with "Not analyzed yet" / "An editor needs to open this tab to run the first analysis.", and no buttons. The copy matches exactly.

### screen:analysis-tab-error (✓ Match)
- Mockup: analysis/design-context/mockups/analysis-tab-error.html (render: `screenshots/e2e-mockup-analysis-tab-error.png`)
- Screenshot: `screenshots/e2e-31-editor-auto-run-error-mocked.png`, `screenshots/e2e-32-editor-retry-content-blocked-mocked.png`
- **Error card.** A `tc-error` message, the CONTENT_BLOCKED hint (copy identical to the mockup) and a primary "Retry". Matches.
- **Header.** Plain "Not analyzed" with no subtitle. This follows the spec's superseding rule (the mockup's subtitle next to "Not analyzed" is explicitly overridden). The header Re-analyze is hidden (deviation g).
- **Hint.** The LLM_UNAVAILABLE variant correctly omits it.
- Not exercised: the variant with a previous analysis rendered below the card.

### screen:info-badge-states (✓ Match)
- Mockup: analysis/design-context/mockups/info-badge-states.html (render: `screenshots/e2e-mockup-info-badge-states.png`)
- Screenshot: `screenshots/e2e-51-editor-details-badge-p16.png` (needs review), `screenshots/e2e-50-editor-details-badge-p1.png` (looks good), `screenshots/e2e-01-editor-details-not-analyzed.png` (not analyzed yet), `screenshots/e2e-20-list-filter-needs-review.png` (list filter annotation)
- All three states match:
  - danger "AI review: Needs review" plus a 12px reasons span ("price −90% below market · category mismatch");
  - success "AI review: Looks good";
  - plain "AI review: not analyzed yet".
- The host-native "AI: needs review" switch and its `pluginFilter=ai-offer-analysis:needsReview:bool:true` query match the annotation.
- Dev-only noise: the Next.js dev indicator overlaps the first letters of the badge (see the e2e report, §5.4 #1).

### component:verdict-badge (✓ Match)
- Mockup: analysis/design-context/mockups/analysis-tab-needs-review-editor.html
- Screenshot: `screenshots/e2e-10-editor-vistara-stored.png`, `screenshots/e2e-53-editor-tab-p16.png`, `screenshots/e2e-03-editor-auto-run-result.png`
- **Badge classes seen in the DOM.**

  | Class | Verdicts and badges |
  |---|---|
  | `tc-badge--danger` | POOR, MISMATCH, BELOW MARKET −90% (flagged) |
  | `tc-badge--success` | GOOD, FITS, FAIR +3% / +12% / −13% |
  | plain `tc-badge` | ACCEPTABLE, "Score n/10", "Confidence: …", "Analyzing…", "Not analyzed" |

- Verdict text is always present, and the Unicode minus is used. Matches the mapping.

### component:analysis-section-card (✓ Match)
- Mockup: analysis/design-context/mockups/analysis-tab-needs-review-editor.html
- Screenshot: `screenshots/e2e-54-editor-tab-p16-price-detailed.png`
- `tc-card tc-section`, an h4 title with badges in a space-between `tc-flex` row, then `tc-table` facts and a reasoning paragraph. Matches.
- DOM check: the tab uses only `tc-plugin, tc-flex, tc-badge(--danger|--success), tc-primary-button, tc-card, tc-section, tc-table` (plus `tc-error` in the error state). No inline hex or rgb colours, and the root padding is 16px.
