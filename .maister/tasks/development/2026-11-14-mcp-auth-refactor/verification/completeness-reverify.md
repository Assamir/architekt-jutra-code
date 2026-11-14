# Completeness Re-verification (cycle 2): MCP auth refactor

## TL;DR
- Status: **passed_with_issues**. 0 critical, 2 warnings, 7 info. The plan is 100% done (121/121 checkboxes), and nothing regressed.
- Of the 13 cycle-1 findings, 8 are resolved with code or doc evidence (W3, I1, I2, I3, I6, I7, I9, plus I10 in part). Five are still open, and none can be closed here: W1 needs the user to commit, W2 needs a manual run, I4 is by design, and I5 and I8 predate this task.
- The fix code follows the applicable standards. The README, CLAUDE.md, the plugin README and architecture.md now describe `.aj-dev.env`, how the secret reaches only two processes, and the corrected 401 wording.
- New drift: the spec text was not updated for the behaviour changes from cycle 1 (CFG-2, AS-1/4/5/8/9/15). No standard yet records grant-type enforcement, `.aj-dev.env` or the DEBUG-only stack traces. All of this is info-level and fixable.

## Open Questions / Risks
- The start-dev `.aj-dev.env` reuse and generation logic has only been checked for syntax. I ran `bash -n start-dev.sh`, which passed, and the PowerShell parser on `start-dev.ps1`, which found no errors. No run is recorded in which the host and MCP server are restarted separately. This is best done during the manual end-to-end run (W2).
- Refresh tokens now go only to clients registered with the `refresh_token` grant. A dynamic client registration with an empty `grant_types` list gets `authorization_code` only, so it gets no refresh token. Omitting `grant_types` entirely is a separate, pre-existing bug: the registration response then fails with a NullPointerException (`PublicClientRegistrationFilter.java:111`, `grants.addAll(grantTypes)`). This is correct per RFC 7591, but it changes what users see and is not documented. The live MCP client check (W2) should confirm which grant types the client registers.
- The DEBUG stack traces in the OAuth catch-all handlers are a narrow exception to the spec's AS-15 rule that no log line contains a token, code or secret, and the spec does not record it. DEBUG is not enabled in any committed config, and `OAuth2LoggingTests` runs at INFO.

---

## 1. Cycle-1 findings: resolution

| ID | Cycle-1 finding | Status | Evidence |
|---|---|---|---|
| W1 | Red-test files untracked, so there is no git-diff form of RG-1 | **Open (user)** | `git ls-files --error-unmatch src/test/.../McpTokenBoundaryTests.java` reports the file is not known to git, and `git status` shows `??` for both files. `sha256sum -c implementation/rg1-baseline.sha256` → OK, OK, so the files are still unchanged |
| W2 | No manual end-to-end check with a real MCP client (SC-8) | **Open (manual)** | Work log "Remaining (not fixable here): V-W1 manual live run" |
| W3 | PRM advertises `tls_client_certificate_bound_access_tokens: true` | **Resolved** | `mcp-server/.../config/SecurityConfig.java:87` `.tlsClientCertificateBoundAccessTokens(false)`; asserted in `McpSecurityChainTests.java:138`; spec MCP-14 (line 265) records it |
| I1 | Spec text not updated for approved deviations | **Resolved** | `spec.md:190` (handler/filter without `OAuth2Properties`), `spec.md:265` and `:433` (`WellKnownController` deleted, PRM through Spring Security) |
| I2 | Stale CLAUDE.md note "architecture.md out of date" | **Resolved** | The note is removed: `grep "out of date\|pre-alpha" CLAUDE.md` finds nothing |
| I3 | `RestClientConfig` used `@Value`, and `Backend` had no validation | **Resolved** | `RestClientConfig.java:19-32` injects `AjProperties` and uses `properties.backend().url()`; `AjProperties.java` `Backend` compact constructor requires a non-blank value and strips the trailing slash; `AjPropertiesTests` 5 → 7 |
| I4 | "Resource resolves but differs → `invalid_grant`" branch unreachable | **Open (by design)** | `OAuth2TokenFilter.java:118-122`. A single MCP resource makes the branch unreachable, so there is no test |
| I5 | Redundant `permitAll` for `/.well-known/oauth-authorization-server` | **Open (pre-existing)** | `SecurityConfiguration.java:111` unchanged |
| I6 | No dummy BCrypt for unknown clients (timing oracle) | **Resolved** | `ClientAuthenticator.java:27,62-67`: one `matches` call against `dummySecretHash` whenever the client is not usable; `ClientAuthenticatorTests` `..._unknownClient_stillVerifiesSecretAgainstDummyHash`, `..._unregisteredMethod_stillVerifiesSecretOnce` |
| I7 | MCP INFO logging of JSON-RPC payloads | **Resolved** | `JacksonMcpJsonMapper.java:64` is now `log.debug` with the length only; `LoggingJsonSchemaValidator.java` logs at DEBUG only, without the instance payload |
| I8 | Frontend lint (16) and stale `extension-points.test.tsx` | **Open (pre-existing)** | `test-suite-results.md` cycle 2: 87/88, 16 lint problems, both identical to the baseline |
| I9 | Docs said "fails closed with 401s" | **Resolved** | `README.md:118`, `CLAUDE.md:43` and `plugins/ai-offer-analysis/README.md:62` now say: the host answers `401 invalid_client`, and the MCP client sees a 5xx (introspection) or a tool error (exchange) |
| I10 | Candidate standards not yet applied | **Partially open** | Still awaiting the user. Cycle 2 adds more candidates (see N2) |

The related verification-summary items fixed in cycle 1 are also present in code:
- V-W2 grant types: `OAuth2AuthorizationFilter.java:89-92`, `OAuth2TokenFilter.java:103,138,147-152`, and refresh tokens only for clients registered with `refresh_token` (`:126-128`). Tests: `authorize_mcpServerClient_returnsUnauthorizedClient`, `exchangeCode_clientWithoutRefreshTokenGrant_omitsRefreshToken`, `refresh_clientWithoutRefreshTokenGrant_returnsUnauthorizedClient`.
- V-W4 redirect building: `authorizationRedirect` with `UriComponentsBuilder` + `UriUtils`. Test: `authorize_stateWithReservedCharactersAndRedirectWithQuery_encodesLocation`.
- V-W5: ERROR keeps `error_type`, and the stack trace is logged at DEBUG in all four OAuth filters.
- V-W6: `.aj-dev.env` reuse in `start-dev.sh:12-21` and `start-dev.ps1:24-37`; `.gitignore:51` (`git check-ignore` confirms the file is ignored).
- Mcp-resource overlap guard: `OAuth2Properties.java` `requireSeparateFromApi`, with tests `OAuth2PropertiesTests` 3 → 9.
- Log sanitisation: `OAuth2LogValues.java`, with tests `OAuth2LogValuesTests` (4) and `OAuth2LoggingTests` (+2).
- Secret passed to the host and MCP processes only, never to plugins: `start-dev.sh:46-53` (`unset` before the plugins start) and `start-dev.ps1:65-77` (env removed in `finally`, the inherited value restored at exit).

## 2. Plan completion

| Metric | Value |
|---|---|
| Checkbox lines `[x]` / `[ ]` | 121 / 0 |
| Completion | 100% |
| Tests (cycle 2) | host 232/232, MCP 51/51, frontend 87/88 (1 pre-existing) |
| Red gate | 4/4 green, RG-1 hashes OK (re-checked now) |

The verification fixes were not added as plan steps. They are recorded in the work log instead, which is acceptable for verification-phase fixes.

## 3. Standards compliance (fix code)

| Standard | Applies? | Reasoning | Followed? |
|---|---|---|---|
| global/error-handling.md | Yes | Catch-all handlers changed; fail-fast config added | Yes: ERROR with `error_type`, DEBUG for the trace, typed `UNAUTHORIZED_CLIENT`, fail-fast `requireSeparateFromApi` and `Backend` checks |
| global/validation.md | Yes | Config and grant-type checks | Yes: allowlist checks on the client's registered grant types, and server-side validation |
| global/conventions.md | Yes | start-dev secret handling, `.gitignore` | Mostly. The secret lives in the gitignored `.aj-dev.env` and is never printed or committed. The standard only describes `.env` copied from `.env.example`, so this generated local-secret file is a new, undocumented pattern (N2). `start-dev.sh` is LF in both the index and the working tree |
| global/coding-style.md | Yes | All fix code | Yes. One cosmetic issue: the log format is `"=> {}chars"`, missing a space (`JacksonMcpJsonMapper.java:64`, N4) |
| global/commenting.md | Yes | New Javadoc and comments | Yes: brief comments that explain why (`OAuth2LogValues`, `requireSeparateFromApi`, `authorizationRedirect`, the dummy-hash comment), with no change-log comments |
| global/minimal-implementation.md | Yes | New helper `OAuth2LogValues` | Yes: one small package-private helper used by four filters; no speculative code |
| backend/security.md | Yes | Grant-type and authorize changes | Yes: no matcher changes and no `@PreAuthorize`; enforcement lives in the AS filters. The standard has no line yet on grant-type enforcement or refresh-token issuance (N2) |
| backend/java-conventions.md | Yes | Records, Jackson | Yes: validation in record compact constructors; Jackson 3 in the host, Jackson 2 in the MCP server |
| backend/api.md | No | No endpoint shape change | n/a |
| backend/queries.md, models.md, jooq.md, migrations.md | No | No persistence changes | n/a |
| backend/plugin-auth.md | No | No change | n/a |
| mcp-server/conventions.md | Yes | PRM flag, logging, `AjProperties` | Yes: the conventions file already says PRM goes through Spring Security and that tokens and the secret are never logged; payload logging is now at DEBUG |
| plugins/plugin-development.md | Yes (minor) | The plugin process env no longer carries the secret | Yes |
| testing/backend-testing.md | Yes | 25 new host and MCP tests | Yes: `*Tests` classes are package-private and named `action_condition_expectedResult`; integration tests import `TestcontainersConfiguration` + `SecurityMockMvcConfiguration`; no `@Disabled`. `RefreshTokenServiceTests` (concurrency) and `OAuth2LogValuesTests` are plain unit tests of pure logic, which the standard allows |
| frontend/* , testing/frontend-testing.md | No | No frontend change in cycle 1 fixes | n/a |

Summary: 25 standards checked, 14 apply to the fix code, all 14 followed (1 cosmetic nit). Status: **compliant**.

## 4. Documentation and spec alignment

| Artifact | Check | Result |
|---|---|---|
| README.md | `.aj-dev.env` (lines 87, 105-106, 120), secret scope, 401 wording (118) | OK |
| CLAUDE.md | start-dev wiring and `.aj-dev.env` (43), 401 wording, stale architecture note removed | OK. The `core/oauth2` bullet (line 50) does not say that grants are checked against the client's registered grant types (N2) |
| plugins/ai-offer-analysis/README.md | `.aj-dev.env`, 401 wording, "plugin never needs that secret" (62) | OK |
| project/architecture.md | Line 84: secret source and scope | OK. Grant-type enforcement not mentioned (N2) |
| standards/** | Updated in cycle 0 | No line for grant-type enforcement, `.aj-dev.env` or DEBUG-only stack traces (N2) |
| spec.md | Approved deviations annotated (I1 resolved) | **New drift (N1)**: CFG-2 still says the six variables, including the secret, are exported "to both apps", with the secret "generated only if unset". In the code the secret is not exported but passed to the host and MCP processes only, and it is persisted in and reused from `.aj-dev.env`. AS-5/AS-8/AS-9 do not mention grant-type checks or the conditional refresh token. AS-1 lacks the overlap guard, AS-4 the dummy hash, and AS-15 the DEBUG stack-trace exception |
| work-log.md | "Verification Fixes Applied" entry, dated, covers groups A and B and the orchestrator, suite counts, RG-1, remaining items | Adequate. Unlike every earlier entry it has no **Files Modified** or **Standards Applied** lines, and it names the new test classes only as "edge tests" (N3) |
| verification/test-suite-results.md | Cycle 2 counts consistent with work log | OK |

## Issues

| # | Source | Severity | Description | Location | Fixable | Suggestion |
|---|---|---|---|---|---|---|
| W1 | plan_completion | warning | RG-1 git baseline still missing. Both red-test files are untracked, though the hash guard passes | `src/test/java/pl/devstyle/aj/core/oauth2/McpTokenBoundaryTests.java`, `mcp-server/src/test/java/pl/devstyle/aj/mcp/security/McpAuthBoundaryTests.java` | true (user) | Commit both files unchanged as the first commit, then run `git diff --exit-code` |
| W2 | plan_completion | warning | Manual end-to-end check (SC-8) with a real MCP client still not run. It should also confirm separate restarts through `.aj-dev.env` and the client's registered `grant_types` (refresh behaviour) | spec SC-8 | true (manual) | Run `start-dev` and connect Claude Code or MCP Inspector at `:8181`; record `resource` and `grant_types`; restart the MCP server alone |
| N1 | documentation | info | Spec not annotated for the cycle-1 behaviour changes. CFG-2 text now contradicts the scripts (secret not exported, persisted in `.aj-dev.env`). AS-1/4/5/8/9/15 omit: overlap guard, dummy BCrypt, grant-type enforcement + conditional refresh token, DEBUG stack-trace exception | `implementation/spec.md` lines 51-55, 79-89, 90-96, 103-118, 168, 288-296 | true | Add "Verification fix (approved)" notes like the existing deviation notes, or one short "Verification-phase changes" section |
| N2 | standards | info | Candidate standards grew. Not documented anywhere in the standards: (a) every grant and `/oauth2/authorize` require the grant type to be registered for the client, and refresh tokens go only to clients registered with `refresh_token` (security.md, CLAUDE.md:50, architecture.md:28); (b) generated local dev secrets in the gitignored `.aj-dev.env` (global/conventions.md, which only covers `.env`/`.env.example`); (c) OAuth error logs: ERROR with `error_type` plus a sanitised identified `client_id`, stack trace at DEBUG only. This adds to the 5 open cycle-1 candidates (I10) | `.maister/docs/standards/backend/security.md`, `standards/global/conventions.md`, `CLAUDE.md:50`, `project/architecture.md:28` | true | Ask the user, then apply the approved ones with `/maister:standards-update`. A one-line doc mention of (a) needs no approval |
| N3 | documentation | info | The verification-fix work-log entry has no Files Modified or Standards Applied lines and does not name the new test classes | `implementation/work-log.md` (last entry) | true | Add the file list (e.g. `OAuth2LogValues`, `OAuth2Properties`, `ClientAuthenticator`, the four filters, `RestClientConfig`, `AjProperties`, `SecurityConfig`, the two MCP logging classes, `start-dev.*`, `.gitignore`, the docs) and the new tests (`OAuth2LogValuesTests`, `RefreshTokenServiceTests`, the new `OAuth2GrantPolicyTests` / `OAuth2PropertiesTests` / `TokenExchange*Tests` methods) |
| N4 | standards | info | Cosmetic log format: `"=> {}chars"` (no space) | `mcp-server/src/main/java/pl/devstyle/aj/mcp/config/JacksonMcpJsonMapper.java:64` | true | `"=> {} chars"` |
| I4 | plan_completion | info | The "resource differs from the stored resource" branch is still unreachable and untested | `OAuth2TokenFilter.java:118-122`, `RefreshTokenService.java` | false | Keep it as a defensive check |
| I5 | standards | info | Pre-existing redundant `permitAll` for `/.well-known/oauth-authorization-server` | `SecurityConfiguration.java:111` | true | Separate cleanup |
| I8 | plan_completion | info | Pre-existing: 16 frontend lint problems and the stale `extension-points.test.tsx` | `src/main/frontend` | true | Separate cleanup task |

Issue counts: critical 0, warning 2, info 7 (N1–N4, I4, I5, I8). Of these, N1, N2, N3 and N4 are new in cycle 2 and all are documentation or cosmetic.
