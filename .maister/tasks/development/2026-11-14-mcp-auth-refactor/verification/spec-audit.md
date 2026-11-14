# Spec Audit: MCP auth refactor (validate-then-exchange, P1)

> Transcribed by the orchestrator from the spec-auditor subagent's returned report (the auditor's operating rules forbid writing files). Content is the auditor's, lightly condensed.

## TL;DR
- Verdict: **pass-with-concerns** — 0 critical, 3 major, 15 minor.
- All 4 red-gate tests pass unchanged if the spec is implemented as written (traced against code and Security 7.0.4 / MCP SDK 0.18.1 bytecode).
- Security model sound: T0/T1/T2 non-interchangeable, consent principal session-only, exchange/introspection bound to `aj-mcp-server`, refresh bound to client.
- Majors: introspector without timeouts, red-test immutability contradiction, test-plan gaps vs success criteria.

## Key Decisions
- No issue rated critical — none breaks a red test, opens token confusion, or blocks implementation.
- I-6 resolved: Security 7.0.4 `AuthenticationEntryPointFailureHandler` rethrows `AuthenticationServiceException` by default; `OAuth2IntrospectionException` → 500 without `invalid_token`; no 503 fallback needed.
- C14 resolved: `WebMvcStatelessServerTransport.handlePost` calls `contextExtractor.extract(...)` synchronously on the servlet thread after security filters.
- Exchange-time default to client scopes rated minor (latent: T1 scopes never empty today).

## Open Questions / Risks
- Real MCP clients' `resource` value still unverified (manual gate); C-1 covers `X/`.
- ≥2 BCrypt verifications per tool call on the host (introspection + exchange) — unmeasured, acceptable locally.
- Requirements say start-dev exports `APP_JWT_SECRET`; spec only inherits it — needs user sign-off.
- Starting one app by hand with a different `AJ_OAUTH_CLIENT_SECRET` fails closed with opaque 401s; root `README.md` "Manual Start" not in docs list.

## Claim verification (evidence)
| Claim | Result | Evidence |
|---|---|---|
| Starter `spring-boot-starter-security-oauth2-resource-server` | ✅ | Boot 4.0.5 BOM (old name also present); not in `.m2` → first build needs network |
| `SpringOpaqueTokenIntrospector` builder URL-encodes credentials (C8) | ✅ | `$Builder`: `URLEncoder.encode` + `new RestTemplate()` + `BasicAuthenticationInterceptor` |
| Introspector maps `scope`→`SCOPE_`, `aud`→list, `iss`→string | ✅ | `convertClaimsSet` |
| `active:false` → 401 `invalid_token` | ✅ | `BadOpaqueTokenException` → `InvalidBearerTokenException` |
| Outage → 5xx (I-6) | ✅ | `rethrowAuthenticationServiceException = true` |
| Extractor on servlet thread (C14) | ✅ | `handlePost` order |
| `AuthorizationGrantType.TOKEN_EXCHANGE` exists | ✅ | oauth2-core 7.0.4 |
| `classpath:/config/application.*` additive (I-8) | ✅ | Boot default locations |
| Non-bean filters keep `@WebMvcTest` unaffected | ✅ | `ApiLayerTests` slice |
| Port inventory (CFG-3) | ✅ mostly | 18 tracked files + `application.properties` new `server.port`; `plugins/sdk.ts` has no port mention |
| Existing tests compatible with AS-8 order | ✅ | `tokenExchange_withInvalidCode_returns400` still `invalid_grant` |

Red-gate trace: (1) T1 `aud`=MCP → `parseApiToken` rejects → 401; (2) default resource → ScopePolicy {read,edit}∩READ = `mcp:read`; (3) fake `active:false` → 401, no backend call; (4) aud matches, `SCOPE_mcp:*`, extractor stores `BearerTokenAuthentication`, exchange → `Bearer exchanged-token-t2`; fake `iss` ignored (MCP-3 checks no `iss`).

## Critical Issues
None.

## Major
- **M1 — Introspection has no timeouts.** MCP-3 `withIntrospectionUri(...).build()` uses `new RestTemplate()` without timeouts (not configurable via builder); a stalled host blocks MCP request threads. Fix: `new SpringOpaqueTokenIntrospector(uri, restTemplate)` with connect 5 s / read 10 s and a `BasicAuthenticationInterceptor` with URL-encoded id/secret (keeps C8); add stalled-fake test expecting 5xx.
- **M2 — Red-test immutability vs "helpers extracted".** Reusable Components says helpers from `McpTokenBoundaryTests` are extracted into a shared helper; red gate requires unchanged tests. Fix: state that `McpTokenBoundaryTests` is not modified (`OAuth2TestFlows` copies helpers); verify with `git diff --exit-code` on both red-test files.
- **M3 — Test plan gaps.** Missing: auth-code redeemed with different `client_id` → `invalid_grant` (I-3); exchange without `resource` → `invalid_target` (AS-10); `resource`=API audience at token for code/refresh → `invalid_target` (ADR-003); expired T1 at introspection → `active:false` (AS-11); MCP-side log hygiene (Success Criterion 6 says both apps; MCP keeps `pl.devstyle`/`io.modelcontextprotocol` DEBUG). Fix: add `OAuth2GrantPolicyTests.authorizationCode_differentClientId_returnsInvalidGrant`, `…tokenWithApiResource_returnsInvalidTarget`, `TokenExchangeValidationTests.exchange_missingResource_returnsInvalidTarget`, `IntrospectionIntegrationTests.introspect_expiredToken_returnsInactive`, MCP `security/McpLoggingTests` (OutputCaptureExtension, full tool call logs neither T1 nor T2).

## Minor
- **m1** Exchange reuses `ScopePolicy.grant` default-to-client-scopes → latent over-grant if subject scopes empty. Fix: empty/missing subject scopes at exchange → `invalid_request`/`invalid_scope`, no default (or explicit flag).
- **m2** ADR-004 hierarchy partly realised: expand → ∩ permissions → ∩ client scopes makes an `mcp:edit`-only DCR client yield edit-only T1/T2; list tools and `/api` GET fail. Fix: re-expand after client intersection, or word DOC-2/3 precisely.
- **m3** Error-code inconsistencies: resource ≠ stored → `INVALID_TARGET` (AS-8) vs `INVALID_GRANT` (AS-9); table "client auth missing → 401" vs AS-4 `INVALID_REQUEST` (400). Fix: `INVALID_GRANT` for "≠ stored" on both; split table row.
- **m4** "Both forms at once" ambiguous. Fix: conflict = Basic + form `client_secret`; form `client_id` next to Basic must equal Basic id.
- **m5** `RefreshTokenService` cannot express AS-9 step 3. Fix: `consumeAndRotate(token, clientId, Optional<String> resource)` checks before conditional remove.
- **m6** `ToolSecurity.requireScope(Authentication, …)` uses `getToken()` (only on `BearerTokenAuthentication`). Fix: use `BearerTokenAuthentication` in signatures.
- **m7** Asymmetric trailing-slash normalisation (`AjProperties` derives URIs without stripping → `http://x//api`); blank `mcpResource` allowed. Fix: strip in `AjProperties` compact ctor; require non-blank `mcpResource`, `mcpClientId`, `server-url`, `base-url`.
- **m8** Secret-bearing records print secrets in `toString()`. Fix: override to mask; unit assertion.
- **m9** `McpClientProvisionerTests` isolation: blank-secret delete of `aj-mcp-server` in shared DB breaks exchange/introspection tests; `new`'d provisioner lacks transaction. Fix: class `@Transactional`, distinct client id.
- **m10** `auth.test.tsx:165-167` fixture JWT lacks `aud` → FE-3 discards it. Fix: add `aud: ["http://localhost:8180/api"]`.
- **m11** AS-8 consumes code before identifying client (RFC-acceptable, keeps existing test green). Fix: state interpretation in Key Decisions.
- **m12** Introspection response headers unspecified. Fix: `Cache-Control: no-store`, `Pragma: no-cache`.
- **m13** Multiple `resource` params unspecified. Fix: >1 → `invalid_target`.
- **m14** Docs inventory: `plugins/sdk.ts` has no port mention; root `README.md` (Manual Start, start-dev) missing from DOC list; DOC-2 should add `insufficientScope` to factory list in `mcp-server/conventions.md:5`.
- **m15** Undeclared standards deviations: `AJ_*` env names for host `app.oauth2.mcp-*` (global/conventions.md:50); `PublicClientRegistrationFilter` stays Jackson 2 (I-7); shared `OAuth2TestFlows` vs per-class helpers (backend-testing.md:73). Fix: list as accepted deviations; suggest standards update for env naming.

## Clarification Needed
1. `mcp:edit`-only DCR client — expand T1 to include `mcp:read` (m2)?
2. `resource` ≠ stored at code grant — `invalid_grant` or `invalid_target` (m3)?
3. Approve `APP_JWT_SECRET` narrowing (inherit, never generate)?
4. Basic + form `client_id` — counts as "both methods" (m4)?

## Recommendations (ordered)
1. M1 timed introspector + stalled-host test. 2. M2 red tests immutable, helpers copied. 3. M3 five missing tests (or reword Criterion 6). 4. m1, m5, m6, m7 before planning (affect signatures). 5. m8–m15 folded into plan task groups.
