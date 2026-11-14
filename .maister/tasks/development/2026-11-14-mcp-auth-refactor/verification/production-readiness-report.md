# Production Readiness Report

**Date**: 2026-11-14
**Path**: uncommitted changes on `feat/mcp-auth-refactor`: host `core/oauth2`, `core/security`, `application.properties`; `mcp-server/` (security, client, config, `application.yml`, `pom.xml`); `start-dev.sh` / `start-dev.ps1`; frontend consent page and `AuthContext`
**Target**: production rigor, rated against the project's real deployment model: local-only, no CI/CD (`.maister/docs/project/tech-stack.md`)
**Status**: With Concerns

## TL;DR
- **GO WITH MITIGATIONS for the stated local-only target.** Nothing blocks a merge: secrets come from env vars, the MCP server fails fast, both hops have timeouts and fail closed, no token is logged (tested), and no DB migration is involved.
- **A real internet-facing deployment is NO-GO.** It would be blocked by the committed `APP_JWT_SECRET` fallback (anyone can forge any token), plain HTTP, no rate limiting on BCrypt-backed endpoints, and in-memory codes and refresh tokens.
- Top mitigation before merge: run the pending manual end-to-end check with a real MCP client against `:8181` and record the `resource` value it sends (spec Success Criterion 8).
- Each tool call now costs 2 host BCrypt checks, 1 user DB lookup and 2 extra loopback HTTP calls. Every MCP HTTP request (including `initialize` and `tools/list`) costs 1 BCrypt check. This is fine locally but unmeasured.
- Rollout breaks every existing session and MCP client config. Ports move 8080/8081/9081 → 8180/8181/9181, all T0/T1 tokens become invalid, and refresh tokens are lost anyway. It is documented, and rollback is code-only.

## Key Decisions
- **Verdict is relative to the local-only target, as instructed.** Items that would block a real deployment are listed separately ("Real-deployment blockers") with severity `warning` in the structured result, not `critical`. Each is marked `real_deployment: blocker`.
- **The committed JWT HMAC fallback is not counted as a blocker for this task.** The spec puts its removal explicitly out of scope, and start-dev deliberately inherits it rather than generating it (user-approved narrowing). It remains the single most important real-deployment blocker, because it makes the new audience binding meaningless to anyone with repo access.
- **Fail-closed behaviour on an introspection outage (500, no `invalid_token`) counts as correct.** A 503 would be nicer, but 500 without a misleading `invalid_token` challenge meets the spec (I-6) and prevents clients from discarding valid tokens.
- **No dependency CVE audit was run.** No audit tooling or network access was used in this read-only check. The only new dependency is `spring-boot-starter-security-oauth2-resource-server`, managed by the Boot 4.0.5 BOM.
- Pre-existing issues outside the diff are reported only where they interact with this change (payload logging on the MCP side, `_token` form param, open DCR, static host health).

## Open Questions / Risks
- **The real MCP client `resource` value is still unverified.** Slash-equality covers `http://localhost:8181/`. Any other form (for example a path suffix) gets `invalid_target` at authorize, and the client cannot connect.
- **Mismatched `AJ_OAUTH_CLIENT_SECRET` when the apps are started separately.** Every MCP request becomes a 500, with a host-side `invalid_client` log line whose `client_id=null`. README / CLAUDE.md document this, but no startup check catches it.
- **BCrypt cost under concurrency is unmeasured.** BCrypt at cost 10 takes roughly 50–100 ms of CPU per verification on typical hardware. Two per tool call is fine for one developer. Several concurrent agents could make the host CPU-bound.
- **Host restart drops all refresh tokens and auth codes (in-memory).** MCP clients must re-authorize after every host restart. This predates the task (FU-2b), but it now applies to every MCP session.

---

## Executive Summary
- **Recommendation**: GO with mitigations (local-only target). Real production deployment: NO-GO.
- **Overall Readiness**: 70%
- **Deployment Risk**: Low (local) / Critical (if exposed publicly as-is)
- **Blockers**: 0 (local target), 5 for a real deployment. **Concerns**: 8. **Recommendations**: 6

## Category Breakdown
| Category | Score | Status |
|----------|-------|--------|
| Configuration | 80% | Good. Env-externalized, fail-fast on the MCP side, masked `toString`, but committed JWT fallback |
| Monitoring | 55% | Concerns. No token logging (tested), but no metrics on the new hops, health ignores the AS dependency, payload logging at INFO (pre-existing) |
| Resilience | 80% | Good. Timeouts on all 3 outbound clients, fail-closed, deliberately no retry, graceful shutdown |
| Performance | 65% | Acceptable locally. 2 BCrypt + 2 HTTP hops per tool call, no introspection cache, no rate limit |
| Security | 70% | Big improvement (audience binding, no passthrough), but committed HMAC key, plain HTTP, `_token` param, open DCR |
| Deployment | 70% | No migrations, code-only rollback. Breaking port/session change documented, manual E2E pending, no CI |

## What Is Ready (evidence)

**Configuration**
- All OAuth wiring is env-driven with local defaults: `APP_OAUTH2_ISSUER`, `AJ_OAUTH_SERVER_URL`, `AJ_BACKEND_URL`, `AJ_MCP_BASE_URL`, `AJ_OAUTH_CLIENT_ID`, `AJ_OAUTH_CLIENT_SECRET`, `APP_CORS_ALLOWED_ORIGINS` (`src/main/resources/application.properties`, `mcp-server/src/main/resources/application.yml`).
- The hard-coded `kuba-*.labs-skillpanel.com` URLs are gone from both apps and from CORS.
- The MCP server fails fast on a blank secret, server URL, client id or base URL, with an actionable message (`mcp-server/.../config/AjProperties.java`).
- The host fails fast on a blank issuer, MCP resource or client id (`core/oauth2/OAuth2Properties.java`).
- Both apps strip one trailing `/`.
- Secrets are masked in `toString()` on both sides (`OAuth2Properties`, `AjProperties.Oauth`).
- start-dev generates a 32-byte base64url client secret only when it is unset, and prints the issuer and base URL, never secrets (`start-dev.sh`, `start-dev.ps1`).
- The host stores the MCP client secret only as a BCrypt hash. It re-hashes only when the secret changes and deletes a stale row when the secret is blank (`McpClientProvisioner`).

**Error handling / fail-closed**
- Introspection outage, stall (10 s read timeout) or rejected MCP credentials → `OAuth2IntrospectionException` → 5xx, never `invalid_token` (`SecurityConfig.opaqueTokenIntrospector`).
- Exchange failures map to typed tool errors: "Insufficient permissions" / "Backend authorization failed". The tool scope check runs before any exchange (`TokenExchangeClient`, `ToolSecurity`).
- The host AS returns typed RFC 6749/8693/7662 errors via one `OAuth2ErrorWriter`. Unexpected exceptions become `server_error` without leaking the exception message.
- Introspection answers `{"active":false}` for unbound callers, foreign audiences and unusable tokens, with `Cache-Control: no-store`.

**Logging**
- MCP `logging.level` DEBUG entries for `pl.devstyle`, `org.springframework.security` and `io.modelcontextprotocol` are removed.
- Host and MCP log lines on the new paths carry only grant type, client_id, user, scope, resource, HTTP status and AS error code. This is proven by `OAuth2LoggingTests` and `McpLoggingTests`.
- DCR logs no longer echo `client_name` or exception messages (log-injection surface reduced).

**Timeouts**
- Introspection: connect 5 s / read 10 s.
- Exchange: connect 5 s / read 10 s.
- Backend API: connect 5 s / read 30 s (unchanged).

**Deployment**
- No Liquibase changes. `deleteByClientId` is a repository method only, so rollback is a code revert.
- MCP `server.shutdown: graceful` with a 30 s phase timeout. The host relies on the Boot default (graceful).
- The test suite is green: host 209/209, MCP 49/49. The frontend has 1 pre-existing failure (`verification/test-suite-results.md`).

## Blockers (Must Fix)

**For the stated local-only target: none.**

### Real-deployment blockers (must be fixed before any non-local deployment)
1. **Committed JWT signing key fallback.**
   - Location: `src/main/resources/application.properties:5` (`app.jwt.secret=${APP_JWT_SECRET:i/WZ…=}`).
   - Issue: The HS256 key is in git. Anyone with repo access can mint T0 (any permissions, including `PLUGIN_MANAGEMENT`), T1 or T2 tokens with the right `iss`/`aud`. This defeats the audience binding.
   - Fix:
     - Remove the default, or fail startup when the value equals the committed one outside a `dev` profile.
     - Inject the key from a secret store.
     - Longer term, move to asymmetric keys + JWKS (FU-6).
2. **No TLS; the issuer is `http://`.**
   - Location: `app.oauth2.issuer`, `aj.oauth.server-url`, `aj.mcp.base-url` defaults.
   - Issue: Basic client credentials, T1 (introspection, exchange) and T2 would cross the network in clear text. The MCP authorization spec requires HTTPS for non-localhost AS/RS endpoints.
   - Fix:
     - Terminate TLS in front of both apps and set `https://` issuer and base URLs.
     - Enable HSTS and HTTP→HTTPS redirects.
3. **No rate limiting on BCrypt-backed and public OAuth endpoints.**
   - Location: `/oauth2/token`, `/oauth2/introspect`, `/oauth2/register`, `/api/auth/login` (`SecurityConfiguration`).
   - Issue: Each request with Basic `aj-mcp-server:<anything>` costs a BCrypt verification, so CPU exhaustion is cheap. Open DCR also allows unbounded `registered_client` rows.
   - Fix:
     - Rate-limit per IP and per client_id at the gateway or with bucket4j.
     - Restrict or replace DCR (FU-1).
4. **Authorization codes and refresh tokens live in in-memory maps.**
   - Location: `AuthorizationCodeService`, `RefreshTokenService`.
   - Issue: They are lost on restart and inconsistent across replicas, so the host cannot scale horizontally or restart without logging out all MCP clients.
   - Fix: Persist them (DB or Redis) with reuse detection (FU-2b).
5. **No error tracking.**
   - Location: both apps.
   - Issue: Introspection outages and exchange failures surface only in local logs.
   - Fix: Add Sentry or an equivalent to both apps.

## Concerns (Should Fix)
1. **The manual end-to-end check with a real MCP client is still pending.**
   - Location: spec Success Criterion 8; `orchestrator-state.yml` notes.
   - Issue: The `resource` value sent by Claude Code or MCP Inspector is unverified. An unexpected form fails at authorize with `invalid_target`.
   - Recommendation: Before merge, run one full flow against the host-served SPA on `:8180` and MCP on `:8181`. Record the `resource` value in the work log.
2. **Introspection runs on every MCP HTTP request, plus exchange on every tool call, all uncached.**
   - Location: `SecurityConfig.opaqueTokenIntrospector`, `TokenExchangeClient`, host `ClientAuthenticator` (BCrypt), `TokenExchangeGrantHandler.currentPermissions` (DB).
   - Issue: Each tool call does 2× BCrypt (cost 10), 1 user lookup and 2 extra HTTP hops. `initialize`, `notifications/initialized` and `tools/list` each pay 1 BCrypt. This is unmeasured.
   - Recommendation:
     - Measure p50/p95 tool-call latency once.
     - For a real deployment, cache introspection results briefly (≤ 30 s, keyed by token hash, never longer than token `exp`), or validate T1 locally via JWKS (FU-6).
     - Alternatively, verify the high-entropy machine secret with a cheaper hash (SHA-256 / HMAC) instead of BCrypt.
3. **Secret or issuer mismatch between the two apps is detected only at request time.**
   - Location: `AjProperties`, `OAuth2Properties`.
   - Issue: The apps start "healthy", but every MCP request returns 500 (rejected introspection) or every tool returns "Backend authorization failed".
   - Recommendation: Make the MCP health indicator (or a startup check) call AS metadata and compare `issuer` with `aj.oauth.server-url`. Optionally make it an authenticated introspection probe with a dummy token, so that `invalid_client` shows up as DOWN.
4. **MCP health does not reflect its critical dependency.**
   - Location: `mcp-server` actuator health (`/actuator/health` on 9181).
   - Issue: Health is UP while the host AS is down, even though every request will then fail with 500. The host `/api/health` is a static `{"status":"UP"}` with no DB check (pre-existing).
   - Recommendation: Add a host-AS reachability indicator to MCP health (readiness group), and add the actuator DB health to the host.
5. **Host-side rejection logs show `client_id=null` for Basic-authenticated callers.**
   - Location: `OAuth2IntrospectionFilter` (warn/error log), `OAuth2TokenFilter` (warn/error log). Both read `request.getParameter("client_id")`.
   - Issue: The most likely production failure (MCP secret mismatch → `invalid_client`) produces log lines that do not name the client. This makes diagnosis harder.
   - Recommendation: Log the Basic-header client id (decoded id only, never the secret) when the form param is absent.
6. **No metrics or tracing on the two new outbound hops.**
   - Location: `TokenExchangeClient` (`RestClient.builder()`), introspection `RestTemplate` (both built by hand, outside Boot's instrumented builders).
   - Issue: No `http.client.requests` timers and no trace propagation MCP→host. The spec defers latency metrics.
   - Recommendation: Build both clients from the Boot-provided `RestClient.Builder` / `RestTemplateBuilder` (keeping the explicit timeouts), so that Micrometer observation applies. Add a counter for exchange failures by AS error code.
7. **MCP logs full JSON-RPC payloads and tool arguments at INFO (pre-existing, outside the diff).**
   - Location: `mcp-server/.../config/JacksonMcpJsonMapper.java:64`, `LoggingJsonSchemaValidator.java:22`.
   - Issue: No tokens are involved (the T1 sits in the transport context and is never serialized by this mapper). But product data and user-supplied tool input go to INFO logs, which is noisy and a data-exposure risk in a real deployment.
   - Recommendation: Drop these to DEBUG or remove them as a follow-up.
8. **Breaking rollout with no zero-downtime path.**
   - Location: ports (`server.port=8180`, MCP 8181/9181), new `iss`/`aud` requirements, `AuthContext.loadStoredAuth` discarding tokens without `aud`.
   - Issue: All SPA sessions are invalidated, MCP client configs must be re-pointed to `http://localhost:8181`, and existing DCR client rows remain as orphans. Plugin `.env` files that override `HOST_BASE_URL` to `:8080` would break (the local `.env` has it only as a comment).
   - Recommendation: Add a short "after pulling this branch" note (re-login, re-add MCP server at :8181, restart plugins), and optionally purge stale DCR clients.

## Recommendations (Nice to Have)
1. **Return 503 instead of 500 on introspection failure.** Map `AuthenticationServiceException` to 503 + `Retry-After` in the MCP entry/failure handler, so that clients and monitors can tell an outage from a bug.
2. **Run the `McpClientProvisioner` provisioning before the web server accepts traffic** (for example via `SmartInitializingSingleton` or a readiness gate), to close the short window where introspection answers `invalid_client` after startup.
3. **Circuit breaker around introspection and exchange.** Fail fast during a host outage instead of each request waiting up to the connect/read timeouts (5 s/10 s). This is nice-to-have at local scale.
4. **Run the BCrypt dummy check for unknown client ids in `ClientAuthenticator`.** This removes the timing oracle on client existence. It is low value because client ids are not secret.
5. **Retire the `_token` request-parameter fallback** (`JwtAuthenticationFilter.extractToken`) for anything other than `POST /oauth2/authorize` (FU-4). It also accepts the token from the query string on `/api/**`, which ends up in access logs and proxies.
6. **Add CI** that runs both Maven suites and the vitest suite. There is no automated gate today (no CI/CD per tech-stack).

## Next Steps
1. Run the manual MCP end-to-end check (Concern 1) and record the `resource` value. This is the only item recommended before merge.
2. Measure one tool call's latency locally to bound the BCrypt + double-hop cost (Concern 2).
3. Add the rollout note for developers pulling the branch (Concern 8).
4. File follow-ups for Concerns 3–7 (health/startup consistency check, `client_id` in rejection logs, instrumented clients, payload logging).
5. Before any non-local deployment, clear all five real-deployment blockers, starting with removing the committed JWT key and adding TLS.
