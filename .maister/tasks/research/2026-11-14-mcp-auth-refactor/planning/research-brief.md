# Research Brief: MCP auth refactor — replacing token passthrough

## TL;DR
The aj MCP server currently forwards the MCP client's bearer token to the host REST API without validating it (`McpJwtFilter` trusts any `Bearer`, `AccessTokenHolder` re-sends it). Research must recommend a standards-compliant replacement (MCP Authorization spec, OAuth 2.1, RFC 8693/7662/8707/9068) that removes token passthrough, confused-deputy and audience-confusion risks, compare alternatives, and produce a design and ADRs to feed `/maister:development`.

## Key Decisions
- Research type: mixed (codebase analysis + standards/literature + course materials) — the answer depends on what the host AS already supports and what the specs require.
- Scope is the MCP ↔ host hop and the host AS; migrating to Spring Authorization Server or an external IdP is evaluated, not implemented.

## Open Questions / Risks
- Which RFC mechanisms the custom host AS can support with acceptable effort (token exchange grant, introspection endpoint, resource indicators, `aud` claim validation).
- Whether the MCP spec revision in force mandates resource indicators (RFC 8707) for clients and audience validation for MCP servers.

## Research Question
How should the aj MCP server authenticate to the aj host backend without forwarding the MCP client's bearer token, in line with the MCP Authorization spec, OAuth 2.1 and related RFCs?

Original (PL): "Jak bezpiecznie zastąpić obecne przekazywanie tokena (token passthrough) z serwera MCP ... mechanizmem zgodnym z aktualnymi standardami (MCP Authorization spec, OAuth 2.1, RFC 8693 token exchange, RFC 7662 introspection, RFC 8707 resource indicators, audience restriction, delegacja uprawnień / on-behalf-of, dedykowany handshake)?"

## Context (current state, branch `feat/mcp-auth-refactor`)
- `mcp-server/` (Spring Boot 4, MCP Java SDK, port 8081):
  - `security/McpJwtFilter` — accepts any `Authorization: Bearer ...` without validation, sets a dummy `mcp-user` principal.
  - `security/AccessTokenHolder` + `config/RestClientConfig` — the raw client token is re-sent to the host REST API.
  - `controller/WellKnownController` — protected resource metadata (RFC 9728) pointing at the host AS.
- Host (`src/main/java/pl/devstyle/aj`):
  - `core/oauth2` — custom OAuth2 AS: dynamic client registration, authorization code + PKCE, token endpoint, refresh tokens, `.well-known` metadata, MCP scopes `mcp:read` / `mcp:edit`.
  - `core/security` — `JwtTokenProvider` (HMAC JWT), `JwtAuthenticationFilter`, `SecurityConfiguration` (centralized `PERMISSION_*` rules incl. `PERMISSION_mcp:read`/`mcp:edit`).

## Vulnerabilities to eliminate
- Token passthrough (explicitly forbidden by the MCP spec): MCP server accepts tokens not issued for it and forwards them.
- Confused deputy: backend cannot distinguish a direct client call from one relayed by the MCP server.
- No audience restriction: a token stolen from or leaked by MCP is valid at the backend and vice versa (replay across services).
- No validation at the MCP layer: unauthenticated or forged tokens reach backend calls; no per-request principal.

## Scope
**Included**: MCP inbound token validation; MCP → host outbound auth; host AS capabilities (token, introspection, exchange, audience); course module 4 materials (`_docs/AJ_M04L01..L07_*.pdf`); MCP Authorization spec (current revision), OAuth 2.1, RFC 8693, 7662, 8707, 9068, 9728, 9700 (OAuth security BCP).

**Excluded**: implementation; frontend and plugin auth; `week7/`–`week10/`, `tools/`.

**Constraints**: Spring Boot 4 / Spring Security 7, Java 25; custom AS built from servlet filters; centralized rules in `SecurityConfiguration`; local-only deployment.

## Alternatives to evaluate (minimum)
1. RFC 8693 token exchange (MCP server as OAuth client exchanging the user token for an audience-restricted backend token; delegation vs impersonation, `act` claim).
2. Inbound validation options: local JWT validation (shared HMAC vs asymmetric keys + JWKS) vs RFC 7662 introspection.
3. Audience binding via RFC 8707 resource indicators and `aud` validation on both MCP and host.
4. Client credentials (service identity) + user context propagation (signed assertion / header) — and why it is weaker.
5. Moving to Spring Authorization Server or an external IdP (Keycloak) — cost/benefit only.
6. Sender-constrained tokens (DPoP, mTLS) — applicability.

## Success Criteria
- Clear recommendation with rationale and cited sources (spec sections, RFCs, course materials).
- Comparison table of alternatives (security, complexity, fit with the custom AS).
- Gap list: what the host AS / MCP server must add.
- High-level design + ADRs usable as input to `/maister:development`.
