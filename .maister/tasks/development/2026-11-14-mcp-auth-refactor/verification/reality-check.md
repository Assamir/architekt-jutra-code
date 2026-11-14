# Reality Check: MCP auth refactor (validate-then-exchange)

## TL;DR
- Status: **⚠️ Issues Found (no blockers)**. Every vulnerability named in the problem statement is closed in the code I traced, and a host or MCP integration test proves each boundary. The MCP server no longer forwards the client token.
- What has not been proven: the two apps have never run together. No live flow has gone MCP client → MCP 8181 → host 8180 introspect/exchange → `/api`. MCP-side tests use a JDK fake host. The `resource` value that real clients (Claude Code, MCP Inspector) send is still unverified, and the spec itself required a manual run.
- I tried a live end-to-end run on a throwaway Postgres container with ports 8180/8181. Starting the host app was denied by the permission classifier. The MCP server alone booted cleanly on 8181/9181 with the real config. I stopped it and removed the container. Ports 8080/8081/9081 were not touched.
- Every other gap is minor: the docs say "401" where a secret mismatch actually gives 5xx; the RG-1 red-test baseline is still uncommitted; tracked `dist/index.html` files still say 8080; findings that predate this task.

## Key Decisions
- `skip_test_execution: true` was respected. I took the test results from `verification/test-suite-results.md`: host 209/209, MCP 49/49, frontend 87/88. The one frontend failure is the stale `extension-points.test.tsx`, which fails on master too. I ran no test commands.
- Read-only checks I ran: `sha256sum -c implementation/rg1-baseline.sha256` (both files OK), a port and process inventory, and a grep of the repo for leftover 8080/8081/9081 references.
- The assessment rests on tracing the real code paths (listed below) and on the names and assertions of the boundary tests, not on the claims in the work log.

## Open Questions / Risks
- **Real MCP client `resource` behaviour** is unverified. `ResourcePolicy.canonical` accepts `http://localhost:8181` and `http://localhost:8181/`. A client configured as `http://127.0.0.1:8181`, or with a path such as `/mcp`, gets `invalid_target` at authorize.
- **Both apps together has never run.** The `SpringOpaqueTokenIntrospector` ↔ host `/oauth2/introspect` contract and the Basic URL-encoding ↔ `ClientAuthenticator` decoding are each proven only against one side. Field shapes do match: the host's `IntrospectionResponse` is a superset of the FakeHost payload (`jti` is added).
- **Rollout:** stored SPA tokens without `aud` are discarded (FE-3), so every user has to log in again. MCP clients must be re-pointed from 8081 to 8181. Both points are documented.
- **Per tool call, the host does at least 2 BCrypt checks** (introspection and exchange). Every JSON-RPC request also adds an introspection, including `initialize` and `tools/list`. This has not been measured. It is fine for local dev.

---

## 1. Reality vs claims

| Claim | Reality | Evidence |
|---|---|---|
| MCP validates every inbound token (introspection + aud) | **True** | `SecurityConfig.opaqueTokenIntrospector()` wraps `SpringOpaqueTokenIntrospector` (5 s/10 s timeouts, URL-encoded Basic) in `AudienceValidatingIntrospector(…, aj.mcp.base-url)`. `/` requires `SCOPE_mcp:read` or `SCOPE_mcp:edit`. Tests: `McpSecurityChainTests` (7), `McpAuthBoundaryTests.toolCall_withInactiveToken_returns401` |
| MCP never forwards the client token | **True** | `AccessTokenHolder`, `McpJwtFilter` and `JwtForwardingInterceptor` are deleted. `ProductService.backendAuthorization` runs `ToolSecurity.requireScope`, then `TokenExchangeClient.exchange(T1)`, then `"Bearer " + T2`. Tests: `McpAuthBoundaryTests.toolCall_withValidToken_neverForwardsClientTokenToBackend`, `ToolAuthorizationTests` (exactly one exchange per call; the backend sees only T2) |
| Per-tool scope check before exchange | **True** | `ToolSecurity.requireScope` throws `insufficientScope` before any network call. Test: `addProduct_withReadOnlyToken_returnsScopeErrorWithoutExchange` |
| T1 unusable at `/api` | **True** | `JwtAuthenticationFilter` → `parseApiToken` requires `aud` ∋ `${issuer}/api`. T1 `aud` = mcpResource. Test: `McpTokenBoundaryTests.mcpAccessToken_replayedAtApi_returns401` |
| T2 unusable at the MCP server | **True** | Host introspection returns `active:false` when `aud` lacks the bound MCP resource. The MCP-side aud check rejects it a second time. Test: `introspect_delegatedApiToken_returnsInactive` |
| T2/T1 unusable as consent principal | **True** | `OAuth2AuthorizationFilter.sessionPrincipal()` requires `ParsedToken.isSessionToken()` (no `client_id`, no `act`). T1 never authenticates at all (no API aud). Test: `authorize_delegatedTokenAsSession_returns403AccessDenied` |
| Only the MCP client can exchange | **True** | `TokenExchangeGrantHandler`: confidential auth, plus `boundResourceOf` (config-bound to `aj-mcp-server` only), plus a `TOKEN_EXCHANGE` grant. DCR only accepts `authorization_code`/`refresh_token` and always issues a random UUID `client_id`. Test: `exchange_confidentialDcrClient_returnsUnauthorizedClient` |
| Exchange subject must be a T1 for this MCP | **True** | `parseAccessToken` (`typ at+jwt`, `iss`), no `act`, `aud` ∋ bound resource. Tests: `exchange_sessionTokenAsSubject_…`, `exchange_delegatedTokenAsSubject_…` |
| T2 is short-lived, audience-restricted, with current permissions | **True** | `exp` = min(now+300 s, T1 `exp`), `aud` = API, `act.sub` = MCP client. Scopes come from `ScopePolicy.grantAtExchange` against the user's **current** authorities. Tests: `exchange_permissionRevokedAfterConsent_returnsInvalidScope`, `exchange_viewerDelegatedToken_postProduct_returns403` |
| Viewer cannot obtain `mcp:edit` | **True** | `ScopePolicy` intersects with the user's permissions at authorize and again at exchange. The consent page hides edit (FE-2), but the server is authoritative. Test: `McpTokenBoundaryTests.authorize_viewerRequestingMcpEdit_isNotGrantedEditScope` |
| SPA and plugins still work (T0 with aud) | **True by code and tests** | `generateToken` adds `iss` and `aud=[issuer/api]`. The plugin SDK and `server-sdk.ts` forward T0 to 8180. The `iss` is configured, so it no longer depends on the Host header. The Vite proxy targets 8180. Not exercised in a browser. |
| No token logging | **True by tests** | `OAuth2LoggingTests`, `McpLoggingTests`. The MCP DEBUG levels have been removed. |
| `start-dev` wires matching secrets | **True** | Both scripts set `AJ_OAUTH_CLIENT_SECRET` only when it is unset and export it before launching the host and MCP. The children inherit it (`&` subshells / `Start-Process`). The secret is never printed. |
| Ports moved to 8180/8181/9181 | **True** | The repo grep (excluding `week*`, `tools`, `_docs`, `dist`, `node_modules`, task folders) found no 8080/8081/9081 references. The MCP server booted on 8181/9181 with the real `application.yml`. |
| End-to-end works with a real MCP client | **Not demonstrated** | No manual run is recorded in the work log. Spec Open Question #1 explicitly asked for one. My attempt to start the host was denied. |

## 2. Request path traced (code)
1. MCP client → `POST http://localhost:8181/` with T1. Spring's resource server calls `AudienceValidatingIntrospector`, which calls `SpringOpaqueTokenIntrospector`, which sends `POST {server-url}/oauth2/introspect` (Basic `aj-mcp-server`).
2. On the host, `OAuth2IntrospectionFilter` runs `ClientAuthenticator.authenticateConfidential`, then `boundResourceOf`, then `parseAccessToken` plus the aud check. It answers `active` with a space-delimited `scope`. Spring maps that to `SCOPE_mcp:*`, and the aud is checked again on the MCP side.
3. `AjMcpApplication.contextExtractor` puts the `BearerTokenAuthentication` into the `McpTransportContext` (no ThreadLocal). The tool handler then calls `ToolSecurity.principal` and `requireScope`.
4. `TokenExchangeClient` sends `POST /oauth2/token` with the RFC 8693 grant and `resource={server-url}/api`. The host's `TokenExchangeGrantHandler` mints T2.
5. `AjApiClient` sends `@RequestHeader Authorization: Bearer T2` to `/api/products` or `/api/categories`. `JwtAuthenticationFilter` maps the delegated token's `scopes` to `PERMISSION_mcp:*`, which satisfies the existing `hasAnyAuthority` matchers.

## 3. Gaps by severity

### Critical
None. Nothing found blocks the stated goal.

### Warning
| # | Gap | Location | Fixable |
|---|---|---|---|
| W1 | **No live two-app end-to-end run.** The MCP side is tested only against `FakeHost`, and the host side only through MockMvc. The cross-app contract has never run for real: `SpringOpaqueTokenIntrospector` parsing the real host response, Basic encode/decode, PRM discovery → AS metadata → DCR → consent → token → tool call. | `mcp-server/src/test/.../FakeHost.java`; spec Open Question #1 | Yes, with a 15-minute manual run (see plan) |
| W2 | **Real-client `resource` value unverified.** Only origin-only forms with or without a trailing slash are accepted. Clients that send the endpoint path, or use `127.0.0.1`, are refused with `invalid_target`. | `ResourcePolicy.canonical/resolveForAuthorization` | Yes, by recording the actual value; widen the rule only if needed |
| W3 | **RG-1 baseline uncommitted.** Both red-test files are still untracked, so the `git diff --exit-code` proof in the spec is not possible yet. A hash file stands in for it today (it currently passes). | `implementation/rg1-baseline.sha256`; `git ls-files` → not tracked | Yes, by committing them |

### Info
| # | Item | Location | Fixable |
|---|---|---|---|
| I1 | The docs say a secret mismatch "fails closed with 401s". In reality introspection returns 401 to the MCP server, `SpringOpaqueTokenIntrospector` throws `OAuth2IntrospectionException`, and the MCP client sees **5xx**. This matches the spec's endpoint table but not the prose. It still fails closed. | `README.md` Manual Start, `CLAUDE.md`, plugin README | Yes, by fixing the wording |
| I2 | Tracked `plugins/{box-size,warehouse}/dist/index.html` still reference 8080. The spec placed them out of scope, and they only matter if someone serves `dist`. | `plugins/*/dist/` | Yes |
| I3 | `OAuth2Properties` does not reject `mcp-resource` equal to `apiAudience` or the issuer. Such a misconfiguration would merge the T1 and T2 audiences. | `OAuth2Properties` compact ctor | Yes, with a one-line guard |
| I4 | Unknown `client_id` skips BCrypt (a timing oracle on client existence). Flagged in the work log. | `ClientAuthenticator` | Yes, with a dummy BCrypt |
| I5 | Predates this task: `state` is appended to the redirect without URL-encoding, and `?code=` breaks redirect URIs that already have a query. | `OAuth2AuthorizationFilter` | Yes |
| I6 | Predates this task: `JwtAuthenticationFilter` accepts `_token` as a request parameter on any path, including `/api` query strings (token-in-URL). | `JwtAuthenticationFilter.extractToken` | Yes (restrict it to `POST /oauth2/authorize`) |
| I7 | Predates this task: the stale `extension-points.test.tsx` and the 16 lint findings. | frontend | Yes |
| I8 | `JacksonMcpJsonMapper` logs JSON-RPC payloads at INFO (product data, no tokens). | mcp-server config | Yes |
| I9 | My verification attempt started the MCP server briefly on 8181/9181 and a `postgres:18` container on 55432. Both are stopped and removed. The ports are free again, and the 8080/8081/9081 processes are untouched. | — | — |

## 4. Integration points
- **Config pairs:** `start-dev` derives `AJ_OAUTH_SERVER_URL` from `APP_OAUTH2_ISSUER` and shares `AJ_MCP_BASE_URL`, `AJ_OAUTH_CLIENT_ID` and `AJ_OAUTH_CLIENT_SECRET`, so the must-equal pairs hold by construction. The MCP server refuses to start when the secret is blank. A host with a blank secret deletes the client, and every MCP call then fails closed.
- **Provisioning:** `McpClientProvisioner` upserts the client and keeps its row id. It re-encodes the secret only when the secret changed, so per-run secret rotation in `start-dev` works. T1s that are already issued survive a rotation, because they are signed with the JWT key.
- **Database:** there are no new migrations, so an existing dev DB works unchanged.
- **Discovery:** the PRM (`resource`=base-url, `authorization_servers`=[issuer]) is served by Spring Security 7's filter through a customizer, and a test covers it. AS metadata is built from the configured issuer (and ignores `X-Forwarded-Host`, which is tested). The consent page passes `resource` through a hidden input.

## 5. Functional completeness
About 90%. All security requirements are implemented and covered by tests at both edges. What is missing is the cross-app runtime proof and the commit of the red-test baseline.

## 6. Pragmatic action plan
| Priority | Task | Success criteria | Effort |
|---|---|---|---|
| High | Manual end-to-end run: `./start-dev.sh` (or `.ps1`), then connect MCP Inspector or Claude Code to `http://localhost:8181`. Log in as `editor`, consent, and call `aj_list_products`, `aj_list_categories` and `aj_add_product`. Repeat as `viewer` and confirm `aj_add_product` returns "Insufficient scope". | All tools work. The host log shows "OAuth2 token exchanged". The `resource` value the client sent is recorded in the work log (from the "Authorization code issued … resource=" log line). | 20-30 min |
| High | During the same run, replay T1 with curl against `http://localhost:8180/api/products`. | 401 | 2 min |
| Medium | Commit the two red-test files as the RG-1 baseline before the implementation commit. | `git diff --exit-code <baseline> -- <both files>` exits 0 | 5 min |
| Low | Fix the "401" wording for a secret mismatch (it is 5xx at the MCP server). | Docs match the behaviour | 10 min |
| Low | Add a guard that `mcp-resource` ≠ `apiAudience`, plus a dummy BCrypt for unknown clients. | Unit tests | 30 min |
| Low (backlog) | `state` encoding and `_token` scoping (both predate this task). | — | 30 min |

## 7. Verdict
**⚠️ Gaps.** The refactor really does replace token passthrough with validate-then-exchange. Audience binding is enforced at the host API, at host introspection and at the MCP server. Exchange is limited to the provisioned MCP client, and T2 is short-lived, carries `act`, and is re-checked against current permissions. The tests behind each boundary are real assertions, not happy-path stubs. What keeps this from being "Ready" is that no one has run the two apps together with a real MCP client. Do that once (W1/W2) and commit the RG-1 baseline (W3), and the work can be called complete.
