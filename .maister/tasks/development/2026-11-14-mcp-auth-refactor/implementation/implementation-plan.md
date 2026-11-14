# Implementation Plan: MCP auth refactor — validate-then-exchange (P1)

## TL;DR
- 17 task groups, 104 steps, 94 new tests (plus 4 renamed and about 16 adapted) and up to 10 gap tests, organised as two independent backend chains (host G2–G9, MCP G10–G13) plus frontend (G14) and dev wiring (G15), which can start right after the red-gate guard (G1).
- The host chain is strictly sequential because every host group compiles and tests the same Maven module. The MCP chain is sequential for the same reason.
- Red tests turn green at fixed points: host test 1 after G2, host test 2 after G4, MCP test 3 after G11, MCP test 4 after G12.
- Docs (G16) wait for all code groups. G17 reviews the tests, runs the full host, MCP and frontend suites, and verifies the four red tests plus the RG-1 hashes. No git commit is planned.

## Key Decisions
- **No commit step (planning constraint overrides the spec's "implementation step 0").** G1 records the SHA-256 of both red-test files in `implementation/rg1-baseline.sha256` and G17 re-checks them with `sha256sum -c`. Committing the baseline and the work stays the user's decision.
- **Host groups form one dependency chain (G2 → … → G9).** They share one Maven module and one `target/` directory. A host group that is mid-edit breaks compilation for any other host group's `./mvnw test`, and parallel Testcontainers runs would race. `SecurityConfiguration`, `OAuth2TokenFilter` and `OAuth2TestFlows` are edited by several of these groups, and the chain serialises them.
- **The MCP chain (G10 → G13) runs alongside the host chain.** It is a separate Maven project (`mcp-server/`) with its own `target/` and no Docker. It shares no files with the host groups. Its tests use a fake host, so it never needs the real host.
- **Bridge edits keep the host module compiling between groups.** G2 makes the smallest change to `OAuth2TokenFilter` needed for the new `JwtTokenProvider` API (configured issuer, T1 `aud` = MCP resource). G4 makes the smallest change needed for the new `AuthorizationCodeData`. G5 then rewrites the filter in full. Each group leaves `./mvnw test` green for the suites it touches.
- **Test counts follow the audited spec test list, not the 2-8 default.** Groups G3 (23 unit tests) and G7/G11 (10-11 tests) go over the default. The spec names every test, the change is a high-risk security boundary, and the pure-logic unit tests are cheap. G17 is still capped at 10 extra tests.
- **The authorize-time test for "T2 as `_token`" mints a T2-shaped token directly through `JwtTokenProvider.generateOAuth2Token`** (`aud` = API, `client_id`, `act`). G4 can then test C-2 before the exchange grant exists in G7.
- **`TokenExchangeClientTests` uses its own small JDK `HttpServer`.** `FakeHost` is package-private in `security` (per the spec) and is not visible from the `client` package.
- **The two red-test files are read-only for every group (RG-1).** `OAuth2TestFlows` and `FakeHost` copy their patterns but never import them.

## Open Questions / Risks
- **`ApiLayerTests` (`@WebMvcTest`) and the `SecurityConfiguration` constructor.** G2, G4, G5, G7 and G8 add dependencies to `SecurityConfiguration`. If the web slice loads that class, missing beans break the slice. Each of these groups reruns `ApiLayerTests`. If it breaks, the fix is to keep the new collaborators non-beans built inside the configuration (as the spec already requires), not to change the test.
- **First MCP build needs network** for `spring-boot-starter-security-oauth2-resource-server` (G10).
- **Host test runs need Docker**, and `-Dskip.jooq.generation=true` works only if `target/generated-sources/jooq` exists. If it does not, run the first G2 test command without that flag.
- **The stall test in G11 runs for more than 10 s by design** (read timeout). Do not shorten the production timeout to speed it up.
- **The manual end-to-end gate (Success Criterion 8: a real MCP client against `http://localhost:8181`, recording the `resource` value sent) is not automated here.** It belongs to the workflow's verification phase.
- **Exchange-time permission revocation test (G7).** It changes the user's permissions in the DB between consent and exchange inside a `@Transactional` test. MockMvc runs on the test thread, so the change is visible and rolled back. If isolation surprises arise, use a dedicated test user instead of mutating `viewer`/`editor`.
- **Task-tracking items could not be created.** `TaskCreate`/`TaskUpdate` are not available in this planner session. The group dependencies below are the source of truth for the executor.

---

## Overview
Total Steps: 104
Task Groups: 17
Expected Tests: 94 new tests (host 64, MCP 25, frontend 5), 4 renamed (2 host, 2 MCP), about 16 existing MCP tests adapted to new signatures, and up to 10 gap tests in G17. Groups G1, G15 and G16 use scripted checks instead of unit tests.

Common commands (run from the repository root `C:\code\_repos\architekt-jutra\architekt-jutra-code`, Git Bash):
- Host: `./mvnw test -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm -Dtest=<ClassA>,<ClassB>` (Docker required)
- MCP: `cd mcp-server && ./mvnw test -Dtest=<ClassA>,<ClassB>`
- Frontend: `cd src/main/frontend && npx vitest run src/test/<file>`

Path prefixes used below: `HOST` = `src/main/java/pl/devstyle/aj`, `HOST_TEST` = `src/test/java/pl/devstyle/aj`, `MCP` = `mcp-server/src/main/java/pl/devstyle/aj/mcp`, `MCP_TEST` = `mcp-server/src/test/java/pl/devstyle/aj/mcp`, `FE` = `src/main/frontend`, `TASK` = `.maister/tasks/development/2026-11-14-mcp-auth-refactor`. The "Files to Modify" lines spell out full paths.

## Implementation Steps

### Task Group 1: Red-Gate Baseline Guard
**Dependencies:** None
**Files to Modify:** .maister/tasks/development/2026-11-14-mcp-auth-refactor/implementation/rg1-baseline.sha256
**Estimated Steps:** 3

- [x] 1.0 Establish the RG-1 immutability guard for both red-test files
  - [x] 1.1 Write the 2 baseline checks
    - Create `TASK/implementation/rg1-baseline.sha256` in `sha256sum -c` format with repo-relative paths:
      - `ca5b084e7a22c663d13851192e7302f45356e9d8e8ebf7ad7089636c32aba543  src/test/java/pl/devstyle/aj/core/oauth2/McpTokenBoundaryTests.java`
      - `5fb1ef79fc558649709e10bfda35f6e121bb18f0e156d9129948942359918980  mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpAuthBoundaryTests.java`
    - Before writing, recompute both hashes and confirm they equal these spec-time values (`ca5b084e…aba543`, `5fb1ef79…918980`). If they differ, stop and report: the red tests were changed after the TDD gate.
    - Do **not** `git add` or commit anything. Committing is the user's decision.
  - [x] 1.2 Confirm the red state
    - `cd mcp-server && ./mvnw -q test -Dtest=McpAuthBoundaryTests`: expect 2 failures (`expected: 401 but was: 200`; `not to contain ["Bearer client-token-t1"]`)
    - `./mvnw -q test -Dtest=McpTokenBoundaryTests -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm`: expect 2 failures (`expected:<401> but was:<200>`; `not to contain ["mcp:edit"]`)
    - Record the outcome in the work log (evidence for the TDD gate).
  - [x] 1.3 Ensure the guard passes
    - From the repo root: `sha256sum -c .maister/tasks/development/2026-11-14-mcp-auth-refactor/implementation/rg1-baseline.sha256` → both `OK`

**Acceptance Criteria:**
- `rg1-baseline.sha256` exists and `sha256sum -c` reports `OK` for both files
- The four red tests fail for the documented reasons on the untouched baseline
- No git state was changed

---

### Task Group 2: Host Configuration & Token Claims
**Dependencies:** 1
**Files to Modify:** src/main/java/pl/devstyle/aj/core/oauth2/OAuth2Properties.java, src/main/java/pl/devstyle/aj/core/security/OAuth2TokenClaims.java, src/main/java/pl/devstyle/aj/core/security/JwtTokenProvider.java, src/main/java/pl/devstyle/aj/core/security/JwtAuthenticationFilter.java, src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java, src/main/java/pl/devstyle/aj/core/oauth2/OAuth2TokenFilter.java, src/main/resources/application.properties, src/test/resources/config/application.properties, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2PropertiesTests.java, src/test/java/pl/devstyle/aj/core/security/AuthIntegrationTests.java
**Estimated Steps:** 8
**Requirements:** AS-1, SEC-1, SEC-2, SEC-3 (CORS property, `@EnableConfigurationProperties`), SEC-4, CFG-1, I-8 (host)

- [x] 2.0 Complete host configuration and the audience-bound token model
  - [x] 2.1 Write 7 focused tests
    - `HOST_TEST/core/oauth2/OAuth2PropertiesTests` (plain JUnit, AssertJ): `constructor_trailingSlash_isStripped`, `constructor_blankMcpResource_throws`, `toString_masksClientSecret`
    - `HOST_TEST/core/security/AuthIntegrationTests` (+4): `login_validCredentials_tokenCarriesIssuerAndApiAudience` (decode the payload: `iss` = `http://localhost:8180`, `aud` contains `http://localhost:8180/api`), `protectedEndpoint_tokenWithoutAudience_returns401`, `protectedEndpoint_tokenWithForeignIssuer_returns401`, `protectedEndpoint_tokenWithForeignAudience_returns401` (hand-build with jjwt and the test secret, like the existing `jwtSignedWithWrongKey` test)
    - The existing 14 `AuthIntegrationTests` stay unchanged and must still pass (the `generateTokenWithExpiration` signature is kept)
  - [x] 2.2 Create `OAuth2Properties` (AS-1)
    - `@ConfigurationProperties("app.oauth2")` record `(issuer, mcpResource, mcpClientId, mcpClientSecret)`
    - Compact constructor: `issuer`, `mcpResource` and `mcpClientId` must be non-blank (fail fast with a clear message). Strip one trailing `/` from `issuer` and `mcpResource`. `mcpClientSecret` may be blank.
    - `apiAudience()` = `issuer + "/api"`. `toString()` masks the secret (`***`, or `<blank>` when unset).
  - [x] 2.3 Configuration files (CFG-1, I-8)
    - `src/main/resources/application.properties`: add `server.port=8180`, `app.oauth2.issuer=${APP_OAUTH2_ISSUER:http://localhost:8180}`, `app.oauth2.mcp-resource=${AJ_MCP_BASE_URL:http://localhost:8181}`, `app.oauth2.mcp-client-id=${AJ_OAUTH_CLIENT_ID:aj-mcp-server}`, `app.oauth2.mcp-client-secret=${AJ_OAUTH_CLIENT_SECRET:}`. Change CORS to `app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:http://localhost:*}`. `app.jwt.*` stays unchanged.
    - New `src/test/resources/config/application.properties` with only `app.oauth2.mcp-client-secret=test-mcp-secret` (Boot loads `classpath:/config/` in addition to the main file, so this gives one cached context)
  - [x] 2.4 Extend `JwtTokenProvider` (SEC-1)
    - Constructor also takes `OAuth2Properties`
    - `generateToken` / `generateTokenWithExpiration`: signatures unchanged; add `iss` = issuer and `aud` = [`apiAudience`] (array via jjwt `.audience().add()`)
    - New top-level record `core/security/OAuth2TokenClaims(subject, scopes, audience, clientId, actorSubject, expiresAt)` and `generateOAuth2Token(OAuth2TokenClaims)`: header `typ: at+jwt`; claims `iss`, `sub`, `aud` [audience], `client_id`, `scopes` (list), `jti` (UUID), `iat`, `exp`, and `act: {sub}` only when `actorSubject` is non-null
    - Replace `ParsedToken` with `(subject, audience, permissions, scopes, clientId, actorSubject, type, jti, issuedAt, expiresAt)` plus `isSessionToken()` = `clientId == null && actorSubject == null`
    - `parseApiToken(token)`: signature, exp, `iss` = issuer, `aud` contains `apiAudience` (handle a string or an array `aud`)
    - `parseAccessToken(token)`: signature, exp, `iss` = issuer, header `typ` = `at+jwt`; no audience requirement
    - Delete `parseToken` (with its `permissions → scopes` fallback), `getUsernameFromToken`, `getPermissionsFromToken`, `validateToken` and the old `generateOAuth2Token(username, scopes, issuer)`
  - [x] 2.5 Update `JwtAuthenticationFilter` (SEC-2)
    - Use `parseApiToken` for both the Bearer header and the `_token` parameter
    - Authorities: session token → `PERMISSION_<permission>`; token with `client_id` → `PERMISSION_<scope>`
    - `authentication.setDetails(parsedToken)`. On rejection, leave the context empty; never write a 401 in the filter.
  - [x] 2.6 `SecurityConfiguration`
    - Add `@EnableConfigurationProperties(OAuth2Properties.class)`, inject `OAuth2Properties` and pass it to `OAuth2TokenFilter`. Matchers and filter order stay unchanged.
  - [x] 2.7 Bridge edit in `OAuth2TokenFilter` (compile-only; G5 rewrites it)
    - Delete `getBaseUrl(request)`. Mint T1 through `generateOAuth2Token(new OAuth2TokenClaims(username, scopes, properties.mcpResource(), clientId, null, now + 900 s))`. `clientId` = `data.clientId()` on the code grant. On the refresh grant it is `null` until G5 binds refresh tokens to clients.
    - Do not touch logging, client auth or the JSON output here
  - [x] 2.8 Ensure the group's tests pass
    - `./mvnw test -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm -Dtest=OAuth2PropertiesTests,AuthIntegrationTests,OAuth2IntegrationTests,ApiLayerTests,SecurityTestHelperTests`
    - Then `-Dtest=McpTokenBoundaryTests#mcpAccessToken_replayedAtApi_returns401`: expected to turn **green** (T1 now carries the MCP `aud`)

**Acceptance Criteria:**
- The 7 new tests pass, and the 14 existing `AuthIntegrationTests` and 8 `OAuth2IntegrationTests` still pass
- Red test `mcpAccessToken_replayedAtApi_returns401` passes
- No caller of the deleted `JwtTokenProvider` methods remains (`grep -rn "parseToken\|getUsernameFromToken\|validateToken" src/main` returns nothing)

---

### Task Group 3: AS Policies, Client Authentication & Error Model
**Dependencies:** 2
**Files to Modify:** src/main/java/pl/devstyle/aj/core/oauth2/OAuth2Error.java, src/main/java/pl/devstyle/aj/core/oauth2/OAuth2RequestException.java, src/main/java/pl/devstyle/aj/core/oauth2/OAuth2ErrorWriter.java, src/main/java/pl/devstyle/aj/core/oauth2/ScopePolicy.java, src/main/java/pl/devstyle/aj/core/oauth2/ResourcePolicy.java, src/main/java/pl/devstyle/aj/core/oauth2/ClientAuthenticator.java, src/test/java/pl/devstyle/aj/core/oauth2/ScopePolicyTests.java, src/test/java/pl/devstyle/aj/core/oauth2/ResourcePolicyTests.java, src/test/java/pl/devstyle/aj/core/oauth2/ClientAuthenticatorTests.java
**Estimated Steps:** 6
**Requirements:** AS-2, AS-3, AS-4, AS-14 (types and writer), AS-16 (parameter reader), C3, C8, C10, m1, m2, m4, m13

- [x] 3.0 Complete the plain-class policy layer of the authorization server (not wired yet)
  - [x] 3.1 Write 23 focused unit tests (plain JUnit + AssertJ, no Spring context)
    - `ScopePolicyTests` (8): `grant_editorRequestingEdit_returnsReadAndEdit`, `grant_viewerRequestingEdit_returnsReadOnly`, `grant_editOnlyUser_returnsEditOnly`, `grant_editOnlyClientForReadUser_reExpandsToReadAndEdit`, `grant_unknownScope_isDropped`, `grant_emptyIntersection_throwsInvalidScope`, `grantAtAuthorization_missingRequested_usesClientScopes`, `grantAtExchange_emptySubjectScopes_throwsInvalidScope`
    - `ResourcePolicyTests` (7): `resolveForAuthorization_missing_defaultsToMcpResource`, `resolveForAuthorization_trailingSlash_returnsCanonical`, `resolveForAuthorization_apiAudience_throwsInvalidTarget`, `resolveForAuthorization_unknown_throwsInvalidTarget`, `resolveForExchange_nonApi_throwsInvalidTarget`, `resourceParam_multipleValues_throwsInvalidTarget` (`MockHttpServletRequest` with two `resource` values), `boundResourceOf_mcpClient_returnsMcpResource`
    - `ClientAuthenticatorTests` (8; `InMemoryRegisteredClientRepository` + `BCryptPasswordEncoder` + `MockHttpServletRequest`): `authenticateConfidential_basic_returnsClient`, `authenticateConfidential_urlEncodedBasic_decodesCredentials`, `authenticateConfidential_post_returnsClient`, `authenticateConfidential_basicAndFormSecret_throwsInvalidRequest`, `authenticateConfidential_basicWithMatchingFormClientId_returnsClient`, `authenticateConfidential_basicWithDifferentFormClientId_throwsInvalidClient`, `authenticateConfidential_wrongSecret_throwsInvalidClient`, `authenticateConfidential_unknownClient_throwsInvalidClient`
  - [x] 3.2 Error model (AS-14)
    - `OAuth2Error`: add `INVALID_TARGET("invalid_target", …)`
    - New unchecked `OAuth2RequestException(OAuth2Error error, String description)`
    - New `OAuth2ErrorWriter(tools.jackson ObjectMapper)` with `write(response, error, description)` and one status map: `INVALID_CLIENT` → 401 + `WWW-Authenticate: Basic realm="aj"`, `ACCESS_DENIED` → 403, `SERVER_ERROR` → 500, everything else (including `UNAUTHORIZED_CLIENT` and `INVALID_TARGET`) → 400. The body is the existing `OAuth2ErrorResponse` record (unchanged).
  - [x] 3.3 `ScopePolicy` (AS-3): final class, static methods
    - `SUPPORTED_SCOPES` = ordered [`mcp:read`, `mcp:edit`]
    - Core, in order: expand (`mcp:edit` adds `mcp:read`) → ∩ permission scopes (`READ`→`mcp:read`, `EDIT`→`mcp:edit`) → ∩ client scopes → re-expand (add `mcp:read` only if the permission scopes contain it) → drop unknown scopes → empty throws `INVALID_SCOPE`
    - `grantAtAuthorization(requested, permissions, clientScopes)`: null or blank requested → client scopes. `grantAtExchange(subjectScopes, permissions, clientScopes)`: null or empty subject scopes → `INVALID_SCOPE`, no default. `toScopeString(set)` in `SUPPORTED_SCOPES` order.
  - [x] 3.4 `ResourcePolicy` (AS-2, AS-16): plain class over `OAuth2Properties`
    - `canonical(uri)`: strips one trailing `/` only for origin-only URIs (no path other than `/`, no query, no fragment)
    - `resolveForAuthorization(param, clientId)`: blank → `mcpResource` plus WARN `OAuth2 resource defaulted | client_id=…`. Slash-equal to `mcpResource` → canonical. Anything else (including `apiAudience`) → `INVALID_TARGET`.
    - `resolveForExchange(param)`: must equal `apiAudience`, else `INVALID_TARGET`. `boundResourceOf(clientId)`: `Optional.of(mcpResource)` for `mcpClientId`, else empty. `audienceContains(aud, resource)`: canonical contains-match. `resourceParam(request)`: `getParameterValues("resource")`, more than one value → `INVALID_TARGET`, blank → empty.
  - [x] 3.5 `ClientAuthenticator` (AS-4)
    - `authenticateConfidential(request)`:
      - Credentials come from Basic (Base64, split at the first `:`, URL-decode both parts as UTF-8) or from form `client_id` + `client_secret`.
      - Basic plus a form `client_secret` → `INVALID_REQUEST`.
      - A form `client_id` next to Basic must equal the Basic id, else `INVALID_CLIENT`.
      - None, malformed, unknown client, unregistered method or a BCrypt mismatch → `INVALID_CLIENT`.
    - `identify(request)`:
      - With credentials → `authenticateConfidential`.
      - Without credentials → a form `client_id` is required (`INVALID_REQUEST`). The client must exist and be registered with method `none`, else `INVALID_CLIENT`.
    - Never log the secret or the header.
  - [x] 3.6 Ensure the group's tests pass
    - `./mvnw test -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm -Dtest=ScopePolicyTests,ResourcePolicyTests,ClientAuthenticatorTests`

**Acceptance Criteria:**
- The 23 unit tests pass
- The new classes are plain (no `@Component`), and `ScopePolicy` and `ResourcePolicy` are the only places that encode scope and audience rules
- `OAuth2ErrorWriter` uses the Jackson 3 `tools.jackson` mapper

---

### Task Group 4: Authorize Endpoint & Consent-Time Grant
**Dependencies:** 3
**Files to Modify:** src/main/java/pl/devstyle/aj/core/oauth2/OAuth2AuthorizationFilter.java, src/main/java/pl/devstyle/aj/core/oauth2/AuthorizationCodeService.java, src/main/java/pl/devstyle/aj/core/oauth2/OAuth2TokenFilter.java, src/main/java/pl/devstyle/aj/core/oauth2/PublicClientRegistrationFilter.java, src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2TestFlows.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2GrantPolicyTests.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2IntegrationTests.java
**Estimated Steps:** 7
**Requirements:** AS-5, AS-6, AS-14 (authorize + registration use the shared writer), AS-15 (authorize logging), AS-16, C2, C-2, I-2, I-7

- [x] 4.0 Complete the authorize endpoint with resource, session-principal and least-privilege rules
  - [x] 4.1 Write the shared helper and 5 focused tests
    - New package-private `HOST_TEST/core/oauth2/OAuth2TestFlows`: MockMvc helpers **copied** (not extracted) from `OAuth2IntegrationTests`/`McpTokenBoundaryTests`: `login(username, password)`, `registerPublicClient()`, `registerConfidentialClient()`, PKCE verifier/challenge, `authorize(t0, clientId, scope, resource…)` returning the code. Later groups extend it. It must not be referenced by either red test (RG-1).
    - `OAuth2GrantPolicyTests` (`@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` + MockMvc + `SecurityMockMvcConfiguration`): `authorize_withoutResource_issuesTokenForMcpAudience`, `authorize_trailingSlashResource_issuesCanonicalAudience` (`resource=http://localhost:8181/` → T1 `aud` = `http://localhost:8181`), `authorize_apiResource_returnsInvalidTarget`, `authorize_multipleResourceParams_returnsInvalidTarget`, `authorize_delegatedTokenAsSession_returns403AccessDenied` (T2-shaped `_token` minted with `JwtTokenProvider.generateOAuth2Token`: `aud` = API, `client_id` = `aj-mcp-server`, `actorSubject` set)
  - [x] 4.2 `AuthorizationCodeService` (AS-6)
    - `AuthorizationCodeData(clientId, redirectUri, scope /*granted*/, codeChallenge, codeChallengeMethod, username, resource, createdAt)`. Remove `permissions`. `storeAuthorizationCode` changes to match. TTL and cleanup stay unchanged.
  - [x] 4.3 Rewrite `OAuth2AuthorizationFilter` (AS-5)
    - Resource: `ResourcePolicy.resourceParam` → `resolveForAuthorization`.
    - Principal: only an authentication whose details are a `ParsedToken` with `isSessionToken()`; otherwise `ACCESS_DENIED` "User must be authenticated" (403).
    - Granted scope: existing client-scope validation, then `ScopePolicy.grantAtAuthorization(requested, sessionPermissions, client.getScopes())`. Store the `resource` and the `toScopeString(grant)` in the code.
    - Errors: all are thrown as `OAuth2RequestException` and written by `OAuth2ErrorWriter`; no redirect errors. Delete `determineError` and the local `sendError`.
    - Remove the parameter-map INFO log. Logs may carry only `client_id`, username, error code and resource.
  - [x] 4.4 Bridge edit in `OAuth2TokenFilter` (G5 rewrites it)
    - Code grant: T1 `aud` = `data.resource()`, scopes = split of stored `data.scope()`, response `scope` = stored grant string. Adapt to the new `AuthorizationCodeData` accessors.
  - [x] 4.5 `PublicClientRegistrationFilter` (I-7)
    - Replace the local error switch with `OAuth2ErrorWriter`. `ALLOWED_SCOPES` references `ScopePolicy.SUPPORTED_SCOPES`. Keep its Jackson 2 request parsing (accepted deviation) and its grant whitelist.
  - [x] 4.6 `SecurityConfiguration` wiring
    - Build `OAuth2ErrorWriter` (Jackson 3 mapper) and `ResourcePolicy` as plain objects. Pass them to the authorization and registration filters. Filter order stays unchanged.
    - If an existing `OAuth2IntegrationTests` assertion depended on the old authorize error codes, adapt only that assertion to the spec's endpoint table
  - [x] 4.7 Ensure the group's tests pass
    - `-Dtest=OAuth2GrantPolicyTests,OAuth2IntegrationTests,McpTokenBoundaryTests,AuthIntegrationTests,ApiLayerTests`
    - Both host red tests are now expected **green**, including `authorize_viewerRequestingMcpEdit_isNotGrantedEditScope`

**Acceptance Criteria:**
- The 5 new tests pass, and `OAuth2IntegrationTests` stays green (authorize without `scope` defaults to the client scopes, I-2)
- Both `McpTokenBoundaryTests` tests pass, and that file is still byte-identical (`sha256sum -c TASK/implementation/rg1-baseline.sha256`)
- No log statement in `OAuth2AuthorizationFilter` prints `_token` or a parameter map

---

### Task Group 5: Token Endpoint — Code & Refresh Grants
**Dependencies:** 4
**Files to Modify:** src/main/java/pl/devstyle/aj/core/oauth2/OAuth2TokenFilter.java, src/main/java/pl/devstyle/aj/core/oauth2/TokenResponse.java, src/main/java/pl/devstyle/aj/core/oauth2/RefreshTokenService.java, src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2TestFlows.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2GrantPolicyTests.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2IntegrationTests.java
**Estimated Steps:** 8
**Requirements:** AS-7, AS-8, AS-9, AS-14 (token filter), AS-15 (token logging), AS-16, C3, C5, C9, I-3, FU-2a, m3, m5, m11

- [x] 5.0 Complete the token endpoint for the code and refresh grants
  - [x] 5.1 Write 5 focused tests and adapt the existing suite
    - `OAuth2GrantPolicyTests` (+5): `authorizationCode_differentClientId_returnsInvalidGrant`, `authorizationCode_apiResource_returnsInvalidTarget`, `refresh_apiResource_returnsInvalidTarget`, `refresh_byDifferentClient_returnsInvalidGrantAndKeepsToken` (the owner's refresh still succeeds afterwards), `refresh_publicClientWithoutClientId_returnsInvalidRequest`
    - `OAuth2IntegrationTests`: rename `tokenExchange_withRefreshToken_returnsNewAccessToken` → `refreshToken_sameClient_returnsNewAccessToken` (now sends `client_id`). Rename `tokenExchange_withInvalidCode_returns400` → `authorizationCode_invalidCode_returns400`. Replace plain `assert` with AssertJ.
    - `OAuth2TestFlows`: add `exchangeCode(...)` and `refresh(...)` helpers
  - [x] 5.2 `TokenResponse` record
    - `@JsonInclude(NON_NULL)` with snake_case `@JsonProperty`: `access_token`, `token_type` = `Bearer`, `expires_in` (computed from `exp`), `refresh_token`, `scope`, `issued_token_type`
  - [x] 5.3 `RefreshTokenService` (AS-9)
    - `RefreshTokenData(username, scope, clientId, resource, createdAt)`. `issueToken(username, scope, clientId, resource)`.
    - `consumeAndRotate(token, clientId, Optional<String> resource)`:
      - Look up without removing. Unknown or expired → empty.
      - A client or resource mismatch → empty, and the token is **not** removed.
      - Only then the conditional `remove(token, data)`. A lost race → empty.
  - [x] 5.4 Restructure `OAuth2TokenFilter` (AS-7, AS-14, AS-15)
    - Dispatch on `grant_type`: `authorization_code`, `refresh_token`, else `UNSUPPORTED_GRANT_TYPE` (G7 adds the exchange branch). Each branch returns a `TokenResponse` or throws `OAuth2RequestException`.
    - Write the response with the injected Jackson 3 mapper and `Cache-Control: no-store`, `Pragma: no-cache`. Errors go through `OAuth2ErrorWriter`. A catch-all → `SERVER_ERROR` if the response is not committed.
    - Delete the StringBuilder JSON, the hard-coded `900`, `new com.fasterxml…ObjectMapper()`, the local `sendError`, and every log of the parameter map, tokens or the response.
  - [x] 5.5 Authorization-code grant (AS-8), in this exact order:
    1. `code` and `redirect_uri` required (`INVALID_REQUEST`).
    2. Consume the code (`INVALID_GRANT`).
    3. `ClientAuthenticator.identify`; the id must equal `data.clientId()` (`INVALID_GRANT`).
    4. `redirect_uri` must equal the stored value (`INVALID_GRANT`).
    5. PKCE check (unchanged).
    6. Optional `resource`: unknown or the API audience → `INVALID_TARGET`; resolves but differs from the stored resource → `INVALID_GRANT`.
    7. Mint T1 (`aud` = stored resource, `client_id`, stored scopes, 900 s).
    8. Issue an RT bound to `(username, scope, clientId, resource)`.
    9. Response `scope` = stored grant.
  - [x] 5.6 Refresh grant (AS-9)
    - `refresh_token` required → `identify` → optional single `resource` resolved (unknown or API → `INVALID_TARGET`) → `consumeAndRotate(token, clientId, resource)` → empty maps to `INVALID_GRANT` → re-issue T1 with the stored scope and resource
  - [x] 5.7 `SecurityConfiguration` wiring
    - Build `ClientAuthenticator(registeredClientRepository, passwordEncoder)`. Pass it, `OAuth2ErrorWriter`, `ResourcePolicy`, `OAuth2Properties` and the Jackson 3 `ObjectMapper` to `OAuth2TokenFilter`.
  - [x] 5.8 Ensure the group's tests pass
    - `-Dtest=OAuth2GrantPolicyTests,OAuth2IntegrationTests,McpTokenBoundaryTests,ApiLayerTests`

**Acceptance Criteria:**
- The 10 `OAuth2GrantPolicyTests` and 8 `OAuth2IntegrationTests` (renamed) pass, and both host red tests stay green
- `OAuth2TokenFilter` has no `getBaseUrl`, no StringBuilder JSON, no Jackson 2 import and no token/param logging
- A refresh by a different client returns `invalid_grant`, and the RT stays usable for its owner

---

### Task Group 6: MCP Client Provisioning
**Dependencies:** 5
**Files to Modify:** src/main/java/pl/devstyle/aj/core/oauth2/McpClientProvisioner.java, src/main/java/pl/devstyle/aj/core/oauth2/DatabaseRegisteredClientRepository.java, src/main/java/pl/devstyle/aj/core/oauth2/RegisteredClientJpaRepository.java, src/test/java/pl/devstyle/aj/core/oauth2/McpClientProvisionerTests.java
**Estimated Steps:** 4
**Requirements:** AS-12, C11, I-4, m9

- [x] 6.0 Complete the env-provisioned confidential `aj-mcp-server` client
  - [x] 6.1 Write 4 focused tests
    - `McpClientProvisionerTests` (`@SpringBootTest` + Testcontainers, **class-level `@Transactional`**). Instantiate the provisioner manually with explicit `OAuth2Properties` for client id `test-provisioned-client`, so the shared `aj-mcp-server` row is never touched.
    - Tests: `run_withSecret_createsConfidentialExchangeClient` (grant `TOKEN_EXCHANGE` only; methods Basic + post; scopes `mcp:read mcp:edit`; no redirect URIs; name `AJ MCP Server`), `run_twiceWithSameSecret_keepsRowIdAndHash`, `run_withRotatedSecret_updatesHash`, `run_withBlankSecret_deletesStaleClient`
  - [x] 6.2 Repository support
    - `DatabaseRegisteredClientRepository.deleteByClientId(String)` (and a derived `deleteByClientId` on `RegisteredClientJpaRepository` if needed). No Liquibase change.
  - [x] 6.3 `McpClientProvisioner` (`@Component`, `ApplicationRunner`, `@Transactional run`)
    - Secret set:
      - `findByClientId`. If the row exists → `RegisteredClient.from(existing)`, keeping its id; re-encode only if `!passwordEncoder.matches`. If no row exists → `withId(UUID)`.
      - Set the name, grant, methods and scopes. Save. Log `client_id` only.
    - Secret blank: `deleteByClientId` plus a WARN (or WARN "MCP client not provisioned" when no row exists)
  - [x] 6.4 Ensure the group's tests pass
    - `-Dtest=McpClientProvisionerTests,AjApplicationTests,OAuth2IntegrationTests` (the context starts with the test secret and provisions `aj-mcp-server`)

**Acceptance Criteria:**
- The 4 tests pass and leave no rows behind (rollback)
- The provisioner never logs the secret or the hash

---

### Task Group 7: Token-Exchange Grant
**Dependencies:** 6
**Files to Modify:** src/main/java/pl/devstyle/aj/core/oauth2/TokenExchangeGrantHandler.java, src/main/java/pl/devstyle/aj/core/oauth2/OAuth2TokenFilter.java, src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2TestFlows.java, src/test/java/pl/devstyle/aj/core/oauth2/TokenExchangeIntegrationTests.java, src/test/java/pl/devstyle/aj/core/oauth2/TokenExchangeValidationTests.java
**Estimated Steps:** 6
**Requirements:** AS-10, AS-16, C10, ADR-004 (current permissions at exchange), m1

- [x] 7.0 Complete the RFC 8693 delegation exchange
  - [x] 7.1 Write 11 focused tests
    - `OAuth2TestFlows`: add `obtainMcpToken(user)` (full flow up to T1), `exchange(t1, basicHeader, resource)`, and a URL-encoding `basic(id, secret)` helper. The MCP client credentials are `aj-mcp-server` / `test-mcp-secret`.
    - `TokenExchangeIntegrationTests` (5): `exchange_mcpClientWithValidSubject_returnsDelegatedApiToken` (asserts `aud`, `client_id`, `act.sub`, `jti`, `typ`, `exp` ≤ min(300 s, T1 `exp`); `issued_token_type`, `expires_in`, no `refresh_token`), `exchange_delegatedToken_getProducts_returns200`, `exchange_viewerSubject_isNotGrantedEditScope`, `exchange_viewerDelegatedToken_postProduct_returns403`, `exchange_permissionRevokedAfterConsent_returnsInvalidScope`
    - `TokenExchangeValidationTests` (6): `exchange_withoutClientAuthentication_returns401WithBasicChallenge`, `exchange_confidentialDcrClient_returnsUnauthorizedClient`, `exchange_sessionTokenAsSubject_returnsInvalidRequest`, `exchange_delegatedTokenAsSubject_returnsInvalidRequest`, `exchange_resourceNotApiAudience_returnsInvalidTarget`, `exchange_missingResource_returnsInvalidTarget`
  - [x] 7.2 `TokenExchangeGrantHandler`, request validation (AS-10 steps 1-4)
    - `authenticateConfidential` (401). The client must hold `AuthorizationGrantType.TOKEN_EXCHANGE` and have a `boundResourceOf`, else `UNAUTHORIZED_CLIENT` (400).
    - `subject_token` is required. `subject_token_type` = access-token URN. `requested_token_type` must be absent or the same URN. `actor_token` must be absent. Violations → `INVALID_REQUEST`.
    - `resource` via `resourceParam` → `resolveForExchange`. Ignore `scope` and `audience`.
  - [x] 7.3 Subject validation and scope recomputation (AS-10 steps 5-7)
    - `parseAccessToken(subject)` must succeed and have no `act`, and its `aud` must contain the caller's bound resource, else `INVALID_REQUEST`.
    - `CustomUserDetailsService.loadUserByUsername(sub)` (`UsernameNotFoundException` → `INVALID_REQUEST`) gives the current permissions → `ScopePolicy.grantAtExchange(subjectScopes, currentPermissions, client.getScopes())`.
  - [x] 7.4 Mint T2 and build the response (AS-10 steps 8-9)
    - `OAuth2TokenClaims(sub, scopes, apiAudience, callerClientId, callerClientId /*act.sub*/, min(now + 300 s, subject exp))` (300 s is a constant)
    - `TokenResponse` with `issued_token_type`, `token_type` `Bearer`, `expires_in`, `scope`, and no `refresh_token`
  - [x] 7.5 Wiring
    - `OAuth2TokenFilter` dispatches `urn:ietf:params:oauth:grant-type:token-exchange` to the handler. `SecurityConfiguration` builds the handler with `ClientAuthenticator`, `ResourcePolicy`, `JwtTokenProvider`, `CustomUserDetailsService` and `OAuth2Properties`. Log grant type, `client_id` and error code only.
  - [x] 7.6 Ensure the group's tests pass
    - `-Dtest=TokenExchangeIntegrationTests,TokenExchangeValidationTests,OAuth2GrantPolicyTests,ApiLayerTests`

**Acceptance Criteria:**
- The 11 tests pass. T2 works at `GET /api/products` and is refused (403) at `POST /api/products` for a viewer.
- Only `aj-mcp-server` can exchange. A confidential DCR client gets `unauthorized_client`.
- T2 lifetime is ≤ 300 s and ≤ T1 `exp`

---

### Task Group 8: Introspection Endpoint & AS Metadata
**Dependencies:** 7
**Files to Modify:** src/main/java/pl/devstyle/aj/core/oauth2/OAuth2IntrospectionFilter.java, src/main/java/pl/devstyle/aj/core/oauth2/IntrospectionResponse.java, src/main/java/pl/devstyle/aj/core/oauth2/AuthorizationServerMetadata.java, src/main/java/pl/devstyle/aj/core/oauth2/OAuth2MetadataController.java, src/main/java/pl/devstyle/aj/core/security/SecurityConfiguration.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2TestFlows.java, src/test/java/pl/devstyle/aj/core/oauth2/IntrospectionIntegrationTests.java, src/test/java/pl/devstyle/aj/core/oauth2/OAuth2IntegrationTests.java
**Estimated Steps:** 6
**Requirements:** AS-11, AS-13, AS-1 (metadata issuer), C4, C7, C13, m12

- [x] 8.0 Complete RFC 7662 introspection and the configured-issuer metadata
  - [x] 8.1 Write 8 focused tests
    - `OAuth2TestFlows`: add `introspect(token, basicHeader)`
    - `IntrospectionIntegrationTests` (7): `introspect_activeMcpToken_returnsMetadataWithSpaceDelimitedScope` (also asserts `Cache-Control: no-store` and `Pragma: no-cache`), `introspect_withoutClientAuthentication_returns401`, `introspect_sessionToken_returnsInactive`, `introspect_delegatedApiToken_returnsInactive`, `introspect_expiredToken_returnsInactive` (T1 minted with a past `exp` through `JwtTokenProvider.generateOAuth2Token`), `introspect_garbageToken_returnsInactive`, `introspect_unboundConfidentialClient_returnsInactive`
    - `OAuth2IntegrationTests`: replace `getMetadata_returnsOAuth2ServerMetadata` with `getMetadata_returnsConfiguredIssuerIntrospectionAndExchangeGrant` (issuer `http://localhost:8180`, `introspection_endpoint`, auth methods, exchange grant)
  - [x] 8.2 `IntrospectionResponse` record
    - `@JsonInclude(NON_NULL)`, snake_case: `active`, `sub`, `scope` (string), `aud` (array), `client_id`, `iss`, `iat`, `exp`, `jti`, `token_type`
  - [x] 8.3 `OAuth2IntrospectionFilter` (`POST /oauth2/introspect`, non-bean `OncePerRequestFilter` modelled on the token filter)
    - `authenticateConfidential` (401 `invalid_client` + `WWW-Authenticate: Basic`). `token` is required (`INVALID_REQUEST`).
    - No bound resource, a failed `parseAccessToken`, or an `aud` without the bound resource → `200 {"active":false}`.
    - Otherwise `active:true` with `scope` = `ScopePolicy.toScopeString(scopes)` and `token_type` `Bearer`. Ignore `token_type_hint`.
    - `Cache-Control: no-store` and `Pragma: no-cache` on **every** response, errors included. Never log the token.
  - [x] 8.4 `SecurityConfiguration`
    - `addFilterAfter(introspectionFilter, OAuth2TokenFilter.class)`. No new matcher: the existing non-`/api/` `permitAll` covers it (C4).
  - [x] 8.5 Metadata (AS-13)
    - New record `AuthorizationServerMetadata` (snake_case) built from `OAuth2Properties.issuer()`. It has the 11 fields of AS-13: introspection endpoint + auth methods, three grant types, `token_endpoint_auth_methods_supported` [`client_secret_post`, `client_secret_basic`, `none`], and `scopes_supported` = `ScopePolicy.SUPPORTED_SCOPES`.
    - Delete `getBaseUrl` from `OAuth2MetadataController`. `GET /api/oauth2/client-info` stays unchanged.
  - [x] 8.6 Ensure the group's tests pass
    - `-Dtest=IntrospectionIntegrationTests,OAuth2IntegrationTests,TokenExchangeIntegrationTests,ApiLayerTests`

**Acceptance Criteria:**
- The 8 tests pass. Introspection is `active:true` only for T1 bound to `aj-mcp-server`'s resource, and always carries no-store headers.
- `grep -rn "getBaseUrl" src/main/java` returns nothing

---

### Task Group 9: Host Token-Logging Hygiene
**Dependencies:** 8
**Files to Modify:** src/test/java/pl/devstyle/aj/core/oauth2/OAuth2LoggingTests.java, src/main/java/pl/devstyle/aj/core/oauth2/*.java, src/main/java/pl/devstyle/aj/core/security/*.java
**Estimated Steps:** 4
**Requirements:** AS-15, FU-3, Success Criterion 6 (host)

- [x] 9.0 Prove that no secret reaches the host logs
  - [x] 9.1 Write 1 focused test
    - `OAuth2LoggingTests` (`@ExtendWith(OutputCaptureExtension.class)`, Spring context, `OAuth2TestFlows`): `fullFlow_neverLogsTokensCodesOrSecrets`. It runs login → authorize (`_token`) → code grant with PKCE → refresh → exchange → introspect. It asserts that the captured output contains none of: T0, T1, T2, RT, the authorization code, the code verifier, `test-mcp-secret`, the DCR client secret, or the Basic header value.
  - [x] 9.2 Audit every `log.` call in `core/oauth2` and `core/security`
    - Allowed: `client_id`, username, grant type, error code, resource. Remove or reduce anything else, such as exception messages that could echo input.
  - [x] 9.3 Confirm that the shipped host logging levels (no DEBUG overrides for `pl.devstyle`) are the ones under test. Do not lower levels in test config to make the test pass.
  - [x] 9.4 Ensure the host OAuth suites pass
    - `-Dtest=OAuth2LoggingTests,OAuth2IntegrationTests,OAuth2GrantPolicyTests,TokenExchangeIntegrationTests,TokenExchangeValidationTests,IntrospectionIntegrationTests,McpClientProvisionerTests,AuthIntegrationTests,McpTokenBoundaryTests`

**Acceptance Criteria:**
- `OAuth2LoggingTests` passes, and every host OAuth and security test class listed above is green

---

### Task Group 10: MCP Configuration & Dependencies
**Dependencies:** 1
**Files to Modify:** mcp-server/pom.xml, mcp-server/src/main/java/pl/devstyle/aj/mcp/config/AjProperties.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/config/SecurityConfig.java, mcp-server/src/main/resources/application.yml, mcp-server/src/test/resources/config/application.yml, mcp-server/src/test/java/pl/devstyle/aj/mcp/config/AjPropertiesTests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/ConfigurationTests.java
**Estimated Steps:** 7
**Requirements:** MCP-1, MCP-2, MCP-14, MCP-15 (levels), CFG-3 (`ConfigurationTests`), I-8 (MCP), m7, m8

- [x] 10.0 Complete the MCP server's configuration contract
  - [x] 10.1 Write 7 focused tests
    - `AjPropertiesTests` (`ApplicationContextRunner` over a tiny config that enables `AjProperties`): `context_withoutClientSecret_failsToStart` (the message mentions `AJ_OAUTH_CLIENT_SECRET`), `context_withBlankBaseUrl_failsToStart`, `derivedUris_followServerUrl`, `derivedUris_trailingSlashServerUrl_haveNoDoubleSlash`, `toString_masksClientSecret`
    - `ConfigurationTests`: rename and reassert to `serverPort_isConfiguredTo8181` and `managementPort_isConfiguredTo9181`
  - [x] 10.2 `pom.xml`
    - Add `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` (version from the Boot 4.0.5 BOM). No new test dependency. The first build needs network.
  - [x] 10.3 `AjProperties` record (`@ConfigurationProperties("aj")`) with nested `Backend(url)`, `Oauth(serverUrl, clientId, clientSecret)`, `Mcp(baseUrl)`
    - Fail fast on a blank `client-secret` with "aj.oauth.client-secret is not set — export AJ_OAUTH_CLIENT_SECRET (start-dev does this)", and on a blank `server-url`, `client-id` or `base-url`
    - Strip one trailing `/` from `server-url` and `base-url`. `Oauth.toString()` masks the secret.
    - Derived values: `introspectionUri()`, `tokenUri()`, `backendResource()` (server-url + `/api`), `resourceMetadataUrl()`
  - [x] 10.4 `application.yml` (MCP-14, MCP-15)
    - `server.port: 8181`, `management.server.port: 9181`
    - `aj.backend.url: ${AJ_BACKEND_URL:http://localhost:8180}`, `aj.oauth.server-url: ${AJ_OAUTH_SERVER_URL:http://localhost:8180}`, `aj.oauth.client-id: ${AJ_OAUTH_CLIENT_ID:aj-mcp-server}`, `aj.oauth.client-secret: ${AJ_OAUTH_CLIENT_SECRET:}`, `aj.mcp.base-url: ${AJ_MCP_BASE_URL:http://localhost:8181}`
    - Delete all three DEBUG entries (`pl.devstyle`, `org.springframework.security`, `io.modelcontextprotocol`)
  - [x] 10.5 New test config `mcp-server/src/test/resources/config/application.yml` with only `aj.oauth.client-secret: test-mcp-secret` (no logging levels; a root-level test `application.yml` would shadow the main file)
  - [x] 10.6 `SecurityConfig`
    - Add `@EnableConfigurationProperties(AjProperties.class)` only. The chain is rewritten in G11.
  - [x] 10.7 Ensure the group's tests pass
    - `cd mcp-server && ./mvnw test -Dtest=AjPropertiesTests,ConfigurationTests,AjMcpApplicationTests,JacksonConfigTests`

**Acceptance Criteria:**
- The 7 tests pass. The MCP context starts with the test secret and refuses to start without it.
- `application.yml` has no `kuba-*` defaults and no DEBUG levels

---

### Task Group 11: MCP Inbound Token Validation
**Dependencies:** 10
**Files to Modify:** mcp-server/src/main/java/pl/devstyle/aj/mcp/security/AudienceValidatingIntrospector.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/config/SecurityConfig.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/security/McpAuthenticationEntryPoint.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/security/McpAccessDeniedHandler.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/security/McpJwtFilter.java (delete), mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpJwtFilterTests.java (delete), mcp-server/src/test/java/pl/devstyle/aj/mcp/security/FakeHost.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/security/AudienceValidatingIntrospectorTests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpSecurityChainTests.java
**Estimated Steps:** 7
**Requirements:** MCP-3, MCP-4, MCP-5, MCP-13 (`McpJwtFilter`), C8, C-1, I-1, I-6, M1

- [x] 11.0 Complete the resource-server chain with introspection, audience check and challenges
  - [x] 11.1 Create the test helper
    - New package-private `FakeHost` (JDK `com.sun.net.httpserver.HttpServer` on port 0). It is modelled on the red test's fake but does not reuse it.
    - Programmable `/oauth2/introspect` response (active, `aud`, `scope`), optional response delay (for the stall case), programmable status (for the outage case), and recordings of request headers and bodies. G13 extends it with exchange and backend endpoints.
  - [x] 11.2 Write 10 focused tests
    - `AudienceValidatingIntrospectorTests` (unit, stub delegate): `introspect_matchingAudience_returnsPrincipal`, `introspect_slashVariantAudience_returnsPrincipal`, `introspect_foreignAudience_throwsBadOpaqueToken`
    - `McpSecurityChainTests` (`RANDOM_PORT`, `@DynamicPropertySource` → `aj.oauth.server-url` = FakeHost): `request_withoutToken_returns401WithResourceMetadataAndNoError`, `request_withForeignAudienceToken_returns401InvalidToken`, `request_withTrailingSlashAudience_isAccepted`, `request_withTokenWithoutMcpScope_returns403InsufficientScope`, `request_whenIntrospectionUnavailable_returns5xxWithoutInvalidToken`, `request_whenIntrospectionStalls_returns5xxWithoutInvalidToken` (delay > 10 s; assert 5xx and that the call returns), `protectedResourceMetadata_resourceEqualsBaseUrl`
  - [x] 11.3 `AudienceValidatingIntrospector` (MCP-3)
    - Wraps a delegate `OpaqueTokenIntrospector`. After delegating, require the principal's `aud` attribute to slash-equal-contain `aj.mcp.base-url`, else `BadOpaqueTokenException`. No `iss` check.
  - [x] 11.4 Rewrite `SecurityConfig` (MCP-4), constructor-injected `AjProperties`
    - `OpaqueTokenIntrospector` bean = `new AudienceValidatingIntrospector(new SpringOpaqueTokenIntrospector(introspectionUri(), restTemplate), baseUrl)`. `restTemplate` is local (not a bean): `SimpleClientHttpRequestFactory` with connect 5 s / read 10 s (constants), plus `BasicAuthenticationInterceptor(urlEncode(clientId), urlEncode(secret))`.
    - Chain: stateless, CSRF off. `permitAll` `/.well-known/**`, `/actuator/health/**`, `/error`. `/` → `hasAnyAuthority("SCOPE_mcp:read","SCOPE_mcp:edit")`. Anything else authenticated. `oauth2ResourceServer(opaqueToken(introspector))` with the entry point and the denied handler.
  - [x] 11.5 Challenges (MCP-5)
    - `McpAuthenticationEntryPoint`: 401 `Bearer resource_metadata="<url>", scope="mcp:read mcp:edit"`, plus `, error="invalid_token"` only for `OAuth2AuthenticationException`
    - New `McpAccessDeniedHandler`: 403 `Bearer error="insufficient_scope", scope="mcp:read", resource_metadata="<url>"`
    - Rely on Security 7.0.4 rethrowing `AuthenticationServiceException` for outages (5xx, no `invalid_token`); no custom 503 handler
  - [x] 11.6 Delete `security/McpJwtFilter.java` and `security/McpJwtFilterTests.java`
  - [x] 11.7 Ensure the group's tests pass
    - `cd mcp-server && ./mvnw test -Dtest=AudienceValidatingIntrospectorTests,McpSecurityChainTests,ConfigurationTests,AjMcpApplicationTests`
    - Then `-Dtest=McpAuthBoundaryTests#toolCall_withInactiveToken_returns401`: expected **green**

**Acceptance Criteria:**
- The 10 tests pass, and red test 3 passes
- An introspection outage or stall never yields `invalid_token`, and the stalled request finishes after about 10 s

---

### Task Group 12: MCP Delegated Outbound Calls
**Dependencies:** 11
**Files to Modify:** mcp-server/src/main/java/pl/devstyle/aj/mcp/AjMcpApplication.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/security/ToolSecurity.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/client/TokenExchangeClient.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/client/TokenExchangeResponse.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/client/AjApiClient.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/config/RestClientConfig.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/service/ProductService.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/service/CategoryService.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/exception/McpToolException.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/security/AccessTokenHolder.java (delete), mcp-server/src/test/java/pl/devstyle/aj/mcp/security/AccessTokenHolderTests.java (delete), mcp-server/src/test/java/pl/devstyle/aj/mcp/config/RestClientConfigTests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/client/TokenExchangeClientTests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/service/ToolCallIntegrationTests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/service/ErrorMappingTests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/service/ToolSchemaValidationTests.java
**Estimated Steps:** 8
**Requirements:** MCP-6, MCP-7, MCP-8, MCP-9, MCP-10, MCP-11, MCP-12, MCP-13, C8, C14, m6

- [x] 12.0 Complete per-call token exchange and remove passthrough
  - [x] 12.1 Write 4 focused tests and adapt 3 suites
    - `TokenExchangeClientTests` (unit; its own minimal JDK `HttpServer`, since `FakeHost` is package-private in `security`): `exchange_sendsUrlEncodedBasicAndRfc8693Form` (a client id/secret containing reserved characters → `Basic base64(urlenc:urlenc)`; form fields `grant_type`, `subject_token`, `subject_token_type`, `requested_token_type`, `resource` = `backendResource()`), `exchange_invalidScope_mapsToInsufficientPermissions`
    - `RestClientConfigTests` (rewrite): `ajApiClient_explicitAuthorization_isSentVerbatim`, `ajApiClient_backend401_mapsToApiErrorWithoutRetry`
    - Adapt `ToolCallIntegrationTests`, `ErrorMappingTests` and `ToolSchemaValidationTests`:
      - `@MockitoBean TokenExchangeClient` (returns a fixed T2).
      - Context `Map.of(ToolSecurity.AUTHENTICATION_KEY, BearerTokenAuthentication)` with `SCOPE_mcp:read`/`SCOPE_mcp:edit`.
      - `AjApiClient` stubs take the authorization argument.
      - Test names and assertions on results, schemas and error types stay unchanged.
  - [x] 12.2 `McpToolException` (MCP-12)
    - Add `ErrorType.INSUFFICIENT_SCOPE` and `insufficientScope(String required)` with the message "Insufficient scope: requires <scope>"
  - [x] 12.3 `ToolSecurity` (MCP-7): final class, static
    - `AUTHENTICATION_KEY`. `principal(McpTransportContext)` → `BearerTokenAuthentication`, else `apiError("Not authenticated")`. `requireScope(BearerTokenAuthentication, scope)` → the subject token value, else `insufficientScope(scope)`.
  - [x] 12.4 `TokenExchangeClient` (MCP-8) + `TokenExchangeResponse` record
    - `@Component` with its own `RestClient.builder()` (connect 5 s / read 10 s; no backend status handler)
    - POST a form to `tokenUri()` with a URL-encoded Basic header. Require `issued_token_type` = access-token URN and `token_type` equalsIgnoreCase `Bearer`.
    - Error mapping: 400 `invalid_scope` → `apiError("Insufficient permissions")`; anything else → `apiError("Backend authorization failed")`.
    - ERROR log with status and AS `error` code only. No cache, no retry.
  - [x] 12.5 `AjApiClient` + `RestClientConfig` (MCP-9, MCP-10)
    - Each method gets a leading `@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization`
    - Delete `JwtForwardingInterceptor` and the `AccessTokenHolder` parameter. Keep the status mapping (400/401/403/404/else). Never log request headers or tokens.
  - [x] 12.6 Services (MCP-11)
    - `ProductService.listProducts(BearerTokenAuthentication, search)`, `addProduct(BearerTokenAuthentication, args)`, `CategoryService.listCategories(BearerTokenAuthentication)`
    - Each runs `requireScope` (`mcp:read` for list tools, `mcp:edit` for `aj_add_product`) → `exchange(t1)` → `ajApiClient.x("Bearer " + t2, …)`. Handlers pass `ToolSecurity.principal(ctx)`.
    - Tool names, titles, annotations, schemas and `CallToolResult` shape stay byte-identical. Error wrapping must not include token text.
  - [x] 12.7 `AjMcpApplication` (MCP-6, MCP-13)
    - In `contextExtractor`, read `SecurityContextHolder.getContext().getAuthentication()` on the servlet thread. A `BearerTokenAuthentication` → `McpTransportContext.create(Map.of(ToolSecurity.AUTHENTICATION_KEY, auth))`, else `EMPTY`.
    - Delete `TOKEN_KEY`. Delete `security/AccessTokenHolder.java` and `AccessTokenHolderTests.java`.
  - [x] 12.8 Ensure the group's tests pass
    - `cd mcp-server && ./mvnw test -Dtest=TokenExchangeClientTests,RestClientConfigTests,ToolCallIntegrationTests,ErrorMappingTests,ToolSchemaValidationTests,McpAuthBoundaryTests,McpSecurityChainTests`
    - Both MCP red tests are now expected **green**

**Acceptance Criteria:**
- The 4 new tests and the adapted suites pass. `ToolSchemaValidationTests` assertions are unchanged.
- `grep -rn "AccessTokenHolder\|TOKEN_KEY\|JwtForwardingInterceptor\|McpJwtFilter" mcp-server/src` returns nothing
- `McpAuthBoundaryTests` passes, and the file is still byte-identical

---

### Task Group 13: MCP Tool Authorization & Logging Proofs
**Dependencies:** 12
**Files to Modify:** mcp-server/src/test/java/pl/devstyle/aj/mcp/security/FakeHost.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/security/ToolAuthorizationTests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpLoggingTests.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/client/TokenExchangeClient.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/config/RestClientConfig.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/service/ProductService.java, mcp-server/src/main/java/pl/devstyle/aj/mcp/service/CategoryService.java
**Estimated Steps:** 5
**Requirements:** MCP-7/8/11 behaviour end-to-end, MCP-15, M3, Success Criteria 2, 6 (MCP)

- [x] 13.0 Prove per-tool authorization, exchange-once, no-retry and no token logging over real HTTP
  - [x] 13.1 Extend `FakeHost`
    - Programmable `/oauth2/token` (exchange) responses (success T2, `invalid_scope`, wrong `issued_token_type`), programmable `/api/products` and `/api/categories` responses (incl. 401), and per-endpoint call counters plus recorded `Authorization` headers
  - [x] 13.2 Write `ToolAuthorizationTests` (`RANDOM_PORT`, JSON-RPC over HTTP, 5 tests)
    - `addProduct_withReadOnlyToken_returnsScopeErrorWithoutExchange`, `toolCall_validToken_exchangesExactlyOncePerCall` (backend sees `Bearer <T2>`, never T1), `toolCall_exchangeRejected_returnsToolErrorWithoutBackendCall`, `toolCall_wrongIssuedTokenType_returnsToolError`, `toolCall_backend401_returnsApiErrorWithoutRetry`
  - [x] 13.3 Write `McpLoggingTests` (`RANDOM_PORT`, `OutputCaptureExtension`, `FakeHost`, 1 test)
    - `toolCall_fullFlow_neverLogsSubjectOrExchangedToken`: runs `aj_list_products` and `aj_add_product` at the shipped logging levels. The captured output contains neither T1, T2 nor `test-mcp-secret`.
  - [x] 13.4 Fix any defect the tests reveal in the listed main files (for example an exception message echoing a body). Do not weaken the tests.
  - [x] 13.5 Ensure the MCP suite passes
    - `cd mcp-server && ./mvnw test` (whole MCP suite, both red tests included)

**Acceptance Criteria:**
- The 6 tests pass, and the whole MCP suite is green
- Every tool call performs exactly one exchange and never forwards T1

---

### Task Group 14: Consent Page & SPA Token Handling
**Dependencies:** 1
**Files to Modify:** src/main/frontend/src/pages/OAuth2AuthorizePage.tsx, src/main/frontend/src/auth/AuthContext.tsx, src/main/frontend/vite.config.ts, src/main/frontend/public/assets/plugin-ui.css, src/main/frontend/src/test/oauth2-authorize.test.tsx, src/main/frontend/src/test/auth.test.tsx, src/main/frontend/src/test/plugin-sdk-auth.test.ts, src/main/frontend/src/test/plugin-sdk.test.ts
**Estimated Steps:** 6
**Requirements:** FE-1, FE-2, FE-3, FE-4, CFG-3 (frontend fixtures and comment), C1, I-5, I-9, m10

- [x] 14.0 Complete the consent-page and SPA changes
  - [x] 14.1 Write 5 focused tests
    - New `src/test/oauth2-authorize.test.tsx` (per-file `renderWithProviders` with `MemoryRouter` initial entries, `vi.mock` for the client-info API / fetch and a mocked `useAuth` permission set): `renders hidden resource input when resource query param is present`, `omits resource input when resource is absent`, `offers only mcp:read to a viewer`, `offers mcp:read and mcp:edit to an editor`
    - `src/test/auth.test.tsx`: add `drops a stored token without aud claim` (seed `localStorage`, `vi.resetModules()`, dynamic import). The existing "provides token/permissions/login/logout via useAuth hook" fixture JWT gains `aud: ["http://localhost:8180/api"]`, with assertions unchanged.
  - [x] 14.2 FE-1
    - `OAuth2AuthorizePage.tsx` reads `resource` from `searchParams` and renders `<input type="hidden" name="resource">` only when it is non-empty (like `state`)
  - [x] 14.3 FE-2 grantable scopes
    - Offered = expand(requested ∩ client scopes, or client scopes when none were requested) filtered by `useAuth()` permissions (`READ`→`mcp:read`, `EDIT`→`mcp:edit`). If nothing is grantable, show the existing error box "You do not have permission to grant the requested access" and keep Allow disabled.
    - Keep the helper non-exported (react-refresh lint: component files export only components) and use existing tokens and styles
  - [x] 14.4 FE-3
    - `decodeJwtPayload` return type gains `aud?: string | string[]`. `loadStoredAuth` also discards a stored token without `aud`.
  - [x] 14.5 Ports
    - `vite.config.ts` proxy target → `http://localhost:8180`. The usage comment in `public/assets/plugin-ui.css` → `8180`. The 14 fixture lines in `plugin-sdk-auth.test.ts` and `plugin-sdk.test.ts` → `8180`/`8181`, with assertions unchanged.
  - [x] 14.6 Ensure the group's tests pass
    - `cd src/main/frontend && npx vitest run src/test/oauth2-authorize.test.tsx src/test/auth.test.tsx src/test/plugin-sdk-auth.test.ts src/test/plugin-sdk.test.ts && npm run lint`

**Acceptance Criteria:**
- The 5 new tests pass, the touched suites stay green and `npm run lint` is clean
- `grep -rn "8080\|8081\|9081" src/main/frontend/src src/main/frontend/vite.config.ts src/main/frontend/public/assets/plugin-ui.css` returns nothing

---

### Task Group 15: Dev Wiring & Plugin Port References
**Dependencies:** 1
**Files to Modify:** start-dev.sh, start-dev.ps1, plugins/server-sdk.ts, plugins/box-size/index.html, plugins/warehouse/index.html, plugins/shipping/index.html, plugins/ai-description/src/pages/_document.tsx, plugins/ai-offer-analysis/src/pages/_document.tsx, plugins/ai-offer-analysis/.env.example
**Estimated Steps:** 5
**Requirements:** CFG-2, CFG-3 (plugins), FU-5, audit clarification 3

- [x] 15.0 Complete the shared env wiring and the port move outside the apps
  - [x] 15.1 Write 3 scripted checks (a scratchpad script, not committed)
    - (a) Port check: `git grep -n "8080\|8081\|9081" -- start-dev.sh start-dev.ps1 plugins/server-sdk.ts plugins/*/index.html plugins/*/src/pages/_document.tsx plugins/ai-offer-analysis/.env.example` returns nothing
    - (b) Syntax check: `bash -n start-dev.sh`, plus a PowerShell parse of `start-dev.ps1` (`[System.Management.Automation.Language.Parser]::ParseFile(...)` reports no errors)
    - (c) Secret check: neither script echoes `AJ_OAUTH_CLIENT_SECRET` or `APP_JWT_SECRET` values, and neither generates `APP_JWT_SECRET`
  - [x] 15.2 `start-dev.sh` (CFG-2)
    - Export, each only if unset: `APP_OAUTH2_ISSUER` (`http://localhost:8180`), `AJ_OAUTH_SERVER_URL` (= issuer), `AJ_BACKEND_URL` (`http://localhost:8180`), `AJ_MCP_BASE_URL` (`http://localhost:8181`), `AJ_OAUTH_CLIENT_ID` (`aj-mcp-server`), `AJ_OAUTH_CLIENT_SECRET` (32 random bytes, base64url, e.g. `head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'`)
    - `APP_JWT_SECRET` is only passed through when already set. Print the effective issuer and MCP base URL, never secrets.
  - [x] 15.3 `start-dev.ps1`: the same contract, compatible with Windows PowerShell 5.1 (`[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)`, then `[Convert]::ToBase64String` with `+/` → `-_` and `=` trimmed), using `$env:` variables only when unset
  - [x] 15.4 Plugin port references
    - `plugins/server-sdk.ts` `DEFAULT_HOST_BASE_URL` + JSDoc → `http://localhost:8180`
    - `plugins/{box-size,warehouse,shipping}/index.html` and `plugins/{ai-description,ai-offer-analysis}/src/pages/_document.tsx` SDK/CSS links → `:8180`
    - `plugins/ai-offer-analysis/.env.example` `HOST_BASE_URL` → `8180`
    - Do not touch `plugins/*/dist/`, `.playwright-mcp/`, `tsconfig.tsbuildinfo` or any `.env`
  - [x] 15.5 Ensure the checks pass: run checks (a)-(c)

**Acceptance Criteria:**
- All 3 checks pass, and both scripts keep starting the same set of services as before
- Only the listed files changed

---

### Task Group 16: Documentation & Standards
**Dependencies:** 9, 13, 14, 15
**Files to Modify:** CLAUDE.md, README.md, plugins/CLAUDE.md, plugins/ai-offer-analysis/README.md, .maister/docs/INDEX.md, .maister/docs/standards/mcp-server/conventions.md, .maister/docs/standards/backend/security.md, .maister/docs/standards/backend/plugin-auth.md, .maister/docs/standards/plugins/plugin-development.md, .maister/docs/standards/frontend/data-access.md, .maister/docs/project/architecture.md, .maister/docs/project/tech-stack.md
**Estimated Steps:** 7
**Requirements:** DOC-1 … DOC-7, C12

- [x] 16.0 Rewrite (do not append to) the docs so they describe the implemented behaviour
  - [x] 16.1 Write 3 scripted checks (a scratchpad script, not committed)
    - (a) `git grep -n "2026-04-13-mcp-token-security"` over the listed files returns nothing
    - (b) `git grep -n "8080\|8081\|9081"` over the listed files returns nothing
    - (c) Every relative Markdown link in the listed files resolves to an existing path
  - [x] 16.2 DOC-1 `CLAUDE.md` + DOC-7 root `README.md`
    - `CLAUDE.md`:
      - Ports on the command lines.
      - `core/security` / `core/oauth2` bullets: fixed issuer, `aud` gate, introspection, token exchange.
      - The MCP paragraph, worded as in DOC-1.
      - A `start-dev` secret-wiring note, and that a mismatched `AJ_OAUTH_CLIENT_SECRET` fails closed with 401s.
    - `README.md`:
      - `start-dev` section: also starts the MCP server, wires the client id and secret, ports 8180/8181/9181.
      - "Manual Start": an MCP step, the same-secret requirement, host port 8180.
  - [x] 16.3 DOC-2 `standards/mcp-server/conventions.md` and DOC-3 `standards/backend/security.md`
    - Rewrite "No Token Passthrough" and the token-claims and MCP-scope lines, using the spec's exact scope-rule wording
    - Add `insufficientScope` to the factory list. Point the dead link to this task.
  - [x] 16.4 DOC-4
    - `plugin-auth.md` (configured issuer makes the forwarded T0 Host-independent), `plugin-development.md` (port table host 8180 / MCP 8181 / management 9181, link example), `data-access.md` (proxy target)
  - [x] 16.5 DOC-5
    - `project/architecture.md`: `core/security` and `core/oauth2` purpose, MCP server purpose, introspect + exchange data flow, env vars, ports
    - `project/tech-stack.md`: the MCP resource-server starter
    - `INDEX.md`: summaries for the changed docs and the port line
  - [x] 16.6 DOC-6
    - Port mentions in `plugins/CLAUDE.md` and `plugins/ai-offer-analysis/README.md`, plus the note "configure MCP clients at `http://localhost:8181`". Leave `plugins/sdk.ts` untouched.
  - [x] 16.7 Ensure the checks pass: run checks (a)-(c)
    - Do **not** apply the suggested env-naming standards update. It is suggested to the user only.

**Acceptance Criteria:**
- All 3 checks pass, and no doc still claims behaviour that the code does not implement

---

### Task Group 17: Test Review, Gap Analysis & Red Gate
**Dependencies:** 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
**Files to Modify:** src/test/java/pl/devstyle/aj/core/**/*Tests.java, mcp-server/src/test/java/pl/devstyle/aj/mcp/**/*Tests.java, src/main/frontend/src/test/*.test.ts, src/main/frontend/src/test/*.test.tsx (never `McpTokenBoundaryTests.java` or `McpAuthBoundaryTests.java`)
**Estimated Steps:** 7

- [x] 17.0 Review the feature tests, fill critical gaps, and prove the red gate across all suites
  - [x] 17.1 Review the tests written in groups 2-14 (94 new, 4 renamed, about 16 adapted)
  - [x] 17.2 Analyse gaps for this feature only
    - Check against the spec's endpoint/error table and Success Criteria 1-7 and 10
    - Candidate gaps:
      - token endpoint: Basic + form `client_secret` → `invalid_request`
      - exchange: wrong `requested_token_type` or a present `actor_token` → `invalid_request`
      - introspection: missing `token` → 400
      - code grant: resource resolving but differing from the stored resource → `invalid_grant`
      - MCP: a token without any `mcp:*` scope hitting a tool
  - [x] 17.3 Write up to 10 additional strategic tests in the existing classes. Never in the two red-test files.
  - [x] 17.4 Run the full host suite
    - `./mvnw test -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm` → all green
  - [x] 17.5 Run the full MCP suite: `cd mcp-server && ./mvnw test` → all green
  - [x] 17.6 Run the full frontend suite
    - `cd src/main/frontend && npm test && npm run lint` → all green and lint-clean
  - [x] 17.7 Verify the red gate (RG-1, Success Criterion 1)
    - The four red tests pass (`McpTokenBoundaryTests`, `McpAuthBoundaryTests`)
    - `sha256sum -c .maister/tasks/development/2026-11-14-mcp-auth-refactor/implementation/rg1-baseline.sha256` → both `OK`
    - Port scan: `git grep -n "localhost:8080\|localhost:8081\|9081"` outside `week*/`, `tools/`, `_docs/`, `.maister/tasks/`, `plugins/*/dist/`, `.playwright-mcp/` and lockfiles returns nothing
    - No git commit is made

**Acceptance Criteria:**
- Host, MCP and frontend suites are all green, and lint is clean
- No more than 10 additional tests were added
- The four red tests pass, and both files are byte-identical to the RG-1 baseline

---

## Execution Order

1. Red-Gate Baseline Guard (3 steps)
2. Host Configuration & Token Claims (8 steps, depends on 1)
3. AS Policies, Client Authentication & Error Model (6 steps, depends on 2)
4. Authorize Endpoint & Consent-Time Grant (7 steps, depends on 3)
5. Token Endpoint — Code & Refresh Grants (8 steps, depends on 4)
6. MCP Client Provisioning (4 steps, depends on 5)
7. Token-Exchange Grant (6 steps, depends on 6)
8. Introspection Endpoint & AS Metadata (6 steps, depends on 7)
9. Host Token-Logging Hygiene (4 steps, depends on 8)
10. MCP Configuration & Dependencies (7 steps, depends on 1)
11. MCP Inbound Token Validation (7 steps, depends on 10)
12. MCP Delegated Outbound Calls (8 steps, depends on 11)
13. MCP Tool Authorization & Logging Proofs (5 steps, depends on 12)
14. Consent Page & SPA Token Handling (6 steps, depends on 1)
15. Dev Wiring & Plugin Port References (5 steps, depends on 1)
16. Documentation & Standards (7 steps, depends on 9, 13, 14, 15)
17. Test Review, Gap Analysis & Red Gate (7 steps, depends on 1-16)

Requirement coverage:
- AS-1 → 2, 8
- AS-2 → 3
- AS-3 → 3
- AS-4 → 3
- AS-5 → 4
- AS-6 → 4
- AS-7 → 5
- AS-8 → 5
- AS-9 → 5
- AS-10 → 7
- AS-11 → 8
- AS-12 → 6
- AS-13 → 8
- AS-14 → 3, 4, 5
- AS-15 → 4, 5, 9
- AS-16 → 3, 4, 5, 7
- SEC-1 → 2
- SEC-2 → 2
- SEC-3 → 2, 4, 5, 7, 8
- SEC-4 → 2
- MCP-1 → 10
- MCP-2 → 10
- MCP-3 → 11
- MCP-4 → 11
- MCP-5 → 11
- MCP-6 → 12
- MCP-7 → 12
- MCP-8 → 12
- MCP-9 → 12
- MCP-10 → 12
- MCP-11 → 12
- MCP-12 → 12
- MCP-13 → 11, 12
- MCP-14 → 10
- MCP-15 → 10, 13
- FE-1 → 14
- FE-2 → 14
- FE-3 → 14
- FE-4 → 14
- CFG-1 → 2
- CFG-2 → 15
- CFG-3 → 10, 14, 15
- DOC-1 … DOC-7 → 16
- RG-1 → 1, 17

## Standards Compliance

Follow standards from `.maister/docs/standards/`:
- global/:
  - `conventions.md`: `app.*`/`aj.*` kebab-case keys with `${UPPER_SNAKE:default}`, secrets from env only, Maven wrapper, LF for shell scripts, course directories untouched. The shared cross-app env names are an accepted deviation; suggest the standards update to the user, do not apply it.
  - `minimal-implementation.md`: no caches, no speculative properties, the 300 s TTL is a constant.
  - `error-handling.md`: typed exceptions, one central OAuth2 error writer, fail-fast configuration.
- backend/:
  - `security.md`: all matchers stay in `SecurityConfiguration`, no `@PreAuthorize`, no redundant matcher for `/oauth2/introspect`.
  - `java-conventions.md`: records for DTOs, Jackson 3 `tools.jackson` in new host code, Jackson 2 only in the MCP server and the registration filter's parsing (accepted deviation).
  - `api.md`: OAuth endpoints keep their RFC paths.
- mcp-server/`conventions.md`: `aj_` tool names, `SyncToolSpecification`, `structuredContent`, `McpToolException` factories, no token passthrough.
- testing/:
  - `backend-testing.md`: `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`, `SecurityMockMvcConfiguration` for MockMvc, `*Tests` package-private, `action_condition_expectedResult`, AssertJ / `jsonPath`. Shared `OAuth2TestFlows` is an accepted deviation.
  - `frontend-testing.md`: tests in `src/test/`, `renderWithProviders`, `vi.mock`.
- frontend/: `components.md` and `tooling.md` (named exports, `import type`, lint clean); `css.md` (no new styling, existing tokens).
- plugins/`plugin-development.md`: fixed-port table updated.

## Notes

- **Test-Driven:** every code group writes its tests first. G1, G15 and G16 write scripted checks instead.
- **Run Incrementally:** run only the group's test classes (plus the listed regression classes). Full suites run only in G17.
- **RG-1:** no group may edit `src/test/java/pl/devstyle/aj/core/oauth2/McpTokenBoundaryTests.java` or `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpAuthBoundaryTests.java`. Re-run `sha256sum -c` whenever in doubt.
- **No commits:** the plan makes no git commits. Committing is left to the user.
- **Out of bounds:** `week7/`–`week10/`, `tools/`, `_docs/`, `plugins/*/dist/`, `.playwright-mcp/`.
- **Mark Progress:** check off steps as they are completed.
- **Reuse First:** `DatabaseRegisteredClientRepository`, `CustomUserDetailsService`, the BCrypt `PasswordEncoder` bean, `SpringOpaqueTokenIntrospector`, `OAuth2ErrorResponse` and the existing filter template, as listed in the spec's Reusable Components.
