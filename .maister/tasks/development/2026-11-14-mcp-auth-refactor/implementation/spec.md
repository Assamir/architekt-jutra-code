# Specification: MCP auth refactor — validate-then-exchange (P1)

## TL;DR
- The MCP server stops forwarding the client's token. It validates every bearer token through RFC 7662 introspection at the host and checks `aud` itself. For each tool call it exchanges the token (RFC 8693) for a short-lived host-API token (T2) and calls `/api` with T2 only.
- The hand-written host AS gets a fixed issuer, an `aud` on every JWT, RFC 8707 `resource` handling (slash-equal origin URIs), `ScopePolicy` (expand ∩ permissions ∩ client scopes, re-expand), `ClientAuthenticator`, an introspection endpoint, an exchange grant and an env-provisioned `aj-mcp-server` client. `/api` accepts only `iss` = issuer and `aud` ∋ `${issuer}/api`.
- Ports move to host 8180, MCP 8181, MCP management 9181. Same-touch hygiene: no token logging in either app (FU-3), local defaults and env wiring (FU-5), refresh tokens bound to their client (FU-2a). Three small consent/SPA touches. Tool names, schemas and results do not change.
- Revised after the spec audit (3 major, 15 minor, 4 user decisions): timed introspection client, byte-identical red tests, stricter error codes and test-plan gaps closed — see Audit resolution.

## Key Decisions
- Granted scope is computed at `/oauth2/authorize` (consent-time permissions) and stored in the auth code. The token endpoint issues the stored grant, and refresh re-issues it unchanged — this fails early, and current permissions are re-checked at every exchange anyway (ADR-004).
- The MCP server derives the introspection URI, token URI and backend resource from `aj.oauth.server-url`. No `introspection-uri`/`token-uri`/`backend.resource` properties: one source of truth, and the five property names used by the red test are kept.
- Exchanged-token TTL is a constant of 300 s, not a property (minimal implementation).
- The `/api` gate builds authorities from `permissions` for session tokens (no `client_id`) and from `scopes` for delegated tokens (with `client_id`). This replaces the silent `permissions → scopes` fallback (C6).
- AS helpers (`ClientAuthenticator`, `ResourcePolicy`, `ScopePolicy`, `OAuth2ErrorWriter`, `TokenExchangeGrantHandler`) are plain classes built in `SecurityConfiguration`, like the existing filters. Only `OAuth2Properties` and `McpClientProvisioner` are Spring-managed.
- No explicit `permitAll` matcher for `/oauth2/introspect`. The existing non-`/api/` matcher already covers it, and the security standard forbids redundant matchers (C4).
- An unauthenticated or non-session principal at authorize gets typed `access_denied` (403) instead of substring-classified `unauthorized_client`. `UNAUTHORIZED_CLIENT` now maps to 400 (C3).
- An authenticated client with no bound resource that calls introspection gets `{"active":false}`, not 401.
- `start-dev` generates `AJ_OAUTH_CLIENT_SECRET` only when it is unset. It only inherits `APP_JWT_SECRET` and never generates it, because the MCP server no longer verifies JWTs and the stable committed fallback keeps SPA sessions alive across restarts. **User-approved narrowing** of the requirements' "export `APP_JWT_SECRET`" (audit clarification 3).
- MCP security tests reuse the red test's JDK `HttpServer` fake-host pattern. No WireMock/`MockRestServiceServer` and no `spring-security-test` on the MCP side.
- The MCP introspector is `new SpringOpaqueTokenIntrospector(uri, restTemplate)` with a timed `RestTemplate` (connect 5 s / read 10 s) and a URL-encoding `BasicAuthenticationInterceptor`. The builder cannot set timeouts, and a stalled host must not pin MCP request threads (M1).
- The two red-test files stay byte-identical. `OAuth2TestFlows` copies their helpers instead of extracting them (M2).
- The granted-scope rule is: expand → ∩ user permissions → ∩ client scopes → expand again. Re-expansion adds `mcp:read` for `mcp:edit` only if the user holds READ (**user decision**, audit clarification 1; m2).
- Defaulting a missing scope to the client's registered scopes (I-2) applies only at authorize. At exchange, empty or missing subject scopes are `invalid_scope` with no default (m1).
- A `resource` that resolves but differs from the stored one is `invalid_grant` at both the code and refresh grants. An unknown `resource` or the API audience is `invalid_target` (**user decision**, audit clarification 2; m3).
- Client-credential conflict means Basic plus a form `client_secret` (`invalid_request`). A form `client_id` next to Basic is allowed only if it equals the Basic id, else `invalid_client` (**user decision**, audit clarification 4; m4).
- AS-8 consumes the code before it identifies the client. A wrong client therefore burns the code. RFC 6749 allows this, and it keeps the existing `authorizationCode_invalidCode_returns400` behaviour (m11).

## Open Questions / Risks
- Which `resource` value real MCP clients send (Claude Code, MCP Inspector) is still unverified. C-1 slash-equality covers the expected `X/` form. A manual end-to-end run must record the actual value.
- Each tool call costs at least two BCrypt verifications on the host (introspection + exchange). This is unmeasured but acceptable for local use. Caching stays out of scope.
- Starting only one app by hand with a different `AJ_OAUTH_CLIENT_SECRET` fails closed with opaque 401s or tool errors. This must be documented in CLAUDE.md, the plugin README and the root `README.md` "Manual Start" (DOC-1, DOC-6, DOC-7).
- Both red-test files are currently untracked. They must be committed as the baseline before the first implementation commit so that `git diff --exit-code` can prove they did not change (RG-1).
- All T0/T1 sessions are invalidated at rollout (new origin, new claims). Users must re-point MCP clients to `http://localhost:8181`.
- The first MCP build needs network access for `spring-boot-starter-security-oauth2-resource-server`.
- The consent page filters scopes on the client side from the T0 `permissions` claim. The server-side `ScopePolicy` remains authoritative.

---

## Goal
Eliminate token passthrough, confused-deputy and audience-replay vulnerabilities on the MCP → host hop by making every token audience-bound and having the MCP server validate inbound tokens and use delegated, short-lived host tokens. The goal includes making the four Phase-3 red tests pass and leaving MCP tool behaviour unchanged for users.

## User Stories
- As an **editor using Claude**, I want `aj_list_products`, `aj_list_categories` and `aj_add_product` to work exactly as before, so that the security change is invisible to me.
- As a **viewer**, I want the consent page to offer only what I can actually grant (`mcp:read`), so that I am not misled. `aj_add_product` is refused with a clear tool error.
- As an **aj user**, I want a token stolen from Claude (T1) to be useless at the host API, so that my authority cannot be replayed or escalated.
- As a **developer**, I want `start-dev` to wire both apps on 8180/8181 with matching credentials, so that a fresh clone works without manual env setup.

## Core Requirements

### Host authorization server (`src/main/java/pl/devstyle/aj/core/oauth2/`)
- **AS-1 Fixed issuer.** New `OAuth2Properties` record (`@ConfigurationProperties("app.oauth2")`, enabled by `@EnableConfigurationProperties` on `SecurityConfiguration`) with `issuer`, `mcpResource`, `mcpClientId`, `mcpClientSecret`.
  - The compact constructor requires non-blank `issuer`, `mcpResource` and `mcpClientId` (fail fast) and strips a trailing `/` from `issuer` and `mcpResource`. `mcpClientSecret` may be blank (see AS-12).
  - `toString()` is overridden to mask `mcpClientSecret` (`***`, or `<blank>` when unset), so the secret never reaches logs or failure messages (m8).
  - Derived `apiAudience()` = `issuer + "/api"`.
  - Every issuer use (login T0, OAuth tokens, metadata, `/api` gate) reads this value. Both `getBaseUrl(request)` copies (`OAuth2TokenFilter`, `OAuth2MetadataController`) are deleted.
  - **Verification fix (cycle 1):** the compact constructor also rejects an `mcpResource` that overlaps the API audience: equal to `issuer`, equal to `issuer + "/api"`, or under `issuer + "/api/"`. A token for the MCP resource can therefore never pass the exact-match API audience gate.
- **AS-2 `resource` (RFC 8707).** New `ResourcePolicy` (plain class over `OAuth2Properties`):
  - `canonical(uri)`: for origin-only URIs (no path other than `/`, no query, no fragment), strip one trailing `/`. Any other URI is unchanged and matched exactly (C-1).
  - `resolveForAuthorization(resourceParam, clientId)`:
    - Missing or blank → `mcpResource` plus a WARN log `OAuth2 resource defaulted | client_id=…`.
    - Slash-equal to `mcpResource` → canonical `mcpResource`.
    - Anything else, including `apiAudience` → `OAuth2RequestException(INVALID_TARGET)`.
  - `resolveForExchange(resourceParam)`: must equal `apiAudience`. Missing or anything else → `INVALID_TARGET`.
  - `boundResourceOf(clientId)`: `Optional.of(mcpResource)` for `mcpClientId`, otherwise empty (config-backed binding, no DB column).
  - `audienceContains(Set<String> aud, String resource)`: canonical contains-match.
  - `resourceParam(HttpServletRequest)` → `Optional<String>`: reads `getParameterValues("resource")`. More than one value → `INVALID_TARGET` (AS-16).
- **AS-3 Scope policy.** New `ScopePolicy` (final class, static methods):
  - `SUPPORTED_SCOPES` = ordered [`mcp:read`, `mcp:edit`].
  - Shared core, in this order:
    1. Expand: `mcp:edit` adds `mcp:read`.
    2. Intersect with the user's permission scopes (`READ`→`mcp:read`, `EDIT`→`mcp:edit`).
    3. Intersect with `clientScopes`.
    4. Expand again: if the result holds `mcp:edit` and the user's permission scopes hold `mcp:read`, add `mcp:read`. An `mcp:edit`-only client thus yields `mcp:read mcp:edit` for an editor with READ, and `mcp:edit` alone for a user without READ (m2, user decision).
    5. Drop unknown scopes.
    6. Empty → `OAuth2RequestException(INVALID_SCOPE)`.
  - `grantAtAuthorization(requested, permissions, clientScopes)`: requested null or blank → `clientScopes` (I-2), then the core. Used only by the authorize endpoint.
  - `grantAtExchange(subjectScopes, permissions, clientScopes)`: subject scopes null or empty → `INVALID_SCOPE` with **no** default, then the core (m1).
  - `toScopeString(set)` returns space-delimited values in `SUPPORTED_SCOPES` order.
  - `PublicClientRegistrationFilter.ALLOWED_SCOPES` and the metadata `scopes_supported` reference `SUPPORTED_SCOPES`.
- **AS-4 Client authentication.** New `ClientAuthenticator(RegisteredClientRepository, PasswordEncoder)`:
  - `authenticateConfidential(request)`:
    - Credentials come from `Authorization: Basic` (Base64, split at the first `:`, URL-decode both parts as UTF-8 per RFC 6749 §2.3.1; C8) or from form `client_id` + `client_secret`.
    - Conflict = a Basic header **and** a form `client_secret` → `INVALID_REQUEST`.
    - A form `client_id` sent next to Basic is allowed only if it equals the Basic client id, else `INVALID_CLIENT` (m4, user decision).
    - None, malformed Basic, unknown client, a method not registered for the client, or a BCrypt mismatch → `INVALID_CLIENT`.
    - Returns the `RegisteredClient`.
  - `identify(request)`, used by the auth-code and refresh grants:
    - With credentials (a Basic header or a form `client_secret`) → `authenticateConfidential`.
    - Without credentials → the form `client_id` is required (else `INVALID_REQUEST`, 400). The client must exist and be registered with method `none` (else `INVALID_CLIENT`, 401).
  - Unknown clients always fail closed (C10). This makes the advertised `client_secret_basic` true (C13).
  - **Verification fix (cycle 1/2):** every path that reaches the client lookup runs exactly one BCrypt `matches` (against a dummy hash built once at startup when the client is unknown, uses an unregistered method or has no secret), so response time does not reveal whether a client exists. The claimed client id (from Basic or form credentials, never the secret) is recorded for logging before the lookup, so a failed authentication such as a host/MCP secret mismatch is logged as `client_id=<claimed id> | error=invalid_client`. Logged client-supplied values are sanitised (control, format and Unicode line/paragraph separator characters replaced, length capped).
- **AS-5 Authorize endpoint** (`OAuth2AuthorizationFilter`):
  - Reads `resource` through `ResourcePolicy.resolveForAuthorization`.
  - Accepts as principal only an authentication whose details are a session `ParsedToken` (no `client_id`, no `act`). Otherwise → `ACCESS_DENIED` "User must be authenticated" (C-2).
  - Computes the granted scope with `ScopePolicy.grantAtAuthorization(requested, sessionPermissions, client.getScopes())`. The existing client-scope validation stays.
  - Stores `resource` and the granted scope in the code.
  - Errors are typed `OAuth2RequestException`s written as JSON by `OAuth2ErrorWriter`, with no redirect errors (C2, Phase 1). `determineError` is deleted.
  - The parameter-map INFO log is removed (FU-3).
  - **Verification fix (cycle 1):** the client must be registered for the `authorization_code` grant, else `UNAUTHORIZED_CLIENT` (400); this rejects the exchange-only `aj-mcp-server` client. The success redirect is built with `UriComponentsBuilder` from the registered `redirect_uri` (its query kept), with `code` and `state` strictly percent-encoded.
- **AS-6 Auth-code data.** `AuthorizationCodeData` becomes `(clientId, redirectUri, scope /*granted, space-delimited*/, codeChallenge, codeChallengeMethod, username, resource, createdAt)`. The now-unused `permissions` field is removed. `storeAuthorizationCode` changes to match.
- **AS-7 Token endpoint** (`OAuth2TokenFilter`), dispatch on `grant_type`: `authorization_code`, `refresh_token`, `urn:ietf:params:oauth:grant-type:token-exchange`, else `UNSUPPORTED_GRANT_TYPE`.
  - Each branch returns a `TokenResponse` record or throws `OAuth2RequestException`.
  - The filter writes JSON with the injected Jackson 3 `ObjectMapper` and `Cache-Control: no-store`, `Pragma: no-cache`.
  - `TokenResponse` (`@JsonInclude(NON_NULL)`, snake_case `@JsonProperty`) has: `access_token`, `token_type`=`Bearer`, `expires_in` (computed from `exp`), `refresh_token`, `scope`, `issued_token_type`.
  - The StringBuilder JSON and the hard-coded `900` are removed.
- **AS-8 Authorization-code grant**, in this order:
  1. `code` and `redirect_uri` are required (`INVALID_REQUEST`).
  2. Consume the code (`INVALID_GRANT` if invalid). The code is consumed before the client is identified, so a wrong client burns it (m11, Key Decisions).
  3. `ClientAuthenticator.identify` → client id must equal `data.clientId()` (else `INVALID_GRANT`; I-3 also applies here).
  4. `redirect_uri` must equal the stored value (`INVALID_GRANT`).
  5. PKCE check (unchanged).
  6. An optional `resource` param (single value, AS-16) must resolve via `resolveForAuthorization`: unknown or the API audience → `INVALID_TARGET`. If it resolves but differs from the stored resource → `INVALID_GRANT` (m3, user decision).
  7. Mint T1 with `aud` = stored resource, `client_id`, `scopes` = stored grant, 900 s.
  8. Issue an RT bound to `(username, scope, clientId, resource)`.
  9. Response `scope` = stored grant string.
  - **Verification fix (cycle 1):** the identified client must be registered for the `authorization_code` grant, else `UNAUTHORIZED_CLIENT`. Step 8 issues a refresh token only when the client is also registered for `refresh_token`; otherwise the response has no `refresh_token`.
- **AS-9 Refresh grant (FU-2a, I-3):**
  1. `refresh_token` is required.
  2. `ClientAuthenticator.identify`.
  3. An optional `resource` (single value, AS-16) must resolve via `resolveForAuthorization`. Unknown or API → `INVALID_TARGET`.
  4. `RefreshTokenService.consumeAndRotate(token, clientId, Optional<String> resource)` (m5):
     - Looks the RT up without removing it. Unknown or expired → empty.
     - Checks `clientId` equals the stored client and, when `resource` is present, that it equals the stored resource. A mismatch → empty, and the RT is **not** removed, so it stays usable for its owner.
     - Only then removes it with the conditional `remove(token, data)`. A lost race → empty.
     - The filter maps every empty result to `INVALID_GRANT`.
  5. Re-issue T1 with the stored scope and resource.
  - `RefreshTokenData` = `(username, scope, clientId, resource, createdAt)`. `issueToken(username, scope, clientId, resource)`.
  - **Verification fix (cycle 1):** after step 2 the client must be registered for the `refresh_token` grant, else `UNAUTHORIZED_CLIENT`. The check runs before `consumeAndRotate`, so an unauthorised client cannot burn the token.
- **AS-10 Token-exchange grant** (new `TokenExchangeGrantHandler`, called from the token filter):
  1. `ClientAuthenticator.authenticateConfidential` (`INVALID_CLIENT` 401).
  2. The client must hold the `AuthorizationGrantType.TOKEN_EXCHANGE` grant and have a `boundResourceOf` (else `UNAUTHORIZED_CLIENT` 400).
  3. `subject_token` is required. `subject_token_type` must be `urn:ietf:params:oauth:token-type:access_token`. `requested_token_type` must be absent or the same URN. `actor_token` must be absent. Any violation → `INVALID_REQUEST`.
  4. `resource` (single value, AS-16) → `ResourcePolicy.resolveForExchange`. Missing or not the API audience → `INVALID_TARGET`. The `scope` and `audience` params are ignored.
  5. `JwtTokenProvider.parseAccessToken(subject)` must succeed (signature, exp, `iss`, `typ: at+jwt`) and have no `act`. Its `aud` must contain the caller's bound resource. Otherwise → `INVALID_REQUEST`.
  6. Load the user's **current** authorities through `CustomUserDetailsService.loadUserByUsername(sub)` (`UsernameNotFoundException` → `INVALID_REQUEST`).
  7. Scopes = `ScopePolicy.grantAtExchange(subject scopes, current permissions, client scopes)`. Empty or missing subject scopes, or an empty result → `INVALID_SCOPE`; never a default to client scopes (m1).
  8. Mint T2 with `aud` = `apiAudience`, `client_id` = caller, `act.sub` = caller, `exp` = min(now + 300 s, subject `exp`).
  9. Response: `access_token`, `issued_token_type` = access-token URN, `token_type` `Bearer`, `expires_in`, `scope`, and **no** `refresh_token`.
- **AS-11 Introspection** (new `OAuth2IntrospectionFilter`, `POST /oauth2/introspect`, registered `addFilterAfter(…, OAuth2TokenFilter.class)`):
  1. `ClientAuthenticator.authenticateConfidential` (401 `invalid_client` + `WWW-Authenticate: Basic`).
  2. `token` is required (`INVALID_REQUEST`).
  3. If the caller has no bound resource, or `parseAccessToken` fails, or `aud` does not contain the bound resource → `200 {"active":false}` with no detail.
  4. Otherwise `200` with `active:true`, `sub`, `scope` (space-delimited string translated from the `scopes[]` claim; C7), `aud` (array), `client_id`, `iss`, `iat`, `exp`, `jti`, `token_type:"Bearer"`.
  - The response is an `IntrospectionResponse` record (`NON_NULL`) written with Jackson 3. `token_type_hint` is ignored.
  - Every introspection response (active, inactive and error) sets `Cache-Control: no-store` and `Pragma: no-cache` (m12).
- **AS-12 Provisioning** (new `McpClientProvisioner`, `@Component ApplicationRunner`, `@Transactional run`):
  - **Secret set:**
    - `findByClientId(mcpClientId)`.
    - If the row exists → `RegisteredClient.from(existing)`, keeping the row id (C11). The secret is re-encoded only if `!passwordEncoder.matches(secret, stored)`.
    - If no row exists → `withId(UUID)`.
    - Either way set: client name `AJ MCP Server`, grant `TOKEN_EXCHANGE` only, methods `CLIENT_SECRET_BASIC` + `CLIENT_SECRET_POST`, scopes `mcp:read mcp:edit`, no redirect URIs.
    - Save. Log `client_id` only.
  - **Secret blank:** delete any existing row through a new `DatabaseRegisteredClientRepository.deleteByClientId(String)` and log a WARN (I-4). No row → WARN "MCP client not provisioned".
- **AS-13 Metadata** (`OAuth2MetadataController`): returns a record (snake_case) built from the configured issuer. Fields:
  - `issuer`, `authorization_endpoint`, `token_endpoint`, `registration_endpoint`.
  - `introspection_endpoint` = `${issuer}/oauth2/introspect`.
  - `introspection_endpoint_auth_methods_supported` = [`client_secret_basic`, `client_secret_post`].
  - `grant_types_supported` = [`authorization_code`, `refresh_token`, `urn:ietf:params:oauth:grant-type:token-exchange`].
  - `response_types_supported`, `code_challenge_methods_supported`.
  - `token_endpoint_auth_methods_supported` = [`client_secret_post`, `client_secret_basic`, `none`].
  - `scopes_supported`.
  - The 11 entries exceed the `Map.of` limit, hence the record. `GET /api/oauth2/client-info` is unchanged.
- **AS-14 Typed errors and shared writer.**
  - New `OAuth2RequestException(OAuth2Error error, String description)` (unchecked).
  - New `OAuth2ErrorWriter(ObjectMapper /*Jackson 3*/)` with `write(response, error, description)` and a single status map:
    - `INVALID_CLIENT` → 401 + `WWW-Authenticate: Basic realm="aj"`.
    - `ACCESS_DENIED` → 403.
    - `SERVER_ERROR` → 500.
    - Everything else (incl. `UNAUTHORIZED_CLIENT`, `INVALID_TARGET`) → 400.
  - Replaces the three copy-pasted `sendError`/`writeError` switches (C3). Add `INVALID_TARGET("invalid_target", …)` to `OAuth2Error`.
  - `OAuth2ErrorResponse` stays as is: its `com.fasterxml.jackson.annotation` annotations are honoured by Jackson 3.
  - `PublicClientRegistrationFilter` only switches to the shared writer and keeps its Jackson 2 request parsing (I-7). The token and authorize filters drop `new com.fasterxml…ObjectMapper()`.
- **AS-15 No secret logging (FU-3).** No log line in `core/oauth2` or `core/security` contains a token, code, verifier, client secret, `_token` or a parameter map. Logs carry `client_id`, username, grant type, error code and resource only.
  - **Verification fix (cycle 1/2), exception:** an unexpected error (`server_error`) is logged at ERROR with `error_type` only; its stack trace is logged at DEBUG, which is off by default, because exception messages can contain request-derived text. Enable it with `logging.level.pl.devstyle.aj.core.oauth2=DEBUG` (documented in README and CLAUDE.md).
- **AS-17 DCR redirect URI validation. Verification fix (cycle 2).** `PublicClientRegistrationFilter` requires a non-empty `redirect_uris` list for every registration (every supported client uses the authorization code flow, which is also the default when `grant_types` is omitted). Each entry must be a non-blank string that `java.net.URI` parses, contains only ASCII characters, has no fragment and no user info, and is either `https` with a host or `http` on an exact loopback host (`localhost`, `127.0.0.1`, `[::1]`; prefix matches such as `localhost.example` are rejected). Any violation → 400 `invalid_client_metadata`. An omitted `grant_types` registers `authorization_code` and the response echoes the registered grant types; an omitted `response_types` defaults to `["code"]` (RFC 7591). The response is built before the client is saved, so a rejected registration never leaves a stored client.
- **AS-16 Single `resource` value (m13).** At authorize, the code grant, the refresh grant and the exchange grant, more than one `resource` parameter → `INVALID_TARGET`. All endpoints read it through `ResourcePolicy.resourceParam`.

### Host security / API gate (`src/main/java/pl/devstyle/aj/core/security/`, `api/`)
- **SEC-1 `JwtTokenProvider`** takes `OAuth2Properties`.
  - `generateToken(username, permissions)` and `generateTokenWithExpiration(username, permissions, expirationMs)`: signatures unchanged. They add `iss` = issuer and `aud` = [`apiAudience`] (T0).
  - New `generateOAuth2Token(OAuth2TokenClaims)` with record `OAuth2TokenClaims(subject, scopes, audience, clientId, actorSubject /*nullable*/, expiresAt)`. It sets:
    - header `typ: at+jwt`
    - `iss`, `sub`, `aud` [audience], `client_id`, `scopes` (list), `jti` (UUID), `iat`, `exp`
    - `act: {sub}` when `actorSubject` is set
  - The old `generateOAuth2Token(username, scopes, issuer)` is removed.
  - **API mode** `parseApiToken(token)` → `Optional<ParsedToken>`: valid signature and exp, `iss` equals issuer, `aud` contains `apiAudience`.
  - **AS mode** `parseAccessToken(token)` → `Optional<ParsedToken>`: valid signature and exp, `iss` equals issuer, header `typ` = `at+jwt`. No audience requirement.
  - `ParsedToken(subject, audience: Set, permissions: Set, scopes: Set, clientId, actorSubject, type, jti, issuedAt, expiresAt)` with `isSessionToken()` = `clientId == null && actorSubject == null`.
  - Delete `parseToken`, `getUsernameFromToken`, `getPermissionsFromToken` and `validateToken` (unused; C6). `aud` is serialised as an array (jjwt) and checked by contains-match.
- **SEC-2 `JwtAuthenticationFilter`**:
  - Uses `parseApiToken` for both the Bearer header and the `_token` form parameter.
  - Authorities: session token → `PERMISSION_<permission>`. Token with `client_id` → `PERMISSION_<scope>` (T2 keeps `mcp:*`).
  - Sets `authentication.setDetails(parsedToken)`.
  - On rejection it leaves the context empty. The existing entry point produces 401 `ErrorResponse`.
- **SEC-3 `SecurityConfiguration`**:
  - Constructs `OAuth2ErrorWriter`, `ClientAuthenticator`, `ResourcePolicy`, `TokenExchangeGrantHandler` and the filters with their new dependencies (`OAuth2Properties`, `CustomUserDetailsService`, Jackson 3 `ObjectMapper`).
  - **Implementation deviation (approved):** `TokenExchangeGrantHandler` and `OAuth2TokenFilter` are built without `OAuth2Properties`. Neither uses it: the handler gets the resource rules through `ResourcePolicy` (which wraps `OAuth2Properties`), and the filter delegates exchange to the handler. `OAuth2Properties` is passed only to the collaborators that read it (`ResourcePolicy`, `OAuth2IntrospectionFilter`, `JwtTokenProvider`).
  - Filter order: Jwt → Registration → Authorization → Token → Introspection.
  - The matchers, including the `PERMISSION_mcp:*` alternatives, are unchanged.
  - CORS origins come from `app.cors.allowed-origins`, now env-overridable.
- **SEC-4 `AuthController`** is unchanged in code. T0 gains `iss`/`aud` through SEC-1.

### MCP server (`mcp-server/`)
- **MCP-1 Dependencies:** add `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` (version from the Boot 4.0.5 BOM). No new test dependency.
- **MCP-2 Properties.** New `config/AjProperties` record (`@ConfigurationProperties("aj")`, enabled on `SecurityConfig`) with nested records:
  - `backend.url`
  - `oauth.server-url`, `oauth.client-id`, `oauth.client-secret`. The compact constructor fails fast when the secret is blank, with the message "aj.oauth.client-secret is not set — export AJ_OAUTH_CLIENT_SECRET (start-dev does this)".
  - `mcp.base-url`
  - The compact constructors also require non-blank `oauth.server-url`, `oauth.client-id`, `mcp.base-url` and `backend.url`, and strip one trailing `/` from each URL, so derived URIs never contain `//` (m7). `RestClientConfig` reads the backend base URL from `AjProperties.backend().url()` (constructor-injected), not `@Value`.
  - `Oauth.toString()` is overridden to mask `client-secret` (m8).
  - Derived values: `introspectionUri()` = server-url + `/oauth2/introspect`, `tokenUri()` = server-url + `/oauth2/token`, `backendResource()` = server-url + `/api`, `resourceMetadataUrl()` = base-url + `/.well-known/oauth-protected-resource`.
  - The names `aj.backend.url`, `aj.oauth.server-url`, `aj.mcp.base-url`, `aj.oauth.client-id` and `aj.oauth.client-secret` are fixed by the red test.
- **MCP-3 Inbound validation:**
  - Explicit `OpaqueTokenIntrospector` bean = `new AudienceValidatingIntrospector(delegate, mcp.base-url)` (I-1).
  - `delegate` = `new SpringOpaqueTokenIntrospector(introspectionUri(), restTemplate)`, not the builder, because the builder's internal `RestTemplate` has no timeouts (M1). The `restTemplate`:
    - uses a `SimpleClientHttpRequestFactory` with connect timeout 5 s and read timeout 10 s (constants, like MCP-8);
    - carries a `BasicAuthenticationInterceptor` built from the client id and secret, each URL-encoded as UTF-8 (`URLEncoder`) first, so C8 still holds;
    - is local to the introspector and is not a bean.
  - A stalled or unreachable host makes the introspector throw `OAuth2IntrospectionException`, which surfaces as 5xx (MCP-5).
  - The wrapper delegates, then requires the principal's `aud` attribute to contain `mcp.base-url` (slash-equal canonical compare). Otherwise it throws `BadOpaqueTokenException`.
  - It does **not** check `iss`. The AS is the authority, and the red test's fake answers `iss: http://localhost`.
  - Scope authorities come from Spring's default `SCOPE_` mapping of the space-delimited `scope`.
- **MCP-4 `SecurityConfig`** (rewritten, constructor-injected):
  - stateless, CSRF off
  - `permitAll` for `/.well-known/**`, `/actuator/health/**`, `/error`
  - `/` requires `hasAnyAuthority("SCOPE_mcp:read","SCOPE_mcp:edit")`; anything else authenticated
  - `oauth2ResourceServer(opaqueToken(introspector))` with `McpAuthenticationEntryPoint` and the new `McpAccessDeniedHandler`
- **MCP-5 Challenges:**
  - `McpAuthenticationEntryPoint` → 401 with `WWW-Authenticate: Bearer resource_metadata="<resourceMetadataUrl>", scope="mcp:read mcp:edit"`. It adds `, error="invalid_token"` only when the exception is an `OAuth2AuthenticationException` (a token was presented).
  - `McpAccessDeniedHandler` → 403 `Bearer error="insufficient_scope", scope="mcp:read", resource_metadata="…"`.
  - An introspection outage or stall (`AuthenticationServiceException`) yields 5xx and never `invalid_token` (I-6). The audit confirmed that Security 7.0.4 rethrows it by default, so no custom 503 handler is needed.
- **MCP-6 Principal hand-off** (C14):
  - In `AjMcpApplication`'s transport `contextExtractor`, read `SecurityContextHolder.getContext().getAuthentication()` on the servlet thread.
  - A `BearerTokenAuthentication` goes into the context as `McpTransportContext.create(Map.of(ToolSecurity.AUTHENTICATION_KEY, auth))`. Otherwise `EMPTY`.
  - `TOKEN_KEY` is deleted.
  - Red test 4 is the end-to-end proof that the extractor sees the validated principal.
- **MCP-7 `security/ToolSecurity`** (final class, static):
  - `AUTHENTICATION_KEY`
  - `principal(McpTransportContext)` → `BearerTokenAuthentication`, or `McpToolException.apiError("Not authenticated")`
  - `requireScope(BearerTokenAuthentication, scope)` → returns the subject token value (`getToken().getTokenValue()`), or throws `McpToolException.insufficientScope(scope)` before any exchange. The parameter is `BearerTokenAuthentication`, not `Authentication`, because only it exposes `getToken()` (m6)
- **MCP-8 `client/TokenExchangeClient`** (`@Component`):
  - Has its own `RestClient.builder()` (no backend status handler, connect 5 s / read 10 s).
  - `String exchange(String subjectToken)` POSTs form `grant_type`, `subject_token`, `subject_token_type`, `requested_token_type` (access-token URN) and `resource` = `backendResource()` to `tokenUri()`.
  - Auth header: `Authorization: Basic base64(urlenc(id):urlenc(secret))` (C8).
  - Response record `TokenExchangeResponse(access_token, issued_token_type, token_type, expires_in, scope)`. It requires `issued_token_type` = access-token URN and `token_type` equalsIgnoreCase `Bearer`.
  - Error mapping:
    - AS 400 `invalid_scope` → `McpToolException.apiError("Insufficient permissions")`.
    - Any other 4xx/5xx, network error or validation failure → `apiError("Backend authorization failed")`.
    - Logs at ERROR with the HTTP status and AS `error` code only. Never the token, never the body.
  - No cache, no retry.
- **MCP-9 `AjApiClient`:** every method gains a leading `@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization` parameter: `listProducts(authorization, search)`, `createProduct(authorization, request)`, `listCategories(authorization)`.
- **MCP-10 `RestClientConfig`:**
  - Delete `JwtForwardingInterceptor` and the `AccessTokenHolder` parameter.
  - Keep the status mapping: 400 `validationError`, 401 `apiError("Authentication required")` (no retry, no re-exchange), 403 `apiError("Insufficient permissions")`, 404 `notFound`, else `apiError`.
  - Never log request headers.
- **MCP-11 Services:**
  - `ProductService.listProducts(BearerTokenAuthentication caller, String search)`, `ProductService.addProduct(BearerTokenAuthentication caller, Map args)`, `CategoryService.listCategories(BearerTokenAuthentication caller)` (m6).
  - Each runs `ToolSecurity.requireScope` (`mcp:read` for the list tools, `mcp:edit` for `aj_add_product`) → `tokenExchangeClient.exchange(t1)` → `ajApiClient.x("Bearer " + t2, …)`.
  - Handlers pass `ToolSecurity.principal(ctx)`.
  - The `AccessTokenHolder` field is removed.
  - Tool names, titles, annotations, input/output schemas and `CallToolResult` shape are byte-identical.
- **MCP-12 `McpToolException`:** add `ErrorType.INSUFFICIENT_SCOPE` and `insufficientScope(String required)`, with message "Insufficient scope: requires <scope>".
- **MCP-13 Deletions:** `security/McpJwtFilter.java`, `security/AccessTokenHolder.java`, `RestClientConfig.JwtForwardingInterceptor`, `AjMcpApplication.TOKEN_KEY`.
- **MCP-14 `application.yml`:**
  - `server.port: 8181`, `management.server.port: 9181`
  - `aj.backend.url: ${AJ_BACKEND_URL:http://localhost:8180}`
  - `aj.oauth.server-url: ${AJ_OAUTH_SERVER_URL:http://localhost:8180}`
  - `aj.oauth.client-id: ${AJ_OAUTH_CLIENT_ID:aj-mcp-server}`
  - `aj.oauth.client-secret: ${AJ_OAUTH_CLIENT_SECRET:}`
  - `aj.mcp.base-url: ${AJ_MCP_BASE_URL:http://localhost:8181}`
  - Logging levels per MCP-15.
  - **Implementation deviation (approved):** `WellKnownController` is deleted. Spring Security 7's `oauth2ResourceServer` auto-registers `OAuth2ProtectedResourceMetadataFilter` on `/.well-known/oauth-protected-resource/**`, which shadowed the controller. Protected resource metadata is now published by that filter, configured in `SecurityConfig` through `protectedResourceMetadataCustomizer`: `resource` = `aj.mcp.base-url`, `authorization_servers` = [`aj.oauth.server-url`], `scopes_supported` = [`mcp:read`, `mcp:edit`], `tls_client_certificate_bound_access_tokens` = `false` (mTLS binding is not supported).
- **MCP-15 No token logging on the MCP side (M3, FU-3):**
  - The `logging.level` block drops all three DEBUG entries (`pl.devstyle`, `io.modelcontextprotocol`, `org.springframework.security`), so they run at the default INFO. The MCP test config must not re-enable DEBUG for them.
  - No MCP log line contains T1, T2, the client secret, an `Authorization` header or a request/response body from introspection or exchange. Logs carry the HTTP status, AS `error` code, `client_id` and tool name only.
  - Proven by `security/McpLoggingTests` (see the test list).

### Frontend (`src/main/frontend/src/`)
- **FE-1 `pages/OAuth2AuthorizePage.tsx`:** read `resource` from `searchParams` and render a hidden `resource` input only when it is non-empty, like `state` (C1).
- **FE-2 Grantable scopes on the consent page:**
  - The offered list = expand(requested ∩ client scopes, or client scopes when none were requested), filtered to what the user's T0 `permissions` (from `useAuth()`) can grant: `READ`→`mcp:read`, `EDIT`→`mcp:edit`. Expand means `mcp:edit` adds `mcp:read`.
  - A viewer sees only `mcp:read`. If nothing is grantable, show the existing error box ("You do not have permission to grant the requested access") and keep Allow disabled.
- **FE-3 `auth/AuthContext.tsx` `loadStoredAuth`:** also discard a stored token whose payload has no `aud` (I-5). `decodeJwtPayload`'s return type gains `aud?: string | string[]`. The existing fixture JWT in `src/test/auth.test.tsx` ("provides token/permissions/login/logout via useAuth hook") gains `aud: ["http://localhost:8180/api"]`, otherwise FE-3 would discard it (m10).
- **FE-4 `vite.config.ts`:** the proxy target becomes `http://localhost:8180`.

### Ports, configuration and scripts
- **CFG-1 Host `application.properties`:**
  - `server.port=8180`
  - `app.oauth2.issuer=${APP_OAUTH2_ISSUER:http://localhost:8180}`
  - `app.oauth2.mcp-resource=${AJ_MCP_BASE_URL:http://localhost:8181}`
  - `app.oauth2.mcp-client-id=${AJ_OAUTH_CLIENT_ID:aj-mcp-server}`
  - `app.oauth2.mcp-client-secret=${AJ_OAUTH_CLIENT_SECRET:}`
  - `app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:*}`
  - `app.jwt.*` unchanged.
- **CFG-2 `start-dev.sh` / `start-dev.ps1`** export to both apps (see the verification fix below for `AJ_OAUTH_CLIENT_SECRET`):
  - `APP_OAUTH2_ISSUER` (default `http://localhost:8180`)
  - `AJ_OAUTH_SERVER_URL` (= issuer)
  - `AJ_BACKEND_URL` (`http://localhost:8180`)
  - `AJ_MCP_BASE_URL` (`http://localhost:8181`)
  - `AJ_OAUTH_CLIENT_ID` (`aj-mcp-server`)
  - `AJ_OAUTH_CLIENT_SECRET` (32 random bytes, base64url, generated only if unset)
  - Each is set only if not already set. `APP_JWT_SECRET` is only inherited (passed through when set) and never generated. This narrows the requirement "export `APP_JWT_SECRET`" and was approved by the user (audit clarification 3).
  - Print the effective issuer and MCP base URL, never secrets.
  - **Verification fix (cycle 1):** `AJ_OAUTH_CLIENT_SECRET` is **not** exported. It is resolved from the environment (an inherited value wins), else from the gitignored `.aj-dev.env` in the repo root, else generated once and stored there (never printed). It is passed only to the host and MCP server processes, never to plugin processes, and reused on later runs so either app can be restarted on its own.
- **CFG-3 Port references (8080/8081/9081 → 8180/8181/9181)**, the full gap-analysis inventory:
  - `plugins/server-sdk.ts` (`DEFAULT_HOST_BASE_URL` + JSDoc)
  - `plugins/{box-size,warehouse,shipping}/index.html`
  - `plugins/{ai-description,ai-offer-analysis}/src/pages/_document.tsx`
  - `plugins/ai-offer-analysis/.env.example`
  - `src/main/frontend/public/assets/plugin-ui.css` (comment)
  - The 14 fixture lines in `src/test/plugin-sdk-auth.test.ts` and `src/test/plugin-sdk.test.ts` (I-9)
  - `mcp-server/.../ConfigurationTests.java`
  - Not touched: `plugins/*/dist/`, `.playwright-mcp/` logs, `tsconfig.tsbuildinfo`, the gitignored `.env`, `week7/`–`week10/`, `tools/`, `_docs/`.

### Documentation and standards (rewrite, do not append — C12)
- **DOC-1 `CLAUDE.md`:**
  - Ports on lines 19/33/41.
  - `core/security` and `core/oauth2` bullets (fixed issuer, `aud` gate, introspection, token exchange).
  - MCP paragraph: "validates bearer tokens via RFC 7662 introspection against the host (audience = its own URL), exchanges them via RFC 8693 for a ≤5-min host-API token, never forwards the client token; needs `AJ_OAUTH_CLIENT_ID/SECRET`; default backend `http://localhost:8180`".
  - A note that `start-dev` wires matching secrets, and that starting the apps by hand with different `AJ_OAUTH_CLIENT_SECRET` values fails closed with 401s.
- **DOC-2 `.maister/docs/standards/mcp-server/conventions.md`:** rewrite the "No Token Passthrough" section to match the code:
  - Introspection with an `aud` = `aj.mcp.base-url` check, no cache.
  - Exchange per tool call, no cache.
  - Principal via `McpTransportContext` (no ThreadLocal, no raw header).
  - Per-tool scope check.
  - T2 keeps `mcp:*` scopes, replacing the "`mcp:read`→READ" mapping. Scope rule, worded exactly: expand (`mcp:edit` adds `mcp:read`) → ∩ the user's permissions → ∩ the client's scopes → expand again, where `mcp:edit` implies `mcp:read` only if the user has READ.
  - No retry on backend 401.
  - Add `insufficientScope` to the `McpToolException` factory list in the "MCP Server Tool Conventions" paragraph (`validationError`, `notFound`, `apiError`, `invalidCriteria`, `insufficientScope`).
  - Fix the dead source link (`2026-04-13-mcp-token-security` → this task).
- **DOC-3 `standards/backend/security.md`:**
  - Token Claims: every host JWT carries `iss` + `aud`. OAuth tokens add `client_id`, `jti`, `typ: at+jwt`, `scopes`, plus `act` on exchanged tokens.
  - `/api` requires `aud` ∋ `${issuer}/api`.
  - Only the exchange grant may mint the API audience.
  - The MCP scope line: "T2 keeps `mcp:*` scopes, matched by the `PERMISSION_mcp:*` alternatives. Granted scopes = expand → ∩ user permissions → ∩ client scopes → expand again (`mcp:edit` implies `mcp:read` only if the user has READ); no default to client scopes at exchange".
  - Fix the dead link.
- **DOC-4:**
  - `standards/backend/plugin-auth.md`: the forwarded T0 is valid regardless of Host header because the issuer is configured.
  - `standards/plugins/plugin-development.md`: port table (host 8180, MCP 8181, management 9181) and link example.
  - `standards/frontend/data-access.md`: proxy target.
- **DOC-5:**
  - `project/architecture.md`: `core/security`/`core/oauth2` purpose, MCP server purpose, data flow (introspect + exchange), env vars, ports.
  - `project/tech-stack.md`: MCP resource-server starter.
  - `INDEX.md`: summaries for the changed docs and the port line.
- **DOC-6:** port mentions in `plugins/CLAUDE.md` and `plugins/ai-offer-analysis/README.md`, plus a re-point note ("configure MCP clients at `http://localhost:8181`"). `plugins/sdk.ts` has no port mention and is not touched (m14).
- **DOC-7 Root `README.md`** (m14):
  - "start-dev" section: the script also starts the MCP server and wires `AJ_OAUTH_CLIENT_ID`/`AJ_OAUTH_CLIENT_SECRET` (secret generated only if unset) for both apps; ports host 8180, MCP 8181, management 9181.
  - "Manual Start" section: add the MCP server step (`cd mcp-server && ./mvnw spring-boot:run`), state that both apps need the same `AJ_OAUTH_CLIENT_SECRET` exported (a mismatch fails closed with 401s), and update the host port to 8180.

### Red gate (`RG`)
- **RG-1 Red tests are immutable (M2).** `src/test/java/pl/devstyle/aj/core/oauth2/McpTokenBoundaryTests.java` and `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpAuthBoundaryTests.java` are **not modified**. They must stay byte-identical.
  - Both files are untracked today. They are committed unchanged as the baseline before the first implementation commit. At every verification `git diff --exit-code <baseline> -- <both files>` must exit 0.
  - Reference SHA-256 values at spec time: `ca5b084e…aba543` (`McpTokenBoundaryTests`) and `5fb1ef79…918980` (`McpAuthBoundaryTests`).
  - Shared host test helpers (`OAuth2TestFlows`) **copy** the helper logic from these files. They do not extract it or make the red tests call it.

## Token and Claim Formats

| | T0 session | T1 MCP access token | T2 delegated API token | RT refresh token |
|---|---|---|---|---|
| Minted by | `POST /api/auth/login` → `generateToken` | auth-code / refresh grant | token-exchange grant | auth-code / refresh grant |
| Header | `alg HS256` | `alg HS256`, `typ at+jwt` | `alg HS256`, `typ at+jwt` | opaque, 32 random bytes base64url, in memory |
| `iss` | `app.oauth2.issuer` | issuer | issuer | — |
| `aud` | [`${issuer}/api`] | [`mcpResource`] (canonical, no slash) | [`${issuer}/api`] | stored `resource` |
| `sub` | username | username | username (from T1) | stored username |
| `client_id` | absent | DCR client id | `aj-mcp-server` | stored, must match on refresh |
| `act` | absent | absent | `{ "sub": "aj-mcp-server" }` | — |
| Rights | `permissions` [READ, EDIT, …] | `scopes` = consent-time `grantAtAuthorization` | `scopes` = `grantAtExchange`(T1 scopes, current perms, client scopes), no default | stored scope string |
| `jti` | absent | UUID | UUID | — |
| Lifetime | 24 h | 900 s | min(300 s, T1 `exp`) | 24 h sliding |
| Accepted at | `/api/**`, `/oauth2/authorize` (session principal) | MCP server (via introspection), exchange subject | `/api/**` only | `/oauth2/token` by the same client |

## Endpoint Contracts and Error Responses

All OAuth errors use the body `{"error": "<code>", "error_description": "<text>"}` (`OAuth2ErrorResponse`).

| Endpoint / situation | Status | Body / headers |
|---|---|---|
| `POST /oauth2/authorize`: missing params, bad redirect, PKCE, unknown `client_id` | 400 | `invalid_request` |
| authorize: scope not allowed for client, or empty grant after `ScopePolicy` | 400 | `invalid_scope` |
| authorize: `response_type` ≠ `code` | 400 | `unsupported_response_type` |
| authorize: `resource` unknown, = API audience, or sent more than once | 400 | `invalid_target` |
| authorize: no principal, or principal not a session token (T2/T1) | 403 | `access_denied` "User must be authenticated" |
| authorize: success | 302 | `Location: <redirect_uri>?code=…[&state=…]` |
| `POST /oauth2/token`: unknown grant | 400 | `unsupported_grant_type` |
| exchange (and introspection): client auth missing, malformed, wrong secret or unknown client | 401 | `invalid_client`, `WWW-Authenticate: Basic realm="aj"` |
| code / refresh: credentials sent but wrong or unknown client; public client unknown or not registered with `none` | 401 | `invalid_client`, `WWW-Authenticate: Basic realm="aj"` |
| code / refresh: no credentials and no form `client_id` | 400 | `invalid_request` |
| token (any grant): Basic header and form `client_secret` both sent | 400 | `invalid_request` |
| token (any grant): form `client_id` next to Basic differs from the Basic id | 401 | `invalid_client`, `WWW-Authenticate: Basic realm="aj"` |
| token: missing required param; bad subject (exchange) | 400 | `invalid_request` |
| token: bad/used code, redirect or client mismatch, PKCE fail, RT unknown, expired or owned by another client | 400 | `invalid_grant` |
| code / refresh: `resource` resolves but differs from the stored resource | 400 | `invalid_grant` |
| code / refresh: `resource` unknown or = API audience; exchange: `resource` missing or ≠ API audience; any grant: `resource` sent more than once | 400 | `invalid_target` |
| token: empty scope intersection; exchange: subject token without scopes | 400 | `invalid_scope` |
| exchange: client lacks token-exchange grant or has no bound resource | 400 | `unauthorized_client` |
| token success (code/refresh) | 200 | `access_token`, `token_type`, `expires_in` 900, `refresh_token`, `scope` |
| exchange success | 200 | `access_token`, `issued_token_type`, `token_type`, `expires_in` ≤ 300, `scope` (no `refresh_token`) |
| `POST /oauth2/introspect`: any response | — | `Cache-Control: no-store`, `Pragma: no-cache` |
| introspect: caller unauthenticated | 401 | `invalid_client` + `WWW-Authenticate: Basic` |
| introspect: missing `token` | 400 | `invalid_request` |
| introspect: unusable token / not bound to caller's resource / caller unbound | 200 | `{"active":false}` |
| introspect: active T1 | 200 | `active`, `sub`, `scope` (string), `aud`, `client_id`, `iss`, `iat`, `exp`, `jti`, `token_type` |
| `/api/**`: no token, bad signature/exp, wrong `iss`, missing/foreign `aud` (T1, legacy T0) | 401 | existing `ErrorResponse` JSON |
| `/api/**`: valid token, missing authority (T2 `mcp:read` on POST) | 403 | existing `ErrorResponse` JSON |
| MCP `/`: no `Authorization` | 401 | `WWW-Authenticate: Bearer resource_metadata="…", scope="mcp:read mcp:edit"` |
| MCP `/`: inactive, malformed, expired or foreign-`aud` token | 401 | same + `error="invalid_token"` |
| MCP `/`: valid token without any `mcp:*` scope | 403 | `Bearer error="insufficient_scope", scope="mcp:read", resource_metadata="…"` |
| MCP `/`: introspection unreachable, stalled past the 10 s read timeout, 5xx, or MCP credentials rejected | 5xx | no `invalid_token`; ERROR log without token |
| MCP tool: per-tool scope shortfall | 200 JSON-RPC tool error | "Insufficient scope: requires mcp:edit" (no exchange, no backend call) |
| MCP tool: exchange `invalid_scope` | tool error | "Insufficient permissions" |
| MCP tool: other exchange failure or wrong `issued_token_type` | tool error | "Backend authorization failed" |
| MCP tool: backend 401 / 403 / 400 / 404 / 5xx | tool error | unchanged mapping, no retry |

## Configuration (must-equal pairs)

| Host property (env, default) | MCP property (env, default) | Invariant |
|---|---|---|
| `app.oauth2.issuer` (`APP_OAUTH2_ISSUER`, `http://localhost:8180`) | `aj.oauth.server-url` (`AJ_OAUTH_SERVER_URL`, `http://localhost:8180`) | byte-identical |
| `apiAudience()` = issuer + `/api` (derived) | `backendResource()` = server-url + `/api` (derived) | identical by construction |
| `app.oauth2.mcp-resource` (`AJ_MCP_BASE_URL`, `http://localhost:8181`) | `aj.mcp.base-url` (`AJ_MCP_BASE_URL`, `http://localhost:8181`) | slash-equal; PRM `resource` |
| `app.oauth2.mcp-client-id` (`AJ_OAUTH_CLIENT_ID`, `aj-mcp-server`) | `aj.oauth.client-id` (`AJ_OAUTH_CLIENT_ID`, `aj-mcp-server`) | identical |
| `app.oauth2.mcp-client-secret` (`AJ_OAUTH_CLIENT_SECRET`, blank → no client) | `aj.oauth.client-secret` (`AJ_OAUTH_CLIENT_SECRET`, blank → startup fails) | identical |
| `server.port=8180` | `server.port: 8181`, `management.server.port: 9181` | — |
| — | `aj.backend.url` (`AJ_BACKEND_URL`, `http://localhost:8180`) | reachable host |

Missing-env behaviour: the host starts without the MCP client (introspection and exchange answer `invalid_client`, and a stale row is deleted). The MCP server refuses to start. Both apps also refuse to start on a blank issuer/server-url, MCP resource/base-url or client id, and both strip one trailing `/` from these URLs (m7).

## Reusable Components

### Existing Code to Leverage
| Existing | Path | Use |
|---|---|---|
| Filter template (`OncePerRequestFilter`, exact URI + POST guard, `new`'d in config) | `core/oauth2/OAuth2TokenFilter.java` | Pattern for `OAuth2IntrospectionFilter`; dispatch extended with exchange branch |
| Registered-client repository | `core/oauth2/DatabaseRegisteredClientRepository.java` | `findByClientId` for `ClientAuthenticator`; `save` (PK upsert) for provisioner; add `deleteByClientId` |
| SAS types on the classpath | `spring-security-oauth2-authorization-server` 7.0.4 | `RegisteredClient.from`, `AuthorizationGrantType.TOKEN_EXCHANGE`, `ClientAuthenticationMethod.CLIENT_SECRET_BASIC/POST`; `InMemoryRegisteredClientRepository` in unit tests |
| Current permissions | `core/security/CustomUserDetailsService.java` | Exchange-time scope recomputation |
| BCrypt encoder bean | `SecurityConfiguration.passwordEncoder()` | Client secret hashing and verification |
| Error enum / response record | `core/oauth2/OAuth2Error.java`, `OAuth2ErrorResponse.java` | Add `INVALID_TARGET`; record reused by shared writer |
| Jackson 3 `ObjectMapper` bean | injected in `SecurityConfiguration` | All new and touched host JSON |
| In-memory stores | `AuthorizationCodeService`, `RefreshTokenService` | Extended with `resource` / `clientId`; same TTLs and cleanup |
| JSON 401/403 entry point | `SecurityConfiguration` | Unchanged `/api` rejection path |
| PRM, entry point | Spring Security `protectedResourceMetadataCustomizer` in `mcp-server/.../config/SecurityConfig.java`, `security/McpAuthenticationEntryPoint.java` | Entry point kept; gains `scope` + conditional `error`. **Implementation deviation (approved):** `WellKnownController` deleted; PRM published by Spring Security's `OAuth2ProtectedResourceMetadataFilter` (see MCP-14) |
| Spring opaque-token introspector | `SpringOpaqueTokenIntrospector` (Security 7) | Wrapped, not reimplemented; built with its `(uri, RestOperations)` constructor and a timed `RestTemplate` + `BasicAuthenticationInterceptor` (M1) |
| Tool registration / handler template | `ProductService`, `CategoryService`, `AjMcpApplication` | Signatures change; specs unchanged |
| Backend status mapping | `mcp-server/.../config/RestClientConfig.java` | Kept minus interceptor |
| Fake host pattern | `mcp-server/.../security/McpAuthBoundaryTests.java` | Model for new MCP HTTP tests (JDK `HttpServer`) |
| OAuth flow test helpers | `OAuth2IntegrationTests`, `McpTokenBoundaryTests` | **Copied** into the shared package-private `OAuth2TestFlows`. `McpTokenBoundaryTests` is not modified and does not call the helper (RG-1) |
| Footprint `@ConditionalOnProperty` / Liquibase seed | footprint `spi/stub`, changelog `013-*` | Considered, not used: the secret is env-provided and must be BCrypt-hashed at runtime |

### New Components Required
| New | Why existing code cannot serve |
|---|---|
| `OAuth2Properties` (host) | No configured issuer or audience exists. Per-request `getBaseUrl` breaks `iss` checks for plugin backends |
| `ResourcePolicy` | `resource` is ignored everywhere. Audience rules need one source of truth |
| `ScopePolicy` | No permission intersection exists (viewer escalation). Scope lists are duplicated ×4 |
| `ClientAuthenticator` | Client auth is inline, post-only and skipped for unknown clients. Basic is advertised but missing |
| `OAuth2RequestException` + `OAuth2ErrorWriter` | Errors are classified by message substring, and the switch is copy-pasted ×3 with wrong statuses |
| `TokenExchangeGrantHandler` | No RFC 8693 grant exists |
| `OAuth2IntrospectionFilter` | No RFC 7662 endpoint exists |
| `McpClientProvisioner` | No confidential MCP client exists. The secret must come from env |
| `TokenResponse`, `IntrospectionResponse`, metadata record | Replace StringBuilder JSON and the 10-entry `Map.of` limit |
| `AjProperties` (MCP) | Fail-fast on a missing secret plus derived URIs. `@Value` fields cannot express this |
| `AudienceValidatingIntrospector` | Spring's introspector does not check `aud` |
| `McpAccessDeniedHandler` | No 403 `insufficient_scope` challenge exists |
| `ToolSecurity` | Principal and scope checks replace the raw-header context key |
| `TokenExchangeClient` | Spring Security 7 has no client-side RFC 8693 support suitable for per-call use. It needs its own `RestClient` without the backend status handler |

## Technical Approach
- **Host flow:**
  - Login mints T0 (`aud` = API).
  - Consent POST (`_token` = T0, hidden `resource`) → `ResourcePolicy` + session-principal check + `ScopePolicy` → code {grant, resource}.
  - Token → T1 (`aud` = MCP) + RT {client, resource}.
  - MCP introspects T1 (Basic) → active metadata.
  - MCP exchanges T1 → T2 (`aud` = API, `act`).
  - `/api` gate accepts T0/T2 only.
- **Filter order:** `JwtAuthenticationFilter` (API mode, `_token` too) → Registration → Authorization → Token (code / refresh / exchange) → Introspection. The new filters stay non-beans so the `@WebMvcTest` slice in `api/ApiLayerTests` is unaffected.
- **MCP flow:**
  - `BearerTokenAuthenticationFilter` → `AudienceValidatingIntrospector` → `SCOPE_mcp:*` authorities → `/` authorisation → transport `contextExtractor` stores the `Authentication`.
  - Tool handler on `boundedElastic` → `ToolSecurity` → `TokenExchangeClient` → `AjApiClient` with T2.
  - No ThreadLocal, no static state, no caches.
- **Migration:** no DB migration. The provisioned client is a row in `oauth2_registered_client`, and grant types round-trip as raw strings. Both apps ship in one change. Rollback = revert; the provisioned row is inert.
- **Implementation order:**
  0. Commit both red-test files unchanged as the baseline (RG-1).
  1. Properties, claims, port config.
  2. Policies, authenticator, writer, authorize/token changes, FU-3.
  3. API gate.
  4. Provisioner, introspection, exchange, metadata.
  5. MCP chain + context hand-off (red tests 3/4 first).
  6. Exchange client, services, deletions.
  7. Tests.
  8. Frontend, start-dev, docs.

## Corrections and Decisions Traceability

| Item | Realised by |
|---|---|
| C1 consent drops `resource` | FE-1 hidden input, plus a frontend test of the form field set; AS-5 validates at authorize, AS-8/AS-9 at token |
| C2 authorize errors | AS-5/AS-14: JSON errors kept, typed `OAuth2RequestException`, `determineError` deleted, no redirect errors |
| C3 status codes / triplicated writer | AS-14 `OAuth2ErrorWriter`: `unauthorized_client` 400, `invalid_client` 401 + Basic challenge |
| C4 introspect matcher | SEC-3: no new matcher (covered by the non-`/api/` rule); filter registered after the token filter |
| C5 Jackson | AS-7/11/13/14 use the injected Jackson 3 mapper; registration filter keeps Jackson 2 parsing; MCP stays Jackson 2; shared `com.fasterxml.jackson.annotation` annotations |
| C6 parse modes / fallback | SEC-1 `parseApiToken` / `parseAccessToken`, extended `ParsedToken`, fallback removed, unused helpers deleted; `generateTokenWithExpiration` signature kept |
| C7 `scope` string | AS-11 translates `scopes[]` → space-delimited `scope` |
| C8 URL-encoded Basic | AS-4 decodes; MCP-8 encodes; MCP-3 URL-encodes id/secret before the `BasicAuthenticationInterceptor` |
| C9 refresh has no client auth / removes first | AS-9 `identify` + `consumeAndRotate(token, clientId, resource)` checks before the conditional `remove(token, data)` |
| C10 unknown client skips auth | AS-4 fails closed; exchange tested with a confidential DCR client |
| C11 provisioner upsert by PK | AS-12 `findByClientId` → `RegisteredClient.from`, client name set |
| C12 docs already claim target | DOC-1..6 rewrite lines and fix dead links |
| C13 Basic advertised but absent | AS-4 implements it; AS-13 adds introspection metadata |
| C14 contextExtractor thread | MCP-6 reads `SecurityContextHolder` on the servlet thread; `TokenExchangeClient` has its own `RestClient` |
| C-1 trailing slash | AS-2 `canonical`, `aud` minted canonical; MCP-3 slash-equal check |
| C-2 consent principal | AS-5 session tokens only; T2-as-`_token` regression test |
| I-1 MCP inbound stack | MCP-1/3: resource-server starter + explicit wrapped `SpringOpaqueTokenIntrospector` with a timed `RestTemplate` |
| I-2 missing scope | AS-3 `grantAtAuthorization` defaults to client scopes; `grantAtExchange` never defaults |
| I-3 refresh binding | AS-8/AS-9 `identify` (confidential auth / public `client_id`), validate before removal |
| I-4 blank secret | AS-12 deletes stale row with WARN |
| I-5 stale T0 | FE-3 drop stored token without `aud`; `auth.test.tsx` fixture gains `aud` |
| I-6 outage | MCP-5: 5xx without `invalid_token`, confirmed by the audit (Security 7.0.4 rethrows); no 503 fallback |
| I-7 Jackson | AS-14 / C5 row |
| I-8 test config | `src/test/resources/config/application.properties` (host) and `mcp-server/src/test/resources/config/application.yml` (MCP), each with `test-mcp-secret` |
| I-9 port mentions | CFG-3 (fixtures updated; `dist/` and logs untouched) |

## Implementation Guidance

### Testing Approach
- 2-8 focused tests per implementation step group. Test verification runs only the new and changed tests per group, then the full suites at the end: host `./mvnw test`, `cd mcp-server && ./mvnw test`, and `npm test` in `src/main/frontend`, all green.
- Integration-first: `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`. Pure-logic classes get plain JUnit unit tests. Classes are `*Tests`, package-private, in the same package. Names follow `action_condition_expectedResult`. AssertJ / `jsonPath`, no plain `assert`.
- Test configuration: host `src/test/resources/config/application.properties` (`app.oauth2.mcp-client-secret=test-mcp-secret`). MCP `mcp-server/src/test/resources/config/application.yml` (`aj.oauth.client-secret: test-mcp-secret`). Both are loaded in addition to the main config, giving one cached context per app.
- **Red gate (must pass; files byte-identical per RG-1, checked with `git diff --exit-code <baseline> -- <both files>`):**
  - `McpTokenBoundaryTests.mcpAccessToken_replayedAtApi_returns401`
  - `McpTokenBoundaryTests.authorize_viewerRequestingMcpEdit_isNotGrantedEditScope`
  - `McpAuthBoundaryTests.toolCall_withInactiveToken_returns401`
  - `McpAuthBoundaryTests.toolCall_withValidToken_neverForwardsClientTokenToBackend`

**Host tests** (`src/test/java/pl/devstyle/aj/core/...`; a shared package-private helper `OAuth2TestFlows` in `core/oauth2` wraps login, DCR (public/confidential), authorize, code exchange, token exchange and introspection over MockMvc. Its logic is **copied** from the existing tests; the red tests are not touched, RG-1):

| Class | Tests |
|---|---|
| `security/AuthIntegrationTests` (adapt) | existing 14 still pass; + `login_validCredentials_tokenCarriesIssuerAndApiAudience`, `protectedEndpoint_tokenWithoutAudience_returns401`, `protectedEndpoint_tokenWithForeignIssuer_returns401`, `protectedEndpoint_tokenWithForeignAudience_returns401` |
| `oauth2/OAuth2IntegrationTests` (adapt) | `getMetadata_returnsConfiguredIssuerIntrospectionAndExchangeGrant` (issuer `http://localhost:8180`); rename `tokenExchange_withRefreshToken_returnsNewAccessToken` → `refreshToken_sameClient_returnsNewAccessToken` (sends `client_id`); rename `tokenExchange_withInvalidCode_returns400` → `authorizationCode_invalidCode_returns400`; plain `assert` → AssertJ |
| `oauth2/OAuth2PropertiesTests` (unit) | `constructor_trailingSlash_isStripped`, `constructor_blankMcpResource_throws`, `toString_masksClientSecret` |
| `oauth2/ScopePolicyTests` (unit) | `grant_editorRequestingEdit_returnsReadAndEdit`, `grant_viewerRequestingEdit_returnsReadOnly`, `grant_editOnlyUser_returnsEditOnly`, `grant_editOnlyClientForReadUser_reExpandsToReadAndEdit`, `grant_unknownScope_isDropped`, `grant_emptyIntersection_throwsInvalidScope`, `grantAtAuthorization_missingRequested_usesClientScopes`, `grantAtExchange_emptySubjectScopes_throwsInvalidScope` |
| `oauth2/ResourcePolicyTests` (unit) | `resolveForAuthorization_missing_defaultsToMcpResource`, `resolveForAuthorization_trailingSlash_returnsCanonical`, `resolveForAuthorization_apiAudience_throwsInvalidTarget`, `resolveForAuthorization_unknown_throwsInvalidTarget`, `resolveForExchange_nonApi_throwsInvalidTarget`, `resourceParam_multipleValues_throwsInvalidTarget`, `boundResourceOf_mcpClient_returnsMcpResource` |
| `oauth2/ClientAuthenticatorTests` (unit, `InMemoryRegisteredClientRepository`, `MockHttpServletRequest`) | `authenticateConfidential_basic_returnsClient`, `authenticateConfidential_urlEncodedBasic_decodesCredentials`, `authenticateConfidential_post_returnsClient`, `authenticateConfidential_basicAndFormSecret_throwsInvalidRequest`, `authenticateConfidential_basicWithMatchingFormClientId_returnsClient`, `authenticateConfidential_basicWithDifferentFormClientId_throwsInvalidClient`, `authenticateConfidential_wrongSecret_throwsInvalidClient`, `authenticateConfidential_unknownClient_throwsInvalidClient` |
| `oauth2/OAuth2GrantPolicyTests` (spans the authorize and token step groups) | `authorize_withoutResource_issuesTokenForMcpAudience`, `authorize_trailingSlashResource_issuesCanonicalAudience`, `authorize_apiResource_returnsInvalidTarget`, `authorize_multipleResourceParams_returnsInvalidTarget`, `authorize_delegatedTokenAsSession_returns403AccessDenied` (T2 as `_token`), `authorizationCode_differentClientId_returnsInvalidGrant`, `authorizationCode_apiResource_returnsInvalidTarget`, `refresh_apiResource_returnsInvalidTarget`, `refresh_byDifferentClient_returnsInvalidGrantAndKeepsToken`, `refresh_publicClientWithoutClientId_returnsInvalidRequest` |
| `oauth2/TokenExchangeIntegrationTests` | `exchange_mcpClientWithValidSubject_returnsDelegatedApiToken` (claims `aud`, `client_id`, `act.sub`, `jti`, `typ`, `exp` ≤ min(300 s, T1 `exp`); response `issued_token_type`, `expires_in`, no `refresh_token`), `exchange_delegatedToken_getProducts_returns200`, `exchange_viewerSubject_isNotGrantedEditScope`, `exchange_viewerDelegatedToken_postProduct_returns403`, `exchange_permissionRevokedAfterConsent_returnsInvalidScope` |
| `oauth2/TokenExchangeValidationTests` | `exchange_withoutClientAuthentication_returns401WithBasicChallenge`, `exchange_confidentialDcrClient_returnsUnauthorizedClient`, `exchange_sessionTokenAsSubject_returnsInvalidRequest`, `exchange_delegatedTokenAsSubject_returnsInvalidRequest`, `exchange_resourceNotApiAudience_returnsInvalidTarget`, `exchange_missingResource_returnsInvalidTarget` |
| `oauth2/IntrospectionIntegrationTests` | `introspect_activeMcpToken_returnsMetadataWithSpaceDelimitedScope` (also asserts `Cache-Control: no-store` and `Pragma: no-cache`), `introspect_withoutClientAuthentication_returns401`, `introspect_sessionToken_returnsInactive`, `introspect_delegatedApiToken_returnsInactive`, `introspect_expiredToken_returnsInactive` (T1 minted with a past `exp` through `JwtTokenProvider`), `introspect_garbageToken_returnsInactive`, `introspect_unboundConfidentialClient_returnsInactive` |
| `oauth2/McpClientProvisionerTests` (Spring context, class-level `@Transactional` so every change rolls back; the provisioner is instantiated with explicit `OAuth2Properties` for the distinct client id `test-provisioned-client`, so the shared `aj-mcp-server` row is never touched — m9) | `run_withSecret_createsConfidentialExchangeClient`, `run_twiceWithSameSecret_keepsRowIdAndHash`, `run_withRotatedSecret_updatesHash`, `run_withBlankSecret_deletesStaleClient` |
| `oauth2/OAuth2LoggingTests` (`OutputCaptureExtension`) | `fullFlow_neverLogsTokensCodesOrSecrets` (authorize, code, refresh, exchange, introspect; asserts no token, code, verifier, secret or `_token` value appears) |

**MCP tests** (`mcp-server/src/test/java/pl/devstyle/aj/mcp/...`; new package-private `security/FakeHost` helper based on JDK `HttpServer` with programmable introspection, exchange and backend responses plus recordings; `McpAuthBoundaryTests` keeps its own fake):

| Class | Tests |
|---|---|
| Delete | `security/McpJwtFilterTests`, `security/AccessTokenHolderTests` |
| `config/RestClientConfigTests` (rewrite) | `ajApiClient_explicitAuthorization_isSentVerbatim`, `ajApiClient_backend401_mapsToApiErrorWithoutRetry` |
| `security/McpSecurityChainTests` (RANDOM_PORT) | `request_withoutToken_returns401WithResourceMetadataAndNoError`, `request_withForeignAudienceToken_returns401InvalidToken`, `request_withTrailingSlashAudience_isAccepted`, `request_withTokenWithoutMcpScope_returns403InsufficientScope`, `request_whenIntrospectionUnavailable_returns5xxWithoutInvalidToken`, `request_whenIntrospectionStalls_returns5xxWithoutInvalidToken` (the fake host holds the introspection response past the 10 s read timeout; asserts 5xx and that the request finished, M1), `protectedResourceMetadata_resourceEqualsBaseUrl` |
| `security/McpLoggingTests` (RANDOM_PORT, `OutputCaptureExtension`, `FakeHost`) | `toolCall_fullFlow_neverLogsSubjectOrExchangedToken` (runs `aj_list_products` and `aj_add_product` through introspection, exchange and backend with the shipped logging levels; asserts that neither T1, T2 nor the client secret appears in the captured output, M3/MCP-15) |
| `security/ToolAuthorizationTests` (RANDOM_PORT) | `addProduct_withReadOnlyToken_returnsScopeErrorWithoutExchange`, `toolCall_validToken_exchangesExactlyOncePerCall`, `toolCall_exchangeRejected_returnsToolErrorWithoutBackendCall`, `toolCall_wrongIssuedTokenType_returnsToolError`, `toolCall_backend401_returnsApiErrorWithoutRetry` |
| `security/AudienceValidatingIntrospectorTests` (unit) | `introspect_matchingAudience_returnsPrincipal`, `introspect_slashVariantAudience_returnsPrincipal`, `introspect_foreignAudience_throwsBadOpaqueToken` |
| `client/TokenExchangeClientTests` (unit, fake server) | `exchange_sendsUrlEncodedBasicAndRfc8693Form`, `exchange_invalidScope_mapsToInsufficientPermissions` |
| `config/AjPropertiesTests` (`ApplicationContextRunner`) | `context_withoutClientSecret_failsToStart`, `context_withBlankBaseUrl_failsToStart`, `derivedUris_followServerUrl`, `derivedUris_trailingSlashServerUrl_haveNoDoubleSlash`, `toString_masksClientSecret` |
| `service/ToolCallIntegrationTests`, `ErrorMappingTests`, `ToolSchemaValidationTests` (adapt) | Same test names and assertions on results, schemas and error types. `@MockitoBean TokenExchangeClient`; context and `Authentication` built from a `BearerTokenAuthentication` with `SCOPE_mcp:read`/`SCOPE_mcp:edit`; `AjApiClient` stubs take the authorization argument |
| `ConfigurationTests` (adapt) | `serverPort_isConfiguredTo8181`, `managementPort_isConfiguredTo9181` |
| `AjMcpApplicationTests`, `JacksonConfigTests` | unchanged; pass with the test-config secret |

**Frontend tests** (`src/main/frontend/src/test/`, `renderWithProviders`, `vi.mock` for fetch/API):

| File | Tests |
|---|---|
| `oauth2-authorize.test.tsx` (new) | `renders hidden resource input when resource query param is present`, `omits resource input when resource is absent`, `offers only mcp:read to a viewer`, `offers mcp:read and mcp:edit to an editor` |
| `auth.test.tsx` (extend) | `drops a stored token without aud claim` (seed localStorage, `vi.resetModules()`, dynamic import); the existing "provides token/permissions/login/logout via useAuth hook" fixture JWT gains `aud: ["http://localhost:8180/api"]` (m10), assertions unchanged |
| `plugin-sdk-auth.test.ts`, `plugin-sdk.test.ts` | fixture ports updated, assertions unchanged |

### Standards Compliance
- `standards/backend/security.md`: centralized matchers, no `@PreAuthorize`, no redundant matchers, JSON 401/403 entry points. The Token Claims and MCP scope lines are rewritten (DOC-3).
- `standards/mcp-server/conventions.md`: `aj_` tools unchanged, `SyncToolSpecification`, `structuredContent`, `McpToolException` factories, validation in the backend, no token passthrough (DOC-2).
- `standards/backend/java-conventions.md`: records for DTOs, Jackson 3 `tools.jackson` in host code, Jackson 2 only in the MCP server.
- `standards/global/conventions.md`: `app.*` / `aj.*` kebab-case keys with `${UPPER_SNAKE:default}` overrides, secrets from env (never committed), Maven wrapper, course directories untouched. One accepted deviation on env names (see below).
- `standards/global/minimal-implementation.md`: no caches, no speculative properties, unused `JwtTokenProvider` helpers deleted, no future stubs.
- `standards/global/error-handling.md`: typed exceptions, a centralized writer, fail fast on missing config.
- `standards/testing/backend-testing.md` / `frontend-testing.md`: as described under Testing Approach.
- `standards/plugins/plugin-development.md`: fixed-port table updated (DOC-4).
- `standards/frontend/components.md` / `css.md`: consent page edits keep named exports and existing tokens. No new styling.

#### Standards compliance — accepted deviations (m15)
| Standard | Deviation | Why accepted |
|---|---|---|
| `global/conventions.md` (env name derived from prefix and key, e.g. `APP_*` for `app.*`) | Host `app.oauth2.mcp-resource` / `mcp-client-id` / `mcp-client-secret` read `AJ_MCP_BASE_URL` / `AJ_OAUTH_CLIENT_ID` / `AJ_OAUTH_CLIENT_SECRET` | Both apps must share one env value per must-equal pair, so `start-dev` exports each once. **Suggested standards update:** allow a shared cross-app env name for values that must be equal in host and MCP server. Suggested to the user only, not applied. |
| `backend/java-conventions.md` (Jackson 3 `tools.jackson` in host code) | `PublicClientRegistrationFilter` keeps its Jackson 2 request parsing and only switches to the shared Jackson 3 error writer | I-7: minimal touch to an out-of-scope DCR path (FU-1) |
| `testing/backend-testing.md` (private `createAndSave*()` helpers per test class) | Shared package-private `OAuth2TestFlows` used by several OAuth test classes | The multi-step OAuth flows (login, DCR, authorize, PKCE, code, exchange, introspect) would otherwise be duplicated across 6+ classes. The red tests keep their own copies (RG-1) |

## Out of Scope
- DCR → CIMD / pre-registration (FU-1). The DCR `grant_types` null path and redirect URI validation were fixed during verification (AS-17).
- Refresh-token persistence, reuse detection, absolute lifetime (FU-2b).
- JWKS / asymmetric keys and local JWT validation at MCP (FU-6), `private_key_jwt` (FU-7), DPoP/mTLS (FU-8), SAS/Keycloak migration (FU-9), CIMD/ID-JAG (FU-10).
- `_token` restriction (FU-4) and the 2C actor requirement on `PERMISSION_mcp:*`.
- Introspection or exchange caching, latency metrics, audit on `act.sub`.
- HTTP 403 step-up per tool (JSON-RPC body inspection), redirect-style authorize errors, `state` URL-encoding and other FU-11 hardening (host `WWW-Authenticate` on `/api`, RFC 9207 `iss`, MCP `Origin` checks).
- Removing the committed `APP_JWT_SECRET` fallback.
- Vite dev-server proxying of `/oauth2/*` (consent must be exercised on the host-served SPA at `:8180`).
- `plugins/*/dist/`, `.playwright-mcp` logs, `week7/`–`week10/`, `tools/`, `_docs/`.
- New candidate standards from the research (suggested to the user only, not applied).

## Success Criteria
1. The four red-gate tests pass with both files byte-identical to the committed baseline (`git diff --exit-code` exits 0), and the full host, MCP and frontend suites are green.
2. **No passthrough:** for every tool, the outbound `Authorization` to the host is T2 and never T1 (automated).
3. **Audience isolation:**
   - T1 at `/api` → 401. T0 and T2 at MCP → 401 (introspection inactive).
   - An authorization-code request for the API audience → `invalid_target`, and so is the API audience as `resource` at the code and refresh grants.
   - An exchange without `resource` → `invalid_target`.
   - T2 as a consent principal → 403.
4. **Least privilege:**
   - A viewer never receives `mcp:edit` at the auth-code or exchange grant.
   - A permission revoked after consent takes effect on the next tool call (`invalid_scope`).
   - T2 lifetime is ≤ 300 s and ≤ T1 `exp`.
5. **Client binding:**
   - Exchange and introspection succeed only for `aj-mcp-server`.
   - A confidential DCR client gets `unauthorized_client` at exchange and `active:false` at introspection.
   - A refresh by another client → `invalid_grant`, and the RT survives.
   - A code redeemed with a different `client_id` → `invalid_grant`.
   - An expired T1 at introspection → `active:false`.
6. **Hygiene:** no token, code, verifier, secret or `_token` value appears in either app's logs during a full flow (automated: `OAuth2LoggingTests` on the host, `McpLoggingTests` on the MCP server). The MCP config has no `DEBUG` on `pl.devstyle`, `io.modelcontextprotocol` or `org.springframework.security`. Introspection responses carry `Cache-Control: no-store`, and secret-bearing property records mask secrets in `toString()`.
7. **Unchanged tool contract:** tool names, input/output schemas and result structures are identical (schema tests unchanged in assertions).
8. **Ports and wiring:**
   - The host serves on 8180, MCP on 8181, management on 9181.
   - `start-dev` on a fresh clone yields a working consent → `aj_list_categories` / `aj_list_products` / `aj_add_product` run with a real MCP client against `http://localhost:8181` (manual gate; record the `resource` value sent).
9. **Docs:** CLAUDE.md, the root `README.md`, the standards and the project docs describe the implemented behaviour, with no dead links.
10. **Resilience:** a stalled introspection endpoint yields a 5xx at the MCP server after the 10 s read timeout instead of hanging the request (automated).

## Audit resolution

Source: `verification/spec-audit.md` (pass-with-concerns: 0 critical, 3 major, 15 minor, 4 clarifications).

| Finding | Spec change |
|---|---|
| M1 introspection without timeouts | MCP-3 builds `new SpringOpaqueTokenIntrospector(uri, restTemplate)` with connect 5 s / read 10 s and a URL-encoding `BasicAuthenticationInterceptor` (C8 kept); `McpSecurityChainTests.request_whenIntrospectionStalls_returns5xxWithoutInvalidToken`; Success Criterion 10 |
| M2 red-test immutability | New RG-1: both red-test files byte-identical, committed as baseline, `git diff --exit-code`; `OAuth2TestFlows` copies helpers; Reusable Components row reworded |
| M3 test-plan gaps | `OAuth2GrantPolicyTests.authorizationCode_differentClientId_returnsInvalidGrant`, `authorizationCode_apiResource_returnsInvalidTarget`, `refresh_apiResource_returnsInvalidTarget`; `TokenExchangeValidationTests.exchange_missingResource_returnsInvalidTarget`; `IntrospectionIntegrationTests.introspect_expiredToken_returnsInactive`; new MCP-15 (DEBUG removed for `pl.devstyle`, `io.modelcontextprotocol`, `org.springframework.security`) + `McpLoggingTests` |
| m1 exchange default to client scopes | AS-3 `grantAtExchange` (empty/missing subject scopes → `invalid_scope`, no default); AS-10 step 7; I-2 row |
| m2 hierarchy after client intersection | AS-3 re-expands after ∩ client scopes (`mcp:edit` ⇒ `mcp:read` only with READ); DOC-2/DOC-3 wording; `grant_editOnlyClientForReadUser_reExpandsToReadAndEdit` (user decision) |
| m3 error-code inconsistencies | AS-8/AS-9: resolves-but-≠-stored → `invalid_grant`, unknown/API → `invalid_target`; endpoint table rows split (exchange/introspection 401 `invalid_client` vs code/refresh 400 `invalid_request` without `client_id`) (user decision) |
| m4 "both forms at once" | AS-4: conflict = Basic + form `client_secret` → `invalid_request`; form `client_id` ≠ Basic id → `invalid_client`; two new `ClientAuthenticatorTests` (user decision) |
| m5 `RefreshTokenService` signature | AS-9 `consumeAndRotate(token, clientId, Optional<String> resource)` checks before conditional `remove(token, data)`; RT survives mismatch |
| m6 `ToolSecurity` type | MCP-7/MCP-11 take `BearerTokenAuthentication` |
| m7 trailing slash / blank values | MCP-2 strips `/` from server-url and base-url and requires non-blank server-url, base-url, client-id; AS-1 requires non-blank `mcpResource`, `mcpClientId`; new `AjPropertiesTests` / `OAuth2PropertiesTests` |
| m8 secrets in `toString()` | AS-1 and MCP-2 override `toString()` to mask; `toString_masksClientSecret` in both apps |
| m9 provisioner test isolation | `McpClientProvisionerTests` class-level `@Transactional`, client id `test-provisioned-client` via explicit properties |
| m10 `auth.test.tsx` fixture | FE-3 + frontend test table: fixture gains `aud: ["http://localhost:8180/api"]` |
| m11 AS-8 consumes code first | Key Decisions bullet + AS-8 step 2 note |
| m12 introspection headers | AS-11 `Cache-Control: no-store`, `Pragma: no-cache`; endpoint table; asserted in the active-token test |
| m13 multiple `resource` params | New AS-16 (`ResourcePolicy.resourceParam`, >1 → `invalid_target`); unit + authorize tests |
| m14 docs inventory | DOC-6 drops `plugins/sdk.ts`; new DOC-7 root `README.md`; DOC-2 adds `insufficientScope` to the factory list |
| m15 undeclared deviations | "Standards compliance — accepted deviations" table; env-naming standards update suggested |
| Clarification 1 (`mcp:edit`-only client) | Yes, re-expand (m2) |
| Clarification 2 (resource ≠ stored) | `invalid_grant` on code and refresh (m3) |
| Clarification 3 (`APP_JWT_SECRET`) | Approved: inherit only, never generate; CFG-2 + Key Decisions |
| Clarification 4 (Basic + form `client_id`) | Not a conflict if equal; differing → `invalid_client` (m4) |
