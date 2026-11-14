# Research Sources

## TL;DR
All codebase, configuration, documentation and course sources below were verified to exist on branch `feat/mcp-auth-refactor` (2026-11-14). The external sources are primary specs and vendor docs, and their current revisions must be confirmed when fetched. Paths are relative to the repo root `C:\code\_repos\architekt-jutra\architekt-jutra-code`.

## Key Decisions
- Excluded: commit `e095025` ("Module 4 hardening", reverted by `bd4de70`) and `.maister/tasks/**/2026-04-13-mcp-token-security`. The user excluded that task, and the commit contains both its artifacts and its code.
- Standards marked "(intent)" describe the target state, not the current code.

## Open Questions / Risks
- The PDFs need `pdftotext -enc UTF-8 <file> -` (verified working). The Read tool cannot render PDFs in this environment because `pdftoppm` is missing.

---

## Codebase Sources: MCP server (gatherer `codebase-mcp`)

### Key Files
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/security/McpJwtFilter.java` — accepts any `Bearer` token without validation and sets a dummy principal
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/security/AccessTokenHolder.java` — per-request holder of the raw client token
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/security/McpAuthenticationEntryPoint.java` — 401 with `WWW-Authenticate: Bearer resource_metadata=...`
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/config/SecurityConfig.java` — filter chain
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/config/RestClientConfig.java` — `JwtForwardingInterceptor` re-sends the client token to the host (line ~80, `setBearerAuth`)
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/controller/WellKnownController.java` — RFC 9728 protected resource metadata
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/client/AjApiClient.java` — HTTP interface client for the host REST API
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/service/ProductService.java`, `CategoryService.java` — tool implementations (where outbound calls happen)
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/AjMcpApplication.java` — MCP transport and tool registration
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/exception/McpToolException.java` — error mapping

### Tests
- `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpJwtFilterTests.java`
- `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/AccessTokenHolderTests.java`
- `mcp-server/src/test/java/pl/devstyle/aj/mcp/config/RestClientConfigTests.java`
- `mcp-server/src/test/java/pl/devstyle/aj/mcp/service/ToolCallIntegrationTests.java`
- `mcp-server/src/test/java/pl/devstyle/aj/mcp/service/ErrorMappingTests.java`
- `mcp-server/src/test/java/pl/devstyle/aj/mcp/ConfigurationTests.java`, `AjMcpApplicationTests.java`

### File Patterns
- `mcp-server/src/main/java/pl/devstyle/aj/mcp/**/*.java`
- `mcp-server/src/test/java/pl/devstyle/aj/mcp/**/*.java`

## Codebase Sources: Host AS and security (gatherer `codebase-host-as`)

### Key Files
- `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2TokenFilter.java` — token endpoint (grant_type dispatch around line 62)
- `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2AuthorizationFilter.java` — authorization endpoint (code + PKCE)
- `src/main/java/pl/devstyle/aj/core/oauth2/PublicClientRegistrationFilter.java` — dynamic client registration (RFC 7591)
- `src/main/java/pl/devstyle/aj/core/oauth2/AuthorizationCodeService.java`, `RefreshTokenService.java`
- `src/main/java/pl/devstyle/aj/core/oauth2/DatabaseRegisteredClientRepository.java`, `RegisteredClientEntity.java`, `RegisteredClientJpaRepository.java`
- `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2MetadataController.java` — RFC 8414 metadata (`grant_types_supported: authorization_code, refresh_token`; `scopes_supported: mcp:read, mcp:edit`)
- `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2Error.java`, `OAuth2ErrorResponse.java`
- `src/main/java/pl/devstyle/aj/core/security/JwtTokenProvider.java` — HMAC key (`Keys.hmacShaKeyFor`, line 25), claims
- `src/main/java/pl/devstyle/aj/core/security/JwtAuthenticationFilter.java` — backend token validation
- `src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java` — central matchers including `PERMISSION_mcp:read` / `PERMISSION_mcp:edit` and the `.well-known` permitAll (around line 92)
- `src/main/java/pl/devstyle/aj/core/security/CustomUserDetailsService.java`

### Migrations
- `src/main/resources/db/changelog/2026/008-create-users-table.yaml`
- `src/main/resources/db/changelog/2026/009-create-oauth2-registered-client-table.yaml`
- `src/main/resources/db/changelog/db.changelog-master.yaml`

### Tests
- `src/test/java/pl/devstyle/aj/core/oauth2/OAuth2IntegrationTests.java`
- `src/test/java/pl/devstyle/aj/core/security/AuthIntegrationTests.java`, `SecurityTestHelperTests.java`

### File Patterns
- `src/main/java/pl/devstyle/aj/core/{oauth2,security}/**/*.java`
- `src/test/java/pl/devstyle/aj/core/{oauth2,security}/**/*.java`

## Configuration Sources
- `src/main/resources/application.properties` — `app.jwt.secret` (HMAC, default value committed via `${APP_JWT_SECRET:...}`), `app.jwt.expiration-ms=86400000` (24h)
- `mcp-server/src/main/resources/application.yml` — `aj.backend.url`, `aj.oauth.server-url`, `aj.mcp.base-url` (defaults point at public `*.labs-skillpanel.com` hosts), DEBUG security logging
- `pom.xml` (host) — `spring-security-oauth2-authorization-server` (line ~71; check whether it is used), jjwt-api/impl/jackson, `spring-boot-starter-security`
- `mcp-server/pom.xml` — Spring Boot 4.0.5, `mcp-spring-webmvc` 0.18.1, `spring-boot-starter-security`; no `oauth2-resource-server` or `oauth2-client` starter
- `compose.yml`, `start-dev.sh`, `start-dev.ps1` — runtime topology and env vars

## Documentation Sources

### Project Documentation
- `CLAUDE.md` — architecture summary (MCP server described as "validates ... exchanges" — this is aspirational on this branch)
- `.maister/docs/INDEX.md`
- `.maister/docs/project/architecture.md` — partly out of date; the code wins
- `.maister/docs/project/tech-stack.md`

### Standards
- `.maister/docs/standards/mcp-server/conventions.md` (intent) — "No Token Passthrough: Introspect, Then Exchange"
- `.maister/docs/standards/backend/security.md` (intent for MCP parts) — `SecurityConfiguration` conventions, MCP scope mapping
- `.maister/docs/standards/backend/plugin-auth.md` — context for how JWTs are forwarded elsewhere (plugins are out of scope)
- `.maister/docs/standards/testing/backend-testing.md` — test conventions for the ADRs and gap list
- `.maister/docs/standards/global/conventions.md` — env var and secret conventions

### Code Documentation
- Javadoc and inline comments in `McpJwtFilter`, `RestClientConfig`, `OAuth2TokenFilter`, `JwtTokenProvider`

## Course Materials (gatherer `course-materials`)
Directory: `_docs/`. Extract with `pdftotext -enc UTF-8 "<file>" -`. The content is in Polish.

| File | Topic | Words | Priority |
|------|-------|-------|----------|
| `_docs/AJ_M04L03_transkrypcja.pdf` + `AJ_M04L03_prezentacja.pdf` | Autoryzacja (MCP authorization) | 1664 + 850 | High |
| `_docs/AJ_M04L07_transkrypcja.pdf` | Nieprzekazywanie tokenów: Introspection i Exchange w praktyce | 527 | High |
| `_docs/AJ_M04L05_transkrypcja.pdf` | Specyfikacja 2026-07-28 | 2060 | High |
| `_docs/AJ_M04L04_transkrypcja.pdf` + `AJ_M04L04_prezentacja.pdf` | Production readiness | 1781 + 455 | Medium |
| `_docs/AJ_M04L06_transkrypcja.pdf` | Jak działa MCP Server w praktyce (kod i flow) | 1356 | Medium |
| `_docs/AJ_M04L02_transkrypcja.pdf` + `AJ_M04L02_prezentacja.pdf` | Komponenty MCP | 3203 + 1879 | Low |
| `_docs/AJ_M04L01_transkrypcja.pdf` + `AJ_M04L01_prezentacja.pdf` | Wprowadzenie do MCP | 1040 + 596 | Low |

## External Sources

### Specifications (gatherer `external-specs`)
- MCP Authorization spec (current revision): https://modelcontextprotocol.io/specification/ — select the latest dated revision, `basic/authorization`; compare with 2026-07-28
- MCP Security Best Practices (token passthrough, confused deputy): https://modelcontextprotocol.io/specification/ (latest revision) `basic/security_best_practices`
- Fallback: https://github.com/modelcontextprotocol/modelcontextprotocol (`docs/specification/`)
- RFC 8693 OAuth 2.0 Token Exchange: https://www.rfc-editor.org/rfc/rfc8693
- RFC 7662 OAuth 2.0 Token Introspection: https://www.rfc-editor.org/rfc/rfc7662
- RFC 8707 Resource Indicators for OAuth 2.0: https://www.rfc-editor.org/rfc/rfc8707
- RFC 9068 JWT Profile for OAuth 2.0 Access Tokens: https://www.rfc-editor.org/rfc/rfc9068
- RFC 9728 OAuth 2.0 Protected Resource Metadata: https://www.rfc-editor.org/rfc/rfc9728
- RFC 9700 Best Current Practice for OAuth 2.0 Security: https://www.rfc-editor.org/rfc/rfc9700
- OAuth 2.1 draft: https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/
- RFC 6750 Bearer Token Usage: https://www.rfc-editor.org/rfc/rfc6750
- RFC 8414 AS Metadata: https://www.rfc-editor.org/rfc/rfc8414
- Applicability only: RFC 9449 DPoP (https://www.rfc-editor.org/rfc/rfc9449), RFC 8705 mTLS (https://www.rfc-editor.org/rfc/rfc8705), RFC 7523 JWT bearer assertions (https://www.rfc-editor.org/rfc/rfc7523)

### Industry Patterns (gatherer `external-patterns`)
- Spring Security reference, OAuth2 Resource Server (JWT, opaque token / introspection): https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html
- Spring Security reference, OAuth2 Client (authorized clients, token exchange provider, RestClient interceptor): https://docs.spring.io/spring-security/reference/servlet/oauth2/client/index.html
- Spring Authorization Server reference (token exchange, introspection, JWKS; status in Spring Security 7): https://docs.spring.io/spring-authorization-server/reference/
- Spring Security DPoP support: https://docs.spring.io/spring-security/reference/ (search "DPoP")
- Keycloak standard token exchange: https://www.keycloak.org/securing-apps/token-exchange
- Auth0 Custom Token Exchange: https://auth0.com/docs/authenticate/custom-token-exchange
- Microsoft Entra on-behalf-of flow: https://learn.microsoft.com/entra/identity-platform/v2-oauth2-on-behalf-of-flow
- MCP Java SDK and Spring AI MCP security: https://github.com/modelcontextprotocol/java-sdk, https://docs.spring.io/spring-ai/reference/ (MCP security section)
- Reference MCP server auth implementations and gateway patterns (search-based; record version and date)
