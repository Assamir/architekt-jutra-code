# TDD Green Gate

## TL;DR
All 4 Phase-3 red tests now PASS on the implemented code, and both test files are byte-identical to the red-gate baseline (SHA-256 check OK). The token-passthrough, confused-deputy, audience-replay and scope-escalation defects are fixed.

## Results

| # | Test | Red (baseline) | Green (now) |
|---|---|---|---|
| 1 | `McpTokenBoundaryTests.mcpAccessToken_replayedAtApi_returns401` | 200 | PASS (401) |
| 2 | `McpTokenBoundaryTests.authorize_viewerRequestingMcpEdit_isNotGrantedEditScope` | viewer got `mcp:edit` | PASS (`mcp:read` only) |
| 3 | `McpAuthBoundaryTests.toolCall_withInactiveToken_returns401` | 200 | PASS (401) |
| 4 | `McpAuthBoundaryTests.toolCall_withValidToken_neverForwardsClientTokenToBackend` | backend got `Bearer client-token-t1` | PASS (backend only sees exchanged T2; introspection performed) |

- `cd mcp-server && ./mvnw -q test -Dtest=McpAuthBoundaryTests` → Tests run: 2, Failures: 0, Errors: 0
- `./mvnw -q test -Dtest=McpTokenBoundaryTests -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm` → Tests run: 2, Failures: 0, Errors: 0
- `sha256sum -c implementation/rg1-baseline.sha256` → both files OK (RG-1: tests unchanged since the red gate)
