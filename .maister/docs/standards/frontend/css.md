## CSS

### Consistent Methodology
Stick to the project's chosen approach (Tailwind, BEM, CSS modules, etc.) across the entire codebase.

### Work With the Framework
Use framework patterns as intended rather than fighting them with excessive overrides.

### Design Tokens
Establish and document consistent values for colors, spacing, and typography.

### Minimize Custom CSS
Prefer framework utilities to reduce custom styling maintenance.

### Production Optimization
Use CSS purging or tree-shaking to remove unused styles.

### Theme Tokens, Never Inline Hex
Host frontend components style with Chakra UI v3 style props. Never inline hex colors, and never port hex values from mockups; verification flags inline hex as a violation. Prefer the theme tokens defined in `src/main/frontend/src/theme/index.ts`: `brand.*`, `accent.*`, semantic tokens and CSS vars. Raw Chakra palette values (`gray.*`, `red.*`, `white`) are allowed, and px literals in Chakra style props are acceptable (team decision).

```tsx
<Text color="#64748B">         // avoid
<Text color="gray.500">        // ok
<Button bg="brand.500">        // preferred for brand surfaces
```

Source: .maister/tasks/development/2026-05-20-footprint-calc-engine-ui/ (analysis, implementation-plan, work-log), src/main/frontend/src/theme/index.ts, src/main/frontend/src/pages/*.tsx
