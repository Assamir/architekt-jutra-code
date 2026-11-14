# External Patterns: Caching Practices and Adoption Effort (custom AS vs SAS vs Keycloak)

## TL;DR
- Introspection: RFC 7662 §4 allows caching, but a cached result **MUST NOT** outlive the response's `exp`, and every second of caching is a window in which a revoked token still works. Spring ships no introspection cache. Running without one is the simplest option for a localhost hop.
- Exchanged tokens: vendors recommend caching them for their lifetime (Auth0) and keying the cache by **a hash of the incoming token** (MSAL default). Spring's default `OAuth2AuthorizedClient` store is keyed by `(registrationId, principalName)`. That key lets two MCP clients of the same user, holding different scopes, share one backend token, and it outlives revocation of the inbound token.
- Effort ranking for aj: (1) extend the custom AS with introspection, exchange and `aud`, and use Spring client/RS on the MCP side: **medium**; (2) migrate the AS to Spring Authorization Server (Spring Security 7): **high**, though it buys RFC completeness; (3) Keycloak: **very high**, with an IdP swap.

## Key Decisions
- Default to no caching of introspection results, or a TTL of a few seconds at most, capped at `exp` — local-only deployment makes per-request introspection cheap, and RFC 7662 frames caching purely as a load/latency trade against revocation.
- If exchanged tokens are cached, key the cache by SHA-256 of the subject token (MSAL pattern) and cap the TTL at min(exchanged `exp`, subject `exp`) — this prevents scope bleed between MCP clients of the same user and stops cached tokens from outliving the inbound token.
- Register `OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(...)` so a backend 401 evicts the cached exchanged token. Retrying is a separate decision: the team standard says no retry on backend 401.

## Open Questions / Risks
- Boot's auto-configured `OAuth2AuthorizedClientRepository` for a resource server is an `AuthenticatedPrincipalOAuth2AuthorizedClientRepository` over `InMemoryOAuth2AuthorizedClientService`. That comes from general Spring knowledge, not a fetched page. Confidence: medium. The implementer should verify it, or always declare a custom bean.
- The in-memory authorized-client store is unbounded. For a long-running MCP server with many users, use a bounded cache or no cache.
- Whether the host AS should cap the exchanged token's `exp` at the subject token's `exp` (Keycloak's default) or issue an independent short TTL is a design choice for synthesis.

---

## 1. Introspection caching

| Source | Guidance |
|---|---|
| RFC 7662 §4 — https://www.rfc-editor.org/rfc/rfc7662.html | Caching trades fresher data against load. It "creates a window during which a revoked token could be used". A response with `exp` "MUST NOT be cached beyond" it. Sensitive deployments may disable caching. The AS **MUST** authenticate protected resources calling the endpoint and **SHOULD** authorize them specifically. Use POST, not GET (keeps tokens out of logs). TLS is required. |
| Spring Security 7.1.2 opaque-token docs — https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html | No caching facility. Introspection is positioned as the choice "when revocation is a requirement". Caching means writing a decorator around `OpaqueTokenIntrospector`. |
| Cloudflare — https://developers.cloudflare.com/agents/model-context-protocol/protocol/authorization/ | The RS calls the AS's `validateToken` on each request over a service binding. No caching is described. |
| MCP security best practices — https://modelcontextprotocol.io/specification/draft/basic/security_best_practices | No caching guidance. "MCP servers MUST NOT accept any tokens that were not explicitly issued for the MCP server", so a cache must store and re-check `aud`, not just `active`. |

**Practical options for the aj MCP server**
1. **No cache** (recommended baseline): one POST to `localhost:8080/oauth2/introspect` per MCP HTTP request. Revocation is immediate.
2. **Short TTL cache** (5–30 s, capped at `exp`), keyed by SHA-256 of the token, never by the raw token. Cache only `active=true` responses, so a token revoked inside the TTL keeps working until the entry expires; don't cache negative results for long. Use a bounded store (e.g. Caffeine `maximumSize`).
3. **Local JWT validation** instead of introspection removes the network call entirely, but revocation only takes effect at `exp`. It needs JWKS (asymmetric) to avoid sharing the host signing secret.

## 2. Exchanged-token caching

| Source | Guidance |
|---|---|
| Auth0 OBO — https://auth0.com/docs/secure/call-apis-on-users-behalf/on-behalf-of-token-exchange | "Cache access tokens for their lifetime" rather than exchanging per call (latency, rate limit of 30 RPS by default). |
| MSAL OBO — https://learn.microsoft.com/entra/msal/dotnet/acquiring-tokens/web-apps-apis/on-behalf-of-flow | Default cache key = **hash of the incoming assertion**. It can be overridden with a session key (`sid`). Refresh token used on expiry. |
| Keycloak V2 — https://www.keycloak.org/securing-apps/token-exchange | The exchanged AT expires no later than the subject token by default; the docs recommend keeping that cap. Revoking the subject AT does **not** revoke the exchanged AT. Prefer short-lived ATs and a fresh exchange over refresh tokens. |
| Spring `TokenExchangeOAuth2AuthorizedClientProvider` — https://raw.githubusercontent.com/spring-projects/spring-security/main/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/TokenExchangeOAuth2AuthorizedClientProvider.java | Reuses the stored `OAuth2AuthorizedClient` until `expiresAt - 60 s`. A token without `expiresAt` is **never** considered expired. No refresh. |
| Spring client core — https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html | Authorized clients are keyed by `clientRegistrationId` + principal name. `InMemoryOAuth2AuthorizedClientService` is the default (process-local, lost on restart). JDBC variant available. |

**Security trade-offs specific to Spring defaults**
- **Scope bleed across MCP clients**: with a principal-name key, user U connecting through client A (`mcp:edit`) and client B (`mcp:read`) gets one cached backend token. Whichever exchange ran first wins, so B may act with edit rights. Mitigations: key by subject-token hash (custom `OAuth2AuthorizedClientRepository`/`Service`), include `client_id` + scope in the key, or skip caching.
- **Revocation lag**: a revoked or expired inbound token still maps to a live cached backend token until that token's `exp`. Mitigation: check the inbound token first (introspection with no cache), cap the exchanged TTL at the subject `exp`, and keep exchanged TTL short (minutes).
- **`expires_in` must be returned** by the host token-exchange response. Otherwise Spring caches the token forever.
- **Eviction on downstream rejection**: `OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(...)` (https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html).
- **Request context**: `DefaultOAuth2AuthorizedClientManager` requires an `HttpServletRequest`. If MCP SDK tool handlers run off the servlet thread, use `AuthorizedClientServiceOAuth2AuthorizedClientManager` and propagate the principal explicitly (`RequestAttributePrincipalResolver`).

## 3. Adoption effort for aj (custom AS built from servlet filters)

| Capability | Custom AS (extend filters) | Spring Authorization Server (in Spring Security 7) | Keycloak 26.x |
|---|---|---|---|
| Introspection endpoint (RFC 7662) | New filter/endpoint with confidential client auth (Basic). Returns `active`, `sub`, `scope`, `aud`, `client_id`, `exp`, `iat`, `iss`. **Low–Medium** | Built in (`OAuth2TokenIntrospectionEndpointFilter`) | Built in |
| Token exchange grant (RFC 8693) | New branch in the token filter: validate client has the grant, validate `subject_token` (signature, `exp`, **`aud` = MCP resource**), mint a token with `aud` = host API, `client_id` = MCP server, optional `act`, scope ⊆ subject scopes. **Medium** | Built in, **but only for SAS-issued subject tokens**. `aud` and narrowing via `OAuth2TokenCustomizer`. Requested scopes are checked only against client scopes, not subject scopes. No RT | V2 built in: confidential clients, `audience` downscoping, no `resource`, delegation preview, impersonation absent |
| `aud` claim + RFC 8707 `resource` at authorize/token | Add param handling and claim. **Low–Medium** | Not native (community `mcp-authorization-server` adds resource indicators) | No `resource` (audience mapper workaround) |
| Asymmetric signing + JWKS | Switch jjwt to RS256/ES256, publish JWKS. **Medium** | Built in (`JWKSource` bean) | Built in |
| Confidential client seeding for MCP server | Migration plus a hashed secret. **Low** | `RegisteredClientRepository` (JDBC) | Admin config |
| DCR (RFC 7591) | Already present (public clients) | Disabled by default. Needs a `client.create` token or open registration | Supported |
| DPoP / mTLS | Not realistic | DPoP binding, mTLS client auth and certificate-bound tokens | Supported |
| Overall | **Medium**. Keeps existing users, permissions and filters. Highest maintenance burden (hand-written security code) | **High** one-off migration (replace 3 filters plus services, new JDBC schema, keys, re-test DCR/PKCE flows). Lower long-term risk | **Very high**. IdP swap, user and permission mapping, compose service, login UI |

MCP-server side effort is the **same in all three columns**:
- Add `spring-boot-starter-security-oauth2-resource-server` and `spring-boot-starter-security-oauth2-client`.
- Replace `McpJwtFilter` with `oauth2ResourceServer(opaqueToken|jwt)` and an audience check.
- Replace `AccessTokenHolder` / `JwtForwardingInterceptor` with `OAuth2ClientHttpRequestInterceptor` + `TokenExchangeOAuth2AuthorizedClientProvider` (custom subject-token resolver if opaque, `audience`/`resource` parameter converter).
- Keep the RFC 9728 metadata.
