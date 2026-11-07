# Test Suite Results (Re-verification Cycle 2)

## TL;DR
- Status: **passed_with_issues**. 96/98 tests pass (97.96%).
- Shipping plugin: 16/16 pass, now on vitest v5.1.0.
- Host frontend: 80/82 pass. Both failures are already known and also fail on HEAD; neither is in code this task touched.
- No regressions. The counts match cycle 1 exactly.
- Backend (Maven): not run, because there are no backend changes.

## Open Questions / Risks
- Two host tests still fail and were failing before this task (`auth.test.tsx`, `extension-points.test.tsx`). Someone needs to own them separately, or they will keep hiding real regressions in those two files.

## Changes since cycle 1
| | Cycle 1 | Cycle 2 |
|---|---|---|
| Shipping plugin | 16/16 | 16/16 (vitest 3 -> 5.1.0; helpers moved to `src/domain.ts`; UI disabled-state fixes) |
| Host frontend | 80/82 | 80/82 (`icons.test.ts`: only test names changed) |
| Total | 96/98 | 96/98 |
| Failing tests | same 2, already known | same 2, already known |

None of the cycle-2 fixes added a regression, and the vitest major upgrade did not change the plugin's test results.

## Commands
| Suite | Command | Result | Duration |
|---|---|---|---|
| Shipping plugin | `cd plugins/shipping && npm test` (vitest run, v5.1.0) | 1 file, 16 passed / 0 failed | ~0.2s |
| Host frontend | `cd src/main/frontend && npx vitest run` | 16 files (14 passed, 2 failed); 82 tests (80 passed, 2 failed) | ~11.5s |
| Backend | `./mvnw test` | not run, no backend changes | - |

## Metrics
| Metric | Value |
|---|---|
| Total | 98 |
| Passing | 96 |
| Failing | 2 |
| Errors | 0 |
| Skipped | 0 |
| Pass rate | 97.96% |

## Failures
| Test | File | Error | Type | Related to implementation | Regression risk |
|---|---|---|---|---|---|
| AuthContext > provides token/permissions/login/logout via useAuth hook | `src/main/frontend/src/test/auth.test.tsx:192` | `toHaveTextContent(mockToken)` received `none` | unit (component) | No. It already fails against committed HEAD `Icons.tsx` | low (already known) |
| ProductListPage with plugin filters > renders plugin filter iframes in filter bar | `src/main/frontend/src/test/extension-points.test.tsx:200` | `expected 0 to be greater than or equal to 1` (no iframes; the host now renders `product.list.filters` itself instead of as an iframe) | unit (component) | No. It already fails against committed HEAD | low (already known) |

## Regressions
Count: 0.

## Notes
- The host run prints React `act(...)` warnings to stderr (for example in `foundation.test.tsx` for `PluginProvider`). These are noise; no test fails because of them.

## Issues
| Severity | Description | Location | Fixable | Suggestion |
|---|---|---|---|---|
| info | Known failure, already present before this task: AuthContext token assertion | `src/main/frontend/src/test/auth.test.tsx` | false (outside this task) | Track and fix separately. Check that the mock JWT `exp` and the token-parsing logic agree |
| info | Known failure, already present before this task: the test expects filter iframes, but `product.list.filters` is now rendered by the host | `src/main/frontend/src/test/extension-points.test.tsx` | false (outside this task) | Update the test to assert on the host-rendered filter controls |

Issue counts: critical 0, warning 0, info 2.
