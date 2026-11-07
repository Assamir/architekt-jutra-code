# Workflow Summary — Shipping Plugin

## TL;DR
The new iframe plugin `plugins/shipping` (port 3010, strictPort) lets editors manage carriers and delivery methods. On each product they can untick unsuitable methods, with a warning when the box-size dimensions are too large. Verification passed with issues: 0 critical, and the only warning was browser coverage, which E2E then delivered (23/23 scenarios). Host changes: the `truck` icon, and plugin menu items are now visible to every logged-in user. Nothing is committed.

## Key Decisions
- Opt-out model: per-product pluginData `{disabledMethodIds}`. Every active method is available unless an editor unticks it.
- Inline edit keeps the same objectId, so per-product opt-outs survive renames and limit changes.
- The size check only warns, never auto-disables. The box may be rotated, and a missing limit means unlimited. There is no weight limit.
- The admin role is PERMISSION_EDIT. The UI gates on a base64url-safe JWT decode, and the backend already enforces EDIT, so there are no backend changes.
- Ports: 3004–3009 are for small plugins and 3010+ for business modules (shipping 3010, next 3011). This is documented in six places.
- Host Sidebar: plugin menu items are shown to all logged-in users, while the "Plugins" admin page stays PLUGIN_MANAGEMENT-only. This was found during E2E.

## Open Questions / Risks
- Manual check pending: in Chrome, enter `1e999` in a limit field. The error "Limits must be positive numbers." should appear; jsdom cannot test this.
- Root `CLAUDE.md` mixes this task's one-line Plugins edit with your earlier uncommitted edits (+77 lines). Stage it deliberately.
- `.playwright-mcp/` (raw E2E output, untracked) is left in the repo root. Delete it before committing; the hook blocked automated removal.
- The shipping and box-size manifests are now registered in your local DB. The E2E run registered box-size, which had not been registered before.

## Phases
| Phase | Result |
|---|---|
| 1 Codebase analysis | ✅ host already supports everything; truck icon gap found |
| 2 Gap analysis | ✅ critical gap (no edit → lost opt-outs) → inline edit added |
| 3 TDD red | ⏭ skipped (no defect) |
| 4 Mockups | ✅ 8 screens, 2 components (approved) |
| 5 Specification | ✅ 28 requirements |
| 6 Spec audit | ✅ pass-with-concerns → all 12 findings applied |
| 7 Planning | ✅ 7 groups, 44 steps |
| 8 Implementation | ✅ 7/7 groups in 5 waves |
| 9 TDD green | ⏭ skipped |
| 10 Verification options | ✅ completeness + tests + production readiness; E2E; docs |
| 11 Verification | ⚠️ passed with issues; 7 fixes; cycle 2: production GO, 0 critical |
| 12 E2E | ✅ 23/23 scenarios; visual fidelity 9 match / 1 minor / 0 drift; 2 follow-up fixes |
| 13 User docs | ✅ documentation/user-guide.md (15 real screenshots) |
| 14 Finalization | ✅ this summary |

## Final Test State
- Plugin: 16/16 (vitest 5.1.0). The build passes under `tsc -b` and is clean. `npm audit`: 0.
- Host frontend: 81/83. The 2 failures (`auth.test.tsx`, `extension-points.test.tsx` "renders plugin filter iframes") are pre-existing and fail identically on HEAD.
- Lint: 16 problems, equal to the baseline of 16. Backend: not run (no backend changes).

## Changed Files
- New: `plugins/shipping/**` (package.json, package-lock.json, index.html, manifest.json, tsconfig.json, vite.config.ts, src/main.tsx, src/vite-env.d.ts, src/domain.ts, src/domain.test.ts, src/pages/ShippingConfigPage.tsx, src/pages/ProductShippingTab.tsx), `src/main/frontend/src/test/icons.test.ts`
- Modified (host): `src/main/frontend/src/components/shared/Icons.tsx`, `src/main/frontend/src/components/layout/Sidebar.tsx`, `src/main/frontend/src/test/extension-points.test.tsx`, `.gitignore`
- Modified (docs): `.maister/docs/standards/plugins/plugin-development.md`, `.maister/docs/INDEX.md`, `.maister/docs/project/tech-stack.md`, `plugins/CLAUDE.md`, `README.md`, `CLAUDE.md` (one line, plus your earlier uncommitted edits)
- Task artifacts: `.maister/tasks/development/2026-11-07-shipping-plugin/`

## Follow-ups (out of scope)
- `PluginContext` does not refetch plugins after login, so menu items appear only after a reload.
- `.tc-plugin p` overrides the `.tc-error` colour in `plugin-ui.css`, so error and hint text renders grey in every plugin.
- The host `resolveIcon` fallback is broken for icons not in the map, so it always shows Plug.
- Two host tests are stale or broken (auth, extension-points filter iframes). The ai-description `npm test` script fails on Windows.
- Warehouse has 6 high-severity vulnerabilities, including runtime react-router-dom 7.13.2. The warehouse and box-size `dist/` folders are still tracked in git.
- Optional: show restrictions on the product list or detail info; add unit tests for the shared helpers; disable form inputs while busy; document the Node ≥22.12 requirement for vitest 5.
