# Design Context Index

## TL;DR
Seven HTML mockups: six states of the AI Offer Analysis product tab (600px iframe) and the 60px info badge in three states. The host-native "AI: needs review" list filter is shown as an annotation. All mockups use plugin-ui.css `tc-*` classes.

| ID | Type | Source | Description |
|----|------|--------|-------------|
| screen:analysis-tab-needs-review-editor | screen | analysis/design-context/mockups/analysis-tab-needs-review-editor.html | Tab result for a flawed product (editor): overall Needs review badge, Re-analyze, description/category/price sections |
| screen:analysis-tab-looks-good | screen | analysis/design-context/mockups/analysis-tab-looks-good.html | Tab result where all checks pass (compact sections) |
| screen:analysis-tab-auto-running | screen | analysis/design-context/mockups/analysis-tab-auto-running.html | Auto-run in progress on tab open (no analysis or fingerprint changed) |
| screen:analysis-tab-viewer-saved-result | screen | analysis/design-context/mockups/analysis-tab-viewer-saved-result.html | READ-only user sees stale saved result + "Only editors can run" note, no Re-analyze |
| screen:analysis-tab-viewer-no-analysis | screen | analysis/design-context/mockups/analysis-tab-viewer-no-analysis.html | READ-only user, nothing analyzed yet |
| screen:analysis-tab-error | screen | analysis/design-context/mockups/analysis-tab-error.html | Gateway block/unavailable → user-facing error + Retry (editor) |
| screen:info-badge-states | screen | analysis/design-context/mockups/info-badge-states.html | product.detail.info 60px badge: Needs review / Looks good / not analyzed; list filter annotation |
| component:verdict-badge | component | analysis/design-context/mockups/analysis-tab-needs-review-editor.html | Verdict badge: tc-badge--danger (bad), tc-badge--success (good), plain tc-badge (neutral/uncertain) |
| component:analysis-section-card | component | analysis/design-context/mockups/analysis-tab-needs-review-editor.html | tc-card section with title + verdict badge(s) + tc-table facts + reasoning/lists |
