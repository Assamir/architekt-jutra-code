# Test Suite Results: AI Offer Analysis plugin (re-verification after fixes)

## TL;DR
- Status: **passed_with_issues**. No regressions from this task.
- ai-offer-analysis: Jest 58/58 passed, `tsc --noEmit` clean. Host backend: 159/159 passed, and seed 013 (both changesets) ran in the test DB.
- 4 failures, all pre-existing and outside the change set: 2 in ai-description Jest (known before this task) and 2 in host frontend Vitest (deterministic, no frontend files changed).
- Pass rate across all suites: 304/308 = 98.7%.

## Key Decisions
- The host frontend failures count as **pre-existing, not regressions**. `git status` shows no changes under `src/main/frontend`, `src/main/java` or `src/test`. Both failing tests mock the API layer, so they cannot depend on the new plugin or the Liquibase seed. Re-running the 2 files on their own gave the same failures, so they are deterministic and not flaky.
- The ai-description failures are classified as pre-existing, as the orchestrator said (2 known-failing before this task). The plugin was not modified in this task.
- The backend ran with `-Dskip.npm -Dskip.installnodenpm -Dskip.jooq.generation=true`. The jOOQ sources already existed in `target/generated-sources/jooq`. The aj app running on :8080 was not touched, because the tests use TestContainers on a random port.
- No `.env` files were read and no LLM calls were made. The ai-offer-analysis and ai-description tests use mocked BAML clients.

## Open Questions / Risks
- 2 host frontend tests fail on master as it stands (see below). They are unrelated to this task, but someone should fix or update them separately.
- The ai-description error-handling tests (2) still fail. They are unrelated to this task.

---

## Status: passed_with_issues

| Suite | Command | Total | Pass | Fail | Skip | Result |
|---|---|---|---|---|---|---|
| ai-offer-analysis (Jest) | `cd plugins/ai-offer-analysis && node node_modules/jest/bin/jest.js` | 58 | 58 | 0 | 0 | PASS (2 suites: `analyze.test.ts`, `domain.test.ts`) |
| ai-offer-analysis (types) | `npx tsc --noEmit -p .` | n/a | n/a | 0 errors | n/a | PASS (exit 0) |
| Host backend (Maven) | `./mvnw test -Dskip.npm -Dskip.installnodenpm -Dskip.jooq.generation=true` | 159 | 159 | 0 | 0 | PASS (BUILD SUCCESS, 40.8 s) |
| Host frontend (Vitest) | `cd src/main/frontend && npx vitest run` | 83 | 81 | 2 | 0 | FAIL, pre-existing (16 files, 2 failed) |
| ai-description (Jest, regression) | `cd plugins/ai-description && node node_modules/jest/bin/jest.js` | 8 | 6 | 2 | 0 | FAIL, pre-existing / known |

**Overall:** total 308, passing 304, failing 4, errors 0, skipped 0, pass rate 98.7%.

## Feature-specific evidence
- ai-offer-analysis Jest: exactly 58 cases, as expected.
- Liquibase seed `013-insert-ai-offer-analysis-plugin.yaml` ran in the TestContainers DB during the backend run. The test profile has no context filter, so `dev` changesets run too. Both changesets ran successfully:
  - `013-insert-ai-offer-analysis-plugin::aj` (4 ms)
  - `013-insert-ai-offer-analysis-demo-products::aj` (5 ms)
- Backend classes that touch plugins all passed after the seed was applied: `PluginRegistryIntegrationTests` (6), `PluginDataAndObjectsIntegrationTests` (6), `PluginDatabaseTests` (4), `PluginGapTests` (6), `PluginObjectApiAndFilterTests` (6), `PluginObjectEntityBindingTests` (6), `PluginObjectGapTests` (8), `ProductIntegrationTests` (9), and `AjApplicationTests` (3, context load).
- The `WARN ... ERROR: duplicate key / foreign key` lines in the Maven log come from negative-path tests that expect those constraint violations. They are not failures.

## Failures

### Host frontend (pre-existing, unrelated)
1. `src/main/frontend/src/test/auth.test.tsx` > AuthContext > "provides token/permissions/login/logout via useAuth hook"
   - Error: `getByTestId("token")` expected the mock JWT, received `none`. The token seeded in localStorage is not picked up by `AuthProvider`.
   - Type: unit/component. Related to implementation: **no**. Regression risk: low (fails deterministically; no frontend changes in the working tree).
2. `src/main/frontend/src/test/extension-points.test.tsx` > ProductListPage with plugin filters > "renders plugin filter iframes in filter bar"
   - Error: `expected 0 to be >= 1` iframes. `product.list.filters` is now rendered natively by the host from manifest metadata, not as iframes (see CLAUDE.md), so this assertion looks out of date.
   - Type: integration (component). Related to implementation: **no**. Regression risk: low (pre-existing test drift).

### ai-description plugin (pre-existing, known before this task)
3. `plugins/ai-description/src/__tests__/generate.test.ts` > POST /api/generate > `generate_bamlFailure_returns500WithUserFacingError`
   - Error: the response body still contains the raw `"LLM provider timeout"` message. The test expects a sanitized, user-facing error.
   - Type: unit. Related: **no**. Regression risk: low (known).
4. `plugins/ai-description/src/__tests__/generate.test.ts` > POST /api/generate > `generate_productFetchFails_returns500`
   - Error: expected status 500, received 404. The handler passes the host API status through.
   - Type: unit. Related: **no**. Regression risk: low (known).

## Regressions
- Count: **0**.

## Issues

```yaml
issues:
  - source: test_suite
    severity: info
    description: "Host frontend AuthContext test fails: token from localStorage not exposed by useAuth (pre-existing)"
    location: src/main/frontend/src/test/auth.test.tsx:192
    fixable: false
    suggestion: "Investigate AuthContext token initialization vs the test's mock token; separate task"
  - source: test_suite
    severity: info
    description: "Host frontend test expects iframe-based product.list.filters; filters are now rendered natively (pre-existing test drift)"
    location: src/main/frontend/src/test/extension-points.test.tsx:222
    fixable: true
    suggestion: "Update assertion to check native filter controls instead of iframes; separate task"
  - source: test_suite
    severity: info
    description: "ai-description generate.test.ts: 2 known-failing error-handling tests (raw LLM error leaked; 404 passthrough vs expected 500)"
    location: plugins/ai-description/src/__tests__/generate.test.ts:135,178
    fixable: true
    suggestion: "Align handler error mapping or tests in ai-description; out of scope for this task"
issue_counts:
  critical: 0
  warning: 0
  info: 3
```
