## Backend Security

### JWT Authentication Pattern

Use stateless JWT with `SecurityFilterChain` bean for URL-based authorization. No `@PreAuthorize` on controllers — keep authorization rules centralized in `SecurityConfiguration`.

### Error Responses for Auth Failures

Use custom `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403) in `SecurityFilterChain` that write JSON `ErrorResponse` directly. These fire before the controller layer, so `GlobalExceptionHandler` cannot catch them.

### Password Storage

Use `BCryptPasswordEncoder` (Spring Security default). Store hashes in VARCHAR(72) columns.

### Token Claims

JWT must contain: `sub` (username), `permissions` (string array), `iat`, `exp`. Parse token once per request — avoid multiple parse/verify calls in the filter chain.

### SecurityConfiguration Matcher Conventions

Every authorization rule is a `requestMatchers` entry in `SecurityConfiguration`:
- GET on domain resources: `hasAnyAuthority("PERMISSION_READ", "PERMISSION_mcp:read")`.
- POST/PUT/DELETE on domain resources: `hasAnyAuthority("PERMISSION_EDIT", "PERMISSION_mcp:edit")`.
- Plugin endpoints: `PERMISSION_READ` / `PERMISSION_EDIT` only. Plugin lifecycle (manifest PUT, enabled PATCH, plugin DELETE): `PERMISSION_PLUGIN_MANAGEMENT`.
- Add a matcher only for a genuinely uncovered path, not a redundant one already covered by an existing `/**` rule. Use single-segment `*` for path variables.
- MCP scopes map to backend permissions (`mcp:read`→READ, `mcp:edit`→EDIT) with the `PERMISSION_` prefix.

```java
.requestMatchers(HttpMethod.GET, "/api/footprints/calculations/*/export")
    .hasAnyAuthority("PERMISSION_READ", "PERMISSION_mcp:read")
```

Source: CLAUDE.md, src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java, .maister/tasks/development/2026-05-20-footprint-calc-engine/implementation/spec.md, 2026-04-13-mcp-token-security/implementation/spec.md

### MCP Token Handling

The MCP server never passes client tokens through to the backend: it introspects, then exchanges for an audience-restricted token. See `standards/mcp-server/conventions.md` ("No Token Passthrough: Introspect, Then Exchange").
