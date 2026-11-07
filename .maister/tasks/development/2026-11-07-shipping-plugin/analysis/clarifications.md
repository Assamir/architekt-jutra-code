# Clarifications (Phase 1)

## TL;DR
The user confirmed the plan-mode decisions and settled three open points: add `truck` to the host ICON_MAP (the only host change), add Vitest tests for the plugin's pure domain helpers, and no seed/default carriers.

## Key Decisions
- Opt-out model: per-product pluginData `{disabledMethodIds}`, all active methods available by default — matches "admin must be able to disable" wording.
- Methods carry optional max L×W×H (cm); product tab warns based on box-size dims, never auto-disables — admin keeps control.
- Admin = PERMISSION_EDIT; UI hides write controls via JWT `permissions` contains "EDIT"; backend already enforces — no backend changes.
- Port 3010 with strictPort; docs updated to reserve 3010+ for new business modules — user requirement (separation from future modules).
- No weight limit — products/box-size carry no weight, so it cannot be checked.
- Host change: add `truck: Truck` to `ICON_MAP` in `src/main/frontend/src/components/shared/Icons.tsx` + a Vitest case — dynamic lookup fails for forwardRef lucide icons.
- Plugin tests: Vitest devDependency + `npm test`, covering domain.ts helpers (fit check with rotation, mappers, canEdit) — UI verified via build + browser.
- No seeding of default carriers — admin adds them manually (minimal implementation).
- Deleting a carrier that still has methods is blocked; box may be rotated in the fit check (sorted-dimension comparison) — from initial plan.

## Q&A
1. Restriction model? → Opt-out list.
2. Size limits? → Limits + warning (no auto-disable).
3. Admin? → PERMISSION_EDIT.
4. Port docs? → 3010 + update docs.
5. Truck icon? → Add truck to ICON_MAP.
6. Tests? → Vitest for domain.ts.
7. Seed data? → No seeding.
