# Pragmatic Review: MCP auth refactor (validate-then-exchange)

## TL;DR
- **Status: Appropriate (minor issues).** No critical issues. 2 warnings, 9 info items. The complexity matches the goal: an exercise in a standards-compliant MCP-to-host security hop on a local course showcase.
- The MCP side got simpler. A hand-made ThreadLocal holder, a no-op JWT filter and a custom well-known controller (104 LOC) were replaced by Spring's resource server plus one 60-LOC audience wrapper.
- The host side grew by about 780 LOC across 13 small classes. Each one maps to an RFC requirement or a red test. None is speculative except two unreachable "resource differs" branches, which the spec asked for.
- Warnings are about developer experience, not over-engineering. OAuth 500s are logged without stack traces. The MCP server cannot start on its own without an exported `AJ_OAUTH_CLIENT_SECRET`.
- Leftovers are cheap to fix: an unused `AjProperties.Backend`, Basic auth built two ways in the MCP app, duplicated JSON-response scaffolding in two host filters, and `client_id=null` in introspection and exchange logs.

## Key Decisions
- Overall verdict: introspection, then token exchange on every tool call, with no cache, is the minimum design that satisfies the four red tests. HS256 rules out local JWT verification at the MCP server without sharing the key. "Never forward T1" rules out passthrough. Not caching is the simple choice here, and it is the right one.
- Staying on the hand-written authorization server instead of Spring Authorization Server's built-in introspection and exchange was decided earlier (ADR-001, option A5 rejected). This review takes that as given and does not count it as over-engineering.
- Items the spec or the user decided are reported as info, not warnings: the port move, the resource-mismatch branches, and the copied test helpers.

## Open Questions / Risks
- Each tool call costs two BCrypt checks on the host (introspection plus exchange), roughly 100–200 ms. That is acceptable locally. Revisit only if someone measures a problem; do not add a cache pre-emptively.
- The port move (8080→8180 and so on) was made because of a conflict on one developer's machine. It caused about 54 reference changes in a security PR. `SERVER_PORT` / `AJ_*` env overrides would have avoided it. This was a user decision, so it is not flagged for change, but it is worth confirming it is meant to be permanent for course material.

---

## 1. Complexity Assessment

| Dimension | Value |
|---|---|
| Project scale | Local-only course showcase (Spring Boot 4 modular monolith plus a separate MCP server); this task is a security exercise |
| Host prod code, `core/oauth2` + `core/security` | 2,264 LOC total; about 780 LOC in 13 new files; tracked diff +581/−641 across host and MCP `src/main` |
| MCP prod code | 1,279 LOC total; 6 new files (317 LOC); 3 deleted (104 LOC) |
| New or changed tests | about 3,300 LOC (host and MCP security tests plus one frontend test) |
| New dependencies | 1 (`spring-boot-starter-security-oauth2-resource-server`, BOM-managed) |
| New infrastructure | none (no cache, broker or extra containers) |
| Overall complexity | **Medium**, appropriate for the stated goal |

Why it is appropriate:
- Every new host class is small (21–112 LOC) and has a single job: `ScopePolicy`, `ResourcePolicy`, `ClientAuthenticator`, `OAuth2ErrorWriter`, `TokenExchangeGrantHandler`, `OAuth2IntrospectionFilter`, `McpClientProvisioner`. They are plain classes created in `SecurityConfiguration`. There are no interfaces, factories or strategy hierarchies.
- The typed `OAuth2RequestException` plus one status map replaced three copy-pasted `sendError`/`writeError` switches and the substring-based `determineError`. That is a net simplification.
- `JwtTokenProvider` lost four unused methods (`parseToken`, `getUsernameFromToken`, `getPermissionsFromToken`, `validateToken`) and the silent `permissions → scopes` fallback.
- On the MCP side, `McpJwtFilter` (which validated nothing), `AccessTokenHolder` (ThreadLocal) and `WellKnownController` were deleted in favour of framework features. That is the most pragmatic change in the PR.

Process overhead (info, outside the code): a 649-line spec, 17 task groups and 104 steps for about 1.1k LOC of production change is heavy. The user chose this maister workflow and the red tests needed precise contracts, so it is not counted against the code.

## 2. Key Issues Found

### Critical
None.

### Warning

**W1 – Unexpected OAuth 500s are logged without a stack trace (DX, debuggability)**
- Evidence:
  - `src/main/java/pl/devstyle/aj/core/oauth2/OAuth2TokenFilter.java:87-89`: `log.error("... error_type={}", ..., e.getClass().getName())`
  - the same pattern in `OAuth2IntrospectionFilter.java:65-67`, `OAuth2AuthorizationFilter.java:53-54` and `PublicClientRegistrationFilter` (the old code passed `e`)
- Problem: AS-15 hygiene was applied to the `RuntimeException` catch-all too. A real bug, such as an NPE in a grant handler, now leaves only a class name in the log. Work-log G9 flagged this trade-off for user review, but it was not resolved.
- Impact: on a showcase used for teaching and debugging, the first unexpected failure needs a code change before anyone can diagnose it.
- Fix: keep the INFO/WARN/ERROR lines free of secrets, and add the stack trace at DEBUG. Logging the throwable at ERROR would also work, because the catch-all only fires for programming errors, not for token-parse failures, which `parse()` already turns into `Optional.empty()`.
  ```java
  log.error("OAuth2 token endpoint error | grant_type={} | client_id={} | error_type={}", grantType, clientId, e.getClass().getName());
  log.debug("OAuth2 token endpoint error detail", e);
  ```
- Fixable: yes. Effort: 15 min, 4 files.

**W2 – The MCP server cannot start outside `start-dev` without manual secret setup (DX)**
- Evidence:
  - `mcp-server/src/main/java/pl/devstyle/aj/mcp/config/AjProperties.java:30-33`: the compact constructor throws when `client-secret` is blank.
  - `mcp-server/src/main/resources/application.yml`: `client-secret: ${AJ_OAUTH_CLIENT_SECRET:}`
  - `src/main/resources/application.properties`: `app.oauth2.mcp-client-secret=${AJ_OAUTH_CLIENT_SECRET:}`, so the host silently skips provisioning.
  - `start-dev.sh` / `start-dev.ps1` generate a new random secret on every run.
- Problem: `cd mcp-server && ./mvnw spring-boot:run` (documented in CLAUDE.md) now fails at startup unless the developer exports the same value the running host has. The spec lists this as a risk (opaque 401s on mismatch), and the docs mention it, but the friction remains. It is also inconsistent with `app.jwt.secret`, which has a committed local fallback for exactly this reason.
- Impact: the one-app restart and debug loop (for example running the MCP server from the IDE while the host runs from start-dev) now needs a secret copied by hand, because start-dev's value is random and never printed.
- Options, simplest first:
  1. Keep fail-fast but let start-dev write the generated secret to a gitignored `.env` (or print where to find it), so a second terminal can reuse it.
  2. Accept a committed dev-only default in both apps, the same pattern as `APP_JWT_SECRET`. This is simplest, but it conflicts with the lesson "secrets only from env", so it is the user's call.
- Fixable: yes (option 1 is about 10 lines per script). Effort: 30 min.

### Info

**I1 – `AjProperties.Backend` is bound but never read; the backend URL is still read with `@Value`**
- Evidence: `AjProperties.java:25` declares `record Backend(String url)`. `grep backend()` finds no callers. `RestClientConfig.java:21` uses `@Value("${aj.backend.url}")`.
- Problem: there are two configuration styles for one namespace, and one dead nested record. The work log kept `@Value` only so the `RestClientConfigTests` signature would not change.
- Fix: either `ajRestClient(AjProperties p)` → `p.backend().url()` with a one-line test update, or delete `Backend` from `AjProperties`. Effort: 10 min.

**I2 – HTTP Basic client auth is built two ways in the MCP app**
- Evidence: `SecurityConfig.java:45-46` uses `BasicAuthenticationInterceptor(urlEncode(id), urlEncode(secret))`. `TokenExchangeClient.java:94-97` builds `"Basic " + Base64(...)` by hand. Both have their own `urlEncode` helper.
- Fix: use one approach, for example `RestClient.builder().requestInterceptor(new BasicAuthenticationInterceptor(...))` in `TokenExchangeClient` too. That removes `basicAuthorization()` and the second `urlEncode`. Effort: 10 min, about −10 LOC.

**I3 – Duplicated response scaffolding in `OAuth2TokenFilter` and `OAuth2IntrospectionFilter`**
- Evidence: `OAuth2TokenFilter.java:170-178` `writeTokenResponse` and `OAuth2IntrospectionFilter.java:89-96` `writeResponse` are the same JSON writer with `no-store`/`no-cache` headers. The try/catch blocks for `OAuth2RequestException` and `RuntimeException` are also duplicated.
- Fix: add `writeJson(response, body)` (with the no-store headers) next to `write(...)` in `OAuth2ErrorWriter`, possibly renaming it `OAuth2ResponseWriter`. Effort: 15 min, about −15 LOC. This is optional; two copies is the limit before extracting.

**I4 – Rejection and introspection logs show `client_id=null` for the MCP server**
- Evidence: `OAuth2IntrospectionFilter.java:62-67` and `OAuth2TokenFilter.java:84-89` log `request.getParameter("client_id")`. The MCP server authenticates with Basic, so the value is always null for the one confidential client (noted in work-log G7).
- Impact: the log line least likely to tell you who called is the one you need when the shared secret is mismatched (see W2).
- Fix: log the authenticated client id when available, or "basic" as the method. Effort: 15 min.

**I5 – Unreachable "resource resolves but differs" branches (spec-mandated, m3 user decision)**
- Evidence: `OAuth2TokenFilter.java:115-120` and `RefreshTokenService.consumeAndRotate` (`resource.isPresent() && !equals`). `resolveForAuthorization` can only return `mcpResource`, and the stored resource is always `mcpResource`. Work-log G17 says these are "Untestable by design".
- Note: this conflicts with `minimal-implementation.md` ("Build What You Need"), but it is about 8 LOC that keep RFC 8707 semantics visible to students. Keep it if that teaching value is intended; otherwise drop the `Optional<String> resource` parameter from `consumeAndRotate`.

**I6 – Small leftovers**
- `TokenExchangeResponse.expiresIn` and `.scope` are only used in `toString()`. They can be dropped; `@JsonIgnoreProperties(ignoreUnknown = true)` is already there.
- `ResourcePolicy.canonical` is `public` but only used inside the class (and in tests). It can be package-private.
- `PublicClientRegistrationFilter.ALLOWED_SCOPES = ScopePolicy.SUPPORTED_SCOPES` is an alias constant. Use `SUPPORTED_SCOPES` directly.
- `McpClientProvisioner.SCOPES` repeats `ScopePolicy.SUPPORTED_SCOPES`.
- `CategoryService` inlines `requireScope` + `exchange` + `"Bearer "`, while `ProductService` has a private `backendAuthorization` helper. A single `TokenExchangeClient.bearerFor(caller, scope)` or a static helper in `ToolSecurity` would make both services identical.
- `TokenExchangeClient` keeps a private static Jackson 2 `ObjectMapper` just to read the error code. The Spring-managed mapper could be injected instead.

**I7 – Slash-canonicalisation is implemented twice (host and MCP)**
- `ResourcePolicy.canonical` and `AudienceValidatingIntrospector.canonical` implement the same rule. They are separate deployables, so sharing code is not worth a module. On the MCP side the rule is defensive only, because the host always mints the canonical audience. Acceptable as is.

**I8 – Consent page duplicates the scope rule on the client**
- `OAuth2AuthorizePage.tsx` `grantableScopes()` approximates `ScopePolicy` (without the "re-expand only if READ" nuance). It is cosmetic, and the server stays authoritative (spec Open Questions). Acceptable. Keep a note in the security standard that the client filter is cosmetic.

**I9 – Test suite cost**
- `McpSecurityChainTests.request_whenIntrospectionStalls_returns5xxWithoutInvalidToken` waits for the 10 s read timeout on every MCP test run. Making the timeout configurable only for tests would add complexity, so accept it. If the feedback loop becomes a problem, tag the test as slow.
- `OAuth2TestFlows` copies helpers from the red tests on purpose (RG-1 byte-identity). Consolidate it once the red tests are committed and the gate is lifted.

## 3. Developer Experience

| Area | Assessment |
|---|---|
| Setup | `start-dev` wires everything (good). Manual or one-app start got harder (W2). |
| Debuggability | Worse for unexpected AS failures (W1). Log lines are structured and consistent (`key=value`). `client_id=null` (I4). Fail-fast property validation has clear messages (good, for example "export AJ_OAUTH_CLIENT_SECRET (start-dev does this)"). |
| Pattern consistency | Host: all AS helpers are plain classes built in `SecurityConfiguration`, consistent with the existing filters. MCP: two config styles (I1) and two Basic-auth styles (I2). |
| Feedback loop | MCP suite pays 10 s for the stall test (I9). Host tests need no new infrastructure. |
| Automation intrusiveness | None. start-dev only sets variables that are unset and never prints secrets. |

## 4. Requirements Alignment

- Implementation matches the spec closely. Every class named in the spec exists, and nothing beyond it was added except the approved `WellKnownController` deletion. That deletion was forced by Spring Security 7 auto-registering the protected-resource-metadata filter, and it simplifies the code.
- No requirement inflation in the code: no caching, retries, key rotation, JWKS, DB-backed token stores or configurable TTLs. Following the spec, the exchange TTL (300 s) and HTTP timeouts are constants, not properties.
- The spec itself is detailed (16 AS items, 15 MCP items). Each item traces to a red test, an RFC MUST or an audit finding. The only speculative piece is resource binding beyond one resource (I5), which the user approved.
- The port move is outside the security goal (see Open Questions). It came from a user clarification, not from implementation drift.

## 5. Context Consistency

- No abandoned or half-implemented patterns. `grep` for `AccessTokenHolder`, `TOKEN_KEY`, `JwtForwardingInterceptor` and `McpJwtFilter` returns nothing (work-log G12).
- Dead or unused code: `AjProperties.Backend` (I1), and the `TokenExchangeResponse.expiresIn`/`scope` fields (I6). Every `OAuth2Error` value is referenced except `TEMPORARILY_UNAVAILABLE`, which was not checked in this review. No unused private methods were found in the new classes.
- Inconsistent approaches: Basic auth (I2), config access (I1), and the service-level exchange helper (I6).
- Error handling is consistent on the host (typed exception plus one writer) and on the MCP side (`McpToolException` factories).

## 6. Recommended Simplifications (top 3)

1. **Restore stack traces for unexpected AS errors (W1)** – about 4 lines changed, 15 min.
   - Before: `log.error("... error_type={}", ..., e.getClass().getName());`
   - After: the same line, plus `log.debug("... detail", e);`. Alternatively pass `e` at ERROR for the catch-all only.
2. **Make one-app restarts possible (W2)** – about 10 lines per start-dev script, 30 min.
   - Before: start-dev generates a secret that only its own child processes know.
   - After: start-dev also writes `AJ_OAUTH_CLIENT_SECRET=...` to a gitignored `.env.local` (or reuses it if present), and the docs say `source .env.local` for manual starts. This also stops the host from re-encoding the client's BCrypt hash on every run.
3. **Unify MCP outbound auth and config (I1 + I2 + I6 services)** – about −20 LOC, 30 min.
   - Before: `@Value` plus an unused `AjProperties.Backend`; a hand-built Basic header plus an interceptor; exchange logic inline in `CategoryService` and in a helper in `ProductService`.
   - After: `RestClientConfig` reads `AjProperties.backend().url()`; `TokenExchangeClient` uses `BasicAuthenticationInterceptor`; both services call one `bearerFor(caller, scope)` helper.

## 7. Summary Statistics

| Metric | Current | After top-3 simplifications |
|---|---|---|
| Host new prod files | 13 (about 780 LOC) | 13 (about 765 LOC with I3) |
| MCP prod LOC | 1,279 | about 1,255 |
| Ways to build Basic auth (MCP) | 2 | 1 |
| Config access styles (MCP) | 2 (`@Value` + `AjProperties`) | 1 |
| Dead config records | 1 | 0 |
| Stack traces on unexpected AS errors | none | at DEBUG |
| Steps to start the MCP server alone | export a secret copied by hand from the start-dev process | source one gitignored file |

## 8. Conclusion

The refactor is appropriate for its goal and, on the MCP side, simpler than what it replaces. It does not need structural simplification. Before or after merge, address the two DX warnings (about 45 min together). The info items are optional cleanups (about 1 h in total) and can go in a follow-up. Nothing here blocks the security objective or the red gate.
