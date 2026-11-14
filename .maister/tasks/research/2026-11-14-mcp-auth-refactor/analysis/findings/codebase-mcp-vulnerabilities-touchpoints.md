# Codebase findings: MCP server vulnerabilities and replacement touchpoints

Companion to `codebase-mcp-auth-flow.md`, which holds the full evidence. Paths are relative to `mcp-server/` unless prefixed with `(root)`.

## TL;DR
- Ten concrete weaknesses on the MCP side. The core four are: passthrough of the raw client token (`RestClientConfig.java:80`), zero inbound validation (`McpJwtFilter.java:31-36`), no audience binding although PRM advertises `resource` (`WellKnownController.java:23`), and a principal that carries no identity or scopes (`McpJwtFilter.java:34-35`).
- Because of the threading model (tools run on Reactor `boundedElastic`), a replacement cannot read `SecurityContextHolder` inside tools. It must either carry a validated principal or subject token through `McpTransportContext`, or switch to `immediateExecution(true)`.
- A replacement touches 9 production files, `application.yml`, `pom.xml`, and 6 test classes. The touchpoint table below gives the change for each.

## Key Decisions
- Vulnerabilities are graded by what the MCP server itself does. Host-side gaps (no `aud` check, `PERMISSION_mcp:*` accepted on the same endpoints as user permissions) are noted as consequences but belong to `codebase-host-as`.
- The touchpoint list stays neutral on mechanism (introspection vs JWT, exchange vs client credentials). Where a choice affects a file, the table lists both options.

## Open Questions / Risks
- If the replacement keeps the MCP endpoint at `/` with a single security rule, per-tool scope enforcement (`mcp:edit` for `aj_add_product`) has to live in the tool handlers or a JSON-RPC-aware check, not in `requestMatchers`. This is a design decision for synthesis.
- How backend 401/403 should surface after the change: as an internal JSON-RPC error, or `isError=true` in `CallToolResult`. With exchange, a backend 401 signals an MCP-to-host credential problem rather than a client one. Today it surfaces as JSON-RPC `-32603` in an HTTP 200 response.

---

## 1. Vulnerabilities (current code)

| # | Weakness | Evidence | Impact |
|---|---|---|---|
| V1 | **Token passthrough**: the MCP client's bearer token is sent unchanged to the host | `RestClientConfig.java:78-80` (`setBearerAuth(accessTokenHolder.getAccessToken())`); token origin is `AjMcpApplication.java:42-46`, carried via `ProductService.java:31,43` and `CategoryService.java:29` | Explicitly forbidden pattern. The host sees the client's own token, so audit trails cannot tell direct calls from relayed ones |
| V2 | **No inbound validation**: any `Bearer <string>` is authenticated | `McpJwtFilter.java:17` (Javadoc "No JWT validation"), `:31-36` | Forged, expired, foreign-issuer or empty tokens pass the MCP layer. The MCP server becomes an open relay to the host for whatever the token can do there |
| V3 | **No audience restriction**: `resource` is advertised but not enforced | `WellKnownController.java:23`; no `aud` comparison anywhere in `src/main` | A token minted for the host (for example a web-app login token with `PERMISSION_EDIT`) works at MCP, and an MCP token works directly at the host, because `(root) src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java:106-120` accepts `PERMISSION_READ` / `PERMISSION_mcp:read` and `PERMISSION_EDIT` / `PERMISSION_mcp:edit` on the same endpoints. This allows replay across services |
| V4 | **Confused deputy**: the MCP server acts with no identity of its own | No client credentials, no OAuth client config (`pom.xml:22-65`; `application.yml:22-28`) | The host cannot authenticate the MCP server as an actor, and no `act` or `azp` claim is possible |
| V5 | **Empty principal, no scope enforcement at MCP** | `McpJwtFilter.java:34-35` (`"mcp-user"`, `List.of()`); single endpoint `/` (`AjMcpApplication.java:41`); `anyRequest().authenticated()` (`SecurityConfig.java:30`) | A `mcp:read`-only token can call `aj_add_product`, and only the host's 403 stops it. MCP-side logs carry no user identity |
| V6 | **Parsing split**: filter and transport extractor read the header independently | `McpJwtFilter.java:30-31` vs `AjMcpApplication.java:43-45` | Whatever security decides is not what the tools receive. A future validating filter would still leave tools holding the raw, unvalidated string |
| V7 | **ThreadLocal never cleared** on pooled worker threads | `AccessTokenHolder.java:12` (static), sets at `ProductService.java:31,43` and `CategoryService.java:29`, no production `clear()` call | The raw token stays in memory on `boundedElastic` threads. If a code path calls `AjApiClient` without setting the holder first, it forwards another user's token (cross-tenant leak) |
| V8 | **Auth failures hidden from the client**: backend 401/403 become JSON-RPC `-32603` in an HTTP 200 response | `RestClientConfig.java:47-48`; SDK `DefaultMcpStatelessServerHandler` maps non-`McpError` to `-32603`; transport `handlePost` returns `ok()` | The client gets no `WWW-Authenticate`, so re-auth or step-up (`insufficient_scope`) cannot be triggered |
| V9 | **External default AS and resource URLs** | `application.yml:26,28`; not overridden by `(root) start-dev.sh:28-30` or `start-dev.ps1:42-44` | Local PRM and the 401 challenge send OAuth clients to a third-party public host (`kuba-app.labs-skillpanel.com`). Tokens are obtained for the wrong resource |
| V10 | **Weak 401 challenge, no 403 handler** | `McpAuthenticationEntryPoint.java:21-23` (no `error`, no `scope`); `SecurityConfig.java:32-35` (no `accessDeniedHandler`) | Partial RFC 6750 / MCP challenge semantics |
| (minor) | DEBUG logging for security and the MCP SDK on by default; case-sensitive `"Bearer "`; no `Origin` validation (`securityValidator` = NOOP) | `application.yml:31-34`; `McpJwtFilter.java:24`; `AjMcpApplication.java:39-49` | Hardening items |

## 2. Replacement touchpoints (what must change)

### Production code
| File:lines | Today | Required change |
|---|---|---|
| `pom.xml:22-65` | security starter only | Add `spring-boot-starter-oauth2-resource-server`. It brings `opaqueToken()` introspection (`SpringOpaqueTokenIntrospector`; `external-patterns` should confirm whether it needs an extra library) or JWT decoding with audience validators. For RFC 8693 / client credentials, add `spring-boot-starter-oauth2-client` (`TokenExchangeOAuth2AuthorizedClientProvider`, `OAuth2ClientHttpRequestInterceptor`) or a hand-written token client on `RestClient`. Add `spring-security-test` (test scope) |
| `config/SecurityConfig.java:21-40` | `addFilterBefore(new McpJwtFilter())`, entry point only | Replace with `oauth2ResourceServer(...)` (opaque token introspection or `jwt` with an `aud` validator against the canonical MCP URI). Keep the custom entry point (`resource_metadata`), add `error="invalid_token"`, and add an `AccessDeniedHandler` (403 `insufficient_scope`, `scope=`). Map `mcp:*` scopes to authorities. Keep the `/.well-known/**` permitAll |
| `security/McpJwtFilter.java` (whole file) | trust-any-bearer | Delete. Its function moves to the resource-server filter (`BearerTokenAuthenticationFilter`) |
| `security/McpAuthenticationEntryPoint.java:18-24` | static challenge | Extend with `error`, `error_description`, `scope` per RFC 6750 §3. Possibly wrap `BearerTokenAuthenticationEntryPoint` |
| `AjMcpApplication.java:35-50` | `contextExtractor` puts the raw header string under `TOKEN_KEY` | Put the **validated** principal into `McpTransportContext` (subject, scopes, and for exchange the validated subject token), taken from `SecurityContextHolder` on the servlet thread inside the extractor, which still runs on that thread. Alternatively set `.immediateExecution(true)` on `McpServer.sync(...)` (`:65`) so handlers stay on the servlet thread and can use the `SecurityContext` and request-scoped beans. Remove `TOKEN_KEY` |
| `AjMcpApplication.java:65-78` | no `immediateExecution`, no `serverInfo` | See above. Optionally set `securityValidator` for `Origin` |
| `security/AccessTokenHolder.java` (whole file) | static `ThreadLocal<String>` of the client token | Delete, or replace with a per-call holder for the **exchanged backend token** that is cleared in `finally`. Better still, pass the principal explicitly and let the interceptor resolve the backend token |
| `config/RestClientConfig.java:28-38,64-86` | `JwtForwardingInterceptor` re-sends the client token | Replace with an interceptor that obtains a **host-audience token**: an RFC 8693 exchange of the validated subject token, client credentials, or `OAuth2ClientHttpRequestInterceptor` with an `OAuth2AuthorizedClientManager`. Never use the inbound token |
| `config/RestClientConfig.java:39-53` | 401 to `apiError("Authentication required")`, 403 to `apiError("Insufficient permissions")` | Re-evaluate semantics. After exchange, a backend 401 is a server-side credential failure: do not retry, do not leak details. Consider `CallToolResult(isError=true)` or an `McpError` with a proper code |
| `service/ProductService.java:26-31,42-43,69-71,183-185` | `String token` parameter, `accessTokenHolder.setAccessToken(token)` | Remove the token parameter and the holder calls. Read the principal or subject from `ctx`. Optionally enforce `mcp:edit` in `addProduct` and `mcp:read` in `listProducts` |
| `service/CategoryService.java:24-29,43` | same pattern | same change |
| `controller/WellKnownController.java:14-28` | `resource` = `aj.mcp.base-url`, `authorization_servers` = `aj.oauth.server-url` | Make sure `resource` is the exact canonical URI that tokens are audience-bound to (and that the validator checks). Point `authorization_servers` at the host issuer. Optionally add `resource_name` and `jwks_uri`. Keep `scopes_supported` in sync with the scope mapping |
| `src/main/resources/application.yml:22-34` | three `aj.*` URLs, external defaults, DEBUG logging | Change defaults to local values (`http://localhost:8080` for the AS issuer, `http://localhost:8081` for the resource). Add introspection URI, MCP client id and secret (env vars per the `AJ_OAUTH_CLIENT_ID` / `AJ_OAUTH_CLIENT_SECRET` convention, `(root) .maister/docs/standards/mcp-server/conventions.md:15`), token endpoint, and target audience/resource for the host. Lower the security log level |
| `(root) start-dev.sh:28-30`, `start-dev.ps1:42-44` | no env vars | Export `AJ_OAUTH_SERVER_URL`, `AJ_MCP_BASE_URL` and client credentials, or rely on the fixed local defaults |

### Tests that pin current behaviour and must be rewritten
| Test | File:lines | Why |
|---|---|---|
| `McpJwtFilterTests` | `src/test/.../security/McpJwtFilterTests.java:16-49` | Class under test is deleted. Replace with MockMvc tests: 401 + `WWW-Authenticate` without a token, 401 for an invalid or foreign-`aud` token, 403 `insufficient_scope` |
| `AccessTokenHolderTests` | `.../security/AccessTokenHolderTests.java:8-27` | Holder removed or changed |
| `RestClientConfigTests` | `.../config/RestClientConfigTests.java:17-45` | Asserts `"Bearer forwarded-token"`, which is the passthrough. Must assert that the exchanged or service token is sent and the inbound token is not |
| `ToolCallIntegrationTests` | `.../service/ToolCallIntegrationTests.java:50,67,79,89,100` | Uses `AjMcpApplication.TOKEN_KEY` and `listProducts("test-token", ...)` signatures |
| `ErrorMappingTests` | `.../service/ErrorMappingTests.java:32,46,59,74,89` | `String token` signatures. Add a test of the status handler for backend 401/403 |
| `ToolSchemaValidationTests` | `.../service/ToolSchemaValidationTests.java:102,139` | `String token` signatures |
| New | n/a | PRM document test (fields, `resource` value), end-to-end tool call with MockRestServiceServer or WireMock verifying the outbound `Authorization` header, introspection and exchange stubs, ThreadLocal hygiene if a holder is kept |

### Documentation drift to fix alongside
- `(root) CLAUDE.md:60` says the MCP server "validates incoming bearer tokens (JWT or introspection ...), exchanges them". That is false on this branch.
- `(root) CLAUDE.md:48` lists "introspection" as a host AS capability. Verification is for `codebase-host-as`.
- The `McpJwtFilter.java:15-19` Javadoc describes the trust-and-forward model. It goes when the class is deleted.
