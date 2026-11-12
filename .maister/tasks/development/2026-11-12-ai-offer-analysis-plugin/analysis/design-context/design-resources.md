# Design Resources

## TL;DR
Plugin UI binds to the host's shared stylesheet `src/main/frontend/public/assets/plugin-ui.css`, loaded by the plugin from `http://localhost:8080/assets/plugin-ui.css`, and its `tc-*` classes; inline styles are allowed for layout only. The stylesheet has no warning/neutral badge variant, so neutral verdicts use a plain `tc-badge`. Reference UIs: the ai-description product tab and the box-size 60px info badge.

## Key Decisions
- Use only `tc-*` classes for visual styling: `tc-plugin`, `tc-card`, `tc-section`, `tc-flex`, `tc-table`, `tc-badge`, `tc-badge--success`, `tc-badge--danger`, `tc-primary-button`, `tc-ghost-button`, `tc-error` — this follows the plugin-development standard.
- Neutral and uncertain verdicts (ACCEPTABLE, QUESTIONABLE, INSUFFICIENT_DATA) use a plain `tc-badge` — the shared CSS has no warning class, and no new color tokens are added.

## Standards (binding)
- .maister/docs/standards/plugins/plugin-development.md — mandatory plugin-ui.css, `tc-*` classes, layout-only inline style
- .maister/docs/standards/frontend/css.md — no new hex colors; use the host's design language
- .maister/docs/standards/frontend/components.md — named function components, single responsibility
- .maister/docs/standards/frontend/accessibility.md — semantic HTML, labels, color not the only signal (verdict text shown alongside badge color)

## Design system
| Kind | Path | Note |
|------|------|------|
| css-var/classes | src/main/frontend/public/assets/plugin-ui.css | `tc-*` component classes; palette #2563EB primary, #0F172A text, #64748B muted, #E2E8F0 borders |
| component | plugins/ai-description/src/pages/product-tab.tsx | Tab layout: tc-plugin root, tc-card result, h4 sections + ul lists, tc-primary-button |
| component | plugins/box-size/src/pages/ProductBoxBadge.tsx | Compact 60px product.detail.info badge pattern |

## Skill hints
- design:* skills available, but not needed — the host design language is fully defined by plugin-ui.css.
