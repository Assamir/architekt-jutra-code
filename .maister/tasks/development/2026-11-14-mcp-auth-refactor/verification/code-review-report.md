# Code Review Report

**Date**: 2026-11-14
**Path**: uncommitted working tree on `feat/mcp-auth-refactor` vs `bd4de70` (host `core/oauth2/**`, `core/security/**`, `mcp-server/**`, frontend `OAuth2AuthorizePage.tsx` / `AuthContext.tsx`, `start-dev.*`, `plugins/server-sdk.ts`, config)
**Scope**: all (quality, security, performance, best practices)
**Status**: ⚠️ Issues Found

## TL;DR
- Token confusion is closed. T0, T1 and T2 can each be used only where they are meant to be. T1 (aud = MCP) is rejected at `/api`. T0 is rejected at introspection and exchange because it has no `typ: at+jwt`. T2 is rejected as an exchange subject (it has `act` and aud = API), at introspection (wrong aud) and at consent (it is not a session token).
- No critical issues. 4 warnings: the committed HMAC fallback key signs all three token types (pre-existing, accepted); ≥2 BCrypt checks per tool call, which also lets unauthenticated callers burn CPU; `authorization_code`/`refresh_token` grants do not check the client's registered grant types; the redirect `Location` is built by string concatenation with `state` not encoded.
- Client auth (Basic URL-decoding, conflict rules, fail-closed), refresh-token binding/rotation (`get` → check → `remove(token, data)`), introspection disclosure and secret-free logging all match the spec and are tested.
- Test quality is high (345/346, red tests byte-identical). A few security branches are untested (refresh race, the exp cap, deleted user at exchange).

## Key Decisions
- I judged severity for a **local-only course showcase**. Two items (W1 hardcoded key, W4 redirect concatenation) are pre-existing but load-bearing for this refactor, so they are reported as warnings, each marked as pre-existing or accepted.
- I checked the T0/T1/T2 matrix against the code, not the spec (see "Token-confusion matrix" below). No path accepts a token outside its intended use.
- The known missing dummy BCrypt for unknown clients (timing oracle) is rated **info**. Client ids are random UUIDs, and the only well-known id (`aj-mcp-server`) is public anyway.
- Pre-existing frontend lint (16 problems) and the stale `extension-points.test.tsx` are out of scope and not counted.

## Open Questions / Risks
- With `APP_JWT_SECRET` unset (the start-dev default), anyone with repo access can mint a valid T2 or T0 for any user. The audience model only protects against *stolen* tokens, not forged ones (W1).
- An operator who sets `AJ_MCP_BASE_URL` to a value under `${issuer}/api` silently re-opens T1 replay at `/api`, because nothing checks this at startup (I2).
- The `resource` value real MCP clients send is still unverified (spec risk). Canonical slash-equality covers `X/`, not case or default-port variants (they fail closed).

---

## Token-confusion matrix (verified)

| Token | Header `typ` | `aud` | `client_id` / `act` | `/api` (`parseApiToken`) | `/oauth2/authorize` consent | Introspection (MCP client) | Exchange subject |
|---|---|---|---|---|---|---|---|
| T0 session | none | `${issuer}/api` | none / none | ✅ permissions | ✅ | ❌ inactive (no `at+jwt`) | ❌ invalid_request (no `at+jwt`) |
| T1 MCP | `at+jwt` | `mcpResource` | DCR client / none | ❌ 401 (aud) | ❌ 403 (no auth) | ✅ active | ✅ |
| T2 delegated | `at+jwt` | `${issuer}/api` | `aj-mcp-server` / `aj-mcp-server` | ✅ scopes only | ❌ 403 (not session) | ❌ inactive (aud) | ❌ invalid_request (`act`, aud) |

`iss` is enforced by `requireIssuer` on every parse (`JwtTokenProvider.java:103`). Signatures are enforced by `parseSignedClaims`, and `exp` by jjwt.

---

## Summary
- **Critical**: 0 issues
- **Warnings**: 4 issues
- **Info**: 15 issues

## Critical Issues
None.

## Warnings

### W1. The committed HMAC fallback key signs T0, T1 and T2 — tokens can be forged by anyone with repo access (security, pre-existing / accepted)
- **Location**: `src/main/resources/application.properties:5` (`app.jwt.secret=${APP_JWT_SECRET:i/WZnr…}`); `start-dev.sh:16`, `start-dev.ps1:28` (deliberately not generated)
- **Description**: One symmetric key signs the session token, the MCP token and the delegated API token. Issuer and audience values are predictable (`http://localhost:8180`, `…/api`), so anyone who knows the committed fallback can mint a T2 or T0 with any `sub` and scopes or permissions. That bypasses introspection, exchange and the `aud` gate. Not generating `APP_JWT_SECRET` in start-dev was a **user-approved narrowing** (audit clarification 3) so that SPA sessions survive restarts.
- **Why it matters**: The refactor's guarantees (audience binding, delegated short-lived tokens) only hold while the key is secret. Running this outside a developer laptop with the default key means full compromise.
- **Recommendation**: Keep the decision for local dev, but fail fast when `APP_JWT_SECRET` is unset outside the `dev` profile. Alternatively, have start-dev generate the key once and persist it to a gitignored `.env`, which keeps sessions stable across restarts. Document the risk in CLAUDE.md or README "Manual Start".
- **Fixable**: false (design/decision)

### W2. BCrypt runs on the MCP hot path (≥2 per tool call) and unauthenticated callers can make the host spend BCrypt CPU (performance / security)
- **Location**: `src/main/java/pl/devstyle/aj/core/oauth2/ClientAuthenticator.java:57-63`; called per request from `OAuth2IntrospectionFilter.java:75` and `TokenExchangeGrantHandler.java:45`
- **Description**: Every MCP request is introspected (1 BCrypt), and every tool call also exchanges a token (1 BCrypt). That is about 2 × 50–100 ms of host CPU per tool call at the default strength 10. Anyone can POST `/oauth2/introspect` or `/oauth2/token` with `Basic aj-mcp-server:<random>` (the client id is a public default). Each such request makes the host compute a full BCrypt. There is no rate limiting or lockout.
- **Why it matters**: It adds latency to every MCP call, and a trivially scriptable CPU-exhaustion vector exists on a `permitAll` endpoint. The spec lists the cost as "unmeasured, acceptable" and puts caching out of scope.
- **Recommendation**: The machine client's secret is 256-bit random, so it does not need a slow KDF. Verify it with `MessageDigest.isEqual(sha256(presented), storedSha256)`. Or, within this scope, cache "this secret verified" keyed by SHA-256(secret) for the process lifetime and invalidate it when provisioning changes the secret. Consider a simple failure-rate limit per client id. The rest of the design does not need to change.
- **Fixable**: false (needs a design choice)

### W3. The token endpoint does not check the client's registered grant types for `authorization_code` / `refresh_token` (security)
- **Location**: `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2TokenFilter.java:96-150`; `OAuth2AuthorizationFilter.java:79-86`
- **Description**: The exchange branch correctly requires `AuthorizationGrantType.TOKEN_EXCHANGE` (`TokenExchangeGrantHandler.java:47`), but the code and refresh branches never check `client.getAuthorizationGrantTypes()`. A DCR client registered without `refresh_token` (the DCR default is `authorization_code` only, `PublicClientRegistrationFilter.java:332-334`) still gets and can use refresh tokens. The provisioned `aj-mcp-server` client (TOKEN_EXCHANGE only) is kept out of the code flow only by the accident that it has no redirect URIs.
- **Why it matters**: RFC 6749 §5.2 `unauthorized_client`. The client's registered metadata is not enforced, and the MCP client's isolation depends on an incidental property, not an explicit rule.
- **Recommendation**: In `handleAuthorizationCodeGrant` and `handleRefreshTokenGrant` (and at authorize), throw `UNAUTHORIZED_CLIENT` unless the client holds the matching grant type. Issue `refresh_token` only when the client is registered for it. Add a test where the `aj-mcp-server` client attempts `/oauth2/authorize`.
- **Fixable**: true

### W4. The authorize redirect is built by string concatenation and `state` is not URL-encoded (security, pre-existing in a rewritten method)
- **Location**: `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2AuthorizationFilter.java:99-102`
- **Description**: `redirectUri + "?code=" + code + "&state=" + state`. A crafted `state` (e.g. `x&code=<attacker-code>`) injects extra query parameters into the client's callback, which is code-injection or mix-up bait for clients that read the last `code`. A registered `redirect_uri` that already has a query string produces a malformed URL with two `?`.
- **Recommendation**: Use `UriComponentsBuilder.fromUriString(redirectUri).queryParam("code", code).queryParamIfPresent("state", Optional.ofNullable(state).filter(s -> !s.isEmpty())).encode().build().toUriString()`. Add a test with `state` containing `&` and `=`.
- **Fixable**: true

## Informational

### I1. Timing oracle on client existence (known, flagged in work-log)
- **Location**: `ClientAuthenticator.java:57-63`
- **Description**: An unknown client fails before BCrypt runs, so response time reveals whether a `client_id` exists. The impact is low (DCR ids are random UUIDs and the MCP id is a public default).
- **Suggestion**: Run `passwordEncoder.matches(secret, DUMMY_HASH)` when the client is unknown. Note that this slightly worsens W2.

### I2. No startup guard against `mcpResource` overlapping the API audience or issuer
- **Location**: `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2Properties.java:8-12`
- **Description**: If `AJ_MCP_BASE_URL` is misconfigured to `http://localhost:8180/api` (or the issuer itself), every T1 carries `aud = apiAudience` and passes `parseApiToken`. That re-opens exactly the replay this refactor closes. `ResourcePolicy.resolveForAuthorization` would accept it, because it equals `mcpResource`.
- **Suggestion**: In the compact constructor, reject `mcpResource` equal to `issuer + "/api"` or starting with it (and equal to `issuer`). Cost: two lines plus one test.

### I3. DCR redirect-URI validation is weak (pre-existing)
- **Location**: `PublicClientRegistrationFilter.java:354-375`
- **Description**: Validation runs only when `grant_types` explicitly contains `authorization_code`. With the default or omitted grant types, URIs are not validated, and `redirect_uris: null` causes an NPE that returns 500. The loopback check is a prefix match, so `http://localhost.attacker.com/cb` and `http://localhost@evil.com` pass. Severity is limited because any `https://` URI is allowed anyway and PKCE is mandatory for public clients.
- **Suggestion**: Always validate when the effective grant set includes `authorization_code`. Parse with `URI` and compare `host` against `localhost` / `127.0.0.1` / `[::1]`. Return `invalid_redirect_uri` for missing URIs.

### I4. Log forging and unhelpful `client_id` in rejection logs
- **Location**: `OAuth2TokenFilter.java:84-89`, `OAuth2IntrospectionFilter.java:62-67`, `OAuth2AuthorizationFilter.java:51-54`
- **Description**: These filters log the raw request parameter `client_id` without sanitising it, so CR/LF can forge log lines. With Basic auth (the MCP server's method) the parameter is absent, so these lines log `client_id=null`.
- **Suggestion**: Log the authenticated client id when available. Otherwise strip control characters, or log a bounded or escaped value.

### I5. Test gaps on security-relevant branches
- **Location**: `src/test/java/pl/devstyle/aj/core/oauth2/*`
- **Description**: The following are not covered:
  - (a) A concurrent refresh-token rotation race. `remove(token, data)` is correct, but no test proves that only one of two parallel refreshes wins.
  - (b) The `exp = min(now + 300 s, subject exp)` branch where the subject expires first. The existing test uses a 900 s subject, so only the 300 s cap is exercised.
  - (c) Exchange for a deleted user (`UsernameNotFoundException` → `invalid_request`).
  - (d) Exchange with an `at+jwt` subject whose `aud` is not the MCP resource.
  - (e) The `aj-mcp-server` client attempting the code flow (see W3).
- **Suggestion**: Add 3–5 focused tests. They are cheap with `OAuth2TestFlows` plus `jwtTokenProvider.generateOAuth2Token`.

### I6. Introspection and refresh do not reflect user deletion or disablement
- **Location**: `OAuth2IntrospectionFilter.java:81-86`, `RefreshTokenService.java:33-60`
- **Description**: A T1 stays `active` for up to 15 minutes after its user is deleted, and refresh re-issues T1 for up to 24 hours. MCP `tools/list` still works. Tool calls fail closed at exchange (`TokenExchangeGrantHandler.java:93-102`). This is consistent with ADR-004.
- **Suggestion**: None required. Optionally check that the user exists at refresh.

### I7. The plugin-held T0 is a valid consent principal (pre-existing)
- **Location**: `OAuth2AuthorizationFilter.java:111-118`, `JwtAuthenticationFilter.java:191-202`
- **Description**: Plugin iframes receive the user's T0 through the SDK. Any plugin can POST `/oauth2/authorize` with `_token` for its own DCR client and obtain an MCP token without the user seeing the consent page. Impact is low, because T0 already grants full `/api` access.
- **Suggestion**: Consider a narrower plugin token (separate `aud`) in a future iteration.

### I8. `start-dev` exports `AJ_OAUTH_CLIENT_SECRET` to every plugin process
- **Location**: `start-dev.sh:16`, `start-dev.ps1:16-28`
- **Description**: The `export` is global, so the Vite and Next.js plugin processes inherit the MCP client secret. It is not bundled to browsers (no `VITE_` / `NEXT_PUBLIC_` prefix), but this is broader than needed. In PowerShell the value also persists in the calling session.
- **Suggestion**: Pass it only to the two Java processes (e.g. `AJ_OAUTH_CLIENT_SECRET=… ./mvnw …`), or `unset` it before the plugin loop.

### I9. Duplication in filters and services
- **Location**: try/catch/log/write blocks in `OAuth2TokenFilter.java:74-93`, `OAuth2IntrospectionFilter.java:59-71`, `OAuth2AuthorizationFilter.java:48-57`; `OAuth2TokenFilter.scopesOf` vs `ScopePolicy` splitting; `CategoryService.java:169-170` inlines what `ProductService.backendAuthorization` does
- **Suggestion**: Optionally extract a small `handle(response, Supplier)` helper and a shared `ScopePolicy.parse(String)`, and reuse one `backendAuthorization` helper on the MCP side.

### I10. `RestClientConfig` bypasses `AjProperties`
- **Location**: `mcp-server/.../config/RestClientConfig.java:21` (`@Value("${aj.backend.url}")`); `AjProperties.Backend` has no validation
- **Suggestion**: Inject `AjProperties` and use `backend().url()`. Validate it as non-blank and strip the trailing `/`, like the other nested records.

### I11. Host still constructs a Jackson 2 `ObjectMapper` for DCR parsing
- **Location**: `PublicClientRegistrationFilter.java:3, 239`
- **Description**: This is an accepted deviation (spec I-7), but it conflicts with the host standard (Jackson 3 only).
- **Suggestion**: Migrate in a follow-up.

### I12. `JwtParser` is rebuilt on every parse
- **Location**: `JwtTokenProvider.java:101-104`
- **Suggestion**: Build the parser once in the constructor. It is immutable and thread-safe. A minor saving on every authenticated request.

### I13. Trailing-slash stripping differs between config and the canonical rule
- **Location**: `OAuth2Properties.java:32-34`, `AjProperties.java:305-307` vs `ResourcePolicy.canonical` / `AudienceValidatingIntrospector.canonical`
- **Description**: Config strips one `/` unconditionally, while canonical comparison strips it only for origin-only URIs. A path-style resource configured as `http://h/mcp/` would never match a client sending `http://h/mcp/`. Today's configuration (origin-only) is unaffected.
- **Suggestion**: Use the same origin-only rule in both places, or document that the resource must be origin-only.

### I14. CORS default drops the external origin (behaviour change)
- **Location**: `application.properties:16`
- **Description**: `https://kuba-app.labs-skillpanel.com` is no longer allowed by default. Deployments that relied on it must set `APP_CORS_ALLOWED_ORIGINS`.
- **Suggestion**: Note it in README or the changelog.

### I15. OAuth 2.1 / RFC 9700 hardening not applied (pre-existing)
- **Location**: `OAuth2AuthorizationFilter.java:144-168`, `RefreshTokenService.java`
- **Description**: PKCE is optional for confidential clients, and refresh-token reuse does not revoke the token family.
- **Suggestion**: Out of scope. Track as a follow-up.

## Positive observations
- `ClientAuthenticator` implements RFC 6749 §2.3.1 correctly. It uses strict Base64, splits at the first `:`, URL-decodes both parts as UTF-8, treats Basic plus a form secret as `invalid_request`, and allows a form `client_id` only if it matches the Basic id. Unknown clients, unregistered methods and a null secret all fail closed. The MCP side URL-encodes before Basic (`SecurityConfig.java:194-195`, `TokenExchangeClient.java:431-434`).
- `RefreshTokenService.consumeAndRotate`: `get` → expiry/client/resource checks → conditional `remove(token, data)`. A wrong client cannot revoke someone else's token, and the race is atomic.
- Consent principal: only `ParsedToken.isSessionToken()` (no `client_id`, no `act`) is accepted, and the granted scope is computed server-side, independent of the consent-page filter.
- Exchange: confidential client + grant + bound resource, `at+jwt`, no `act`, `aud` ∋ bound resource, current permissions re-loaded, `exp` capped, no refresh token.
- Introspection discloses only to the bound client and only for tokens bound to it. Everything else returns `{"active":false}` with no detail. `no-store` is set on every response path.
- MCP: a timed introspection `RestTemplate` (5 s / 10 s); a 5xx (not `invalid_token`) on AS outage; explicit `BearerTokenAuthentication` hand-off through `McpTransportContext` (no thread-locals); scope checked before exchange; T1 never reaches `/api`; secrets masked in `toString()`; DEBUG logging removed; logging tests on both sides assert that no token, code, verifier or secret appears in captured output.
- Jackson usage matches the module split: the host writes OAuth2 JSON with injected Jackson 3 and `com.fasterxml.jackson.annotation` annotations (honoured by Jackson 3); the MCP side uses Jackson 2.

## Metrics
- Max function length (changed code): ~37 lines (`ClientAuthenticator.authenticateConfidential`). The pre-existing `PublicClientRegistrationFilter.handleRegistrationRequest` is ~54. MCP tool builders are long only because of the JSON-schema text blocks (pre-existing).
- Max nesting depth: 3 levels
- Potential vulnerabilities: 4 warnings (W1–W4), 0 critical
- N+1 query risks: 0 (one `findByClientId` per client authentication; one `findByUsername` per exchange)
- Files analyzed: ~45 (host oauth2/security 29, MCP main 17, frontend 2, scripts/config 5, plus test classes sampled)

## Prioritized Recommendations
1. **W3**: Enforce registered grant types at the code and refresh grants (and authorize). This is a small, contained fix that makes the MCP client's isolation explicit.
2. **W4**: Build the redirect `Location` with `UriComponentsBuilder` and encode `state`.
3. **I2**: Fail fast if `mcpResource` overlaps the API audience or issuer (two-line guard against silently re-opening T1 replay).
4. **W2**: Replace BCrypt for the env-provisioned machine client with a constant-time SHA-256 compare, or cache the verification. Add a basic failure rate limit.
5. **W1**: Fail fast on a missing `APP_JWT_SECRET` outside `dev`, or generate and persist it in start-dev.
6. **I5**: Add the missing tests (refresh race, exp-cap branch, deleted user, foreign-aud subject, MCP client in the code flow).
7. Then the hygiene items: I4 (log sanitisation), I8 (scope the secret env), I10/I12/I9 (small refactors).
