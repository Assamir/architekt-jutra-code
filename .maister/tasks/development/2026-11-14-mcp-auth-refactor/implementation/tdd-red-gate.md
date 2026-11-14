# TDD Red Gate

## TL;DR
Four new tests reproduce the token-passthrough vulnerabilities on the baseline (`bd4de70`, branch `feat/mcp-auth-refactor`); all four FAIL for the expected reason. They must pass after implementation (Phase 9).

## Key Decisions
- MCP boundary tested over real HTTP (`RANDOM_PORT`) against a fake host (`com.sun.net.httpserver`) that serves `/oauth2/introspect`, `/oauth2/token` and `/api/products` — the test stays valid after the refactor, with the fake AS answering introspection and exchange.
- Host boundary tested via the real OAuth flow (DCR public client → authorize → token) with MockMvc + Testcontainers; scope check asserts on the RFC 6749 `scope` response field (implementation-agnostic).

## Open Questions / Risks
- The MCP test sets `aj.oauth.client-id/secret` and `aj.mcp.base-url` via `@DynamicPropertySource`; property names follow the design's config table and must match the implementation.

## Tests

| # | Test | Vulnerability | Result on baseline |
|---|---|---|---|
| 1 | `src/test/java/pl/devstyle/aj/core/oauth2/McpTokenBoundaryTests.mcpAccessToken_replayedAtApi_returns401` | MCP client token (T1) accepted at host `/api` — no audience restriction, replay | FAIL: `Status expected:<401> but was:<200>` |
| 2 | `McpTokenBoundaryTests.authorize_viewerRequestingMcpEdit_isNotGrantedEditScope` | `viewer` (READ only) granted `mcp:edit` — scopes not intersected with permissions | FAIL: `Expecting ["mcp:read", "mcp:edit"] not to contain ["mcp:edit"]` |
| 3 | `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpAuthBoundaryTests.toolCall_withInactiveToken_returns401` | MCP server accepts any bearer — no validation | FAIL: `expected: 401 but was: 200` |
| 4 | `McpAuthBoundaryTests.toolCall_withValidToken_neverForwardsClientTokenToBackend` | Token passthrough / confused deputy | FAIL: `Expecting ["Bearer client-token-t1"] not to contain ["Bearer client-token-t1"]` |

## Commands
```bash
cd mcp-server && ./mvnw -q test -Dtest=McpAuthBoundaryTests
./mvnw -q test -Dtest=McpTokenBoundaryTests -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm
```
