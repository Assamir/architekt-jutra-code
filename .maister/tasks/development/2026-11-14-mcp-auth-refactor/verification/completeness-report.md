# Completeness Report: MCP auth refactor (validate-then-exchange, P1)

## TL;DR
- Status: **passed_with_issues**. 0 critical, 3 warnings, 10 info.
- Plan: all 104 steps (121 checkbox lines) are checked, and I found code for every one of the 50 spec requirements (AS-1..16, SEC-1..4, MCP-1..15, FE-1..4, CFG-1..3, DOC-1..7, RG-1). Of the 26 spec-named tests I sampled, all 26 exist.
- Standards: 21 of 25 indexed standards apply. All 21 are followed, with the 3 declared deviations plus 2 approved implementation deviations.
- Warnings: (1) both red-test files are still untracked, so the git-diff form of RG-1 has not been done; (2) the manual end-to-end check with a real MCP client (Success Criterion 8) has not been run; (3) the Spring protected-resource metadata now advertises `tls_client_certificate_bound_access_tokens: true`.
- Docs: the work log is complete, and CLAUDE.md, README, the standards and the project docs were rewritten. No old port numbers or dead links remain in tracked files.

## Key Decisions
- I accepted the hash guard (`implementation/rg1-baseline.sha256`, re-checked now: both files `OK`) as interim RG-1 evidence. The plan's no-commit rule overrides the spec's "commit the baseline" step. The git-diff form stays open (W1).
- I treated the work-log deviations as approved and compliant: `WellKnownController` deleted and protected-resource metadata configured through Spring's `protectedResourceMetadataCustomizer`; `TokenExchangeGrantHandler`/`OAuth2TokenFilter` built without `OAuth2Properties`; tool errors kept as JSON-RPC errors. Behaviour matches the spec, and tests cover it.
- I classified the frontend lint errors (16) and the stale `extension-points.test.tsx` failure as pre-existing, not new gaps. The files are unchanged, and on the touched test files the lint errors sit on lines the diff did not change. G14/G17 acceptance ("lint clean", "all green") is therefore not literally met, as the work log says openly.

## Open Questions / Risks
- Real MCP clients (Claude Code, MCP Inspector) have not been tested against `http://localhost:8181`, and the `resource` value they send has not been recorded. C-1 slash-equality covers the expected `X/` form, but this is unverified (W2).
- The PRM advertises mTLS-bound tokens, which the system does not support (W3). Strict clients may try certificate-bound flows or reject the server.
- Commit the red-test baseline before the first implementation commit. Otherwise `git diff --exit-code` can never prove RG-1 (W1).

---

## 1. Plan Completion

| Metric | Value |
|---|---|
| Total steps (plan Overview) | 104 (121 checkbox lines incl. group headers) |
| Checked `[x]` | 121 / 121 |
| Unchecked `[ ]` | 0 |
| Completion | 100% |
| Status | Complete. Code was found for every group. Frontend acceptance is partial, from pre-existing issues only. |

### Code spot checks by requirement

**Host authorization server (`src/main/java/pl/devstyle/aj/core/oauth2/`)**

| Req | Evidence | Result |
|---|---|---|
| AS-1 | `OAuth2Properties.java:5-34`: record with fail-fast non-blank checks for issuer/mcpResource/mcpClientId, trailing-slash strip, `apiAudience()`, masked `toString()`. `getBaseUrl` no longer exists anywhere in `src/main`. | OK |
| AS-2 | `ResourcePolicy.java:22-80`: `canonical` (origin-only strip), `resolveForAuthorization` (WARN default, INVALID_TARGET), `resolveForExchange`, `boundResourceOf`, `audienceContains`, `resourceParam` | OK |
| AS-3 | `ScopePolicy.java:37-52`: expand, then ∩ permissions, then ∩ client scopes, then re-expand only with READ, then drop unknown scopes, then empty → INVALID_SCOPE. `grantAtExchange` has no default (`:26-31`). Used by `PublicClientRegistrationFilter` and the metadata record (`SUPPORTED_SCOPES`). | OK |
| AS-4 | `ClientAuthenticator.java:29-80`: URL-decoded Basic, Basic plus form secret → INVALID_REQUEST, differing form `client_id` → INVALID_CLIENT, method check, BCrypt; `identify()` for public clients needs method `none` | OK |
| AS-5 | `OAuth2AuthorizationFilter.java:60-118`: session-only principal (`isSessionToken`) → ACCESS_DENIED; `ResourcePolicy`, then `ScopePolicy.grantAtAuthorization`, then the code stores grant and resource; typed errors through `OAuth2ErrorWriter`; parameter-map log removed | OK |
| AS-6 | `AuthorizationCodeService.java:63-72`: record has `resource`, `permissions` removed | OK |
| AS-7 | `OAuth2TokenFilter.java:75-81`: three-way dispatch plus UNSUPPORTED_GRANT_TYPE; Jackson 3; `no-store`/`no-cache` (`:174-175`); `TokenResponse` record with computed `expires_in` | OK |
| AS-8 | `OAuth2TokenFilter.java:96-131`: steps 1–9 in the specified order (code consumed before `identify`) | OK |
| AS-9 | `OAuth2TokenFilter.java:133-150` + `RefreshTokenService.java:33-60`: lookup, client and resource checks, then conditional `remove(token, data)`; a refresh token that fails these checks is kept | OK |
| AS-10 | `TokenExchangeGrantHandler.java:44-103`: confidential auth, grant and bound resource → UNAUTHORIZED_CLIENT, parameter checks (actor_token, requested_token_type), API-audience resource, subject `at+jwt` without `act` and with bound `aud`, current permissions, `grantAtExchange`, `exp` = min(300 s, subject `exp`), `act.sub`, no refresh token | OK |
| AS-11 | `OAuth2IntrospectionFilter.java:49-87` + `IntrospectionResponse.java`: client auth, `token` required, inactive for unbound caller / unparseable token / foreign audience, `scope` as a string, `no-store` on every response. Registered after `OAuth2TokenFilter` (`SecurityConfiguration.java:165-167`). | OK |
| AS-12 | `McpClientProvisioner.java:30-77`: `findByClientId`, then `RegisteredClient.from` (keeps id), re-encodes only on mismatch, TOKEN_EXCHANGE only, BASIC+POST, scopes, no redirect URIs; a blank secret means `deleteByClientId` + WARN; logs `client_id` only | OK |
| AS-13 | `AuthorizationServerMetadata.java` (11 fields from the configured issuer) + `OAuth2MetadataController.java:42` | OK |
| AS-14 | `OAuth2RequestException.java`, `OAuth2ErrorWriter.java:29-36` (401 + `Basic realm="aj"`, 403, 500, otherwise 400), `OAuth2Error.INVALID_TARGET` | OK |
| AS-15 | Every log line in `core/oauth2` and `core/security` was reviewed. They carry client_id, user, grant_type, scope, resource, error code or error_type only. The registration filter no longer logs `client_name`. Proven by `OAuth2LoggingTests`. | OK |
| AS-16 | `resourceParam` is used in authorize (`:88-89`), the code and refresh grants (`OAuth2TokenFilter:152-155`) and exchange (`TokenExchangeGrantHandler:52`) | OK |

**Host security / API gate**

| Req | Evidence | Result |
|---|---|---|
| SEC-1 | `JwtTokenProvider.java`: `iss`/`aud` on T0 (`:44-56`), `generateOAuth2Token(OAuth2TokenClaims)` with `typ at+jwt`, `jti`, `client_id`, `scopes`, `act` (`:58-73`); `parseApiToken` / `parseAccessToken` (`:87-97`). Old helpers removed. | OK |
| SEC-2 | `JwtAuthenticationFilter.java:29-49`: `parseApiToken` for the header and `_token`; session token → permissions, `client_id` token → scopes; `setDetails(parsed)` | OK |
| SEC-3 | `SecurityConfiguration.java:42,73-167`: `@EnableConfigurationProperties`; non-bean collaborators; order Jwt → Registration → Authorization → Token → Introspection; matchers unchanged; CORS from env | OK |
| SEC-4 | `AuthController` not in the diff | OK |

**MCP server**

| Req | Evidence | Result |
|---|---|---|
| MCP-1 | `mcp-server/pom.xml`: `spring-boot-starter-security-oauth2-resource-server` (version from the BOM) | OK |
| MCP-2 | `config/AjProperties.java`: nested records, fail-fast secret message, non-blank checks, trailing-slash strip, masked `Oauth.toString()`, four derived URIs | OK |
| MCP-3 | `SecurityConfig.java:38-50`: timed `RestTemplate` (5 s / 10 s), URL-encoded `BasicAuthenticationInterceptor`, `SpringOpaqueTokenIntrospector(uri, restTemplate)` wrapped in `AudienceValidatingIntrospector` (slash-equal `aud`, no `iss` check) | OK |
| MCP-4 | `SecurityConfig.java:53-80`: stateless, CSRF off, the specified permitAll paths, `/` needs `SCOPE_mcp:*`, `opaqueToken(introspector)` | OK |
| MCP-5 | `McpAuthenticationEntryPoint.java:21-26` (scope plus a conditional `error="invalid_token"`); `McpAccessDeniedHandler.java` (403 `insufficient_scope`); 5xx and stall cases covered by `McpSecurityChainTests` | OK |
| MCP-6 | `AjMcpApplication.java:43-49`: `contextExtractor` stores `BearerTokenAuthentication`; `TOKEN_KEY` deleted | OK |
| MCP-7 | `security/ToolSecurity.java` | OK |
| MCP-8 | `client/TokenExchangeClient.java`: its own timed `RestClient`, the RFC 8693 form, URL-encoded Basic, `issued_token_type`/`token_type` validation, error mapping, ERROR log with status and code only, no cache or retry | OK |
| MCP-9 | `client/AjApiClient.java`: a leading `@RequestHeader(AUTHORIZATION)` on all three methods | OK |
| MCP-10 | `config/RestClientConfig.java`: interceptor and `AccessTokenHolder` gone, status mapping kept, WARN logs status and path only | OK (see I3) |
| MCP-11 | `ProductService`/`CategoryService`: `requireScope`, then `exchange`, then `"Bearer " + t2`; handlers use `ToolSecurity.principal(ctx)` | OK |
| MCP-12 | `McpToolException.java:41-43,54` | OK |
| MCP-13 | `McpJwtFilter`, `AccessTokenHolder` (and tests), `JwtForwardingInterceptor`, `TOKEN_KEY`: deleted. `grep` finds no remaining references in `mcp-server/src`. | OK |
| MCP-14 | `application.yml`: ports 8181/9181 and env defaults as specified. Deviation: `WellKnownController` was **deleted** (the spec said "logic unchanged") and replaced by Spring's PRM filter, which keeps `resource`, `authorization_servers` and `scopes_supported` | OK with deviation (W3) |
| MCP-15 | `application.yml` has no `logging.level` DEBUG entries. The test config contains only the secret. Proven by `McpLoggingTests`. | OK |

**Frontend, configuration, scripts**

| Req | Evidence | Result |
|---|---|---|
| FE-1 | `OAuth2AuthorizePage.tsx`: `resource` read from search params, hidden input rendered only when non-empty | OK |
| FE-2 | `grantableScopes()` (not exported): expand, then filter by `useAuth().permissions`; empty → existing error box | OK |
| FE-3 | `AuthContext.tsx`: `aud?` type; tokens without `aud` dropped; `auth.test.tsx:173` fixture gains `aud` | OK |
| FE-4 | `vite.config.ts` → `http://localhost:8180` | OK |
| CFG-1 | `application.properties`: `server.port=8180`, `app.oauth2.*`, CORS from env; `src/test/resources/config/application.properties` | OK |
| CFG-2 | `start-dev.sh:6-18`, `start-dev.ps1:16-30`: six variables exported (only if unset), secret generated only if unset, `APP_JWT_SECRET` not generated, no secrets printed. Both scripts still start `mcp-server`. | OK |
| CFG-3 | `git grep` for `localhost:808[01]` / `:9081` / `808[01]` returns nothing outside the excluded paths (`week*`, `tools`, `_docs`, `.maister/tasks`, `plugins/*/dist`, `.playwright-mcp`, lockfiles) | OK |
| RG-1 | `sha256sum -c rg1-baseline.sha256` → `OK`, `OK`. `git ls-files --error-unmatch` → **both untracked**. | Partial (W1) |

**Documentation** (DOC-1..7): see section 3. All are present.

### Test-plan spot check
I checked 26 spec-named test methods across the host, MCP and frontend for existence. All 26 exist (e.g. `grant_editOnlyClientForReadUser_reExpandsToReadAndEdit`, `refresh_byDifferentClient_returnsInvalidGrantAndKeepsToken`, `exchange_missingResource_returnsInvalidTarget`, `introspect_expiredToken_returnsInactive`, `request_whenIntrospectionStalls_returns5xxWithoutInvalidToken`, `toolCall_fullFlow_neverLogsSubjectOrExchangedToken`, plus the 4 `oauth2-authorize.test.tsx` cases and `drops a stored token without aud claim`). `verification/test-suite-results.md` reports host 209/209, MCP 49/49, frontend 87/88 (1 pre-existing failure), and all 4 red tests pass.

---

## 2. Standards Compliance

| Standard | Applies? | Reasoning | Followed? |
|---|---|---|---|
| global/error-handling.md | Yes | New typed exceptions and error writer | Yes: `OAuth2RequestException` + one status map; fail-fast config in both apps; 5xx on outage (I-6) |
| global/validation.md | Yes | Request parameter and config validation | Yes: server-side validation of every grant parameter; allowlist of supported scopes and resources |
| global/conventions.md | Yes | New properties, env vars, scripts | Yes, with **declared deviation**: host `app.oauth2.*` reads shared `AJ_*` env names. Keys are kebab-case with `${UPPER_SNAKE:default}`; secrets come from env only; course directories untouched. The working tree has CRLF from `core.autocrlf=true`, but the index is LF. |
| global/coding-style.md | Yes | All code | Yes: descriptive names, small focused methods |
| global/commenting.md | Yes | All code | Yes: sparse Javadoc on non-obvious classes, no change-log comments |
| global/minimal-implementation.md | Yes | New AS helpers and MCP classes | Yes: no caches, TTL as a constant, unused `JwtTokenProvider` helpers deleted, `WellKnownController` removed as dead code |
| backend/api.md | Yes | New `/oauth2/introspect` and metadata changes | Yes: RFC paths kept; metadata returns a record. `ResponseEntity` is only used where the standard permits it. |
| backend/module-structure.md | Yes | New classes in `core/oauth2` and `core/security` | Yes: flat feature package, no technical sub-packages |
| backend/java-conventions.md | Yes | New records and Jackson usage | Yes, with **declared deviation**: `PublicClientRegistrationFilter` keeps Jackson 2 for parsing (I-7). All other host code uses `tools.jackson`, and the MCP server uses Jackson 2. |
| backend/models.md | No | No entity changes (`RegisteredClientEntity` untouched) | n/a |
| backend/queries.md | Yes | Provisioner writes through JPA | Yes: `@Transactional` on the public `run`. `deleteByClientId` relies on the caller's transaction, which the standard allows. |
| backend/jooq.md | No | No jOOQ queries touched | n/a |
| backend/migrations.md | No | No changelog changes (spec: no DB migration) | n/a |
| backend/security.md | Yes | Core of the change | Yes: matchers only in `SecurityConfiguration`, no `@PreAuthorize`, no new redundant matcher for `/oauth2/introspect`, BCrypt for client secrets, JSON 401/403 on `/api` unchanged. One redundant `/.well-known/oauth-authorization-server` permitAll predates this task (I5). |
| backend/plugin-auth.md | Yes | `server-sdk.ts` default URL; the forwarded T0 now carries `aud` | Yes: the default is 8180, and the doc was updated |
| frontend/css.md | Yes | Consent page edit | Yes: no new styling (an inline hex `#0F172A` predates this task and its line is untouched) |
| frontend/components.md | Yes | Consent page | Yes: named export, helper not exported, `import type` unaffected |
| frontend/data-access.md | Yes | Proxy target | Yes: relative `/api`; the consent page's direct `fetch` predates this task |
| frontend/tooling.md | Yes | TS strict and lint | Yes for new code (`tsc -b` OK). There are 16 lint problems in total. Two touched test files have errors only on unchanged lines, so they predate this task. |
| frontend/accessibility.md | Yes (minor) | Consent scope list and error box | Yes: only a hidden input was added; the existing error box is reused |
| frontend/responsive.md | No | No layout change | n/a |
| plugins/plugin-development.md | Yes | Port table and plugin HTML links | Yes: 8180/8181/9181 table and link example updated |
| mcp-server/conventions.md | Yes | Core of the change | Yes: `aj_` tools unchanged, `SyncToolSpecification`, `structuredContent`, factories incl. `insufficientScope`, no passthrough, no retry, credentials from env |
| testing/backend-testing.md | Yes | About 70 new host and MCP tests | Yes, with **declared deviation**: shared `OAuth2TestFlows` helper. Classes are `*Tests` and package-private, names follow `action_condition_expectedResult`, AssertJ is used, there is no `@Disabled` and no plain `assert`. |
| testing/frontend-testing.md | Yes | New and extended vitest files | Yes: per-file `renderWithProviders`, `vi.mock`, `vi.resetAllMocks()` in `beforeEach`, tests under `src/test/` |

Summary: 25 standards checked, 21 applicable, 21 followed. There are 3 declared deviations (spec "accepted deviations" table) and 2 approved implementation deviations from the work log. Status: **compliant**.

Five candidate standards are recorded in the work log and still await the user's decision: PRM configured through `protectedResourceMetadataCustomizer`; REST client error logs carry status + path only; security error logs carry `error_type` only; `.gitattributes *.sh eol=lf`; shared `AJ_*` env names across apps.

---

## 3. Documentation Completeness

| Artifact | Check | Result |
|---|---|---|
| implementation-plan.md | All checkboxes `[x]`; file intact (707 lines, 17 groups) | OK |
| work-log.md | Start entry, 17 dated group entries, final completion entry; standards per group; files changed; deviations (WellKnownController, handler without `OAuth2Properties`, JSON-RPC tool errors, logging trade-off) | OK |
| spec.md alignment | All 50 requirements implemented. Deviations are recorded in the work log but not in the spec text (MCP-14 and Reusable Components still say `WellKnownController` is kept). | Minor gap (I1) |
| DOC-1 CLAUDE.md | Ports on lines 19/33/41, `core/security` + `core/oauth2` bullets, MCP paragraph, start-dev and secret-mismatch note | OK. The note on line 80 calling `architecture.md` "out of date" is now partly stale (I2). |
| DOC-2 mcp-server/conventions.md | No Token Passthrough rewritten, exact scope-rule wording, `insufficientScope` in the factory list, dead link fixed | OK |
| DOC-3 backend/security.md | Token claims, `/api` `aud` gate, exchange-only API audience, MCP scope line, dead link fixed | OK |
| DOC-4 | plugin-auth.md, plugin-development.md (ports + link), data-access.md (proxy) | OK |
| DOC-5 | architecture.md (security, oauth2, MCP, data flow, env, ports), tech-stack.md (resource-server starter), INDEX.md summaries + port line | OK |
| DOC-6 | `plugins/CLAUDE.md` and `ai-offer-analysis/README.md`: ports plus "configure MCP clients at :8181"; manifest curls now include an auth header | OK |
| DOC-7 README.md | start-dev section (MCP server, env wiring, ports) and Manual Start (MCP step, shared secret, host port 8180) | OK (wording nuance I9) |
| Stale references | `git grep` finds no old ports, no `2026-04-13-mcp-token-security` and no deleted class names in the 12 doc files | OK |
| verification/test-suite-results.md | Present, with consistent counts | OK |

Status: **complete**, with minor info-level gaps.

---

## Issues

| # | Source | Severity | Description | Location | Fixable | Suggestion |
|---|---|---|---|---|---|---|
| W1 | plan_completion | warning | RG-1 git baseline missing. Both red-test files are untracked, so `git diff --exit-code <baseline>` cannot run (Success Criterion 1, git form). The hash guard currently passes. | `src/test/java/pl/devstyle/aj/core/oauth2/McpTokenBoundaryTests.java`, `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpAuthBoundaryTests.java` | true | User commits both files unchanged as the baseline before the implementation commit, then runs `git diff --exit-code` |
| W2 | plan_completion | warning | Manual end-to-end check (Success Criterion 8) not run: a real MCP client against `:8181` running the three tools after consent, with the sent `resource` value recorded | spec Success Criteria 8; plan Open Questions | true | Run `start-dev` and connect Claude Code or MCP Inspector; record the `resource` parameter and the tool results |
| W3 | standards | warning | `WellKnownController` was replaced by Spring's `OAuth2ProtectedResourceMetadataFilter`. Its built-in defaults set `tlsClientCertificateBoundAccessTokens(true)` (I confirmed this by disassembling spring-security-oauth2-resource-server 7.0.4 with `javap`: `iconst_1`). The customizer does not override it, so the PRM advertises mTLS-bound tokens, which are unsupported (FU-8 out of scope). The old controller did not publish this field. | `mcp-server/src/main/java/pl/devstyle/aj/mcp/config/SecurityConfig.java:82-87` | true | Add `.tlsClientCertificateBoundAccessTokens(false)` in `describeProtectedResource`, and assert it in `McpSecurityChainTests.protectedResourceMetadata_resourceEqualsBaseUrl` |
| I1 | documentation | info | The spec text still says `WellKnownController` is "kept" / "logic unchanged" (MCP-14, Reusable Components) and that SEC-3 passes `OAuth2Properties` to the filters. Approved deviations are recorded only in the work log. | `implementation/spec.md` MCP-14, Reusable Components, SEC-3 | true | Add a short "Implementation deviations" note to the spec, or point the verification report at the work-log entries G5/G7/G11/G12 |
| I2 | documentation | info | CLAUDE.md still says `architecture.md` "still describes the pre-alpha scaffolding phase and is out of date", but this task updated its security, OAuth2 and MCP sections | `CLAUDE.md:80` | true | Reword the note to say only the untouched sections may be out of date (outside DOC-1 scope; user decision) |
| I3 | standards | info | `RestClientConfig` still reads `@Value("${aj.backend.url}")` instead of `AjProperties.backend().url()`, and `AjProperties.Backend` has no non-blank check. Inconsistent, but not a violation. | `mcp-server/src/main/java/pl/devstyle/aj/mcp/config/RestClientConfig.java:21`, `AjProperties.java:25` | true | Optional: inject `AjProperties`; the work log kept `@Value` for the `RestClientConfigTests` signature |
| I4 | plan_completion | info | The "resource resolves but differs from the stored resource → `invalid_grant`" branch cannot be reached with a single configured MCP resource, so it has no test (noted in the work log) | `OAuth2TokenFilter.java:115-119`, `RefreshTokenService.java:48-52` | false | Keep it as a defensive check; test it once a second resource exists |
| I5 | standards | info | A pre-existing redundant `permitAll` for `GET /.well-known/oauth-authorization-server` overlaps the non-`/api/` rule (security.md "no redundant matchers"). Not introduced by this task. | `SecurityConfiguration.java:111` | true | Remove it in a separate cleanup |
| I6 | standards | info | `ClientAuthenticator` skips BCrypt for unknown clients, which is a timing oracle on whether a `client_id` exists (hardening idea from the work log) | `ClientAuthenticator.java:57-63` | true | Optional: compare against a dummy hash when the client is unknown |
| I7 | standards | info | Pre-existing MCP INFO logging of every JSON-RPC response payload and schema-validation instance. It contains no tokens (`McpLoggingTests` proves this), but it is noisy. | `mcp-server/.../config/JacksonMcpJsonMapper.java:64`, `LoggingJsonSchemaValidator.java:20-27` | true | Lower to DEBUG in a follow-up |
| I8 | plan_completion | info | G14/G17 acceptance ("`npm run lint` clean", "all green") not literally met: 16 lint problems and 1 failing stale test (`extension-points.test.tsx`), all predating this task | `src/main/frontend` | true | Separate cleanup task |
| I9 | documentation | info | README/CLAUDE.md say a mismatched `AJ_OAUTH_CLIENT_SECRET` "fails closed with 401s". The 401s happen on the host's introspection and exchange endpoints. MCP clients see 5xx (introspection credentials rejected; spec endpoint table) or tool errors. | `README.md` Manual Start, `CLAUDE.md:43` | true | Optional wording: "fails closed (host returns 401 to the MCP server; MCP requests fail)" |
| I10 | standards | info | Five candidate standards (listed in section 2) are suggested in the work log and not yet applied | `implementation/work-log.md` G11/G13/G9/G15/G16 notes | true | Ask the user; apply approved ones through `/maister:standards-update` |

Issue counts: critical 0, warning 3, info 10.
