# Host OAuth2 AS and JWT security: capability inventory (branch `feat/mcp-auth-refactor`)

## TL;DR
- The host "authorization server" is hand-written: three `OncePerRequestFilter`s (`/oauth2/register`, `/oauth2/authorize`, `/oauth2/token`) plus a metadata controller. It supports only `authorization_code` (+PKCE S256) and `refresh_token`. It has no introspection, token exchange, revocation, JWKS, `client_credentials` or `resource` parameter.
- Every token (app login JWT and OAuth2 access token) is HS256-signed with one shared HMAC secret. The default value is committed in `application.properties`, and no compose or start script overrides it. Tokens carry no `aud`, no `client_id` and no `typ`. Only the OAuth2 token carries `iss`.
- `JwtAuthenticationFilter` checks only the signature and `exp`. It never checks `iss`, `aud` or token type. Any validly signed token is accepted on every `/api/**` endpoint, whether it is an app session token or an MCP OAuth token. This is the property that makes token passthrough work today.
- `spring-security-oauth2-authorization-server` (7.0.4 via the Boot 4.0.5 BOM) is used only as a type library: `RegisteredClient`, `RegisteredClientRepository`, `OidcClientRegistration` and its converter. The SAS filter chain, `AuthorizationServerSettings` and `JWKSource` are never configured.
- Granted OAuth scopes are not intersected with the user's permissions. A READ-only `viewer` can obtain `mcp:edit` and therefore write products and categories.

## Key Decisions
- The inventory was built from the code only. `.maister/docs/standards/*` and `CLAUDE.md` were not used as evidence, because their wording ("validates ... exchanges") describes the target state.
- Confidence is High (direct code reading) unless a finding says otherwise.

## Open Questions / Risks
- Liquibase changelog numbering skips `010` (`src/main/resources/db/changelog/2026/` holds 001–009 and 011–013). Whatever 010 was, it is not on this branch. It was not investigated, per the exclusion rule.
- In-memory authorization code and refresh token stores (`ConcurrentHashMap`) are lost on restart and are not shared between instances. Any design that adds introspection or exchange state must decide on persistence.
- The token endpoint and the DCR handler log full access and refresh tokens at INFO level. The same applies to any new grant built by copying this code.

---

## 1. Endpoint inventory

| Endpoint | Implemented by | Security matcher | Notes |
|---|---|---|---|
| `GET /.well-known/oauth-authorization-server` | `OAuth2MetadataController.java:37-52` | `permitAll` (`SecurityConfiguration.java:92`) | RFC 8414 metadata, hand-built `Map` |
| `POST /oauth2/register` | `PublicClientRegistrationFilter.java:47-120` | `permitAll` (`SecurityConfiguration.java:93`) | Open, unauthenticated DCR (RFC 7591 style) |
| `POST /oauth2/authorize` | `OAuth2AuthorizationFilter.java:43-122` | Not explicitly matched. It falls under the "non-API path" `permitAll` at `SecurityConfiguration.java:103`, and the filter checks `SecurityContext` itself (`OAuth2AuthorizationFilter.java:92-95`) | Only POST. GET `/oauth2/authorize` is served by the SPA consent page (`src/main/frontend/src/pages/OAuth2AuthorizePage.tsx`) |
| `POST /oauth2/token` | `OAuth2TokenFilter.java:49-76` | `permitAll` (`SecurityConfiguration.java:94`) | Grants `authorization_code` and `refresh_token` only |
| `GET /api/oauth2/client-info?client_id=` | `OAuth2MetadataController.java:24-35` | `permitAll` (`SecurityConfiguration.java:97`) | Unauthenticated. Returns client name and scopes for the consent page |
| `POST /api/auth/login` | `api/AuthController.java:36-62` | `permitAll` (`SecurityConfiguration.java:98`) | Username/password → app JWT with `permissions` claim |
| Introspection, revocation, JWKS, token exchange, userinfo, PAR | — | — | **Not present.** Grep for `introspect`, `jwks`, `revoc`, `token_exchange`, `audience` and `resource` in `core/` returns nothing |

Filter wiring (`SecurityConfiguration.java:134-142`) runs `JwtAuthenticationFilter`, then `PublicClientRegistrationFilter`, then `OAuth2AuthorizationFilter`, then `OAuth2TokenFilter`. All are added with `new` inside the chain, not as beans. Each AS filter short-circuits only when the exact URI and method match (for example `OAuth2TokenFilter.java:52`).

## 2. Token endpoint (`OAuth2TokenFilter`)

### 2.1 Grant dispatch
```java
// OAuth2TokenFilter.java:62-69
String grantType = request.getParameter("grant_type");
if ("authorization_code".equals(grantType)) { handleAuthorizationCodeGrant(...) }
else if ("refresh_token".equals(grantType)) { handleRefreshTokenGrant(...) }
else { sendError(response, OAuth2Error.UNSUPPORTED_GRANT_TYPE, ...) }
```
Supported grants: `authorization_code` and `refresh_token`. `client_credentials` and `urn:ietf:params:oauth:grant-type:token-exchange` fall through to `unsupported_grant_type`.

### 2.2 authorization_code (`OAuth2TokenFilter.java:78-154`)
- `code` and `redirect_uri` are required (:84-92). The code is consumed one time (`AuthorizationCodeService.consumeAuthorizationCode`, `AuthorizationCodeService.java:31-45`, `remove` then a 600 s TTL check).
- `client_id` is optional. It is compared only when it is present (:102-107).
- `redirect_uri` must match the value from the authorization request exactly (:110-114).
- **Client authentication**: the client counts as confidential when none of its methods is `none` (:117-121). The secret is read **only from the form parameter `client_secret`** (:82) and checked with `passwordEncoder.matches` (BCrypt) (:126). The `Authorization: Basic` header is never parsed, so `client_secret_basic` is advertised (metadata :49, DCR :128) but not implemented. A client registered with `client_secret_basic` that sends a Basic header gets `invalid_client` (:122-124).
- **PKCE**: verified when a challenge was stored (:134-140). The check enforces verifier length 43–128, the unreserved charset, S256 only, and base64url(SHA-256) equality (:183-214).
- **Scope → token**: `grantedScopes = data.scope().split(...)` (:143-145). The user's permissions captured at authorization time (`AuthorizationCodeData.permissions`) are **never read**: `grep "permissions()"` finds no consumer of `AuthorizationCodeData.permissions`.
- `issuer` = `getBaseUrl(request)`, derived from `X-Forwarded-Proto/Host/Port` or the request (:146, :254-282). It is controllable by the caller through headers.
- The token response is a hand-built JSON string (:216-237) with `token_type: Bearer`, hard-coded `expires_in: 900`, `refresh_token` and `scope`. It sets `Cache-Control: no-store`. No `issued_token_type` field.
- **Sensitive logging**: `log.info("OAuth2 token issued | ... access_token={} | refresh_token={}", ...)` (:152-153) and `log.info("OAuth2 token response body: {}", ...)` (:234). The request-parameter dump at :57-59 also logs `code`, `code_verifier`, `client_secret` and `refresh_token`.

### 2.3 refresh_token (`OAuth2TokenFilter.java:156-181`, `RefreshTokenService.java`)
- Only `refresh_token` is required. There is **no client authentication and no client binding**: `RefreshTokenData(username, permissions, scope, createdAt)` (`RefreshTokenService.java:75-80`) stores no `clientId`.
- Rotation: `consumeAndRotate` removes the old token and issues a new one (`RefreshTokenService.java:31-50`). A reused token fails with `invalid_grant`. There is no reuse detection or family revocation (RFC 9700 §4.14.2 recommends invalidating the active token on replay).
- TTL is 24 h from the issuance of **each rotated token** (`RefreshTokenService.java:18,45`). This is a sliding window, so a session can be extended indefinitely.
- Storage: an in-memory `ConcurrentHashMap` (:21), with a cleanup job every 30 min (:52). Tokens are opaque: 32 random bytes, base64url (:69-73).
- Scope cannot be narrowed on refresh. The `scope` request parameter is ignored and the original scope is reissued (:172-176).

## 3. Authorization endpoint (`OAuth2AuthorizationFilter`)
- POST only (`OAuth2AuthorizationFilter.java:46`). Requires `client_id`, `redirect_uri` and `response_type=code` (:75-81).
- The client must exist (:83-86). `redirect_uri` must match a registered URI exactly (:130-134). Each requested scope must be among the client's scopes (:136-147).
- PKCE is **required for public clients** (auth method `none`) and optional for confidential ones. Only S256 is accepted, and the challenge length and format are checked (:149-174).
- User authentication comes from the `SecurityContext` that `JwtAuthenticationFilter` populated. The consent page submits a native form with the app JWT in a hidden `_token` field (`OAuth2AuthorizePage.tsx:97-99`). `JwtAuthenticationFilter.extractToken` accepts `_token` on **any** request (`JwtAuthenticationFilter.java:47-51`), including query strings.
- The filter collects the user's `PERMISSION_*` authorities (:98-102) and stores them, but they are never used later (see 2.2). The consent UI also does not filter scopes by user permission (`OAuth2AuthorizePage.tsx:47-57`). Result: a `viewer` (READ only, `008-create-users-table.yaml:116-120`) can consent to `mcp:edit` and receive a token that maps to `PERMISSION_mcp:edit`, which grants POST/PUT/DELETE on products and categories (`SecurityConfiguration.java:115-120`). **Privilege escalation, High confidence.**
- No `resource` parameter is read (grep finds no matches). `state` is appended to the redirect without URL-encoding (:111-114).
- Codes: 32 random bytes, base64url, 10 min TTL, in memory (`AuthorizationCodeService.java:16-18`).

## 4. Dynamic client registration (`PublicClientRegistrationFilter`)
- Unauthenticated, open registration (`SecurityConfiguration.java:93`). No initial access token and no rate limiting.
- `client_id` and `client_secret` are random UUIDs (`PublicClientRegistrationFilter.java:84-85`). The **secret is BCrypt-hashed before storage** (:91). A secret is generated and returned in the response **even for `token_endpoint_auth_method=none`** (:85, :103), although it is never checked for such clients.
- `token_endpoint_auth_method` accepts `client_secret_post` (default), `client_secret_basic` or `none` (:122-133).
- Grant types allowed: only `authorization_code` and `refresh_token` (:135-147). A `client_credentials` or token-exchange client **cannot be registered** through DCR.
- Scopes are allowlisted to `mcp:read` and `mcp:edit` and default to both (:32, :79-82, :181-187).
- Redirect URIs must be HTTPS or loopback with no fragment, and are only validated when `grant_types` contains `authorization_code` (:158-179). If `grant_types` is omitted, the code defaults to auth code (:136-138) but skips redirect validation, and the later `uris.addAll(redirectUris)` / `grants.addAll(grantTypes)` calls (:92, :107) would hit a null. This likely ends in a 500 `server_error`. Medium confidence: inferred, not tested.
- The response is built with SAS's `OidcClientRegistration` and `OidcClientRegistrationHttpMessageConverter` (:101-116), with `client_secret_expires_at=0`.

## 5. Client persistence
- `DatabaseRegisteredClientRepository` implements SAS `RegisteredClientRepository` over JPA (`DatabaseRegisteredClientRepository.java:21-103`). It maps auth methods, grant types, redirect URIs and scopes to `text[]` columns.
- Migration `009-create-oauth2-registered-client-table.yaml` creates `oauth2_registered_client` (id uuid, client_id unique, client_id_issued_at, client_secret varchar(255), client_secret_expires_at, client_name, client_authentication_methods text[], authorization_grant_types text[], redirect_uris text[], scopes text[], created_at, updated_at) plus an index on `client_id`. Entity: `RegisteredClientEntity.java:20-70`.
- **No client is seeded.** No migration inserts into `oauth2_registered_client` (`db.changelog-master.yaml` → `includeAll ./2026`, and only 009 touches this table). No MCP-server confidential client exists. The only seeded identities are users `viewer`, `editor` and `admin` (`008-create-users-table.yaml:87-145`, context `dev`).
- The table has no columns for token settings, client settings, JWKS URI, allowed audiences or allowed token-exchange subjects.

## 6. AS metadata (RFC 8414)
`OAuth2MetadataController.java:41-51` returns exactly:
```
issuer, authorization_endpoint, token_endpoint, registration_endpoint,
grant_types_supported = [authorization_code, refresh_token],
response_types_supported = [code],
code_challenge_methods_supported = [S256],
token_endpoint_auth_methods_supported = [client_secret_post, client_secret_basic, none],
scopes_supported = [mcp:read, mcp:edit]
```
Missing: `jwks_uri`, `introspection_endpoint`, `introspection_endpoint_auth_methods_supported`, `revocation_endpoint`. Neither the metadata nor the code has token-exchange support. `issuer` is computed per request from `X-Forwarded-*` headers (:54-83), so it is not a stable configured value. `client_secret_basic` is advertised but not honoured by the token endpoint (section 2.2).

## 7. JWT issuance (`JwtTokenProvider`)
- **Key**: `Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret))` (`JwtTokenProvider.java:23-26`), from `app.jwt.secret=${APP_JWT_SECRET:i/WZnrbvFqiPfShuZjGmc5kC7IXxRZfpueJEdgCzGFc=}` (`application.properties:4`). The default decodes to 32 bytes, so jjwt 0.12.6 (`pom.xml:82-97`) signs with **HS256**. That is symmetric, there is no `kid`, and there is no rotation. `APP_JWT_SECRET` is not set in `compose.yml`, `start-dev.sh` or `start-dev.ps1` (grep finds nothing), so the committed default is effectively the key everywhere.
- **App login token** (`generateToken` / `generateTokenWithExpiration`, :29-43): `sub`, `permissions` (e.g. `READ`, `EDIT`, `PLUGIN_MANAGEMENT`), `iat`, `exp` = 24 h (`app.jwt.expiration-ms=86400000`, `application.properties:5`). No `iss`, `aud`, `jti` or `typ`.
- **OAuth2 access token** (`generateOAuth2Token`, :45-58): `iss` (request-derived), `sub`, `scopes` (a JSON array, not the RFC 9068 / RFC 8693 space-delimited `scope` string), `iat`, `exp` = 15 min. **No `aud`, `client_id`, `jti`, `auth_time`, `act` or `typ: at+jwt`.**
- jjwt's default header carries only `alg`. No `typ` is set.

## 8. JWT validation (`JwtAuthenticationFilter` + `JwtTokenProvider.parseToken`)
```java
// JwtTokenProvider.java:68-83
Claims claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
List<String> permissions = claims.get("permissions", List.class);
if (permissions == null) permissions = claims.get("scopes", List.class);
```
- Checks the signature and the standard time claims (`exp`, plus `nbf` if present, which jjwt enforces by default with zero clock skew). **There is no `requireIssuer`, `requireAudience` or token-type check.**
- `permissions` and `scopes` are merged into one namespace. Each value becomes a `PERMISSION_<value>` authority (`JwtAuthenticationFilter.java:28-35`). A token holding `scopes:[mcp:read]` gets `PERMISSION_mcp:read`, and one holding `permissions:[EDIT]` gets `PERMISSION_EDIT`.
- An invalid token is silently ignored and the request continues anonymous (:27-39), so the result is a 401 from the entry point (`SecurityConfiguration.java:65-76`). No `WWW-Authenticate` header is emitted.
- Token sources: the `Authorization: Bearer` header, or the `_token` request parameter on any path (:42-53).
- Consequence for the MCP hop: an OAuth token issued to an MCP client is accepted directly by the host REST API. Nothing distinguishes "token for the MCP server" from "token for the host". This is the audience confusion that makes passthrough possible. In the other direction, a 24 h app session token with `PLUGIN_MANAGEMENT` would also be accepted if presented to the MCP server and forwarded.

## 9. `SecurityConfiguration` rules relevant to MCP
- `mcp:read` → `PERMISSION_mcp:read`, accepted alongside `PERMISSION_READ` on `GET /api/categories/**`, `GET /api/products/**` and `GET /api/footprints/calculations/*/export` (`SecurityConfiguration.java:106-108`).
- `mcp:edit` → `PERMISSION_mcp:edit`, accepted alongside `PERMISSION_EDIT` on POST/PUT/DELETE `/api/categories/**` and `/api/products/**` (:115-120).
- Plugin endpoints accept only `PERMISSION_READ`, `PERMISSION_EDIT` or `PERMISSION_PLUGIN_MANAGEMENT` (:109-112, :121-129), so MCP-scoped tokens cannot reach them.
- Fallback: `.anyRequest().authenticated()` (:132). Non-`/api/` paths are `permitAll` (:103).
- There are no matchers for introspection or token-exchange endpoints (they do not exist). `/oauth2/token` is `permitAll`, and client authentication happens inside the filter.
- `authorizeHttpRequests` holds all authorization. No `@PreAuthorize` is used (consistent with CLAUDE.md).

## 10. `spring-security-oauth2-authorization-server` usage
- Declared at `pom.xml:69-72` without a version. It is managed by `spring-boot-starter-parent` 4.0.5 (`pom.xml:5-10`), and the local `~/.m2` holds **7.0.4**. In Spring Security 7, SAS is part of the Spring Security release train.
- Imports in `src/main/java`: `RegisteredClient`, `RegisteredClientRepository`, `OidcClientRegistration`, `OidcClientRegistrationHttpMessageConverter` (SAS), plus `AuthorizationGrantType` and `ClientAuthenticationMethod` (`spring-security-oauth2-core`, transitive). The SAS configurer, `AuthorizationServerSettings`, `JWKSource`, `OAuth2TokenGenerator`, `OAuth2AuthorizationService` and the SAS filters are **not used** (grep for `AuthorizationServerSettings|JWKSource|OAuth2AuthorizationServer` returns nothing).
- No Boot auto-configuration module for the AS is on the classpath: only the raw artifact is declared, not `spring-boot-starter-security-oauth2-authorization-server`. No resource-server starter is present either (`spring-boot-starter-oauth2-resource-server` is absent). Validation is pure jjwt.
- **Implication**: the dependency gives a ready `RegisteredClient` model, so migrating to SAS would reuse `DatabaseRegisteredClientRepository`. But the token endpoint, authorization endpoint and token format would all have to be replaced, not extended.

## 11. Tests
`src/test/java/pl/devstyle/aj/core/oauth2/OAuth2IntegrationTests.java` (8 tests, `@Transactional`, MockMvc):
- `getMetadata_returnsOAuth2ServerMetadata` (:85-93) asserts only issuer and the three endpoint URLs. It does not assert grant types or scopes.
- `registerClient_returns201WithClientCredentials` (:95-115) and `registerClient_withInvalidName_returns400` (:117-132).
- `authorizationCodeFlow_fullCycle` (:134-177), using a public client with PKCE and user `admin`.
- `authorize_withPkce_requiresCodeVerifierOnTokenExchange` (:179-212), `authorize_withInvalidRedirectUri_returns400` (:214-230).
- `tokenExchange_withRefreshToken_returnsNewAccessToken` (:232-278). Despite the name, this tests the refresh grant, not RFC 8693.
- `tokenExchange_withInvalidCode_returns400` (:280-289).

`core/security/AuthIntegrationTests.java` covers login, 401 without, expired, malformed and wrong-key tokens, 403 for viewer EDIT and editor PLUGIN_MANAGEMENT. `SecurityTestHelperTests.java` covers the mock-user annotations.

**Not tested**: confidential-client authentication at the token endpoint, `client_secret_basic`, refresh-token reuse, using an OAuth access token against `/api/**` (the `scopes` → `PERMISSION_mcp:*` mapping), the viewer-gets-`mcp:edit` escalation, `iss`/`aud` checks, and claim contents of issued tokens.
