# Production Readiness Report

**Date**: 2026-11-07
**Path**: `plugins/shipping/` + `src/main/frontend/src/components/shared/Icons.tsx` + root `.gitignore`
**Target**: production, judged against the project's own deployment model (local dev only, no CI/CD, per `.maister/docs/project/tech-stack.md`)
**Status**: Ready (for the local-dev model)
**Cycle**: re-verification cycle 2 (cycle 1 verdict: GO WITH MITIGATIONS)

## TL;DR
- **GO** for the local-dev model. W-1, W-2 and I-8 are resolved; 0 blockers, 0 warnings, 9 info items remain (I-1 to I-7, I-9 carried over, plus new I-10 and I-11).
- `npm audit` and `npm audit --omit=dev` both report **0 vulnerabilities** (read-only re-run; vitest 5.1.0, @vitest/mocker 5.1.0, tinypool no longer present).
- `plugins/*/dist/` is in `.gitignore` (line 43) and `git check-ignore` confirms it matches `plugins/shipping/dist/`. The warehouse and box-size dists are still tracked (outside this task).
- The fixes introduced no regressions: 16/16 vitest tests pass on v5.1.0, `tsc --noEmit` is clean, and the busy/saving guards are correct. Two minor new info items: vitest 5 needs Node `^22.12 || ^24`, and edit-row inputs stay editable while a save is running.

## Open Questions / Risks
- If the platform ever moves beyond local dev, every plugin needs a configurable host origin, a configurable manifest url and HTTPS (platform-wide, unchanged from cycle 1).
- `.gitignore` does not untrack the already committed `plugins/warehouse/dist` and `plugins/box-size/dist` (4 files). Whether to `git rm --cached` them is a separate decision.
- The warehouse plugin's runtime `react-router-dom@7.13.2` (I-9) is still an open follow-up outside this task.

## Re-verification (cycle 2)

### W-1: vitest dev-only CVEs. Resolved
- `plugins/shipping/package.json` devDependency is now `vitest ^5.1.0`. The lockfile resolves `vitest 5.1.0` and `@vitest/mocker 5.1.0`, and `npm ls` shows no `tinypool` at all.
- `npm audit` reports `found 0 vulnerabilities`. `npm audit --omit=dev` reports `found 0 vulnerabilities`. Both ran read-only, with no `audit fix`.
- Runtime dependencies are unchanged and clean: react 19.3.1, react-router-dom 7.18.5. vite stays at 8.3.6.
- The upgrade was checked: `npx vitest run` shows `RUN v5.1.0`, 1 file, **16 passed**.

### W-2: `plugins/*/dist` not ignored. Resolved
- `.gitignore:43` now has `plugins/*/dist/` in the Plugins section. `git check-ignore -v plugins/shipping/dist/index.html` matches that rule.
- `plugins/shipping/dist` does not exist, and `git status` shows `plugins/shipping/` as one untracked directory, so nothing built would be committed.
- Remaining (outside this task, info): the 4 files already tracked under `plugins/warehouse/dist` and `plugins/box-size/dist` stay tracked, because ignore rules do not apply to tracked files.

### I-8: "Saved!" timer not cleared. Resolved
- `ProductShippingTab.tsx` keeps the timer in `savedTimerRef`. It clears it before scheduling a new one in `handleSave`, and clears it on unmount (`useEffect(() => () => clearTimeout(savedTimerRef.current), [])`). Fast repeated saves no longer stack timers either.

### Other fixes and regression check
- **Shared helpers in `src/domain.ts`.** `errorMessage`, `compareNames`, `formatLimits`, `UNKNOWN_CARRIER` and the mappers are now imported by both pages. Nothing is duplicated, and `tsc --noEmit -p .` exits 0 with `noUnusedLocals` and `noUnusedParameters` on. The domain tests still pass.
- **Disabled while busy or saving.**
  - `ShippingConfigPage.tsx`: every action button and both list-row Active checkboxes use `disabled={busy}`. `mutate` resets `busy` in `finally`. `handleDeleteCarrier` resets `busy` on both early-return paths (list failure, blocked delete) before calling `mutate`.
  - `ProductShippingTab.tsx`: the Save button and the availability checkboxes use `disabled={saving}`. `savingRef` still blocks a focus-triggered refresh during a save. Refresh does not touch `disabledIds`, so a save cannot race with it.
  - Errors are still caught and shown on every path, so the cycle-1 error-handling table still holds.
- **New, minor (info):** see I-10 and I-11 below. Neither is a correctness or security problem.

## Executive Summary
- **Recommendation**: GO (local-dev model)
- **Overall Readiness**: 92%
- **Deployment Risk**: Low (local dev). It would be High if exposed publicly, for platform reasons unrelated to this plugin.
- **Blockers**: 0. **Concerns**: 0. **Recommendations**: 9

## Category Breakdown
| Category | Score | Status | Notes |
|----------|-------|--------|-------|
| Configuration | 85% | OK | No change. Localhost URLs follow convention. Port 3010 with `strictPort` is free. |
| Monitoring | N/A | N/A (platform) | Static iframe plugin. |
| Resilience | 95% | Good | I-8 is fixed. Controls are disabled during mutations. |
| Performance | 90% | Good | No change. |
| Security | 95% | Good | The audit is clean, both dev and runtime. Backend enforces EDIT. |
| Deployment | 90% | Good | `dist` is ignored. Manifest registration is still manual and documented. |

## Findings carried over from cycle 1 (unchanged)
The cycle-1 analysis of error handling (SDK timeout, 403/404, refresh failure, missing box-size data), configuration, security (backend `PERMISSION_EDIT` enforcement in `SecurityConfiguration.java`, fail-closed `canEdit`, UUID object ids, no XSS sinks, postMessage origin check) and data integrity (last-write-wins, pruning of stale `disabledMethodIds`, carrier delete guard) was re-checked against the current source. It still applies.

## Blockers (Must Fix)
None.

## Concerns (Should Fix)
None. W-1 and W-2 are resolved.

## Recommendations (Nice to Have)

| # | Severity | Category | Location | Issue | Fixable |
|---|---|---|---|---|---|
| I-1 | info | configuration | `plugins/shipping/index.html:7-8` | Hardcoded `http://localhost:8080` SDK and CSS. This is the existing convention. | false (platform-wide) |
| I-2 | info | configuration | `plugins/shipping/manifest.json` (`url`) | `http://localhost:3010`, plain HTTP, manual registration. | false (platform-wide) |
| I-3 | info | security | `ShippingConfigPage.tsx` (`crypto.randomUUID()`) | Needs a secure context. Fine on localhost. | true (only if the deployment model changes) |
| I-4 | info | resilience | `ProductShippingTab.tsx` (box line) | When box-size is not registered, the hint points to a "Box Size tab" that does not exist. | true |
| I-5 | info | resilience | `ProductShippingTab.tsx` (initial `load`) | All-or-nothing initial load. Spec-compliant. | true |
| I-6 | info | resilience | host `api/client.ts:7` | Error text can be terse (`Failed to save: 403`). Accepted. | true (host-side, out of scope) |
| I-7 | info | deployment | spec.md:30 | Last-write-wins on concurrent edits. Accepted. | false (needs backend versioning) |
| I-9 | info | security | `plugins/warehouse` (out of scope) | 6 high-severity vulns, including runtime `react-router-dom@7.13.2`. Also: its `dist` and box-size's `dist` are still tracked despite the new ignore rule. | true |
| I-10 | info (new) | deployment | `plugins/shipping/package.json` (`vitest ^5.1.0`) | vitest 5.1.0 declares `engines.node: ^22.12.0 \|\| ^24.0.0 \|\| >=26.0.0`. The local Node is v24.19.0, so it works. On Node 20 `npm test` would fail; the build (vite 8, needs `^20.19 \|\| >=22.12`) would not. The frontend-maven-plugin uses Node 22, and this plugin is not part of that build. | true (optionally document the Node >= 22.12 requirement for plugin tests) |
| I-11 | info (new) | resilience | `ShippingConfigPage.tsx` (`renderCarrierRow` / `renderMethodRow` edit branches, `renderLimitInputs`, add-form inputs) | While `busy`, the text inputs, selects and draft Active checkboxes in edit rows and add forms are still enabled; only buttons and list checkboxes are disabled. Edits typed during an in-flight save are dropped when the save succeeds (`setEditing(null)` / form reset). Also, `disabled` comes from state, so a same-frame double click could in theory start two mutations before the re-render (unlikely, and the effect is idempotent saves). | true (optional: also pass `disabled={busy}` to the draft inputs) |

I-8 is resolved and removed from the list.

### Not applicable (platform-level, by design for local dev)
Unchanged from cycle 1: health check endpoint, metrics, error tracking, graceful shutdown, connection pooling, rate limiting, request size limits, HTTPS, CORS, CSP and DB migrations. Rollback means reverting the plugin directory, the `Icons.tsx` change, the `.gitignore` line, and the manifest registration (`DELETE /api/plugins/shipping`).

## Next Steps
1. Commit `plugins/shipping/` (including `package-lock.json`). Confirm again that no `dist` is staged.
2. Register the manifest with a PLUGIN_MANAGEMENT token, then smoke-test both pages with an EDIT user and a READ user.
3. (I-9) Follow-up outside this task: `npm audit fix` in `plugins/warehouse`, and decide whether to untrack the warehouse and box-size `dist` directories.
4. (Optional) I-10 and I-11 polish.

## Structured Result
```yaml
status: "ready"
recommendation: "GO"
report_path: "C:\\code\\_repos\\architekt-jutra\\architekt-jutra-code\\.maister\\tasks\\development\\2026-11-07-shipping-plugin\\verification\\production-readiness-report.md"
overall_readiness: 92
deployment_risk: "low"
categories:
  configuration: { score: 85, status: "ok" }
  monitoring: { score: null, status: "n/a (platform)" }
  resilience: { score: 95, status: "good" }
  performance: { score: 90, status: "good" }
  security: { score: 95, status: "good" }
  deployment: { score: 90, status: "good" }
resolved: ["W-1", "W-2", "I-8"]
issues:
  - { source: "production_readiness", severity: "info", category: "deployment", description: "vitest 5.1.0 requires Node ^22.12 || ^24 || >=26 for npm test", location: "plugins/shipping/package.json", fixable: true, suggestion: "Document Node >= 22.12 for plugin tests" }
  - { source: "production_readiness", severity: "info", category: "resilience", description: "Edit-row and add-form inputs stay enabled while busy; edits typed during a save are discarded on success", location: "plugins/shipping/src/pages/ShippingConfigPage.tsx", fixable: true, suggestion: "Pass disabled={busy} to draft inputs, selects and checkboxes" }
  - { source: "production_readiness", severity: "info", category: "security", description: "Out of scope: warehouse vulns (runtime react-router-dom 7.13.2); warehouse/box-size dist still tracked", location: "plugins/warehouse, plugins/box-size", fixable: true, suggestion: "npm audit fix in warehouse; git rm --cached the tracked dists" }
  # I-1..I-7 carried over unchanged (see table)
issue_counts:
  critical: 0
  warning: 0
  info: 9
```
