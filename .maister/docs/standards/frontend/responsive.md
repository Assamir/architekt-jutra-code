## Responsive Design

### Mobile-First
Start with mobile layout and progressively enhance for larger screens.

### Standard Breakpoints
Use consistent breakpoints (mobile, tablet, desktop) across the application.

### Fluid Layouts
Use percentage-based widths and flexible containers that adapt to screen size.

### Relative Units (Optional)
rem/em are welcome where they help scaling (e.g. typography), but they are no longer mandatory. px literals in Chakra style props (`fontSize="24px"`, `mt="4px"`) are acceptable in the host frontend; keep spacing and sizes consistent with existing pages. Team decision (standards discovery 2026-11): relaxed from the earlier rem/em requirement to match established code.

Source: src/main/frontend/src/pages/*.tsx (24 of 33 page/component files use px literals), team decision

### Cross-Device Testing
Test across multiple screen sizes to ensure a balanced experience.

### Touch-Friendly
Size tap targets appropriately (minimum 44x44px) for mobile users.

### Mobile Performance
Optimize images and assets for mobile network conditions.

### Readable Typography
Maintain readable font sizes across all breakpoints.

### Content Priority
Show the most important content first on smaller screens.
