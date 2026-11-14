## Backend Security

### JWT Authentication Pattern

Use stateless JWT with `SecurityFilterChain` bean for URL-based authorization. No `@PreAuthorize` on controllers — keep authorization rules centralized in `SecurityConfiguration`.

### Error Responses for Auth Failures

Use custom `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) in `SecurityFilterChain` that write JSON `ErrorResponse` directly. These fire before the controller layer, so `GlobalExceptionHandler` cannot catch them.

### Password Storage

Use `BCryptPasswordEncoder` (Spring Security default). Store hashes in VARCHAR(72) columns.

### Token Claims

Every host JWT carries `iss` (the configured `app.oauth2.issuer`, never derived from the request) and `aud`, plus `sub`, `iat`, `exp`. Login session tokens add `permissions` (string array) and have `aud` = `${issuer}/api`. OAuth access tokens (`typ: at+jwt`) add `client_id`, `jti` and `scopes`, plus `act` (`{"sub": <client id>}`) on exchanged tokens. Parse token once per request — avoid multiple parse/verify calls in the filter chain.

- `/api` accepts only tokens whose `aud` contains `${issuer}/api`; tokens for the MCP resource are rejected there with 401.
- Only the RFC 8693 token-exchange grant may mint the API audience for an OAuth token; the authorization-code and refresh grants reject it with `invalid_target`.
- `/oauth2/authorize` accepts only session tokens, never OAuth access tokens.

### SecurityConfiguration Matcher Conventions

Every authorization rule is a `requestMatchers` entry in `SecurityConfiguration`:
- GET on domain resources: `hasAnyAuthority("PERMISSION_READ", "PERMISSION_mcp:read")`.
- POST/PUT/DELETE on domain resources: `hasAnyAuthority("PERMISSION_EDIT", "PERMISSION_mcp:edit")`.
- Plugin endpoints: `PERMISSION_READ` / `PERMISSION_EDIT` only. Plugin lifecycle (manifest PUT, enabled PATCH, plugin DELETE): `PERMISSION_PLUGIN_MANAGEMENT`.
- Add a matcher only for a genuinely uncovered path, not a redundant one already covered by an existing `/**` rule. Use single-segment `*` for path variables.
- Session tokens map `permissions` to `PERMISSION_*` authorities; OAuth tokens map `scopes` the same way. T2 keeps `mcp:*` scopes, matched by the `PERMISSION_mcp:*` alternatives. Granted scopes = expand → ∩ user permissions → ∩ client scopes → expand again (`mcp:edit` implies `mcp:read` only if the user has READ); no default to client scopes at exchange.

```java
.requestMatchers(HttpMethod.GET, "/api/footprints/calculations/*/export")
    .hasAnyAuthority("PERMISSION_READ", "PERMISSION_mcp:read")
```

Source: CLAUDE.md, src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java, src/main/java/pl/devstyle/aj/core/security/JwtTokenProvider.java, src/main/java/pl/devstyle/aj/core/oauth2/ScopePolicy.java, .maister/tasks/development/2026-05-20-footprint-calc-engine/implementation/spec.md, .maister/tasks/development/2026-11-14-mcp-auth-refactor/implementation/spec.md

### MCP Token Handling

The MCP server never passes client tokens through to the backend: it introspects (`/oauth2/introspect`, client authentication required, `active` only when the token's `aud` contains the caller's bound resource), then exchanges for a host-API token (`/oauth2/token`, token-exchange grant, only the `aj-mcp-server` client, TTL ≤ min(300 s, subject `exp`), no refresh token). See `standards/mcp-server/conventions.md` ("No Token Passthrough: Introspect, Then Exchange").
