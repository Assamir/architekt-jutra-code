## MCP Server Conventions

### MCP Server Tool Conventions

The MCP server (`mcp-server/`) is a stateless proxy: it owns no data and calls the host REST API through `AjApiClient` (`AJ_BACKEND_URL`). Tool names start with `aj_` (`aj_list_products`, `aj_add_product`, `aj_list_categories`). Register tools programmatically with `McpStatelessServerFeatures.SyncToolSpecification` (no annotation-driven tools) and return results as `structuredContent`. Errors surfaced to the LLM are `McpToolException`s built from static factories (`validationError`, `notFound`, `apiError`, `invalidCriteria`, `insufficientScope`). Input validation stays in the backend.

```text
aj_list_products(search?) -> GET /api/products?search={search}
```

Source: .maister/tasks/development/2026-04-04-build-mcp-server/implementation/spec.md, work-log.md, CLAUDE.md

### No Token Passthrough: Introspect, Then Exchange

Never forward the MCP client's token (T1) to the backend.

- **Inbound:** the MCP server is a Spring Security OAuth2 resource server. Every bearer token is validated by RFC 7662 introspection against the host (`SpringOpaqueTokenIntrospector`, client credentials in Basic auth, no caching), and `AudienceValidatingIntrospector` rejects it unless its `aud` contains `aj.mcp.base-url` (`AJ_MCP_BASE_URL`). An inactive or wrong-audience token gets 401 with `WWW-Authenticate: Bearer error="invalid_token"` and `resource_metadata`; an introspection outage is a 5xx, never a pass.
- **Principal:** the validated `BearerTokenAuthentication` reaches tools through `McpTransportContext` (`ToolSecurity`): no ThreadLocal and no raw `Authorization` header in tool code.
- **Per-tool scope check:** each tool checks its scope (`mcp:read` for reads, `mcp:edit` for writes) before any exchange; a missing scope fails with `McpToolException.insufficientScope`.
- **Outbound:** `TokenExchangeClient` exchanges T1 via RFC 8693 for a host-API token (T2) on every tool call, with no caching, and `AjApiClient` sends T2 explicitly. T2 keeps the `mcp:*` scopes (no `mcp:read`→READ mapping); the host matches them with the `PERMISSION_mcp:*` alternatives. Scope rule: expand (`mcp:edit` adds `mcp:read`) → ∩ the user's permissions → ∩ the client's scopes → expand again, where `mcp:edit` implies `mcp:read` only if the user has READ.
- **Failures:** a backend 401 propagates as `McpToolException.apiError`; do not retry the exchange. Tool errors stay JSON-RPC errors.
- **Configuration:** OAuth client credentials come from env vars (`AJ_OAUTH_CLIENT_ID`, `AJ_OAUTH_CLIENT_SECRET`); the server fails fast at startup without the secret. Protected resource metadata is published by Spring Security's `OAuth2ProtectedResourceMetadataFilter`, configured in `SecurityConfig` (no hand-written `.well-known` controller). Never log tokens, the client secret or the Basic header value.

Source: .maister/tasks/development/2026-11-14-mcp-auth-refactor/implementation/spec.md, .maister/tasks/research/2026-11-14-mcp-auth-refactor, CLAUDE.md, mcp-server/src/main/java/pl/devstyle/aj/mcp/config/SecurityConfig.java, mcp-server/src/main/resources/application.yml
