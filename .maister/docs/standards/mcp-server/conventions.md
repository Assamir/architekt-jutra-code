## MCP Server Conventions

### MCP Server Tool Conventions

The MCP server (`mcp-server/`) is a stateless proxy: it owns no data and calls the host REST API through `AjApiClient` (`AJ_BACKEND_URL`). Tool names start with `aj_` (`aj_list_products`, `aj_add_product`, `aj_list_categories`). Register tools programmatically with `McpStatelessServerFeatures.SyncToolSpecification` (no annotation-driven tools) and return results as `structuredContent`. Errors surfaced to the LLM are `McpToolException`s built from static factories (`validationError`, `notFound`, `apiError`, `invalidCriteria`). Input validation stays in the backend.

```text
aj_list_products(search?) -> GET /api/products?search={search}
```

Source: .maister/tasks/development/2026-04-04-build-mcp-server/implementation/spec.md, work-log.md, CLAUDE.md

### No Token Passthrough: Introspect, Then Exchange

Never forward the MCP client's token to the backend. Validate every incoming bearer token with RFC 7662 introspection against the host (no caching), exchange it via RFC 8693 token exchange for an audience-restricted backend token (Token-B), and call the host with Token-B. Map scopes (`mcp:read`→READ, `mcp:edit`→EDIT) and drop unknown ones. A 401 from the backend propagates as `McpToolException.apiError` — do not retry the exchange. OAuth client credentials come from env vars (`AJ_OAUTH_CLIENT_ID`, `AJ_OAUTH_CLIENT_SECRET`).

Source: .maister/tasks/development/2026-04-13-mcp-token-security/implementation/spec.md, CLAUDE.md, mcp-server/src/main/resources/application.yml
