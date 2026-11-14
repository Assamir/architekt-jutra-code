# Test Suite Results: MCP auth refactor (re-verification, cycle 2)

## TL;DR
- Status: **passed_with_issues**. This is the cycle 2 re-run after the verification fixes. No regressions; the only problems are the two known pre-existing issues.
- Host backend: 232/232 passed (was 209). MCP server: 51/51 passed (was 49). Frontend vitest: 87/88 passed (unchanged). Overall 370/371 (99.7%).
- The one failure is still the known pre-existing `extension-points.test.tsx` "renders plugin filter iframes in filter bar" (the file is unchanged versus HEAD).
- Frontend lint still has 16 problems (14 errors, 2 warnings), the same files and lines as the baseline.
- All 4 TDD boundary tests pass, and `rg1-baseline.sha256` verifies OK for both files.

## Open Questions / Risks
- `extension-points.test.tsx` is stale: it still expects plugin filters to render as iframes, but filters are native now. This task did not cause it. It should be fixed in a separate task.
- The 2 `no-explicit-any` lint errors in `plugin-sdk-auth.test.ts` and `plugin-sdk.test.ts` are on lines this task did not change (as noted in cycle 1). They are not regressions.
- The MCP logs contain expected `ERROR` lines and stack traces: introspection timeouts, introspection 503s, token exchange `invalid_scope`, and an unexpected token type. These come from negative-path tests that inject faults on purpose, and the tests pass. They are not failures.

---

## Comparison with cycle 1

| Suite | Cycle 1 | Cycle 2 | Delta |
|-------|---------|---------|-------|
| Host backend | 209/209 | 232/232 | +23 tests, all passing |
| MCP server | 49/49 | 51/51 | +2 tests, all passing |
| Frontend vitest | 87/88 | 87/88 | same (1 known pre-existing failure) |
| Frontend lint | 16 problems (14 errors, 2 warnings) | 16 problems (14 errors, 2 warnings) | same |

Where the new tests are (all from the verification fixes, all passing):
- Host:
  - ClientAuthenticatorTests: 8 → 11 (+3)
  - OAuth2GrantPolicyTests: 12 → 16 (+4)
  - OAuth2LoggingTests: 1 → 3 (+2)
  - OAuth2LogValuesTests: new, 4 (+4)
  - OAuth2PropertiesTests: 3 → 9 (+6)
  - RefreshTokenServiceTests: new, 1 (+1)
  - TokenExchangeIntegrationTests: 5 → 7 (+2)
  - TokenExchangeValidationTests: 9 → 10 (+1)
  - Total: +23
- MCP: AjPropertiesTests 5 → 7 (+2).
- No test was removed or skipped between the cycles.

## Suite 1: Host backend

- Command: `./mvnw test -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm` (repo root)
- Result: BUILD SUCCESS, exit 0, 31.3 s. Testcontainers PostgreSQL 18 was used.

| Total | Passing | Failing | Errors | Skipped | Pass rate |
|-------|---------|---------|--------|---------|-----------|
| 232 | 232 | 0 | 0 | 0 | 100% |

oauth2 and security classes:

| Class | Tests | Class | Tests |
|-------|-------|-------|-------|
| ClientAuthenticatorTests | 11 | IntrospectionIntegrationTests | 8 |
| McpClientProvisionerTests | 4 | **McpTokenBoundaryTests** | 2 |
| OAuth2GrantPolicyTests | 16 | OAuth2IntegrationTests | 8 |
| OAuth2LoggingTests | 3 | OAuth2LogValuesTests | 4 |
| OAuth2PropertiesTests | 9 | RefreshTokenServiceTests | 1 |
| ResourcePolicyTests | 7 | ScopePolicyTests | 8 |
| TokenExchangeIntegrationTests | 7 | TokenExchangeValidationTests | 10 |
| AuthIntegrationTests | 18 | SecurityTestHelperTests | 3 |

The modules this task did not touch all passed with the same counts as cycle 1: AjApplicationTests, IntegrationTests, api, pricing archetype, category, plugin (7 classes), footprint (9 classes), product and user.

## Suite 2: MCP server

- Command: `cd mcp-server && ./mvnw test`
- Result: BUILD SUCCESS, exit 0

| Total | Passing | Failing | Errors | Skipped | Pass rate |
|-------|---------|---------|--------|---------|-----------|
| 51 | 51 | 0 | 0 | 0 | 100% |

Classes:
- AjMcpApplicationTests (1)
- TokenExchangeClientTests (3)
- AjPropertiesTests (7)
- JacksonConfigTests (2)
- RestClientConfigTests (2)
- ConfigurationTests (2)
- AudienceValidatingIntrospectorTests (3)
- **McpAuthBoundaryTests (2)**
- McpLoggingTests (1)
- McpSecurityChainTests (7)
- ToolAuthorizationTests (5)
- ErrorMappingTests (5)
- ToolCallIntegrationTests (5)
- ToolSchemaValidationTests (6)

## Suite 3: Frontend (vitest and lint)

- Command: `cd src/main/frontend && npm test`
- Result: exit 1. 17 test files: 16 passed, 1 failed.

| Total | Passing | Failing | Errors | Skipped | Pass rate |
|-------|---------|---------|--------|---------|-----------|
| 88 | 87 | 1 | 0 | 0 | 98.9% |

- Command: `npm run lint`
- Result: 16 problems (14 errors, 2 warnings), identical to the baseline.
  - 2 warnings: `react-hooks/exhaustive-deps` in `useProducts.ts` and `PluginFrame.tsx`.
  - `set-state-in-effect` errors, including `CarbonFootprintLandingPage.tsx:29`.
  - `react-refresh/only-export-components` in `PluginContext.tsx:107` and `router.tsx:20`.
  - `no-unused-vars` in `footprint-api.test.ts:136`.
  - `no-explicit-any` in:
    - `plugin-sdk-auth.test.ts:39`
    - `plugin-sdk.test.ts:86` and `:108`
    - `plugins.test.tsx:163`, `:194`, `:243` and `:286`

## TDD tests and baseline checksum (required)

| Test | Suite | Result |
|------|-------|--------|
| McpTokenBoundaryTests.mcpAccessToken_replayedAtApi_returns401 | host | PASS |
| McpTokenBoundaryTests.authorize_viewerRequestingMcpEdit_isNotGrantedEditScope | host | PASS |
| McpAuthBoundaryTests.toolCall_withValidToken_neverForwardsClientTokenToBackend | mcp-server | PASS |
| McpAuthBoundaryTests.toolCall_withInactiveToken_returns401 | mcp-server | PASS |

- Command (repo root): `sha256sum -c .maister/tasks/development/2026-11-14-mcp-auth-refactor/implementation/rg1-baseline.sha256`
- Output:
  - `src/test/java/pl/devstyle/aj/core/oauth2/McpTokenBoundaryTests.java: OK`
  - `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpAuthBoundaryTests.java: OK`
- Exit code: 0

The TDD tests were not modified after the red-green baseline.

## Failures

| Test | File | Error | Type | Related to impl | Regression risk | Category |
|------|------|-------|------|-----------------|-----------------|----------|
| ProductListPage with plugin filters > renders plugin filter iframes in filter bar | src/main/frontend/src/test/extension-points.test.tsx:222 | expected 0 to be >= 1 (no iframes; filters are rendered natively) | unit (component) | No. The file is unchanged versus HEAD (`git diff` is empty). | low | pre-existing (stale test) |

## Regressions
- Count: 0

## Issues

| Source | Severity | Description | Location | Fixable | Suggestion |
|--------|----------|-------------|----------|---------|------------|
| test_suite | info | Pre-existing stale test that expects plugin filter iframes | src/main/frontend/src/test/extension-points.test.tsx | true | Update it to assert the native filter controls, in a separate task |
| test_suite | info | Pre-existing lint baseline of 16 problems (14 errors, 2 warnings), unchanged | src/main/frontend | true | Clean it up in a separate task |

Issue counts: critical 0, warning 0, info 2.

## Execution notes
- Ports 8080/8081/9081 were left alone, and no processes were killed. Both Spring suites ran on random ports next to the processes already running.
- No git state was changed. The only file written is this results file.
