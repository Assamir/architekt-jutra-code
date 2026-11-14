# Code Review Report — Re-verification (cycle 2)

**Date**: 2026-11-14
**Path**: uncommitted working tree on `feat/mcp-auth-refactor` (fixes from work-log entry "Verification Fixes Applied (Phase 11, cycle 1)")
**Scope**: all (re-check of the cycle-1 findings plus a review of the code the fixes added)
**Status**: ⚠️ Issues Found (0 critical, 0 new warnings; the only open warnings are W1/W2, which are accepted by design, plus info items)

## TL;DR
- Every fixable cycle-1 item is **resolved**: V-W2 (grant types), V-W3 (PRM mTLS flag), V-W4 (redirect building), V-W5 (stack traces at DEBUG), V-W6 (`.aj-dev.env`), and info a–i. Each has a matching test.
- I re-ran the tests. Host: 11 targeted OAuth2 classes, 83/83 green. MCP: full suite, 52/52 green.
- The fixes introduced no critical issues and no warnings. I found 6 small new info items: Basic-auth failures still log `client_id=null`, the sanitiser covers ASCII control characters only, a malformed registered redirect gives a 500, README wording, DEBUG not documented, and a weaker test assertion.
- Not addressed, by design or decision: W1/V-W8 (HMAC fallback key) and W2/V-W9 (BCrypt cost; the dummy hash slightly widens it). Also V-W1, V-W7, V-W10 and the pre-existing items I3, I6, I7, I11, I15.

## Open Questions / Risks
- A host/MCP secret mismatch is the failure mode the docs describe. In that case the host still logs `client_id=null | error=invalid_client` for the MCP server's Basic-auth calls, because the client id is recorded only after authentication succeeds. That makes the most likely misconfiguration harder to diagnose (N1).
- Stack traces for unexpected OAuth 500s are only logged at DEBUG. No configuration or doc says how to turn that on, so by default an unexpected 500 still leaves only `error_type` in the logs (N5).

---

## Cycle-1 findings: status

| ID (cycle 1 / verification) | Status | Evidence |
|---|---|---|
| **W1 / V-W8** HMAC fallback key signs T0/T1/T2 | not addressed (by design, user-approved) | `src/main/resources/application.properties:5` unchanged; `start-dev.sh:24`, `start-dev.ps1:39` still inherit `APP_JWT_SECRET` and never generate it |
| **W2 / V-W9** ≥2 BCrypt per tool call; BCrypt CPU on permitAll endpoints | not addressed (by design). **Residual, slightly widened**: the dummy hash (info d) now makes *unknown* client ids cost one BCrypt as well | `ClientAuthenticator.java:60-65` (one `passwordEncoder.matches` on every path that reaches lookup) |
| **W3 / V-W2** grant types not enforced for code/refresh/authorize | **resolved** | `OAuth2AuthorizationFilter.java:90-93` (`UNAUTHORIZED_CLIENT` unless `AUTHORIZATION_CODE`); `OAuth2TokenFilter.java:106` (code grant), `:140` (refresh grant, checked **before** `consumeAndRotate`, so the token is not burned), `:128-130` (refresh token only if the client is registered for `REFRESH_TOKEN`), `:157-162` helper. Tests: `OAuth2GrantPolicyTests` `authorize_mcpServerClient_returnsUnauthorizedClient`, `exchangeCode_clientWithoutRefreshTokenGrant_omitsRefreshToken`, `refresh_clientWithoutRefreshTokenGrant_returnsUnauthorizedClient`; `TokenResponse` is `NON_NULL`, so no `refresh_token: null` |
| **W4 / V-W4** redirect concatenation, `state` not encoded | **resolved** | `OAuth2AuthorizationFilter.java:110, 123-130`: `UriComponentsBuilder.fromUriString(redirectUri)` keeps the existing query; `code` and `state` are strictly encoded with `UriUtils.encode`, then `build(true)`. Test `OAuth2GrantPolicyTests.java:245-274` checks a `state` of `x&code=evil=1 +/?` against a redirect that already has `?tenant=a%20b`. See N3 for one edge case |
| **I1 / info d** timing oracle on client existence | **resolved** | `ClientAuthenticator.java:29` (dummy hash built once with the same `BCryptPasswordEncoder`), `:60-67` (exactly one `matches` whether the client is unknown, uses the wrong method, has a null secret, or matches). Early rejections (malformed Basic, missing credentials) skip BCrypt but reveal nothing about existence. Tests `authenticateConfidential_unknownClient_stillVerifiesSecretAgainstDummyHash`, `..._unregisteredMethod_stillVerifiesSecretOnce`. I checked that `BCryptPasswordEncoder` 7.0.4 `matches` returns `false` (no exception) for secrets over 72 bytes, so long secrets cannot turn this path into a 500 |
| **I2 / info a** no guard on `mcpResource` overlapping issuer / API audience | **resolved** | `OAuth2Properties.java:14, 38-45` rejects `== issuer`, `== issuer/api` and `issuer/api/…`. This is sufficient because `parseApiToken` uses exact `Set.contains(apiAudience)` (`JwtTokenProvider.java:88`). Tests: `OAuth2PropertiesTests.constructor_mcpResourceOverlapsIssuerOrApiAudience_throws` (parameterised) and `constructor_mcpResourceSharingApiPrefixOnly_isAccepted` |
| **I3** DCR redirect-URI validation weak | not addressed (pre-existing) | `PublicClientRegistrationFilter.java:162-182` unchanged (prefix loopback check; validated only when `grant_types` contains `authorization_code`). The NPE when `grant_types` is omitted (`:107` `grants.addAll(null)`, after `save`) is also still there, as the work-log says |
| **I4 / info c** log forging; `client_id=null` for Basic | **resolved** for authenticated or identified clients. **Residual** for failed client auth (N1) and non-ASCII line separators (N2) | `OAuth2LogValues.java:21-38`, used in `OAuth2TokenFilter.java:85-90`, `OAuth2IntrospectionFilter.java:62-67`, `OAuth2AuthorizationFilter.java:54-59`. The client id is recorded at `ClientAuthenticator.java:69, 85` and `OAuth2AuthorizationFilter.java:89`. `grant_type` is sanitised. Tests: `OAuth2LogValuesTests` (4), `OAuth2LoggingTests.tokenRejection_clientSuppliedValuesWithLineBreaks_logsSanitizedValues`, `introspectionRejection_basicAuthenticatedClient_logsAuthenticatedClientId` |
| **I5 / info i** security test gaps | **resolved** | (a) `RefreshTokenServiceTests.java:23` (8 threads, latch, exactly one succeeds, token gone afterwards); (b) `TokenExchangeIntegrationTests.exchange_subjectExpiringBeforeCap_delegatedTokenExpiresWithSubject`; (c) `exchange_subjectUserDeleted_returnsInvalidRequest`; (d) `TokenExchangeValidationTests.exchange_accessTokenSubjectWithForeignAudience_returnsInvalidRequest`; (e) `OAuth2GrantPolicyTests.authorize_mcpServerClient_returnsUnauthorizedClient` |
| **I6** introspection/refresh ignore user deletion | not addressed (by design, ADR-004) | `OAuth2IntrospectionFilter.java:75-88` unchanged |
| **I7** plugin-held T0 is a consent principal | not addressed (pre-existing) | `OAuth2AuthorizationFilter.java:132-139` |
| **I8 / info g** secret exported to plugin processes | **resolved** | `start-dev.sh:22-23` (secret not in the `export` list), `:48, :52` (passed per command), `:55` (`unset` after both Java processes have forked, which also removes an inherited exported value before the plugin loop). `start-dev.ps1:24-25, 66-77` (set only around the two `Start-Process` calls; `Start-Process` captures the env at spawn) and `:107` (the caller's inherited value is restored on exit). Both scripts parse without errors (`bash -n`, PowerShell `Parser.ParseFile`: 0 errors) |
| **I9** duplication in filters / `scopesOf` | not addressed (optional) | `OAuth2TokenFilter.java:175-180` still duplicates `ScopePolicy` splitting |
| **I10 / info e** `RestClientConfig` bypasses `AjProperties` | **resolved** | `RestClientConfig.java:19-23, 32`; `AjProperties.java:25-31` (`Backend` non-blank, trailing slash stripped). Tests: `AjPropertiesTests` (blank → fails, slash stripped); `RestClientConfigTests.java:33` uses a URL with a trailing slash |
| **I11** Jackson 2 in DCR | not addressed (accepted deviation) | `PublicClientRegistrationFilter.java:3` |
| **I12** `JwtParser` rebuilt per parse | not addressed (optional) | `JwtTokenProvider.java:101` |
| **I13** trailing-slash rules differ (config vs canonical) | not addressed | `OAuth2Properties.java:47-49` strips one `/` unconditionally; `ResourcePolicy.java:22-23` strips it only for origin-only URIs. The current configuration is origin-only, so nothing breaks today |
| **I14** CORS default dropped the external origin | **addressed (documented)** | `CLAUDE.md:49` documents `APP_CORS_ALLOWED_ORIGINS` (default `http://localhost:*`) |
| **I15** OAuth 2.1 / RFC 9700 hardening | not addressed (out of scope) | — |
| **V-W3** PRM advertises `tls_client_certificate_bound_access_tokens: true` | **resolved** | `mcp-server/.../config/SecurityConfig.java:87` `.tlsClientCertificateBoundAccessTokens(false)`; test `McpSecurityChainTests.java:138` (see N6) |
| **V-W5** unexpected OAuth 500s logged without a stack trace | **resolved** (trace at DEBUG; ERROR keeps `error_type` only, per AS-15) | `OAuth2AuthorizationFilter.java:60`, `OAuth2TokenFilter.java:91`, `OAuth2IntrospectionFilter.java:68`, `PublicClientRegistrationFilter.java:65`. Residual: DEBUG is off by default and enabling it is not documented (N5) |
| **V-W6** MCP server cannot be restarted alone | **resolved** | `start-dev.sh:12-21` and `start-dev.ps1:22-38` reuse the secret from the gitignored `.aj-dev.env`, or generate it once and store it there (bash: `umask 077`; never printed). `.gitignore:49-51` (`git check-ignore` confirms). Manual restart is documented in `README.md:103-106, 118-120`. Residual wording issue: N4 |
| **info b** "fails closed with 401s" wording | **resolved** | `README.md:118`, `CLAUDE.md:43`, `plugins/ai-offer-analysis/README.md:62` now say: host `401 invalid_client`, which the MCP client sees as a 5xx (introspection) or a tool error (exchange) |
| **info f** JSON-RPC payloads logged at INFO | **resolved** | `JacksonMcpJsonMapper.java:64, 76` (DEBUG, size only); `LoggingJsonSchemaValidator.java:20-28` (DEBUG, instance type plus schema, no instance payload); the DEBUG logging levels were removed from `mcp-server/src/main/resources/application.yml` |
| **info h** spec not updated for approved deviations; stale `CLAUDE.md` note | **resolved** | `implementation/spec.md:190, 265, 433, 578`; `CLAUDE.md` no longer contains the "architecture.md is out of date" note |
| V-W1 live end-to-end run, V-W7 commit red-test baseline, V-W10 AS health indicator | not addressed (manual / user / follow-up) | outside code |

---

## New code: findings

### Critical Issues
None.

### Warnings
None.

### Informational

#### N1. A failed client authentication still logs `client_id=null` for Basic callers (the secret-mismatch case)
- **Location**: `src/main/java/pl/devstyle/aj/core/oauth2/ClientAuthenticator.java:69` (records only on success); `OAuth2LogValues.java:25-30` falls back to the form `client_id`, which is absent with Basic
- **Description**: The docs name a host/MCP secret mismatch as the expected failure mode. In that case the MCP server's introspection and exchange calls are logged as `client_id=null | error=invalid_client`, so the log does not show that `aj-mcp-server` is the client failing.
- **Suggestion**: On failure, record the sanitised Basic client id as a claimed id, e.g. `recordClientId(request, "claimed:" + clientId)` before throwing, or log it under a separate `claimed_client_id` key.
- **Fixable**: true

#### N2. The log sanitiser strips only ASCII control characters
- **Location**: `OAuth2LogValues.java:15` (`\p{Cntrl}` = `[\x00-\x1F\x7F]`)
- **Description**: U+0085 (NEL), U+2028 and U+2029 pass through unchanged. Some log viewers and JSON log pipelines treat these as line breaks. The risk is very low here (plain-text console logs, local setup).
- **Suggestion**: Use `[\p{Cntrl}\u0085  ]`, or `\p{C}` (adding `Pattern.UNICODE_CHARACTER_CLASS` is not needed for `\p{C}`).
- **Fixable**: true

#### N3. A registered redirect URI with characters that are illegal in an encoded URI makes `/oauth2/authorize` return 500
- **Location**: `OAuth2AuthorizationFilter.java:129` (`build(true)` verifies encoding); `PublicClientRegistrationFilter.java:162-182` (DCR accepts anything starting with `https://` or a loopback prefix)
- **Description**: A DCR client can register, for example, `http://localhost:3000/cb?x=a b` or a URI containing `{` or non-ASCII characters. At authorize, `HierarchicalUriComponents.verify()` throws `IllegalArgumentException`, which the catch-all turns into `server_error` with an ERROR log. Only the client that registered the bad URI is affected, and the old concatenation produced a broken `Location` anyway, so this is not a regression in practice.
- **Suggestion**: At DCR, reject redirect URIs that `URI.create(uri)` cannot parse (this also helps I3). Or, at authorize, map an `IllegalArgumentException` from building the redirect to `invalid_request`.
- **Fixable**: true

#### N4. README step 2 still says start-dev "exports" `AJ_OAUTH_CLIENT_SECRET`
- **Location**: `README.md:87`
- **Description**: The same paragraph then says the secret goes only to the host and the MCP server. That matches `start-dev.sh:22-23, 55`, where the secret is not exported. The word "Export" for this variable is now inaccurate.
- **Suggestion**: Reword: "Export … `AJ_OAUTH_CLIENT_ID` (default `aj-mcp-server`), and resolve `AJ_OAUTH_CLIENT_SECRET` …"
- **Fixable**: true

#### N5. Turning on DEBUG for OAuth 500 stack traces is not documented
- **Location**: catch-alls in `OAuth2AuthorizationFilter.java:60`, `OAuth2TokenFilter.java:91`, `OAuth2IntrospectionFilter.java:68`, `PublicClientRegistrationFilter.java:65`; no `logging.level` in `application.properties`
- **Description**: The V-W5 trade-off is acceptable. A developer debugging an unexpected `server_error` has to know to set `logging.level.pl.devstyle.aj.core.oauth2=DEBUG`. Note that stack-trace messages can contain request-derived text, which is why this should stay opt-in.
- **Suggestion**: Add one line to the README troubleshooting section, or a commented-out property line.
- **Fixable**: true

#### N6. The PRM test cannot tell an absent mTLS flag from `false`
- **Location**: `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpSecurityChainTests.java:138` (`asBoolean(false)`)
- **Description**: The assertion passes whether the field is absent or `false`. That still satisfies V-W3, which only requires the flag not to be advertised as `true`, but the test does not pin the explicit `false` the fix sets.
- **Suggestion**: Optionally assert `metadata.has(...)` and `isBoolean()`. Low value.
- **Fixable**: true

#### N7. The two scripts parse `.aj-dev.env` slightly differently, and the file permissions differ on Windows
- **Location**: `start-dev.sh:15` (keeps surrounding spaces; strips `\r`), `start-dev.ps1:29` (`.Trim()`), `start-dev.ps1:36` (`Add-Content`, inherited ACLs, no equivalent of `umask 077`)
- **Description**: Neither script strips quotes or an `export ` prefix from a hand-edited value. Within one run both apps get the same value, and the host re-provisions the client from it at each start, so this cannot cause a mismatch. It matters only when someone copies the value by hand. On Windows the file has the default user-profile ACL, which is fine for local dev.
- **Suggestion**: None needed. Optionally document "unquoted `KEY=VALUE`" (README already says `KEY=VALUE` lines).
- **Fixable**: true

#### N8. The consent page's Cancel button redirects to an unvalidated `redirect_uri` (pre-existing, newly observed, out of scope)
- **Location**: `src/main/frontend/src/pages/OAuth2AuthorizePage.tsx:93-103` (`handleCancel` was not changed in this diff)
- **Description**: The deny path builds `window.location.href` from the query-string `redirect_uri` without checking it against the client's registered URIs. This is an open redirect that needs a click from a logged-in user.
- **Suggestion**: Send the deny through the server (`/oauth2/authorize` with a deny flag that validates the registered URI), or have `client-info` return the registered URIs and check against them on the client. Track as a follow-up.
- **Fixable**: false (follow-up, outside this task)

---

## Verified non-issues (checked while reviewing the new code)
- **Grant-check ordering**: in the refresh grant, the grant type is checked before `consumeAndRotate`, so an unauthorised client cannot burn a token. In the code grant the code is consumed before client identification, which is the pre-existing single-use behaviour and is unchanged.
- **Redirect encoding**: `UriUtils.encode` percent-encodes everything outside the unreserved set (including `+`, `&`, `=`, `/`, `?`), so form-decoding clients read `+` back correctly. The registered query stays byte-for-byte. Registered URIs cannot contain a fragment (DCR rejects `#`).
- **Overlap guard**: complete with respect to the exact-match API audience gate. Case and port variants of `mcpResource` cannot equal `issuer/api` exactly, so they cannot pass `parseApiToken`.
- **Dummy hash**: `matches` is called once on every lookup path. Spring Security 7.0.4 `BCryptPasswordEncoder.matches` returns `false` for secrets over 72 bytes and for empty secrets (checked locally), so no new 500 path.
- **Secret isolation in start-dev**: the bash subshells fork before `unset`, and PowerShell `Start-Process` copies the environment at spawn time, so the secret is removed before any plugin starts. An inherited exported secret is also kept from plugins.
- **MCP logging**: no payload, argument or token values remain at INFO. The ERROR paths in `JacksonMcpJsonMapper` log only the exception message (pre-existing).
- **Logs elsewhere**: `PublicClientRegistrationFilter.java:122` logs client-supplied `grant_types`, `scopes` and `auth_method`, but only after they are validated against allowlists, so there is no injection.

## Test evidence (this cycle)
- Host, targeted (`-Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm`): `ClientAuthenticatorTests` 11, `IntrospectionIntegrationTests` 8, `McpClientProvisionerTests` 4, `McpTokenBoundaryTests` 2, `OAuth2GrantPolicyTests` 16, `OAuth2IntegrationTests` 8, `OAuth2LogValuesTests` 4, `OAuth2LoggingTests` 3, `OAuth2PropertiesTests` 9, `RefreshTokenServiceTests` 1, `TokenExchangeIntegrationTests` 7, `TokenExchangeValidationTests` 10. **83/83 passed.**
- MCP server, full `./mvnw test`: **52 run, 0 failures, 0 errors.**
- I did not re-run the full host suite. The work-log reports 232/232.

## Summary
- **Critical**: 0
- **Warnings**: 2 open, both accepted by design and carried from cycle 1 (W1/V-W8, W2/V-W9). 0 new.
- **Info**: 8 new (N1–N8; N8 is pre-existing). Still open from cycle 1: I3, I6, I7, I9, I11, I12, I13, I15.

## Metrics
- Max function length (new code): ~40 lines (`ClientAuthenticator.authenticateConfidential`)
- Max nesting depth: 3
- Potential vulnerabilities in new code: 0 (N3 is robustness, N8 is pre-existing)
- N+1 query risks: 0

## Prioritized Recommendations
1. N1: record the claimed Basic client id on authentication failure. This makes the documented secret-mismatch failure diagnosable.
2. N4 and N5: two one-line doc fixes (README "export" wording; how to enable DEBUG for OAuth 500 traces).
3. N3: parse-validate redirect URIs at DCR, together with the I3 hardening follow-up.
4. N2: widen the sanitiser to Unicode line separators.
5. Follow-ups already tracked: W1 (HMAC/JWKS), W2 (cheaper machine-client check or a cache, plus a rate limit), N8 (server-side deny), I9, I12, I13.
