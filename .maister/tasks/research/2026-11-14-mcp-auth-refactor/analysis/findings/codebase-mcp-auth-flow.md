# Codebase findings: MCP server inbound/outbound auth flow (current state)

Branch `feat/mcp-auth-refactor`, HEAD `bd4de70`; `mcp-server/` has no uncommitted changes. Paths below are relative to `mcp-server/` unless noted. MCP SDK behaviour was checked by disassembling `mcp-core-0.18.1.jar` / `mcp-spring-webmvc-0.18.1.jar` from the local Maven repository with `javap`, since no sources jar was available.

## TL;DR
- The MCP server does no token validation. `McpJwtFilter` marks any request that has `Authorization: Bearer <anything>` as authenticated, using the fixed principal `"mcp-user"` with no authorities. Separately, the MCP transport's `contextExtractor` copies the raw token string into `McpTransportContext`. Each tool handler then puts that string into a `ThreadLocal` (`AccessTokenHolder`), and `JwtForwardingInterceptor` sends it to the host unchanged with `setBearerAuth`. This is token passthrough.
- Tool handlers do not run on the servlet thread. They run on Reactor `boundedElastic`, because `immediateExecution` defaults to `false`. The Spring `SecurityContext` therefore never reaches the tools, and the token travels only as a raw string in `McpTransportContext`. The `ThreadLocal` is set on the worker thread and never cleared.
- The RFC 9728 metadata and the 401 `WWW-Authenticate: Bearer resource_metadata=...` challenge exist. However, both default to public `*.labs-skillpanel.com` URLs, and nothing advertises or checks audience or scope.
- When the backend returns 401 or 403, the client gets a JSON-RPC error `-32603` inside an HTTP 200 response. It never receives an HTTP 401/403 or an `insufficient_scope` challenge.
- `pom.xml` has only `spring-boot-starter-security`: no `oauth2-resource-server`, no `oauth2-client`, no JOSE library. No test covers authentication end to end. The existing tests lock in the forwarding behaviour.

## Key Decisions
- Treat `CLAUDE.md:60` ("validates incoming bearer tokens ... exchanges them") and `.maister/docs/standards/mcp-server/conventions.md:13-15` ("Introspect, Then Exchange") as the target state. Neither is implemented on this branch. Rationale: the code at the cited lines contradicts both.
- The threading model is reported as a hard constraint for the replacement. Rationale: anything that relies on `SecurityContextHolder` or the servlet thread inside a tool handler will silently fail unless `immediateExecution(true)` is set or the context is propagated explicitly.

## Open Questions / Risks
- Whether the host AS issues tokens with an `aud` claim, and whether `JwtAuthenticationFilter` checks it, is for the `codebase-host-as` gatherer. On the MCP side, nothing checks it.
- `target/classes/application.yml` differs from the source file (line numbers shift, and it has extra keys at line 30). It is a stale build output, probably from the reverted commit, and was deliberately not analysed. A clean build is needed before any runtime check.
- The `spring.ai.mcp.server.*` keys in `application.yml:13-18` have no Spring AI dependency behind them (`pom.xml:22-65`). They are dead configuration, and the server name and version are not applied to `McpServer.sync(...)` (`AjMcpApplication.java:65-78`).

---

## 1. Component inventory

| Component | File:lines | Role in auth |
|---|---|---|
| `SecurityConfig` | `src/main/java/pl/devstyle/aj/mcp/config/SecurityConfig.java:21-40` | Single `SecurityFilterChain`: CSRF off, STATELESS, permitAll for `/.well-known/**`, `/actuator/health/**` and `/error`, `anyRequest().authenticated()`, custom entry point, `McpJwtFilter` before `UsernamePasswordAuthenticationFilter` |
| `McpJwtFilter` | `.../security/McpJwtFilter.java:15-46` | Sets a dummy authentication whenever the header starts with `"Bearer "`. No validation |
| `McpAuthenticationEntryPoint` | `.../security/McpAuthenticationEntryPoint.java:18-24` | 401 plus `WWW-Authenticate: Bearer resource_metadata="<base>/.well-known/oauth-protected-resource"` |
| `WellKnownController` | `.../controller/WellKnownController.java:10-29` | RFC 9728 Protected Resource Metadata (PRM) |
| `AjMcpApplication` | `.../AjMcpApplication.java:35-79` | Stateless WebMVC transport, `contextExtractor` copies the raw token into `McpTransportContext` under `TOKEN_KEY="bearer_token"`, sync stateless server with 3 tools |
| `ProductService` / `CategoryService` | `.../service/ProductService.java:30-64,66-79,180-193`; `.../service/CategoryService.java:28-38,40-52` | Tool handlers read the token from ctx and call `accessTokenHolder.setAccessToken(token)` |
| `AccessTokenHolder` | `.../security/AccessTokenHolder.java:9-29` | Static `ThreadLocal<String>` singleton bean |
| `RestClientConfig` + `JwtForwardingInterceptor` | `.../config/RestClientConfig.java:28-55,64-86` | `RestClient` to `aj.backend.url`. The interceptor adds `Authorization: Bearer <client token>`. The status handler maps 4xx/5xx to `McpToolException` |
| `AjApiClient` | `.../client/AjApiClient.java:14-25` | HTTP interface: `GET /api/products?search`, `POST /api/products`, `GET /api/categories` |
| `McpToolException` | `.../exception/McpToolException.java:7-51` | Error types `VALIDATION`, `NOT_FOUND`, `API_ERROR`. It is not an `McpError` |

## 2. Inbound request path (MCP client to tool handler)

### 2.1 Filter chain and principal
`SecurityConfig.java:23-37`:
```java
.csrf(csrf -> csrf.disable())
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/.well-known/**").permitAll()
        .requestMatchers("/actuator/health/**").permitAll()
        .requestMatchers("/error").permitAll()
        .anyRequest().authenticated())
.exceptionHandling(ex -> ex.authenticationEntryPoint(new McpAuthenticationEntryPoint(
        mcpBaseUrl + "/.well-known/oauth-protected-resource")))
.addFilterBefore(new McpJwtFilter(), UsernamePasswordAuthenticationFilter.class);
```
`McpJwtFilter.java:15-19` says so in its Javadoc: "No JWT validation — trust-and-forward model. The backend validates tokens." The relevant code is at `McpJwtFilter.java:30-36`:
```java
String authHeader = request.getHeader(AUTHORIZATION_HEADER);
if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
    log.info("Received Bearer token from client (length={})", ...);
    var authentication = new UsernamePasswordAuthenticationToken("mcp-user", null, List.of());
    SecurityContextHolder.getContext().setAuthentication(authentication);
}
```
- **Principal**: the constant `"mcp-user"`, with no credentials and an empty authority list (`:34-35`). The principal carries no `sub`, scope, client id or audience.
- The 3-argument `UsernamePasswordAuthenticationToken` constructor marks the token as authenticated, so `anyRequest().authenticated()` passes for any value. That includes an empty string (`"Bearer "`) or a token from any issuer.
- The `"Bearer "` prefix match is case-sensitive (`:24`), although RFC 6750 treats the scheme name as case-insensitive. This is minor.
- The context is cleared in `finally` (`:42-44`). It is set by mutating `getContext()` rather than creating an empty context. This is minor.
- Requests without a Bearer header pass through unauthenticated (`:37-39`). `AuthorizationFilter` then denies them, and the entry point answers 401.
- There is no scope-based authorization. All tools are reachable at the single JSON-RPC endpoint `/` (`AjMcpApplication.java:41`), and no `hasAuthority("SCOPE_mcp:edit")` check exists anywhere, so a read-only token can invoke `aj_add_product`. Only the host's 403 stops it.

### 2.2 Token extraction into the MCP transport context
The token is parsed a second time, independently of the filter, at `AjMcpApplication.java:37-50`:
```java
.messageEndpoint("/")
.contextExtractor(request -> {
    String auth = request.headers().firstHeader("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) {
        return McpTransportContext.create(Map.of(TOKEN_KEY, auth.substring("Bearer ".length())));
    }
    return McpTransportContext.EMPTY;
})
```
The filter's decision and the extractor's value are not linked. The tool sees whatever raw string was in the header, not anything the security layer approved.

### 2.3 MCP SDK threading model (verified in bytecode)
- `WebMvcStatelessServerTransport.handlePost` calls `contextExtractor.extract(request)`, then `handler.handleRequest(ctx, req).contextWrite(...).block()`. The servlet thread blocks, and the response is always `ServerResponse.ok()` with JSON (bytecode offsets 64-222 of `handlePost`).
- `McpServer.StatelessSyncSpecification` initialises `immediateExecution = false` in its constructor (offset 4-6), and `build()` passes that flag to `McpStatelessServerFeatures.Async.fromSync(..., immediateExecution)`.
- `AsyncToolSpecification.lambda$fromSync$1` wraps the sync handler in `Mono.fromCallable(...)`. When `immediateExecution == false`, it applies `.subscribeOn(Schedulers.boundedElastic())`.
- `AjMcpApplication.java:65-78` never calls `.immediateExecution(true)`.

**Consequence**: sync tool handlers (`ProductService.java:68-79,182-193`, `CategoryService.java:42-52`) run on a pooled Reactor `boundedElastic` thread, not on the Tomcat request thread. The Spring `SecurityContext` that `McpJwtFilter` set (servlet thread, `MODE_THREADLOCAL`) is not visible there. That explains the design: the token has to be carried in `McpTransportContext` and re-materialised into a `ThreadLocal` on the worker thread.

### 2.4 From tool handler to `AccessTokenHolder` to `AjApiClient`
- `ProductService.java:69-71`: `listProducts((String) ctx.get(AjMcpApplication.TOKEN_KEY), ...)`. The same pattern appears at `:183-185` and `CategoryService.java:43`.
- `ProductService.java:30-33`:
  ```java
  public Map<String, List<ProductResponse>> listProducts(String token, String search) {
      accessTokenHolder.setAccessToken(token);
      try { return Map.of("products", ajApiClient.listProducts(search)); }
  ```
  The same pattern is at `ProductService.java:42-43` and `CategoryService.java:28-29`. **No `clear()` is ever called in production code.** `clear()` is called only in tests (`AccessTokenHolderTests.java:14`, `RestClientConfigTests.java:23`).
- `AccessTokenHolder.java:12` declares `private static final ThreadLocal<String> TOKEN`. The raw token therefore stays on the `boundedElastic` worker thread after the tool call ends, until a later handler on the same thread overwrites it. Every current handler sets the value first, `null` included, so no cross-request reuse happens today. Any future code path that calls `AjApiClient` without calling `setAccessToken` first would forward another user's token.
- `RestClientConfig.java:76-85`, the passthrough itself:
  ```java
  if (accessTokenHolder.hasAccessToken()) {
      LOG.info("Forwarding Bearer token to backend: {} {}", request.getMethod(), request.getURI());
      request.getHeaders().setBearerAuth(accessTokenHolder.getAccessToken());
  } else {
      LOG.warn("No token to forward for backend call: {} {}", ...);
  }
  ```
  `RestClient` is synchronous, so the interceptor runs on the same worker thread that set the `ThreadLocal`. With no token, the call still goes out unauthenticated, and the host answers 401.

### 2.5 Sequence (current)
```
MCP client --POST / (Authorization: Bearer T)--> Tomcat thread
  McpJwtFilter: header starts with "Bearer " -> SecurityContext = "mcp-user" (no validation)
  AuthorizationFilter: authenticated() OK
  WebMvcStatelessServerTransport.handlePost
    contextExtractor: McpTransportContext{bearer_token=T}
    handler.handleRequest(...).block()   (servlet thread blocks)
      -> boundedElastic thread: SyncToolSpecification.callHandler(ctx, req)
           ProductService.listProducts(T, ...) -> AccessTokenHolder.set(T)  (never cleared)
           AjApiClient.listProducts -> RestClient -> JwtForwardingInterceptor
             Authorization: Bearer T  ------> host :8080 /api/products
             host JwtAuthenticationFilter validates T; SecurityConfiguration accepts
             PERMISSION_READ or PERMISSION_mcp:read (src/.../SecurityConfiguration.java:106-107)
  McpJwtFilter finally: SecurityContextHolder.clearContext()
<-- HTTP 200 JSON-RPC response (result or error -32603)
```

## 3. RFC 9728 metadata and the 401 challenge

`WellKnownController.java:20-28`:
```java
@GetMapping({"/oauth-protected-resource", "/oauth-protected-resource/**"})
public Map<String, Object> getProtectedResourceMetadata() {
    return Map.of(
            "resource", mcpBaseUrl,
            "authorization_servers", new String[]{authServerUrl},
            "bearer_methods_supported", new String[]{"header"},
            "scopes_supported", new String[]{"mcp:read", "mcp:edit"});
}
```
- Fields served: `resource` (`aj.mcp.base-url`), `authorization_servers` (a single entry, `aj.oauth.server-url`), `bearer_methods_supported=["header"]`, and `scopes_supported=["mcp:read","mcp:edit"]`. Not served: `jwks_uri`, `resource_name`, `resource_signing_alg_values_supported`, `dpop_*`, `tls_client_certificate_bound_access_tokens`, `authorization_details_types_supported`.
- The path-suffixed variant (`/.well-known/oauth-protected-resource/**`) returns the same document, so path-insertion discovery works. The MCP endpoint is `/` (`AjMcpApplication.java:41`), so the canonical resource URI equals the base URL.
- The document is publicly accessible (`SecurityConfig.java:27`).
- The `resource` value is advertised but **never enforced**. No code compares a token's `aud` (or anything else) against `mcpBaseUrl`.
- 401 challenge (`McpAuthenticationEntryPoint.java:21-23`): status 401 with `WWW-Authenticate: Bearer resource_metadata="<aj.mcp.base-url>/.well-known/oauth-protected-resource"`. It has no `error="invalid_token"` (there is no validation, so an invalid token never triggers 401), no `scope=` parameter, and no body.
- There is no `AccessDeniedHandler` and no 403 `insufficient_scope` challenge. The server never makes a 403 decision of its own.

## 4. Error handling for backend 401/403
`RestClientConfig.java:39-53`:
```java
.defaultStatusHandler(status -> status.is4xxClientError() || status.is5xxServerError(),
    (request, response) -> {
        int statusCode = response.getStatusCode().value();
        String body = new String(response.getBody().readNBytes(8192));
        LOG.warn("Backend API error: {} {} - {}", statusCode, request.getURI(), body);
        throw switch (statusCode) {
            case 400 -> McpToolException.validationError(body);
            case 401 -> McpToolException.apiError("Authentication required");
            case 403 -> McpToolException.apiError("Insufficient permissions");
            case 404 -> McpToolException.notFound(body);
            default -> McpToolException.apiError("Backend error (status %d). Please retry.".formatted(statusCode));
        };
    })
```
- Services rethrow `McpToolException` unchanged (`ProductService.java:34-35,58-59`; `CategoryService.java:32-33`) and wrap anything else as `apiError("Failed to ...: " + ex.getMessage())` (`ProductService.java:36-38,60-62`; `CategoryService.java:34-36`).
- `McpToolException` is not an `io.modelcontextprotocol.spec.McpError`. In the SDK, `DefaultMcpStatelessServerHandler.lambda$handleRequest$1` turns any non-`McpError` throwable into a `JSONRPCError(-32603, throwable.getMessage(), null)`. The transport serialises this with HTTP 200 (`handlePost` offsets 191-222).
- **Net effect**: when the host rejects the forwarded token (expired, revoked, wrong permission), the MCP client receives HTTP 200 with JSON-RPC `{"code":-32603,"message":"Authentication required"}` or `"Insufficient permissions"`. It gets no HTTP 401/403, no `WWW-Authenticate`, and nothing that would trigger a re-auth or step-up flow. Tool results never set `isError=true`. The handlers build `new CallToolResult(..., false, ...)` on success only, at `ProductService.java:74-75,188-189` and `CategoryService.java:47-48`.
- Error bodies of up to 8 KB from the host are logged at WARN, and the body text is passed into 400/404 messages returned to the client.

## 5. Configuration properties and defaults
`src/main/resources/application.yml`:

| Key | Line | Default | Used at |
|---|---|---|---|
| `server.port` | 2 | `8081` | n/a |
| `management.server.port` | 7 | `9081` (health and info exposed) | n/a |
| `aj.backend.url` | 24 | `${AJ_BACKEND_URL:http://localhost:8080}` | `RestClientConfig.java:29` |
| `aj.oauth.server-url` | 26 | `${AJ_OAUTH_SERVER_URL:https://kuba-app.labs-skillpanel.com}` | `WellKnownController.java:14` (`authorization_servers`) |
| `aj.mcp.base-url` | 28 | `${AJ_MCP_BASE_URL:https://kuba-mcp.labs-skillpanel.com}` | `WellKnownController.java:17` (`resource`), `SecurityConfig.java:18,34` (`resource_metadata`) |
| `logging.level.org.springframework.security` / `io.modelcontextprotocol` / `pl.devstyle` | 31-34 | `DEBUG` | n/a |
| `spring.ai.mcp.server.name/version` | 14-18 | `aj-mcp` / `1.0.0` | unused, no Spring AI on the classpath |

- **External default URLs**: neither `start-dev.sh:28-30` nor `start-dev.ps1:42-44` sets `AJ_OAUTH_SERVER_URL` or `AJ_MCP_BASE_URL`. A local run therefore advertises a third-party public AS (`kuba-app.labs-skillpanel.com`) and resource URI (`kuba-mcp.labs-skillpanel.com`) in both PRM and the 401 challenge. A spec-compliant client would run OAuth against an external host, and the token it obtained would carry no audience matching the local server. The same host appears in the host CORS allow-list (`src/main/resources/application.properties:8`, repo root).
- No properties exist for introspection URI, client id or secret, token-exchange audience, JWKS URI, issuer, or expected audience.
- `DEBUG` logging for Spring Security and the MCP SDK is on by default. `McpJwtFilter` logs only the token length (`:32`), and the interceptor logs only the method and URI (`:79`). Raw tokens are not logged by application code.

## 6. Dependencies (`pom.xml`)
- Parent: `spring-boot-starter-parent` `4.0.5` (`pom.xml:5-9`). Java `25` (`:18`). MCP SDK `mcp.version=0.18.1` (`:19`) via `io.modelcontextprotocol.sdk:mcp-spring-webmvc` (`:23-27`).
- `spring-boot-starter-web`, `-actuator`, `-security` (`:32-43`). Jackson 2 (`com.fasterxml.jackson.core:jackson-databind`, `jackson-datatype-jsr310`; `:28-31,44-47`). Lombok with an explicit `annotationProcessorPaths` entry (`:48-52,70-82`).
- Test: `spring-boot-starter-test`, `com.networknt:json-schema-validator:2.0.0` (`:54-64`).
- **Absent**: `spring-boot-starter-oauth2-resource-server` (gives `opaqueToken` introspection and JWT `JwtDecoder` with audience validators), `spring-boot-starter-oauth2-client` (gives `OAuth2AuthorizedClientManager`, `TokenExchangeOAuth2AuthorizedClientProvider` and `OAuth2ClientHttpRequestInterceptor` for `RestClient`), and any JOSE library (nimbus-jose-jwt or jjwt). No `spring-security-test` is declared explicitly. It is not part of `spring-boot-starter-test`, so `SecurityMockMvcRequestPostProcessors` such as `jwt()` and `opaqueToken()` are unavailable today.
- Transport security: `WebMvcStatelessServerTransport.Builder` defaults `securityValidator` to `ServerTransportSecurityValidator.NOOP` (Builder bytecode offsets 20-23), and `AjMcpApplication.java:39-49` does not set one. No `Origin`/`Host` header validation is configured. This is relevant to the spec's DNS-rebinding guidance, though secondary to this research.

## 7. Test coverage (what is pinned)

| Test | File:lines | What it asserts | Relevance |
|---|---|---|---|
| `McpJwtFilterTests.doFilter_withBearerToken_setsAuthentication` | `src/test/java/pl/devstyle/aj/mcp/security/McpJwtFilterTests.java:20-34` | Calls the chain and asserts the context is null afterwards. The name says "setsAuthentication", but the test never checks the principal | Pins "any Bearer passes". No negative case |
| `McpJwtFilterTests.doFilter_withoutBearerToken_passesThrough` | `:36-48` | Chain called, no auth | n/a |
| `AccessTokenHolderTests.threadLocal_storesAndClearsToken` | `.../security/AccessTokenHolderTests.java:17-26` | set/get/clear | Pins the ThreadLocal design |
| `RestClientConfigTests.interceptor_addsAuthorizationHeader_whenTokenPresent` | `.../config/RestClientConfigTests.java:26-44` | Outbound header equals `"Bearer forwarded-token"` | **Pins the passthrough behaviour explicitly** |
| `ToolCallIntegrationTests` (5 tests) | `.../service/ToolCallIntegrationTests.java:44-105` | `@SpringBootTest` with a `@MockitoBean AjApiClient`. Handlers invoked directly with `McpTransportContext{TOKEN_KEY:"test-token"}` | Pins the `TOKEN_KEY` contract and the `String token` service signatures. It bypasses HTTP, security and the interceptor |
| `ErrorMappingTests` (5 tests) | `.../service/ErrorMappingTests.java:27-96` | Services propagate or wrap `McpToolException`. Called with `"token"` | Does **not** test the `RestClientConfig` status handler (401/403 mapping is untested) |
| `ToolSchemaValidationTests` | `.../service/ToolSchemaValidationTests.java:102,139` | Calls `listProducts("test-token", null)` and `listCategories("test-token")` | Signature coupling |
| `ConfigurationTests`, `AjMcpApplicationTests` | `.../ConfigurationTests.java:18-26`, `.../AjMcpApplicationTests.java:9-11` | Ports 8081/9081, context loads | Context loads with external default URLs |

**Missing coverage**: no MockMvc or HTTP-level test of the security chain (401 without a token, the `WWW-Authenticate` header format, PRM JSON), no test that an invalid, foreign-audience or expired token is rejected, no test of backend 401/403 mapping through `RestClient`, no test of threading or `ThreadLocal` hygiene, and no WireMock/MockRestServiceServer test of the outbound `Authorization` header in an end-to-end tool call.
