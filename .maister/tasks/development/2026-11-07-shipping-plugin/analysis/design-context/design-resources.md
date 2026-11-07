# Design Resources

## TL;DR
Mockups bind to the host's `plugin-ui.css` (`tc-*` classes, embedded verbatim in the mockups) and the host Chakra theme (`brand.*` blue, `accent.*` amber, body `#FAFAF9`). Icons come from Lucide (sidebar uses `truck`). Frontend standards apply: no inline hex in plugin code, `tc-*` for standard UI, inline style for layout only.

## Standards (binding)
- .maister/docs/standards/plugins/plugin-development.md — mandatory plugin-ui.css, tc-* classes, inline style only for layout
- .maister/docs/standards/frontend/css.md — theme tokens, never inline hex colors
- .maister/docs/standards/frontend/components.md
- .maister/docs/standards/frontend/accessibility.md — labels, keyboard, contrast
- .maister/docs/standards/frontend/responsive.md

## Design system
- css-var/classes: src/main/frontend/public/assets/plugin-ui.css — tc-plugin, tc-primary-button, tc-ghost-button(--danger), tc-input, tc-select, tc-table, tc-card, tc-badge(--success|--danger), tc-section, tc-flex, tc-error. No checkbox/muted/warning classes → native checkbox in label.tc-flex.
- theme: src/main/frontend/src/theme/index.ts — brand/accent tokens (host shell only)
- component-lib: Chakra UI v3 (host shell)
- icon-lib: lucide-react (host sidebar; `truck` added to ICON_MAP)

## Skill hints
- design:design-critique, design:accessibility-review available (not consulted; tc-* system is fixed)
