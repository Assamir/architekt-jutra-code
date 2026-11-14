# Work Log

## 2026-11-14T17:09:04Z - Implementation Started

**Total Steps**: 104 (121 checkbox lines incl. acceptance items)
**Task Groups**: G1 Red-Gate Baseline Guard; G2 Host Configuration & Token Claims; G3 AS Policies, Client Authentication & Error Model; G4 Authorize Endpoint & Consent-Time Grant; G5 Token Endpoint — Code & Refresh Grants; G6 MCP Client Provisioning; G7 Token-Exchange Grant; G8 Introspection Endpoint & AS Metadata; G9 Host Token-Logging Hygiene; G10 MCP Configuration & Dependencies; G11 MCP Inbound Token Validation; G12 MCP Delegated Outbound Calls; G13 MCP Tool Authorization & Logging Proofs; G14 Consent Page & SPA Token Handling; G15 Dev Wiring & Plugin Port References; G16 Documentation & Standards; G17 Test Review, Gap Analysis & Red Gate
**Execution mode**: parallel waves (sequential=false). TaskCreate/TaskList unavailable in this session — plan checkboxes + this log are the progress record.

**Planned waves** (from Dependencies + disjoint Files to Modify):
- Wave 1: G1
- Wave 2: G2, G10, G14, G15
- Then host chain G3→G4→G5→G6→G7→G8→G9 alongside MCP chain G11→G12→G13; G16 after G9/G13/G14/G15; G17 last.

## Standards Reading Log

### Loaded Per Group
(Entries added as groups execute)

## 2026-11-14T17:10:41Z - Group 1 Complete (wave 1)

**Steps**: 1.1 through 1.3 completed
**Standards Applied**:
- From plan: RG-1 (spec Red gate)
- From INDEX.md: global/conventions.md (Maven wrapper, LF)
- Discovered: none
**Tests**: 4 red tests confirmed failing on baseline (expected):
- McpAuthBoundaryTests.toolCall_withInactiveToken_returns401 — expected 401 but was 200
- McpAuthBoundaryTests.toolCall_withValidToken_neverForwardsClientTokenToBackend — backend received "Bearer client-token-t1"
- McpTokenBoundaryTests.mcpAccessToken_replayedAtApi_returns401 — Status expected 401 but was 200
- McpTokenBoundaryTests.authorize_viewerRequestingMcpEdit_isNotGrantedEditScope — ["mcp:read","mcp:edit"] contains mcp:edit
**Guard**: sha256sum -c implementation/rg1-baseline.sha256 → OK (both files)
**Files Modified**: implementation/rg1-baseline.sha256 (created)
**Notes**: red-test files untracked → hash guard replaces git diff until the user commits them.

## 2026-11-14T17:13:27Z - Group 15 Complete (wave 2)

**Steps**: 15.1 through 15.5 completed
**Standards Applied**:
- From plan: global/conventions.md (env vars, no secrets printed, LF in index), plugins/plugin-development.md (fixed ports), backend/plugin-auth.md
- From INDEX.md: global/minimal-implementation.md, global/commenting.md
- Discovered: global/conventions.md LF section (.gitattributes only forces /mvnw)
**Tests**: scripted checks 6/6 pass (port scan, bash -n, PS ParseFile, no secret printed, APP_JWT_SECRET not generated, CFG-2 vars present); env blocks exercised in bash and PS 5.1
**Files Modified**: start-dev.sh, start-dev.ps1, plugins/server-sdk.ts, plugins/{box-size,warehouse,shipping}/index.html, plugins/{ai-description,ai-offer-analysis}/src/pages/_document.tsx, plugins/ai-offer-analysis/.env.example
**Notes**: AJ_OAUTH_SERVER_URL defaults to effective APP_OAUTH2_ISSUER; empty secret treated as unset. plugins/CLAUDE.md 8080 mentions left for G16. Tracked plugins/{box-size,warehouse}/dist/index.html still 8080 (out of scope). Suggestion: .gitattributes rule "*.sh text eol=lf".

## 2026-11-14T17:14:09Z - Group 10 Complete (wave 2)

**Steps**: 10.1 through 10.7 completed
**Standards Applied**:
- From plan: global/conventions.md (aj.* kebab keys, env defaults, secret only from env), mcp-server/conventions.md (AJ_OAUTH_CLIENT_ID/SECRET), backend/java-conventions.md (records, Lombok), testing/backend-testing.md
- From INDEX.md: global/minimal-implementation.md, global/error-handling.md (fail-fast config)
- Discovered: none
**Tests**: 10 passed (AjPropertiesTests 5, ConfigurationTests 2 → 8181/9181, AjMcpApplicationTests 1, JacksonConfigTests 2); full MCP test sources compile; RG-1 hashes OK
**Files Modified**: mcp-server/pom.xml (resource-server starter), config/AjProperties.java (new), config/SecurityConfig.java (@EnableConfigurationProperties), src/main/resources/application.yml (ports, localhost defaults, DEBUG removed), src/test/resources/config/application.yml (new), AjPropertiesTests (new), ConfigurationTests
**Notes**: Deleted stale untracked build output mcp-server/target/test-classes/application.yml that shadowed main config (source removed in bd4de70). Derived values on AjProperties: introspectionUri(), tokenUri(), backendResource(), resourceMetadataUrl(). Remaining @Value reads (WellKnownController, RestClientConfig, SecurityConfig) to move to AjProperties in G11/G12. Suggest mvn clean before G17 full suite.

## 2026-11-14T17:16:05Z - Group 2 Complete (wave 2)

**Steps**: 2.1 through 2.8 completed
**Standards Applied**:
- From plan: backend/security.md (matchers only in SecurityConfiguration, 401 from entry point), global/conventions.md (app.oauth2.* keys, env defaults; AJ_* shared names = accepted deviation), backend/java-conventions.md (records), testing/backend-testing.md
- From INDEX.md: global/error-handling.md, global/minimal-implementation.md, global/commenting.md
- Discovered: none
**Tests**: 36 passed (OAuth2PropertiesTests 3, AuthIntegrationTests 18 incl. 4 new iss/aud tests, OAuth2IntegrationTests 8, ApiLayerTests 4, SecurityTestHelperTests 3). Red test McpTokenBoundaryTests.mcpAccessToken_replayedAtApi_returns401 now GREEN; authorize_viewer... still red (expected until G4). RG-1 OK.
**Files Modified**: core/oauth2/OAuth2Properties.java (new), core/security/OAuth2TokenClaims.java (new), JwtTokenProvider.java (iss/aud on T0, at+jwt OAuth tokens, parseApiToken/parseAccessToken, old parseToken + fallback removed), JwtAuthenticationFilter.java, SecurityConfiguration.java, OAuth2TokenFilter.java (bridge), application.properties (server.port=8180, app.oauth2.*, CORS from env), src/test/resources/config/application.properties (new), OAuth2PropertiesTests (new), AuthIntegrationTests
**Notes**: CORS default no longer includes kuba-app origin (document in G16). OAuth2MetadataController.getBaseUrl remains until G8; token logging + Jackson 2 in OAuth2TokenFilter remain until G5/G9. New tests not run separately red (compile-time / behaviour obviously red).

## 2026-11-14T17:16:35Z - Group 14 Complete (wave 2) — PARTIAL on acceptance "lint clean" (pre-existing)

**Steps**: 14.1 through 14.6 completed
**Standards Applied**:
- From plan: testing/frontend-testing.md, frontend/components.md, frontend/tooling.md, frontend/css.md, frontend/data-access.md, plugins/plugin-development.md
- From INDEX.md: global/minimal-implementation.md, global/commenting.md
- Discovered: none (backend/security.md consulted: client-side scope filter is cosmetic, server ScopePolicy decides)
**Tests**: group 20/20 pass (oauth2-authorize 4 new, auth 6 incl. new aud test, plugin-sdk-auth 3, plugin-sdk 7); tsc -b OK; no 8080/8081/9081 left in frontend src/vite/css.
Full `npm test`: 87 passed, 1 failed — extension-points.test.tsx "renders plugin filter iframes in filter bar" (stale: ProductListPage renders native filters; files untouched by this task → pre-existing).
`npm run lint`: 16 problems (14 errors, 2 warnings), none on changed lines; same findings on HEAD versions → pre-existing.
**Files Modified**: src/main/frontend/src/pages/OAuth2AuthorizePage.tsx (hidden resource input FE-1, grantable scopes FE-2), src/auth/AuthContext.tsx (drop token without aud FE-3), vite.config.ts (proxy 8180), public/assets/plugin-ui.css (comment), src/test/oauth2-authorize.test.tsx (new), src/test/auth.test.tsx (aud fixture m10, fresh-module helper), src/test/plugin-sdk-auth.test.ts, src/test/plugin-sdk.test.ts (fixture ports)
**Notes**: Fixed pre-existing failing AuthContext test via vi.resetModules()+vi.importActual helper (test-only). Carry to verification: pre-existing lint errors and stale extension-points test. Candidate frontend-testing standard: reload modules reading localStorage at load after seeding storage.

## 2026-11-14T17:19:46Z - Group 11 Complete (wave 3)

**Steps**: 11.1 through 11.7 completed
**Standards Applied**:
- From plan: mcp-server/conventions.md (introspection, no cache), backend/security.md (matchers in SecurityConfig, custom entry point/denied handler), backend/java-conventions.md (Jackson 2), testing/backend-testing.md, global/error-handling.md (fail closed, I-6)
- From INDEX.md: global/minimal-implementation.md, global/commenting.md
- Discovered: none
**Tests**: 13 passed (AudienceValidatingIntrospectorTests 3, McpSecurityChainTests 7 incl. 10 s stall test, ConfigurationTests 2, AjMcpApplicationTests 1). Red test McpAuthBoundaryTests.toolCall_withInactiveToken_returns401 now GREEN. Full MCP suite 39/40 (only the G12 red test remains). RG-1 OK.
**Files Modified**: security/AudienceValidatingIntrospector.java (new), config/SecurityConfig.java (rewritten: AjProperties, timed SpringOpaqueTokenIntrospector + URL-encoded Basic, resource-server chain, PRM customizer), security/McpAuthenticationEntryPoint.java, security/McpAccessDeniedHandler.java (new), security/McpJwtFilter.java + McpJwtFilterTests.java (deleted), test security/FakeHost.java, AudienceValidatingIntrospectorTests, McpSecurityChainTests (new)
**Notes**: Deviation — Spring Security 7 oauth2ResourceServer auto-registers OAuth2ProtectedResourceMetadataFilter on /.well-known/oauth-protected-resource/** (resource derived from request URL) and shadows WellKnownController; fixed via protectedResourceMetadataCustomizer publishing resource=aj.mcp.base-url, authorization_servers, scopes_supported. WellKnownController is now dead code → delete in G12. Candidate mcp-server standard: configure PRM via protectedResourceMetadataCustomizer.

## 2026-11-14T17:20:14Z - Group 3 Complete (wave 3)

**Steps**: 3.1 through 3.6 completed
**Standards Applied**:
- From plan: global/error-handling.md (typed OAuth2RequestException, single status map), backend/java-conventions.md (Jackson 3 in OAuth2ErrorWriter), backend/security.md (non-bean collaborators), testing/backend-testing.md, global/minimal-implementation.md
- From INDEX.md: global/conventions.md (LF); AS-15 no secret logging
- Discovered: none
**Tests**: 23 passed (ScopePolicyTests 8, ResourcePolicyTests 7, ClientAuthenticatorTests 8); regression AuthIntegrationTests 18, OAuth2IntegrationTests 8, ApiLayerTests 4, OAuth2PropertiesTests 3 green; RG-1 OK
**Files Modified**: core/oauth2/OAuth2Error.java (INVALID_TARGET), OAuth2RequestException.java, OAuth2ErrorWriter.java, ScopePolicy.java, ResourcePolicy.java, ClientAuthenticator.java (new) + 3 test classes
**Notes**: Signatures: ScopePolicy.grantAtAuthorization(String requested, Set perms, Set clientScopes), grantAtExchange(Set subjectScopes, Set perms, Set clientScopes) with raw permission names. Not wired yet (G4/G5). Possible hardening flagged for G17: no dummy BCrypt for unknown clients (timing oracle on client_id existence).

## 2026-11-14T17:25:08Z - Group 4 Complete (wave 4)

**Steps**: 4.1 through 4.7 completed
**Standards Applied**:
- From plan: backend/security.md (no matcher changes, non-bean collaborators), global/error-handling.md (typed errors, substring classification removed), backend/java-conventions.md (Jackson 3 for error output; Jackson 2 only for DCR parsing per I-7), testing/backend-testing.md (OAuth2TestFlows = accepted deviation)
- From INDEX.md: global/minimal-implementation.md, global/commenting.md; AS-15 logging rule
- Discovered: none
**Tests**: 63 passed (OAuth2GrantPolicyTests 5 new, McpTokenBoundaryTests 2/2, OAuth2IntegrationTests 8, AuthIntegrationTests 18, ApiLayerTests 4, policy unit tests 23, OAuth2PropertiesTests 3). BOTH host red tests now GREEN. RG-1 OK.
**Files Modified**: core/oauth2/OAuth2AuthorizationFilter.java (rewritten: session-only principal C-2, ResourcePolicy, ScopePolicy grant, OAuth2ErrorWriter), AuthorizationCodeService.java (resource instead of permissions), OAuth2TokenFilter.java (bridge), PublicClientRegistrationFilter.java (shared writer, SUPPORTED_SCOPES), core/security/SecurityConfiguration.java; tests OAuth2TestFlows (new, copied helpers), OAuth2GrantPolicyTests (new)
**Notes**: Session principal checked before parameter validation (no client_id enumeration). OAuth2TokenFilter still logs tokens / Jackson 2 until G5/G9; refresh still mints MCP audience until G5.

## 2026-11-14T17:25:22Z - Group 12 Complete (wave 4)

**Steps**: 12.1 through 12.8 completed (+ approved extra: WellKnownController deleted)
**Standards Applied**:
- From plan: mcp-server/conventions.md (no passthrough: introspect then exchange, no cache, no retry on backend 401; aj_ tools; McpToolException factories), backend/java-conventions.md (records, Jackson 2), global/error-handling.md, global/minimal-implementation.md, testing/backend-testing.md
- From INDEX.md: backend/security.md (programmatic per-tool scope check per MCP-7)
- Discovered: MCP-15 — TokenExchangeResponse.toString masks token; exchange client logs only status + AS error code
**Tests**: group 30 passed; full MCP suite 43/43. Red test McpAuthBoundaryTests.toolCall_withValidToken_neverForwardsClientTokenToBackend now GREEN → all 4 red tests green. grep for AccessTokenHolder/TOKEN_KEY/JwtForwardingInterceptor/McpJwtFilter empty. RG-1 OK.
**Files Modified**: AjMcpApplication.java (contextExtractor stores BearerTokenAuthentication), security/ToolSecurity.java (new), client/TokenExchangeClient.java + TokenExchangeResponse.java (new), client/AjApiClient.java (explicit Authorization header), config/RestClientConfig.java, service/ProductService.java, service/CategoryService.java, exception/McpToolException.java (INSUFFICIENT_SCOPE); deleted security/AccessTokenHolder.java, controller/WellKnownController.java, test AccessTokenHolderTests; tests TokenExchangeClientTests (new, 3), RestClientConfigTests (rewritten), ToolCallIntegrationTests, ErrorMappingTests, ToolSchemaValidationTests (adapted)
**Notes**: RestClientConfig still logs backend error body at WARN (pre-existing; G13 logging test to confirm) and still uses @Value aj.backend.url. ToolSecurity paths covered by G13.

## 2026-11-14T17:29:59Z - Group 13 Complete (wave 5)

**Steps**: 13.1 through 13.5 completed
**Standards Applied**:
- From plan: mcp-server/conventions.md (no passthrough proven end-to-end), testing/backend-testing.md, global/error-handling.md
- From INDEX.md: global/minimal-implementation.md, backend/security.md
- Discovered: global/conventions.md — kept @Value in RestClientConfig (signature used by RestClientConfigTests)
**Tests**: group 15 passed; full MCP suite 49/49. ToolAuthorizationTests (5): viewer add_product → scope error without exchange; exactly one exchange per call, backend sees only Bearer T2; exchange rejected → tool error, no backend call; wrong issued_token_type → tool error; backend 401 → apiError, no retry. McpLoggingTests (1): no T1, T2, client secret, Basic value or base64(T1) in captured logs. RG-1 OK.
**Files Modified**: test security/FakeHost.java (exchange + backend contexts), ToolAuthorizationTests (new), McpLoggingTests (new); main config/RestClientConfig.java (WARN logs status + path only, no body/query — MCP-15)
**Notes**: Tool errors surface as HTTP 200 JSON-RPC `error` (code -32603) — unchanged pre-refactor contract; tests assert it (not `isError: true`). Carry to verification: JacksonMcpJsonMapper logs every JSON-RPC response payload at INFO (no tokens, but noisy) — candidate cleanup. Candidate standard: REST client error handlers log status + path only.

## 2026-11-14T17:30:37Z - Group 5 Complete (wave 5)

**Steps**: 5.1 through 5.8 completed
**Standards Applied**:
- From plan: backend/security.md, backend/java-conventions.md (TokenResponse record, Jackson 3), global/error-handling.md (OAuth2RequestException + OAuth2ErrorWriter only), testing/backend-testing.md, global/minimal-implementation.md
- From INDEX.md: global/commenting.md
- Discovered: none
**Tests**: 24 passed (OAuth2GrantPolicyTests 10 incl. 5 new: authorizationCode_differentClientId_returnsInvalidGrant, authorizationCode_apiResource_returnsInvalidTarget, refresh_apiResource_returnsInvalidTarget, refresh_byDifferentClient_returnsInvalidGrantAndKeepsToken, refresh_publicClientWithoutClientId_returnsInvalidRequest; OAuth2IntegrationTests 8; McpTokenBoundaryTests 2; ApiLayerTests 4). Regression: AuthIntegrationTests 18, policy units 23, OAuth2PropertiesTests 3, SecurityTestHelperTests 3 green. RG-1 OK.
**Files Modified**: core/oauth2/TokenResponse.java (new), RefreshTokenService.java (client+resource binding, check before conditional remove), OAuth2TokenFilter.java (rewritten: Jackson 3, no-store, typed errors, no token logging), core/security/SecurityConfiguration.java (ClientAuthenticator); tests OAuth2TestFlows, OAuth2GrantPolicyTests, OAuth2IntegrationTests (renames, AssertJ, header/expires_in assertions)
**Notes**: OAuth2TokenFilter does not take OAuth2Properties (unused); G7 passes it to TokenExchangeGrantHandler. Orchestrator deleted stale untracked build outputs target/test-classes/.../{OAuth2IntrospectionTests,TokenExchangeIntegrationTests,JwtTokenProviderTests}.class (sources removed by bd4de70) that produced false errors.

## 2026-11-14T17:33:40Z - Group 6 Complete (wave 6)

**Steps**: 6.1 through 6.4 completed
**Standards Applied**:
- From plan: backend/security.md (BCrypt, no secret logging), backend/queries.md (@Transactional on public service method only), global/error-handling.md (blank secret → WARN, no startup failure), global/conventions.md, testing/backend-testing.md
- From INDEX.md: global/minimal-implementation.md, global/commenting.md
- Discovered: none
**Tests**: McpClientProvisionerTests 4/4 (new); regression 49 passed (OAuth2IntegrationTests 8, OAuth2GrantPolicyTests 10, McpTokenBoundaryTests 2, AuthIntegrationTests 18, ApiLayerTests 4, AjApplicationTests 3, provisioner 4). RG-1 untouched.
**Files Modified**: core/oauth2/McpClientProvisioner.java (new, ApplicationRunner), DatabaseRegisteredClientRepository.java (deleteByClientId), RegisteredClientJpaRepository.java (derived deleteByClientId); test McpClientProvisionerTests (new, test-provisioned-client)
**Notes**: Same-row upsert keeps id and hash when secret unchanged (matches short-circuit); rotation re-encodes. Stale JwtTokenProviderTests surefire XML predates removal of the stale .class (orchestrator deleted it after G5) — ignore.

## 2026-11-14T17:39:00Z - Group 7 Complete (wave 7)

**Steps**: 7.1 through 7.6 completed
**Standards Applied**:
- From plan: backend/security.md (no new matchers; T2 accepted via PERMISSION_mcp:read), backend/java-conventions.md (record DTO factory, Jackson 3), global/error-handling.md, testing/backend-testing.md (Integration/Validation split, createAndSaveUser with saveAndFlush)
- From INDEX.md: global/minimal-implementation.md, global/commenting.md
- Discovered: none
**Tests**: 11 new (TokenExchangeIntegrationTests 5, TokenExchangeValidationTests 6) — all red before implementation (unsupported_grant_type), green after; group run 25 passed; regression green (OAuth2IntegrationTests 8, McpTokenBoundaryTests 2, AuthIntegrationTests 18, McpClientProvisionerTests 4, policy units 23, AjApplicationTests 3). RG-1 OK.
**Files Modified**: core/oauth2/TokenExchangeGrantHandler.java (new), OAuth2TokenFilter.java (exchange case), TokenResponse.java (issuedToken factory — outside declared list, no conflict), core/security/SecurityConfiguration.java (CustomUserDetailsService, handler); tests OAuth2TestFlows (exchange helpers), TokenExchangeIntegrationTests, TokenExchangeValidationTests (new)
**Notes**: Handler built without OAuth2Properties (ResourcePolicy supplies API audience). Check order: client auth → grant/bound resource → params → resource → subject. Revoked-permission test uses dedicated user exchange-revoked. Rejection logs show form client_id only (null for Basic) — acceptable under AS-15.

## 2026-11-14T17:43:53Z - Group 8 Complete (wave 8)

**Steps**: 8.1 through 8.6 completed
**Standards Applied**:
- From plan: backend/security.md (no redundant matcher), backend/java-conventions.md (records, Jackson 3), backend/api.md (RFC paths), global/error-handling.md, global/minimal-implementation.md (no cache, no act field), testing/backend-testing.md
- From INDEX.md: global/commenting.md
- Discovered: AS-15 — filter logs client_id + error code only
**Tests**: 8 new (IntrospectionIntegrationTests 7 incl. expired → inactive, T2 → inactive, unbound client → inactive, no-store headers; metadata test with X-Forwarded-Host attacker.example ignored) — all red before (500 / attacker issuer), green after; group run 24 passed; regression all green (grant policy 10, exchange 11, boundary 2, auth 18, provisioner 4, policy units 23, properties 3). getBaseUrl gone from src/main. RG-1 untouched.
**Files Modified**: core/oauth2/IntrospectionResponse.java, OAuth2IntrospectionFilter.java, AuthorizationServerMetadata.java (new), OAuth2MetadataController.java (configured issuer), core/security/SecurityConfiguration.java (filter after OAuth2TokenFilter); tests OAuth2TestFlows (introspect), IntrospectionIntegrationTests (new), OAuth2IntegrationTests (metadata test replaced)
**Notes**: Pre-existing redundant permitAll for /.well-known/oauth-authorization-server left as is (overlaps non-/api permitAll) — candidate cleanup.

## 2026-11-14T17:48:19Z - Group 9 Complete (wave 9)

**Steps**: 9.1 through 9.4 completed
**Standards Applied**:
- From plan: backend/security.md, global/error-handling.md (responses unchanged, logs reduced), testing/backend-testing.md
- From INDEX.md: global/minimal-implementation.md, global/commenting.md
- Discovered: AS-15 allowlist applied to free-text client_name (unauthenticated DCR, log-injection vector)
**Tests**: OAuth2LoggingTests.fullFlow_neverLogsTokensCodesOrSecrets (new; passed already before audit — happy path cleaned by G4/G5/G8) + 14 regression classes → 95 passed across 15 classes. RG-1 OK.
**Files Modified**: test OAuth2LoggingTests (new); OAuth2AuthorizationFilter, OAuth2TokenFilter, OAuth2IntrospectionFilter (error logs: error_type instead of exception message/stack), PublicClientRegistrationFilter (error code instead of message; no client_name), DatabaseRegisteredClientRepository (client_id only)
**Notes**: Trade-off — unexpected OAuth 500s logged without stack trace (strict AS-15); flag for user review. AuthController clean (username only). Candidate standard: security error logs carry error_type, never exception message or free-text request fields.

## 2026-11-14T17:53:01Z - Group 16 Complete (wave 10)

**Steps**: 16.1 through 16.7 completed
**Standards Applied**:
- From plan: mcp-server/conventions.md, backend/security.md, backend/plugin-auth.md, plugins/plugin-development.md, frontend/data-access.md — rewritten to describe actual code (claims verified against source)
- From INDEX.md: global/conventions.md, global/commenting.md; INDEX.md summaries kept consistent
- Discovered: none
**Tests**: scripted doc checks 5/5 pass (no dead 2026-04-13 refs, no 8080/8081/9081, no kuba-* origins, no old passthrough/scope-mapping claims, relative links + .maister/tasks paths resolve)
**Files Modified**: CLAUDE.md, README.md (start-dev env, Manual Start with MCP + shared secret), plugins/CLAUDE.md (8180, auth header on manifest curls), plugins/ai-offer-analysis/README.md, .maister/docs/INDEX.md, standards/mcp-server/conventions.md, standards/backend/security.md, standards/backend/plugin-auth.md, standards/plugins/plugin-development.md, standards/frontend/data-access.md, project/architecture.md, project/tech-stack.md
**Notes**: CLAUDE.md still says architecture.md is out of date (not in DOC-1 scope). Candidate standards (not applied, for the user): PRM via protectedResourceMetadataCustomizer; REST client error logs status+path only; security error logs carry error_type only; .gitattributes *.sh eol=lf; shared AJ_* env names for cross-app values.

## 2026-11-14T17:57:29Z - Group 17 Complete (wave 11) — PARTIAL on frontend acceptance (pre-existing issues only)

**Steps**: 17.1 through 17.7 completed (17.6 run; frontend failures pre-existing, unchanged from baseline)
**Standards Applied**:
- From plan: testing/backend-testing.md; RG-1
- From INDEX.md: backend/security.md, global/minimal-implementation.md
- Discovered: none
**Tests**:
- Host full suite: 209 passed, 0 failed, 0 skipped
- MCP full suite: 49 passed, 0 failed, 0 skipped
- Frontend npm test: 87 passed, 1 failed (extension-points.test.tsx "renders plugin filter iframes in filter bar" — stale, file untouched, pre-existing)
- Frontend lint: 16 problems (14 errors, 2 warnings) — identical to baseline, no new findings
- 4 red tests GREEN (explicit runs + full suites); sha256sum -c rg1-baseline.sha256 → OK, OK
- 6 gap tests added: exchange_basicAndFormClientSecret_returnsInvalidRequest, exchange_unsupportedRequestedTokenType_returnsInvalidRequest, exchange_actorTokenPresent_returnsInvalidRequest, introspect_missingToken_returns400InvalidRequest, refresh_unknownPublicClient_returns401InvalidClientWithBasicChallenge, token_unknownGrantType_returnsUnsupportedGrantType
**Port scan**: no real leftovers; only tracked plugins/{box-size,warehouse}/dist/index.html (out of scope) and a hash false positive in plugins/ai-description/tsconfig.tsbuildinfo
**Files Modified**: TokenExchangeValidationTests, IntrospectionIntegrationTests, OAuth2GrantPolicyTests (tests only); deleted orphan build output target/classes/.../OAuth2ClientAuthenticator*.class
**Notes**: Untestable by design: resource-resolves-but-differs → invalid_grant (single MCP resource). Hardening idea: dummy BCrypt for unknown clients. Red-test files still untracked — commit needed for git diff form of RG-1.

## 2026-11-14T17:57:29Z - Implementation Complete

**Total Steps**: 104 completed (all 17 groups; G14 and G17 PARTIAL only on pre-existing frontend lint/test issues)
**Total Standards**: global (conventions, error-handling, minimal-implementation, commenting), backend (security, java-conventions, api, queries, plugin-auth), mcp-server/conventions, frontend (components, tooling, css, data-access), plugins/plugin-development, testing (backend, frontend)
**Test Suite**: host 209/209 passed; MCP 49/49 passed; frontend 87/88 (1 pre-existing stale failure); lint unchanged from baseline (16 pre-existing problems)
**Red gate**: 4/4 red tests green; RG-1 hashes OK

## 2026-11-14T18:20:06Z - Verification Fixes Applied (Phase 11, cycle 1)

**Group A (host)**: V-W2 grant-type enforcement at authorize/code/refresh + refresh_token only for refresh-registered clients; V-W4 redirect built with UriComponentsBuilder/UriUtils (state encoded, existing query kept); V-W5 DEBUG stack trace in catch-alls (ERROR keeps error_type); info c OAuth2LogValues (identified client id, sanitised client-supplied values); info a OAuth2Properties rejects mcp-resource overlapping issuer/API audience; info d dummy BCrypt on every ClientAuthenticator failure path; info i edge tests (short subject exp, deleted user, foreign aud, concurrent refresh race). Full host suite 232/232.
**Group B (MCP/dev/docs)**: V-W3 PRM tls_client_certificate_bound_access_tokens=false; info e RestClientConfig uses AjProperties.backend() (validated, slash-stripped); info f JSON-RPC payload/tool-argument logging moved to DEBUG without payload; V-W6 start-dev reuses/generates AJ_OAUTH_CLIENT_SECRET in gitignored .aj-dev.env; info g secret passed only to host+MCP processes, not plugins; info b docs wording (host 401 invalid_client, MCP client sees 5xx/tool error); info h spec approved deviations + stale CLAUDE.md note removed. Full MCP suite 51/51.
**Orchestrator**: architecture.md start-dev secret line updated.
**RG-1**: hashes OK (both groups).
**Remaining (not fixable here)**: V-W1 manual live run; V-W7 commit red-test baseline (user); V-W8 HMAC fallback (scope); V-W9 BCrypt cost/rate limits (design); V-W10 AS health check (follow-up); pre-existing DCR NPE when grant_types omitted; pre-existing frontend lint/stale test.

## 2026-11-14T18:31:55Z - Verification Fixes — cycle 1 detail (files, standards, tests)

**Standards Applied**: backend/security.md, global/error-handling.md, testing/backend-testing.md, backend/java-conventions.md, mcp-server/conventions.md, global/conventions.md (secrets in gitignored local file, LF for .sh), global/minimal-implementation.md, global/commenting.md
**Files Modified (Group A, host)**: core/oauth2/OAuth2LogValues.java (new), OAuth2TokenFilter.java, OAuth2AuthorizationFilter.java, OAuth2IntrospectionFilter.java, PublicClientRegistrationFilter.java, ClientAuthenticator.java, OAuth2Properties.java; tests OAuth2TestFlows, OAuth2GrantPolicyTests (+4), OAuth2PropertiesTests (+2, one parameterised ×5), ClientAuthenticatorTests (+3), OAuth2LogValuesTests (new, 4), RefreshTokenServiceTests (new, 1), TokenExchangeIntegrationTests (+2), TokenExchangeValidationTests (+1), OAuth2LoggingTests (+2)
**Files Modified (Group B, MCP/dev/docs)**: mcp-server config/SecurityConfig.java, AjProperties.java, RestClientConfig.java, JacksonMcpJsonMapper.java, LoggingJsonSchemaValidator.java; tests McpSecurityChainTests, AjPropertiesTests (+2), RestClientConfigTests, McpLoggingTests; start-dev.sh, start-dev.ps1, .gitignore (.aj-dev.env), README.md, CLAUDE.md, plugins/ai-offer-analysis/README.md, implementation/spec.md + spec.html
**Files Modified (orchestrator)**: .maister/docs/project/architecture.md (start-dev secret line)
**Tests after cycle 1**: host 232/232, MCP 51/51 (cycle-2 test-suite-runner: 370/371 overall, the 1 failure pre-existing frontend)
**Re-verification cycle 2**: verification/code-review-reverify.md and verification/completeness-reverify.md — all fixable cycle-1 findings resolved; 0 critical, 0 new warnings; 9 info items → Fix Group C (cycle 2) launched.

## 2026-11-14T18:39:24Z - Verification Fixes — cycle 2 (Fix Group C)

**Items**: claimed client id logged on failed Basic auth (no secret); Unicode-aware log sanitiser; DCR redirect URI validation (parsed, ASCII, exact loopback host, https otherwise; redirect_uris required; grant_types/response_types defaults; response built before save → no orphan rows); README secret wording + Troubleshooting (DEBUG for OAuth 500s); CLAUDE.md DEBUG line; strict PRM mTLS-flag assertion; spec notes AS-1/4/5/8/9/15, CFG-2, new AS-17; JacksonMcpJsonMapper log format.
**Standards Applied**: testing/backend-testing.md (red confirmed: 9 failures), global/error-handling.md, global/validation.md (allowlist), backend/security.md (AS-15), global/commenting.md, global/minimal-implementation.md, global/coding-style.md
**Files Modified**: ClientAuthenticator.java, OAuth2LogValues.java, PublicClientRegistrationFilter.java; tests OAuth2IntegrationTests (+6 methods / 17 cases), OAuth2LoggingTests (+1), OAuth2LogValuesTests (+1), ClientAuthenticatorTests (+1); mcp-server McpSecurityChainTests, JacksonMcpJsonMapper.java; README.md, CLAUDE.md, implementation/spec.md + spec.html
**Tests**: full host 252/252 (was 232), full MCP 51/51 (reverify "52" was a miscount); RG-1 OK
**Remaining**: N7 script parsing nuance (optional), N8 open redirect on consent Cancel (pre-existing, follow-up), candidate standards (user), manual live run, commit red-test baseline, HMAC fallback, BCrypt cost, AS health check.
