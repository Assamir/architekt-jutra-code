# External Patterns: How Industry Implements MCP-Server-to-Backend Auth (no passthrough)

## TL;DR
- Every major vendor converges on one pattern: the middle tier (MCP server) validates an inbound token whose `aud` is itself, then exchanges it at the AS for a new token whose `aud` is the downstream API (RFC 8693 or the Entra jwt-bearer OBO equivalent). It never forwards the inbound token. Auth0, Okta, Entra, Keycloak and Spring all document this.
- Spring Security 7.1 already ships every *client/RS-side* piece the aj MCP server needs, and they work against any RFC-compliant AS, including a hand-written one: `opaqueToken()` introspection (RFC 7662), `JwtDecoder` + audience / `at+jwt` validators, `TokenExchangeOAuth2AuthorizedClientProvider` (since 6.3), and `OAuth2ClientHttpRequestInterceptor` for `RestClient`.
- Spring Authorization Server, now inside Spring Security 7, supports token exchange, introspection, JWKS, mTLS and DPoP. Its token exchange only accepts subject tokens stored in its own `OAuth2AuthorizationService`, though, so it cannot exchange tokens minted by aj's custom filters. Adopting it means a full AS migration, not an add-on.
- Lowest-effort compliant path for aj: add `/oauth2/introspect` and a `token-exchange` grant to the custom AS, emit and validate `aud`, and keep Spring's standard client and RS components on the MCP side. DPoP and mTLS bring little value for a local-only deployment, and Spring has no DPoP *client*.

## Key Decisions
- Treat RFC 8693 exchange (delegation flavour, with `act`) as the industry baseline — Auth0 OBO, Okta MCP guide, Keycloak V2, SAS and Entra OBO all issue a new audience-restricted token for the downstream hop.
- Prefer Spring's built-in client components over hand-rolled HTTP — `TokenExchangeOAuth2AuthorizedClientProvider` and `OAuth2ClientHttpRequestInterceptor` already handle the grant, expiry/clock skew and error mapping, and are AS-agnostic.
- Do not cache exchanged tokens with Spring's default principal-name key without review — see `external-patterns-caching-effort.md`. The key mixes scopes across MCP clients of the same user.
- SAS / Keycloak migration is a cost/benefit item, not a prerequisite — the client/RS side is identical either way. Only the AS changes.

## Open Questions / Risks
- If the MCP server uses opaque-token introspection, the Spring principal is `BearerTokenAuthentication`. Its `getPrincipal()` is an `OAuth2AuthenticatedPrincipal`, not an `OAuth2Token`, so the default subject-token resolver of `TokenExchangeOAuth2AuthorizedClientProvider` returns `null` and you need a custom `setSubjectTokenResolver`. Confidence: medium-high, inferred from the provider source plus the Spring token class hierarchy, not tested.
- `JwtValidators.createDefault*()` includes `JwtTypeValidator.jwt()`, which rejects `typ: at+jwt`. If the host adopts RFC 9068 `typ`, the MCP server must use `createAtJwtValidator()` or `validateTypes(false)`.
- `NimbusJwtDecoder.withSecretKey` defaults to HS256, and jjwt picks the HMAC algorithm from key length. A shared-secret design must pin the algorithm on both sides (verify against the host code).
- The InfoQ article (dated 2026-10-15) is the only source for the 7.1 `.dPoP()` opt-in change and the 7.1.1 replay validator. Confidence: medium.
- AWS AgentCore and Azure APIM details come from search snippets and conflict in places. Low confidence. Context only.

---

## 1. Spring Security 7 (version 7.1.2 docs, stable as of 2026-11)

### 1.1 Inbound: opaque-token introspection (RFC 7662)
**Source**: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/opaque-token.html (7.1.2)
- **Mechanism**: `OpaqueTokenAuthenticationProvider` delegates to an `OpaqueTokenIntrospector`. The default is `SpringOpaqueTokenIntrospector`, auto-configured by Boot. The docs recommend `RestClientOpaqueTokenIntrospector` for timeout control.
- **Config**: `spring.security.oauth2.resourceserver.opaquetoken.{introspection-uri,client-id,client-secret}`. The RS authenticates to the introspection endpoint with client credentials (Basic), which satisfies the RFC 7662 §4 requirement that the AS authenticate callers.
- **Authorities**: `scope` is mapped to `SCOPE_<scope>`. A custom introspector can map to `PERMISSION_*`-style authorities.
- **Trust model**: "the authorization server's word is the law". Revocation takes effect immediately on the next call. The page describes this as useful "when revocation is a requirement".
- **Not covered by Spring**: audience validation for introspection results (you check the `aud` attribute yourself) and caching (no built-in cache; you wrap a custom introspector).
- **Defaults**: 30 s connect/read timeout.
- **Confidence**: High.

### 1.2 Inbound: JWT validation (RFC 9068 / audience)
**Source**: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html (7.1.2); `JwtValidators.java` on `main` (https://raw.githubusercontent.com/spring-projects/spring-security/main/oauth2/oauth2-jose/src/main/java/org/springframework/security/oauth2/jwt/JwtValidators.java)
- `issuer-uri` / `jwk-set-uri`, plus the Boot property `spring.security.oauth2.resourceserver.jwt.audiences` for `aud` checks. Programmatic alternative: `JwtClaimValidator<List<String>>(AUD, ...)` combined with `createDefaultWithIssuer`.
- Defaults: `createDefault()` = `JwtTypeValidator.jwt()` + `JwtTimestampValidator` (60 s skew) + `X509CertificateThumbprintValidator`. It has **no issuer and no audience check** unless you add one.
- RFC 9068: `JwtValidators.createAtJwtValidator()` (`@since 6.5`) requires `typ` `at+jwt`, `exp`, `iat`, `sub`, `jti` and `client_id`, and `build()` requires `issuer()` and `audience()`. The decoder must be built with `.validateTypes(false)`.
- Symmetric keys: `NimbusJwtDecoder.withSecretKey(key)` is supported, with no caveats on the page. Asymmetric verification trusts only RS256 by default.
- JWKS cache: in-memory, 5 minutes by default. A Spring `Cache` can replace it.
- **Confidence**: High.

### 1.3 Outbound: token exchange client (RFC 8693)
**Sources**: https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html (7.1.2, `#oauth2-client-token-exchange`); provider source https://raw.githubusercontent.com/spring-projects/spring-security/main/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/TokenExchangeOAuth2AuthorizedClientProvider.java ; blog https://spring.io/blog/2024/03/19/token-exchange-support-in-spring-security-6-3-0-m3
- **Registration**: `authorization-grant-type: urn:ietf:params:oauth:grant-type:token-exchange` with `token-uri`, `client-id`, `client-secret` and `scope`.
- **Provider** (`@since 6.3`): `TokenExchangeOAuth2AuthorizedClientProvider` is registered through `OAuth2AuthorizedClientProviderBuilder.builder().provider(...)`.
  - **Subject token (default)**: `context.getPrincipal().getPrincipal() instanceof OAuth2Token`. This works for `JwtAuthenticationToken` (principal = `Jwt`). See the risk above for `BearerTokenAuthentication`.
  - **Actor token**: none by default (`setActorTokenResolver`). Without an actor token, RFC 8693 semantics are impersonation-like, and whether `act` appears depends on the AS.
  - **Reuse**: returns the stored authorized client while `expiresAt - 60 s` has not passed. It never uses refresh tokens. On expiry it runs a new exchange.
- **`audience` / `resource` parameters**: not sent by default. Add them with `RestClientTokenExchangeTokenResponseClient.addParametersConverter(...)`. The docs example sets `OAuth2ParameterNames.AUDIENCE`.
- **Transport**: `RestClientTokenExchangeTokenResponseClient` (RestClient). Errors go through `OAuth2ErrorResponseErrorHandler`.
- **Confidence**: High.

### 1.4 Outbound: attaching the token to `RestClient`
**Source**: https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorized-clients.html (7.1.2)
- `new OAuth2ClientHttpRequestInterceptor(authorizedClientManager)` is added via `RestClient.builder().requestInterceptor(...)`.
- Registration ID: request attribute `clientRegistrationId("aj-backend")`, or a custom `ClientRegistrationIdResolver`.
- Principal: `SecurityContextHolderPrincipalResolver` by default. This matters for MCP SDK threading, because the security context must be present on the thread that calls `RestClient`. `RequestAttributePrincipalResolver` is the alternative.
- Failure handling: `OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(repository|service)` removes the cached authorized client when the downstream rejects the token.
- `DefaultOAuth2AuthorizedClientManager` needs an `HttpServletRequest`. `AuthorizedClientServiceOAuth2AuthorizedClientManager` works outside one (https://docs.spring.io/spring-security/reference/servlet/oauth2/client/core.html).
- **Confidence**: High.

### 1.5 Spring Boot 4 starters
- `spring-boot-starter-oauth2-resource-server` and `-oauth2-client` are deprecated in Boot 4 in favour of `spring-boot-starter-security-oauth2-resource-server` and `spring-boot-starter-security-oauth2-client`. The AS starter likewise becomes `spring-boot-starter-security-oauth2-authorization-server`. Sources: Maven Central listing https://central.sonatype.com/artifact/org.springframework.boot/spring-boot-starter-oauth2-resource-server and the InfoQ article below. **Confidence**: Medium-High.
- Applies to the aj MCP server (Boot 4, Jackson 2 per standards): adding the RS and client starters is the only dependency change needed for Spring-native inbound validation and outbound exchange.

## 2. Spring Authorization Server (inside Spring Security 7)

**Sources**: https://spring.io/blog/2025/09/11/spring-authorization-server-moving-to-spring-security-7-0 ; https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/protocol-endpoints.html (7.1.2); provider source https://raw.githubusercontent.com/spring-projects/spring-security/main/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/authentication/OAuth2TokenExchangeAuthenticationProvider.java ; InfoQ https://infoq.com/articles/spring-security-7-server-side-dpop-token-exchange (2026-10-15)

- **Status**: merged into Spring Security 7.0 (announced 2025-09-11, GA with 7.0.0 on 2025-11-17). The artifact `org.springframework.security:spring-security-oauth2-authorization-server` is unchanged, and the standalone 1.5.x line reached OSS end of life in June 2026. **It works with Boot 4.**
- **Grants**: `authorization_code`, `refresh_token`, `client_credentials`, `device_code`, `token-exchange`. Also introspection (`OAuth2TokenIntrospectionEndpointFilter`), revocation, JWKS (only when a `JWKSource` bean exists), RFC 8414 metadata, and OIDC/RFC 7591 DCR (disabled by default, needs a `client.create`-scoped token, or `setOpenRegistrationAllowed(true)`).
- **Token exchange internals** (source-verified):
  - The subject token is looked up with `authorizationService.findByToken(subjectToken, ACCESS_TOKEN)`, so **it must be a token this SAS instance issued and stored**. It must be active, and its type must be `access_token`, or `jwt` for self-contained tokens.
  - `may_act` on the subject token forces an `actor_token` whose `iss` and `sub` match. Actors are collected into `OAuth2TokenExchangeCompositeAuthenticationToken`, and the JWT generator renders that as `act`. Nested chains are supported.
  - The client must have the `TOKEN_EXCHANGE` grant, otherwise the AS returns `unauthorized_client`. Requested scopes must be a subset of the **client's** registered scopes, and they are **not** intersected with the subject token's scopes.
  - **No refresh token** is issued. **Audience and resource are not handled**: you write an `OAuth2TokenCustomizer<JwtEncodingContext>` to set `aud` and to narrow scope.
  - A DPoP proof is verified if present.
- **RFC 8707**: not mentioned in the SAS protocol docs. The community `mcp-authorization-server` module adds resource indicators (see §6).
- **Implication for aj**: SAS cannot be bolted onto the custom AS just for token exchange, because it would reject tokens it did not issue. Using SAS means replacing `OAuth2TokenFilter`, `OAuth2AuthorizationFilter`, `PublicClientRegistrationFilter`, the refresh-token store and the client table with SAS equivalents (JDBC `oauth2_authorization` schema, `RegisteredClientRepository`), plus switching to asymmetric JWKS signing. Effort: **high**. Benefit: a maintained, RFC-complete AS with introspection, exchange, JWKS, DPoP and mTLS.
- **Confidence**: High (source and docs).

## 3. Keycloak — Standard Token Exchange V2 (26.2+)
**Source**: https://www.keycloak.org/securing-apps/token-exchange
- **Scope**: internal-to-internal only, within the same realm. The requester must be a **confidential** client with "Standard token exchange" enabled. Public clients cannot exchange.
- **Subject-token rule**: the requesting client must be in the subject token's `aud`, unless the token was issued to itself. Only `access_token` subject types are accepted.
- **Parameters**: `subject_token`, `subject_token_type`, optional `requested_token_type` (access/ID/refresh), `scope` (can up-scope within the requester's optional scopes), and `audience` (filters, never adds). **`resource` is not supported.**
- **Lifetime**: the exchanged token's expiry is capped at the subject token's expiry by default.
- **Revocation**: revoking the exchanged access token does not revoke the original, and vice versa. Revoking the subject token does revoke exchanged refresh tokens. The docs advise short-lived access tokens.
- **Delegation**: `may_act` + `actor_token` produces `act`. It is a preview feature (`token-exchange-delegation`, plus `parameterized-scopes` and FGAP v2). Impersonation is "not implemented yet" in V2. V1 is deprecated.
- **Sender-constrained**: DPoP/mTLS-bound subject tokens are accepted only from the bound client with a valid proof.
- **RFC 8707 at authorization**: a third-party PR text (via https://mail-archive.com/issues@solr.apache.org/msg65780.html) says Keycloak lacks `resource=` support for authorization requests, so MCP audiences are set with audience mappers. **Low confidence.**
- **Effort for aj**: very high. Users, login UI, permissions-to-roles mapping, DCR for MCP clients and the dev stack (compose) all move to Keycloak. It is an alternative for the "external IdP" option only.

## 4. Auth0
### 4.1 On-Behalf-Of Token Exchange (RFC 8693 profile)
**Source**: https://auth0.com/docs/secure/call-apis-on-users-behalf/on-behalf-of-token-exchange ; MCP quickstart https://auth0.com/ai/docs/mcp/get-started/call-your-apis-on-users-behalf
- **Request**: `grant_type=token-exchange`, `subject_token` (incoming AT), `subject_token_type=access_token`, `requested_token_type=access_token`, `audience` (downstream API, required), and confidential client auth (`none` not allowed).
- **Who can call**: only a "Custom API client" (`app_type: resource_server`) whose `resource_server_identifier` equals the incoming token's audience. This is how Auth0 enforces "you can only exchange tokens issued to you".
- **Result claims**: `sub` preserved, `aud` = downstream, `azp` = MCP server client, and a nested `act` (max 5 levels) whose outermost `act.sub` equals `azp`. Scopes come from user RBAC.
- **Caching**: Auth0 advises caching exchanged tokens for their lifetime instead of exchanging on every call. Rate limit: 30 RPS by default.
- **DPoP/mTLS**: Auth0 does **not** re-verify the subject token's binding during exchange, so the middle tier must verify it. Without a proof, the result is an unbound bearer token.
### 4.2 Custom Token Exchange
**Source**: https://auth0.com/docs/authenticate/custom-token-exchange — an Action-driven exchange for external or arbitrary subject tokens. The developer must validate the subject token, and the docs warn of spoofing and replay if validation is weak. It is not the right tool for first-party tokens.

## 5. Microsoft Entra ID — On-Behalf-Of (OBO)
**Source**: https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-on-behalf-of-flow (updated 2026-06-15); MSAL caching https://learn.microsoft.com/entra/msal/dotnet/acquiring-tokens/web-apps-apis/on-behalf-of-flow
- **Grant**: `urn:ietf:params:oauth:grant-type:jwt-bearer` with `requested_token_use=on_behalf_of`, `assertion` = incoming AT and `scope` = downstream scopes, plus client secret or certificate (`client_assertion`). This is **not RFC 8693** wire format, but it has the same semantics.
- **Audience rule**: the incoming token "must have an audience (`aud`) claim of the app making this OBO request". Tokens meant for other APIs must be rejected, not exchanged.
- **Explicit warning**: do not send access tokens issued to the middle tier anywhere except their intended audience. That is the passthrough prohibition in vendor form.
- **Delegated scopes only**: roles stay with the user principal. OBO works only for user principals; app-only tokens use client credentials.
- **Errors**: Conditional Access errors (`interaction_required` + claims challenge) must be surfaced to the client as `401` + `WWW-Authenticate` per RFC 6750 §3.1.
- **Caching**: MSAL caches OBO tokens keyed by a **hash of the incoming assertion** by default, and can be overridden with a session key such as `sid`. It uses the cached refresh token on expiry.

## 6. MCP-specific implementations
### 6.1 Spring AI `mcp-security` (community, spring-ai-community)
**Sources**: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-security.html (Spring AI 2.0.2 docs); https://github.com/spring-ai-community/mcp-security (v0.1.14; 0.1.x requires Spring AI 2.0.x); https://spring.io/blog/2025/09/30/spring-ai-mcp-server-security/
- `mcp-server-security`: OAuth2 RS for Spring AI MCP servers (WebMVC only, Streamable HTTP or stateless). It uses **JWT only (no opaque tokens)**, sets `issuer-uri`, and returns a 401 `WWW-Authenticate` pointing to resource metadata. `validateAudienceClaim(true)` is **off by default**, because not every AS supports RFC 8707. It also offers API-key auth, origin/host validation and optional session binding.
- `mcp-authorization-server`: extends SAS with DCR and resource indicators. Limitation: every client can use every resource.
- **Downstream calls from tools: not addressed at all.** Neither docs nor README cover exchange or passthrough. Combining it with Spring's §1.3/§1.4 client components is left to the developer.
- **Fit for aj**: aj's MCP server uses the raw MCP Java SDK with a custom filter. Adopting `mcp-server-security` would require Spring AI 2.0 plus JWT inbound (shared HMAC or JWKS). Plain `spring-boot-starter-security-oauth2-resource-server` gives the same RS behaviour with fewer moving parts. **Confidence**: High (docs). WIP status is stated by the project.

### 6.2 Okta — MCP server token exchange guide (Beta)
**Source**: https://developer.okta.com/docs/guides/ai-mcp-server-token-exchange/main/
- The MCP server receives T1 (`aud` = MCP server). It runs a token exchange to get an **ID-JAG** (`requested_token_type=id-jag`, `audience` = downstream AS issuer, `resource`, `scope`), then runs a jwt-bearer grant at the downstream custom AS to get T3 (`aud` = downstream resource).
- Client auth is `private_key_jwt` (mandatory). `sub` is preserved, `act` is nested (MCP server outermost, max 5 actors), and there is one exchange per audience. Lifetimes: T2 300 s, T3 3600 s. No caching guidance.
- This is the cross-domain variant (Identity Assertion Authorization Grant). For aj, with a single AS, a one-step RFC 8693 exchange is sufficient.

### 6.3 Cloudflare Agents / `workers-oauth-provider`
**Source**: https://developers.cloudflare.com/agents/model-context-protocol/protocol/authorization/
- The MCP server sits behind an AS (the older `OAuthProvider` combined both roles). Every issued token is **bound to the MCP server resource URL**, and the RS rejects tokens for other resources.
- Upstream (third-party) tokens obtained during sign-in are stored **encrypted in `props`** and used only for the server's own upstream calls. The client's MCP token is never forwarded. `requiredScopes` is advertised but not enforced, so handlers must check scopes.
- Pattern name: "MCP server issues its own token, keeps upstream credentials server-side" (token-vault pattern). It applies when the upstream AS is a third party, which is not aj's case.

### 6.4 Gateways (AWS Bedrock AgentCore Gateway, Azure API Management) — low confidence
**Sources**: https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/gateway-outbound-auth.html ; https://aws.amazon.com/blogs/machine-learning/servicenow-on-behalf-of-token-exchange-with-bedrock-agentcore-gateway/ ; https://learn.microsoft.com/azure/api-management/secure-mcp-servers
- AgentCore uses outbound credential providers (`CreateOauth2CredentialProvider`): client_credentials M2M, or 3LO, where sources conflict on whether MCP targets support it. A worked example shows native OBO with Entra.
- APIM validates inbound Entra tokens (`validate-azure-ad-token`). Its credential manager (experimental) injects per-user upstream OAuth tokens. OBO is done via policies in community examples. APIM can *also* forward the Authorization header, which the docs present as an option and which is exactly the passthrough anti-pattern when audiences differ.
- **Takeaway**: gateways centralise the same two patterns, exchange/OBO or a stored upstream credential.

## 7. Sender-constrained tokens in Spring
**Sources**: https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/dpop-tokens.html (7.1.2); InfoQ (2026-10-15); SAS 1.3 release notes https://www.github.com/spring-projects/spring-authorization-server/releases ; `JwtValidators.java`
- **DPoP (RFC 9449)**:
  - RS verification has been GA since Spring Security 6.5. It requires the `cnf.jkt` claim, the `Authorization: DPoP` scheme and the `DPoP` proof header with `ath`/`htm`/`htu`/`iat`/`jti`.
  - In 7.1 it became opt-in via `.dPoP()`. Upgrading from 7.0 silently drops enforcement unless `.dPoP()` is added.
  - SAS binds tokens (`cnf.jkt`), and public-client refresh tokens too.
  - The replay cache is single-node in-memory, pluggable since 7.1.1.
  - **No nonce support** (`use_dpop_nonce`) on either side.
  - **No documented DPoP support in Spring OAuth2 Client.** An MCP-server-as-DPoP-client would need custom proof generation (Nimbus).
  - Confidence: medium-high.
- **mTLS (RFC 8705)**: SAS 1.3+ supports `tls_client_auth` / `self_signed_tls_client_auth` and certificate-bound access tokens (`cnf.x5t#S256`). On the RS side, `X509CertificateThumbprintValidator` is in `JwtValidators.createDefault()`, so it is active by default whenever a client certificate is present. Confidence: high (source).
- **Applicability to aj**: the MCP-to-host hop is server-to-server on localhost or a private network. Audience restriction plus short TTL plus confidential-client authentication of the MCP server covers RFC 9700's replay concern at much lower cost. DPoP and mTLS are documentable future hardening, not a requirement. The MCP spec does not mandate either for this hop (cross-check with `external-specs`).

## 8. Pattern comparison (industry view)

| Pattern | Who uses it | RFCs | Claims produced | Revocation / expiry | Spring Boot 4 support | Effort for aj (custom AS) |
|---|---|---|---|---|---|---|
| RFC 8693 exchange, delegation (`act`) | Auth0 OBO, Okta, Keycloak V2 (preview), SAS | 8693, 8707 (`resource`), 9068 | `sub` = user, `aud` = backend, `azp`/`client_id` = MCP server, `act.sub` = MCP server | New token, typically capped at subject `exp` (Keycloak). Revoking the subject does not kill the exchanged AT (Keycloak), so keep TTL short | Client: `TokenExchangeOAuth2AuthorizedClientProvider` (6.3+). AS: SAS only for SAS-issued tokens | **Medium**: add the grant in `OAuth2TokenFilter`, a confidential MCP client, `aud` + `act` claims, and backend `aud` check |
| RFC 8693 exchange, impersonation (no `act`) | SAS default without actor, Keycloak V2 default | 8693 | `sub` = user, `aud` = backend, `client_id` = MCP server | as above | as above | Medium. The backend loses the "via MCP" signal unless `client_id` is checked |
| Entra OBO (jwt-bearer) | Microsoft | 7523 + proprietary `requested_token_use` | `aud` = downstream, `azp`/`appid` = middle tier | MSAL cache keyed by assertion hash | n/a (MSAL4J) | n/a |
| Introspection inbound (RFC 7662) | SAS, Keycloak, Auth0 (opaque), Cloudflare (`validateToken`) | 7662 | `active`, `aud`, `scope`, `client_id`, `sub` | Immediate (unless cached) | `opaqueToken()` (High) | **Low-Medium**: one endpoint plus client auth on the AS |
| Local JWT validation inbound | Spring AI mcp-security, most RS | 9068, 7517 (JWKS) | as issued | Only at `exp` | `jwt()` + audience/at+jwt validators (High) | Low with a shared HMAC key (risky). Medium with RS256 + JWKS |
| Token vault / stored upstream credential | Cloudflare props, APIM credential manager, AgentCore | — | upstream-specific | Upstream refresh | custom | Not applicable (single first-party AS) |
| Client credentials + user header | (anti-pattern discussed by vendors as weaker) | 6749 §4.4 | `sub` = MCP server, user in an unsigned header | n/a | `clientCredentials()` | Low effort, but the backend must trust MCP for user identity (confused deputy) |
