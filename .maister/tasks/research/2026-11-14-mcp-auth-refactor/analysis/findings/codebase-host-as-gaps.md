# Host AS gap list: what is missing for a no-passthrough MCP → host design

## TL;DR
- The custom AS has none of the building blocks the target designs need: no RFC 8693 grant, no RFC 7662 endpoint, no RFC 8707 `resource` handling, no `aud` claim or check, no JWKS or asymmetric keys, and no seeded confidential client for the MCP server. DCR cannot even register a client with a non-auth-code grant.
- The cheapest additions in the current architecture are a new `grant_type` branch in `OAuth2TokenFilter.java:62-69` and a new filter or endpoint for introspection. Both reuse `RegisteredClientRepository`, BCrypt secret checks and `JwtTokenProvider`. The prerequisite is a real client-authentication helper that supports Basic and post.
- Audience binding needs changes on both sides: `JwtTokenProvider` must issue `aud` (plus `client_id`, `jti`, `scope`), and `JwtAuthenticationFilter`/`parseToken` must require `aud` (and `iss`). Today both token families pass the same check.
- Pre-existing defects found during the inventory (scope escalation, unauthenticated and unbound refresh, tokens in logs, request-derived `iss`, a committed HMAC default) bear directly on the new design and should be fixed alongside it.

## Key Decisions
- Each gap is tied to the exact code location that would need to change, so the synthesizer can estimate effort for "extend custom AS" against "migrate to SAS / Keycloak".
- RFC requirement wording is left to the `external-specs` gatherer. This file states only what the code lacks.

## Open Questions / Risks
- If the MCP server validates tokens locally with the current HS256 secret, it must hold the same signing key as the AS, which lets it mint host tokens. Gap (d) is therefore a precondition for local JWT validation at the MCP server, but not for introspection.
- In-memory stores (codes, refresh tokens) mean that introspection of opaque tokens or exchange bookkeeping would not survive a restart. JWT access tokens avoid that, but revocation would need a denylist or short TTLs.

---

## (a) RFC 8693 token exchange: MISSING

| Gap | Where | Evidence |
|---|---|---|
| No `urn:ietf:params:oauth:grant-type:token-exchange` branch; any other `grant_type` gets `unsupported_grant_type` | `OAuth2TokenFilter.java:62-69` | dispatch handles only `authorization_code` / `refresh_token` |
| No parsing of `subject_token`, `subject_token_type`, `actor_token`, `requested_token_type`, `audience`, `resource`, `scope` | `OAuth2TokenFilter.java` | none of these parameter names appear in `core/` |
| No `issued_token_type` in the response, and the response builder is a fixed string with `expires_in:900` and a mandatory `refresh_token` | `OAuth2TokenFilter.java:216-237` | a token-exchange response must not be forced to include a refresh token |
| No `act` (actor) claim support in token generation; `generateOAuth2Token(username, scopes, issuer)` has no audience or actor parameters | `JwtTokenProvider.java:47-58` | — |
| No policy for which client may exchange which subject tokens for which audience | `RegisteredClientEntity.java`, migration 009 | no columns for allowed audiences or exchange permissions |
| DCR rejects `token-exchange` (and `client_credentials`) grant types | `PublicClientRegistrationFilter.java:140-146` | `default -> throw ... "Unsupported grant type"` |
| AS metadata does not advertise the grant | `OAuth2MetadataController.java:46` | `grant_types_supported = [authorization_code, refresh_token]` |
| Subject-token validation would have to reuse `parseToken`, which drops `iss`/`aud` and merges `permissions` with `scopes` | `JwtTokenProvider.java:68-87` | `ParsedToken(username, permissions)` only |

## (b) RFC 7662 introspection: MISSING

| Gap | Where | Evidence |
|---|---|---|
| No `/oauth2/introspect` endpoint, filter or controller | `core/oauth2/*` | grep for `introspect` finds nothing |
| No `SecurityConfiguration` matcher for it (it would need to be `permitAll` at the chain level with client authentication inside, like `/oauth2/token`, or a dedicated authenticated matcher) | `SecurityConfiguration.java:91-94` | only metadata, register and token are listed |
| No reusable client-authentication component: secret checking is inline in the auth-code handler and reads only the `client_secret` form parameter | `OAuth2TokenFilter.java:116-131`, `:82` | `client_secret_basic` is not parsed anywhere |
| A token-to-introspection-response mapping would have to derive `active`, `scope`, `client_id`, `sub`, `aud`, `iss`, `exp`, `iat`, `token_type`, but tokens carry no `client_id` or `aud` | `JwtTokenProvider.java:47-58` | — |
| No revocation or denylist, so `active` could only reflect signature and `exp` | — | access tokens are self-contained JWTs; there is no token store |
| Metadata lacks `introspection_endpoint` and `introspection_endpoint_auth_methods_supported` | `OAuth2MetadataController.java:41-51` | — |

## (c) RFC 8707 resource indicators + `aud` validation: MISSING

| Gap | Where | Evidence |
|---|---|---|
| `resource` is not read at `/oauth2/authorize` and is not stored in `AuthorizationCodeData` | `OAuth2AuthorizationFilter.java:67-73`, `AuthorizationCodeService.java:64-73` | the record has no resource field |
| `resource` is not read or checked at `/oauth2/token` (it must match or be a subset of the authorized resource) | `OAuth2TokenFilter.java:78-82` | — |
| Access tokens have no `aud` claim | `JwtTokenProvider.java:50-57` | only `iss`, `sub`, `scopes`, `iat`, `exp` |
| The host does not validate `aud` (or `iss`): `Jwts.parser().verifyWith(secretKey)` with no `requireAudience` / `requireIssuer` | `JwtTokenProvider.java:70-74` | — |
| App session tokens and OAuth tokens are indistinguishable at the host (same key, same filter, `permissions`/`scopes` fallback) | `JwtTokenProvider.java:76-79`, `JwtAuthenticationFilter.java:28-35` | — |
| `iss` is derived from `X-Forwarded-*` request headers, so it is not a stable value that can be checked | `OAuth2TokenFilter.java:146, 254-282`, `OAuth2MetadataController.java:39, 54-83` | caller-controlled `X-Forwarded-Host` changes `iss` and the metadata `issuer` |
| No registry of valid resource identifiers (MCP server URL, host API URL) | `application.properties` | no `app.oauth2.*` / resource properties exist |
| Refresh tokens carry no resource or audience, so they cannot be audience-restricted on refresh | `RefreshTokenService.java:75-80` | — |

## (d) Asymmetric signing / JWKS: MISSING

| Gap | Where | Evidence |
|---|---|---|
| Symmetric HS256 only, with one key for app login tokens and OAuth tokens | `JwtTokenProvider.java:20-26` | `Keys.hmacShaKeyFor(...)` |
| The default secret is committed and not overridden by compose or start scripts | `application.properties:4` | `${APP_JWT_SECRET:i/WZnrbvFqiPfShuZjGmc5kC7IXxRZfpueJEdgCzGFc=}`, and no `APP_JWT_SECRET` in `compose.yml`, `start-dev.sh` or `start-dev.ps1` |
| No `kid` header, no key rotation, no `/oauth2/jwks` (or `/.well-known/jwks.json`) endpoint | `JwtTokenProvider.java`, `OAuth2MetadataController.java` | — |
| No `jwks_uri` in AS metadata | `OAuth2MetadataController.java:41-51` | — |
| No `typ: at+jwt` header (RFC 9068) | `JwtTokenProvider.java:50-57` | jjwt default header carries `alg` only |
| SAS's `JWKSource`/`NimbusJwtEncoder` infrastructure is on the classpath through the SAS dependency but unused | `pom.xml:69-72` | — |

## (e) Confidential client for the MCP server: MISSING

| Gap | Where | Evidence |
|---|---|---|
| No seeded `oauth2_registered_client` row (MCP server `client_id`, hashed secret, grants, allowed scopes) | `db/changelog/2026/*` | only 009 creates the table, and no insert exists |
| DCR only allows `authorization_code` / `refresh_token`, so an MCP-server client for exchange or `client_credentials` cannot be self-registered | `PublicClientRegistrationFilter.java:135-147` | — |
| Client authentication is only implemented for the auth-code grant, and only `client_secret_post` | `OAuth2TokenFilter.java:116-131` | Basic is advertised but ignored |
| The refresh grant does no client authentication at all and refresh tokens are not bound to the client | `OAuth2TokenFilter.java:156-167`, `RefreshTokenService.java:75-80` | — |
| No configuration properties for the MCP server's client credentials on the host side (for example a dev seed through `${MCP_CLIENT_SECRET}` per the env-var conventions) | `application.properties` | — |
| No `private_key_jwt` / mTLS client auth (only relevant if (d) adds asymmetric keys) | — | — |

## Other defects that the refactor touches (pre-existing)

1. **Scope escalation**: granted scopes are never intersected with user permissions. `OAuth2AuthorizationFilter.java:98-109` stores permissions, but `OAuth2TokenFilter.java:142-147` uses only `data.scope()`, and the consent UI does not filter either (`OAuth2AuthorizePage.tsx:47-57`). A `viewer` can obtain `mcp:edit` and then write data through `SecurityConfiguration.java:115-120`. Any exchange grant must not repeat this: the exchanged token's permissions should be at most `subject scopes ∩ user permissions ∩ client allowed scopes`.
2. **Tokens and secrets in INFO logs**: `OAuth2TokenFilter.java:57-59, 152-153, 234` and `OAuth2AuthorizationFilter.java:64-66`.
3. **`_token` request parameter accepted on every path**: `JwtAuthenticationFilter.java:47-51` lets bearer tokens arrive in query strings.
4. **Refresh-token reuse** is not detected (no family revocation), and the sliding 24 h TTL never ends: `RefreshTokenService.java:31-50`.
5. **No `WWW-Authenticate` header** on host 401s: `SecurityConfiguration.java:65-76`. This matters if the host itself becomes an RFC 6750 / RFC 9728 resource server.
6. **Unauthenticated `/api/oauth2/client-info`** lets anyone look up any client's name and scopes: `OAuth2MetadataController.java:24-35`, `SecurityConfiguration.java:97`.
7. **`expires_in` is hard-coded** (`OAuth2TokenFilter.java:226`) and duplicates `OAUTH2_TOKEN_EXPIRATION_MS` (`JwtTokenProvider.java:45`).

## Test gaps to close with the refactor
The current suite covers none of the following: confidential-client authentication (post or Basic), introspection, token exchange, `aud`/`iss` rejection at `/api/**`, MCP-scoped tokens against host endpoints, the viewer → `mcp:edit` case, refresh-token client binding and reuse, or the claim contents of issued tokens. The existing `OAuth2IntegrationTests` tests named `tokenExchange_*` (`:232`, `:280`) are about the refresh and auth-code grants, not RFC 8693, and should be renamed to avoid confusion once a real exchange grant exists.
