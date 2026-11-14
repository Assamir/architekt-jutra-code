# Codebase Analysis Report

**Date**: 2026-11-14
**Task**: Module 4 MCP auth refactor: replace MCP-server token passthrough with RFC 7662 introspection plus an `aud` check, and RFC 8693 token exchange at the host AS
**Description**: Module 4 MCP auth refactor. Replace MCP server token passthrough with RFC 7662 introspection plus an `aud` check (MCP) and RFC 8693 delegation token exchange at the hand-written host AS. Add an `aud` gate at host `/api`. Scope follows research package P1 (ADR-001..009); see `analysis/research-context/` (research-report.md, high-level-design.md, decision-log.md).
**Analyzer**: codebase-analyzer skill (3 Explore agents: File Discovery + Code Analysis, Context Discovery, Pattern Mining)

---

## TL;DR
- Every "changed" and "delete" file in the design's Component Change Map exists. None of the "(new)" classes exist yet. The design's line references are accurate. Host: 15 auth classes (~1,580 lines). MCP: ~1,000 lines to touch.
- The docs (CLAUDE.md, standards, architecture.md) already describe the target state. The code still does passthrough. The MCP server's `McpJwtFilter` accepts any bearer. The host turns T1 into `/api` authorities through a `permissions → scopes` fallback in `JwtTokenProvider.parseToken`.
- The design is implementable, but **14 contradictions/corrections** must reach the spec. The most important are: the consent page drops `resource`; the authorize filter never redirects errors; `UNAUTHORIZED_CLIENT` maps to 403, not 400; the refresh grant has no client auth; RFC 7662 needs a space-delimited `scope`; and Spring's introspector URL-encodes Basic credentials.
- Blast radius in tests: on the host only `AuthIntegrationTests` (14) and `OAuth2IntegrationTests` (8) mint real JWTs. All `@WithMock*` classes are unaffected. On the MCP side, all 25 tests touch the changed surface (4 classes rewritten or deleted, 4 change signatures).
- Overall: **Complex**, risk **Medium-High**. Security-critical, cross-cutting across two apps, and the host's per-request issuer silently couples token validity to the `Host` header.

## Key Decisions
- **Configured issuer (`app.oauth2.issuer`) replaces the request-derived `getBaseUrl()` everywhere: login, OAuth tokens, metadata.** With a per-request issuer, enforcing `iss`/`aud` breaks plugin backends (`plugins/server-sdk.ts`, ai-description, ai-offer-analysis) that forward T0 to `http://localhost:8080` under a different Host than the browser used.
- **New host filters stay non-bean `OncePerRequestFilter`s, `new`'d in `SecurityConfiguration`** (template: `OAuth2TokenFilter`). The `@WebMvcTest` slice in `api/ApiLayerTests` would pick up any `@Component` filter.
- **Extend the existing `OAuth2TokenFilter` grant dispatch with a `token-exchange` branch; do not add a separate endpoint.** Dispatch is already a single switch (L62-69). DCR's grant whitelist (`PublicClientRegistrationFilter` L135-147) already stops DCR clients from registering the exchange grant.
- **Client→resource binding stays config-backed (`OAuth2Properties`/`ResourcePolicy`), not a DB column.** `oauth2_registered_client` has no resource or settings column. Grant types round-trip as raw strings, so the token-exchange URN persists without a migration. No new changeset is needed (010 is a gap left by the revert, if one is ever needed).
- **Use a startup provisioner (new pattern: `ApplicationRunner` or `@EventListener(ApplicationReadyEvent)`) for `aj-mcp-server`, not a Liquibase seed.** The secret comes from env and must be BCrypt-encoded at runtime. The upsert must reuse the existing row id.
- **New host code injects the Jackson 3 `tools.jackson` `ObjectMapper`** (per standards and `SecurityConfiguration`). Do not copy the `new com.fasterxml ObjectMapper()` found in the three existing OAuth filters. The MCP side stays on Jackson 2.
- **The `/api` gate leaves the SecurityContext empty on rejection; it does not write a 401.** The existing entry point produces the 401. Writing it in the filter breaks `@WithMock*` tests and the `_token` path.

## Open Questions / Risks
- **Consent UI does not forward `resource`** (`OAuth2AuthorizePage.tsx` L98-105 hidden inputs). The authorize-side `ResourcePolicy` will always take the "missing → default MCP + WARN" path, so the "frontend out of scope" claim and design Example 4 do not hold. Decide one of: (a) add one hidden input from `searchParams`, or (b) validate `resource` at the token endpoint only. MockMvc tests that POST `resource` directly will hide this gap.
- **Boot 4 resource-server starter name** (likely `spring-boot-starter-security-oauth2-resource-server`) is not in the local `.m2`. Confirm it against the 4.0.5 BOM. The Security 7.0.4 jar has only `SpringOpaqueTokenIntrospector` (the Nimbus introspector is gone).
- **Introspection outage semantics.** `SpringOpaqueTokenIntrospector` throws `OAuth2IntrospectionException` (transport) and `BadOpaqueTokenException` (inactive). The entry point must tell them apart: an outage must not return `invalid_token`. The exact status on Spring's `AuthenticationServiceException` path is still to be confirmed.
- **Rollout.** Stored legacy T0 (no `aud`, 24h) gets one 401, then `client.ts` clears it and redirects to login. But `AuthGuard` passes on any stored token, and the consent page's native form POST with a legacy `_token` yields a raw JSON 403. `client.ts`'s redirect covers only `fetch`.
- **Test-context cost.** The host provisioner runs in every `@SpringBootTest`. Exchange and introspection tests need the secret via `@SpringBootTest(properties=...)`, which creates an extra Spring context (there is no `src/test/resources` today). The MCP fail-fast on a missing secret breaks the 5 MCP `@SpringBootTest` classes unless test properties are added.
- **Logging leaks must be removed in the same change.** They are listed under Concerns. The MCP `application.yml` also has DEBUG on `org.springframework.security`, which logs headers.
- **`_token` request param is read on every endpoint** (`JwtAuthenticationFilter.extractToken` L48). The API parse mode must apply to that path too. The exchange request is form-encoded, and its `subject_token` must never be treated as the principal.

---

## Contradictions / corrections to the design (the spec MUST account for these)

| # | Design assumption | Actual code | Required spec correction |
|---|---|---|---|
| C1 | Authorize reads and validates `resource`. Frontend out of scope (Example 4) | `OAuth2AuthorizePage.tsx` L98-105 form posts `_token, client_id, redirect_uri, response_type, scope, state, code_challenge*`, but **no `resource`** | Add a hidden `resource` input (small frontend change), or enforce `resource` only at `/oauth2/token`. Add a test that drives the form's field set |
| C2 | Authorize errors: "redirect error where a valid redirect URI exists" | `OAuth2AuthorizationFilter` never redirects errors. All errors are JSON 400/403/500. `determineError` (L176-184) classifies by **message substring** | Either keep JSON errors (document the deviation) or implement redirect errors. `invalid_target` needs explicit mapping, not substring matching |
| C3 | `unauthorized_client` → 400 and `invalid_client` → 401 + `WWW-Authenticate: Basic` | `sendError` maps `UNAUTHORIZED_CLIENT` → **403**, `INVALID_CLIENT` → 401 **without** `WWW-Authenticate`. The switch is copy-pasted ×3 (Token L239-252, Registration L189-201, Authorization L186-198) | Extract one shared OAuth2 error writer. Fix the status codes. Add the Basic challenge. Check whether existing tests assert 403 |
| C4 | `SecurityConfiguration`: `permitAll POST /oauth2/introspect` | L103 `!uri.startsWith("/api/")` already permits it. Adding the matcher is a no-op | The real work is registering `OAuth2IntrospectionFilter` (`addFilterAfter(…, OAuth2TokenFilter.class)`). Adding the explicit matcher is optional, for readability |
| C5 | Jackson implied uniform | Host: `SecurityConfiguration` uses Jackson 3. `OAuth2TokenFilter`/`AuthorizationFilter`/`RegistrationFilter` + `OAuth2ErrorResponse` use Jackson 2 (`com.fasterxml`, pom L65-68). MCP uses Jackson 2. Boot 4 `RestClient` converters default to Jackson 3 | New host classes use `tools.jackson`. Either migrate `OAuth2ErrorResponse` or keep it consistent with the shared writer. On MCP, parse exchange responses with the Jackson 2 bean or a typed record |
| C6 | `parseApiToken` / `parseAccessToken` modes | Single `parseToken` → `ParsedToken(username, permissions)`, checks signature and exp only, with a `permissions → scopes` fallback (L76-79). `generateTokenWithExpiration` is used by `AuthIntegrationTests:104` | `ParsedToken` gains `aud, iss, clientId, act, exp, typ`. **Remove the fallback.** Signature changes on `generateToken`/`generateOAuth2Token`/`generateTokenWithExpiration` break that test, so update it. Delete the unused helpers (`getUsernameFromToken`, `getPermissionsFromToken`, `validateToken`) |
| C7 | Introspection response "standard" | T1/T2 carry `scopes` as a JSON **list**. `SpringOpaqueTokenIntrospector` reads `scope` (space-delimited), `aud`, `client_id`, `exp`, `iat`, `iss`, `nbf` | Introspection endpoint translates `scopes[]` → `scope` string |
| C8 | `client_secret_basic` "standard" | `SpringOpaqueTokenIntrospector.Builder` **URL-encodes** id and secret before Base64 (verified in bytecode) | `ClientAuthenticator` URL-decodes per RFC 6749 §2.3.1. `TokenExchangeClient` encodes the same way |
| C9 | Refresh: "check client binding" (FU-2a) | `handleRefreshTokenGrant` (L156-181) has **no client authentication and no `client_id`**. `consumeAndRotate` removes the token **before** any check. Rotation resets `createdAt` (sliding 24h) | Validate `client_id` (public) / `ClientAuthenticator` (confidential) **before** removal, or re-insert on mismatch. `RefreshTokenData` gains `clientId`, `resource` |
| C10 | "Exchange by a non-MCP client → rejected" | DCR clients are **confidential by default** (`client_secret_post`, L123-125) with real BCrypt secrets. On the token endpoint, an unknown client **skips** auth entirely (L116-131) | The binding check is the only guard, so test with a confidential DCR client. The unknown-client path must fail closed |
| C11 | `McpClientProvisioner` "upsert" | `DatabaseRegisteredClientRepository.save` upserts by **PK** from `RegisteredClient.getId()`. A fresh id violates the `client_id` unique constraint. `client_name` NOT NULL | Use `findByClientId` → `RegisteredClient.from(existing)`, then re-encode the secret and set `clientName`. `AuthorizationGrantType.TOKEN_EXCHANGE`, `ClientAuthenticationMethod.CLIENT_SECRET_BASIC/POST` are available (SAS 7.0.4 on the host classpath) |
| C12 | Docs need "updates" | Docs already state the target (conventions.md:13-17, security.md:35-37, CLAUDE.md:48,60, architecture.md:53) and cite a nonexistent task dir `2026-04-13-mcp-token-security`. security.md:17 (claims) and :26 (`mcp:read`→READ mapping) **contradict** the design | Rewrite these lines rather than append. Fix the dead source link |
| C13 | Token endpoint advertises `client_secret_basic` | Metadata advertises `[client_secret_post, client_secret_basic, none]`, but Basic is **not implemented** (form param only) | `ClientAuthenticator` makes the advertisement true. Add `introspection_endpoint` + `introspection_endpoint_auth_methods_supported` |
| C14 | `contextExtractor` "stores validated Authentication" | `contextExtractor` is a lambda inside the `webMvcStatelessServerTransport` bean (`AjMcpApplication` L38-50) that receives `ServerRequest`. Handlers run on `boundedElastic` (verified, mcp-core 0.18.1) | Read `SecurityContextHolder` (servlet thread) or `request.servletRequest().getUserPrincipal()` there. `TokenExchangeClient` needs its **own** `RestClient` without the backend status handler |

Additional items not in the change map:
- `app.cors.allowed-origins` hard-codes a public tunnel origin. Make it `${APP_CORS_ALLOWED_ORIGINS:http://localhost:*}` (FU-5).
- `OAuth2MetadataController` duplicates `getBaseUrl` (L54-83 vs `OAuth2TokenFilter` L254-282). Both are replaced by the configured issuer. `OAuth2IntegrationTests.getMetadata` assertions on `issuer` will change from the request-derived `http://localhost` to the configured value.
- Redirect `state` is not URL-encoded (`OAuth2AuthorizationFilter` L111-113). This is low-cost to fix while the authorize redirect code is being changed anyway.

---

## Summary

The host's hand-written authorization server issues three token kinds with one shared HS256 secret and no audience. T0 (SPA login: `sub, permissions`, 24h), T1 (OAuth: `iss` per request, `scopes` list, 15 min) and the refresh token (in-memory) are told apart only by `permissions` vs `scopes`, and a fallback erases even that distinction at `/api`. The MCP server validates nothing and forwards T1 through a never-cleared ThreadLocal. The design's change map matches the code closely. The work is mostly additive (6 new host classes, 4 new MCP classes) plus surgical edits to `OAuth2TokenFilter`, `JwtTokenProvider` and the MCP services. The spec must still absorb the 14 corrections above.

---

## Files Identified

### Primary Files: Host (`src/main/java/pl/devstyle/aj/`)

**core/oauth2/OAuth2TokenFilter.java** (283 lines). Cited by all 3 agents.
- `/oauth2/token`. Grant dispatch L62-69 handles `authorization_code` and `refresh_token`, else `UNSUPPORTED_GRANT_TYPE`. Auth-code L78-154: `client_id` is checked only if provided (L103). Client auth is `client_secret_post` only, and an unknown client skips it (L116-131). PKCE L134-140. **Scope escalation** at L142-147: `grantedScopes = data.scope()` verbatim, and `data.permissions()` is unused. Issuer from `getBaseUrl` (L146). Refresh L156-181 has no client auth. `sendTokenResponse` L216-237 builds JSON with a StringBuilder and hard-codes `expires_in 900`.
- Leaks: L57-59 (full param map at INFO), L152-153 (access and refresh tokens), L234 (token response).
- Change: add the exchange branch, read `resource`, use `ClientAuthenticator` + `ScopePolicy`, configured issuer, refresh binding, remove the logging.

**core/oauth2/OAuth2AuthorizationFilter.java** (199)
- POST `/oauth2/authorize` only. GET is served by the SPA (`SpaForwardController` + `OAuth2AuthorizePage.tsx`). Principal comes from `SecurityContextHolder`, set by `JwtAuthenticationFilter` from `_token` (L92-102). `validateScopes` (L136-147) checks client scopes only, not user permissions. `storeAuthorizationCode` (L106) has no `resource`. L64-66 logs the full param map, **including `_token`**.

**core/oauth2/AuthorizationCodeService.java** (74): `@Service`, in-memory, 600s TTL, one-time remove. `AuthorizationCodeData(clientId, redirectUri, scope, codeChallenge, codeChallengeMethod, username, permissions, createdAt)` needs a `resource` field added.

**core/oauth2/RefreshTokenService.java** (86): `issueToken(username, permissions, scope)` (called with scopes passed as "permissions"). `consumeAndRotate` removes before validation. `RefreshTokenData` needs `clientId` and `resource` fields added.

**core/oauth2/OAuth2MetadataController.java** (84): `/.well-known/oauth-authorization-server` (per-request issuer, no `introspection_endpoint`, grants `[authorization_code, refresh_token]`) and `GET /api/oauth2/client-info` (permitAll).

**core/oauth2/OAuth2Error.java** (35): add `INVALID_TARGET` (and possibly `INVALID_TOKEN` for internal use).

**core/security/JwtTokenProvider.java** (120). Cited by all 3 agents.
- `generateToken`/`generateTokenWithExpiration` (L29-43): `sub, permissions, iat, exp`, with no `iss`/`aud`. `generateOAuth2Token` (L44-58): `iss, sub, scopes, iat, exp` (15 min), with no `aud/client_id/jti/typ`. `parseToken` (L62-83) has the fallback. `@Value` for `app.jwt.secret` / `app.jwt.expiration-ms`.

**core/security/JwtAuthenticationFilter.java** (54): non-bean. Maps scope/permission `p` → `PERMISSION_<p>`. On an invalid token it leaves the context empty. `extractToken` reads the Bearer header, then the `_token` param, on **every** endpoint.

**core/security/SecurityConfiguration.java** (168)
- Constructor wiring L44-55. permitAll L91-103. `mcp:*` alternatives L106-120. Filter chain L134-142 (Jwt → Registration → Authorization → Token). CORS L158-167. `PasswordEncoder` (BCrypt) bean L147-150.

**api/AuthController.java**: `POST /api/auth/login` → `generateToken(name, permissions)` (L48). No `HttpServletRequest`, so the issuer must come from config.

**src/main/resources/application.properties**: needs the `app.oauth2.*` keys added, and CORS origins made env-overridable.

**src/main/frontend/src/pages/OAuth2AuthorizePage.tsx**: consent form (L98-105). See C1.

### Primary Files: MCP (`mcp-server/src/main/java/pl/devstyle/aj/mcp/`)

**AjMcpApplication.java** (80): `TOKEN_KEY="bearer_token"` (L35). `contextExtractor` re-parses the raw `Authorization` header (L38-50). Tools are registered explicitly (L57-79).

**service/ProductService.java** (304) and **service/CategoryService.java** (97): methods take `(String token, …)`, call `setAccessToken` (never cleared), read `ctx.get(TOKEN_KEY)` (Product L70, L184; Category L43), and wrap errors as `apiError("Failed to …: " + msg)`.

**config/RestClientConfig.java** (87): `JwtForwardingInterceptor` (L38, `setBearerAuth` L78-80) is the passthrough. Status handler 400/401/403/404 → `McpToolException`. Logs up to 8 KB of backend body at WARN (L42-43) and INFO per forward (L79).

**config/SecurityConfig.java** (41): permitAll on `/.well-known/**`, `/actuator/health/**`, `/error`. Adds `new McpJwtFilter()` and wires `McpAuthenticationEntryPoint`. `@Value` field injection.

**security/McpJwtFilter.java** (46): any Bearer → `("mcp-user", null, [])`. **Delete.**

**security/AccessTokenHolder.java** (29): static ThreadLocal, never cleared. **Delete.**

**security/McpAuthenticationEntryPoint.java** (25): 401 with `resource_metadata` only. Add `scope` and conditional `error`.

**client/AjApiClient.java** (25): `@HttpExchange` methods. Add an `@RequestHeader(AUTHORIZATION)` parameter.

**exception/McpToolException.java** (51): add `insufficientScope(required)`.

**mcp-server/pom.xml**: add the resource-server starter and `spring-security-test`.

**mcp-server/src/main/resources/application.yml**: external `kuba-*` defaults → localhost. Add `aj.oauth.client-id/secret/introspection-uri/token-uri` and `aj.backend.resource`. Drop the security DEBUG logging.

### Related Files

- **core/oauth2/PublicClientRegistrationFilter.java** (202): unchanged. Its grant whitelist protects the exchange grant. Template for `ALLOWED_SCOPES`/`validateScope` (L32, L181-187), which `ScopePolicy` should centralize. Known NPE at L107 (FU-1, out of scope).
- **core/oauth2/DatabaseRegisteredClientRepository.java** (103), **RegisteredClientEntity.java** (70), **RegisteredClientJpaRepository.java** (11): used by the provisioner and `ClientAuthenticator`. Schema from changelog 009, no change.
- **core/security/CustomUserDetailsService.java** (36): `loadUserByUsername` returns current DB permissions. Reuse it for the exchange grant's scope recomputation.
- **Permission enum + changelog 008**: viewer{READ}, editor{READ,EDIT}, admin{READ,EDIT,PLUGIN_MANAGEMENT}. Defines the `ScopePolicy` intersection.
- **mcp-server/.../controller/WellKnownController.java** (29): PRM. Logic unchanged, defaults change via yml.
- **mcp-server/.../config/JacksonConfig.java** (20): Jackson 2 bean.
- **start-dev.sh / start-dev.ps1**: currently export no env. Must export shared `AJ_OAUTH_CLIENT_ID/SECRET`, `APP_JWT_SECRET` and URL variables to both apps.
- **plugins/server-sdk.ts** (L39, L50-68, L87-137), **ai-description generate.ts:41**, **ai-offer-analysis analyze.ts:107-180**: server-to-server T0 forwarding. Safe only with a configured issuer.
- **frontend `api/client.ts`** (L112-149), **AuthContext.tsx** (L14-30, L51-69), **PluginMessageHandler.ts** (L61-72, L189-198): T0 consumers. They work unchanged, apart from a forced re-login on deploy.

---

## Current Functionality

### Key Components/Functions
- **JwtTokenProvider**: sole JWT minter and parser in production (no other module mints tokens or makes outbound HTTP).
- **OAuth2TokenFilter / OAuth2AuthorizationFilter / PublicClientRegistrationFilter**: hand-written AS endpoints as servlet filters.
- **AuthorizationCodeService / RefreshTokenService**: in-memory stores with `@Scheduled` cleanup. Contents are lost on restart.
- **McpJwtFilter + AccessTokenHolder + JwtForwardingInterceptor**: the passthrough chain.

### Data Flow (today)
- **(A) SPA**: login → `AuthController` → T0 `{sub, permissions, iat, exp 24h}` → localStorage → `client.ts` Bearer → `JwtAuthenticationFilter` → `PERMISSION_*` matchers. Plugins forward T0 (iframe `getToken`, server-sdk).
- **(B) MCP**: Claude → 401 `resource_metadata` → PRM → AS metadata → DCR (confidential by default) → browser GET `/oauth2/authorize` (SPA consent) → native POST with `_token=T0` (no `resource`) → code → `/oauth2/token` → T1 (`iss` per request, `scopes` list) + refresh token, **both logged** → Claude POST `/` Bearer T1 → `McpJwtFilter` accepts it → `contextExtractor` copies the raw T1 → handler on `boundedElastic` → ThreadLocal → `JwtForwardingInterceptor` → host `/api` → `parseToken` scopes fallback → `PERMISSION_mcp:read`.

### Failure points (security)
- Scope escalation: a viewer can obtain `mcp:edit`, because neither the authorize nor the token path intersects with user permissions.
- T1 is accepted at `/api` (no `aud`, fallback). Any bearer is accepted at the MCP server.
- Tokens, codes, verifiers, secrets and `_token` are written to INFO logs.

---

## Dependencies

### Imports (what the change depends on)
- jjwt 0.12.6 (HS256) on the host. `.audience().add()` is available for `aud`.
- spring-security-oauth2-authorization-server (SAS 7.0.4, types only: `RegisteredClient`, `AuthorizationGrantType.TOKEN_EXCHANGE`, `ClientAuthenticationMethod`). It also pulls in oauth2-resource-server + jose on the host.
- MCP: `mcp-spring-webmvc` 0.18.1, Spring Security 7.0.4, **new** resource-server starter (`SpringOpaqueTokenIntrospector`).
- Spring Boot 4.0.5, Java 25 (both apps).

### Consumers (what depends on the changed code)
- **SPA**: `AuthContext.tsx`, `api/client.ts`, `footprints.ts` CSV export, `OAuth2AuthorizePage.tsx`, `PluginMessageHandler.ts`, `plugin-sdk/host-app.ts`.
- **Plugin backends**: `plugins/server-sdk.ts`, ai-description (`generate.ts`), ai-offer-analysis (`domain.ts:478-488`, `analyze.ts`). These forward T0 server-side, which creates the issuer coupling risk.
- **Plugin browser code**: shipping `domain.ts` (decodes `permissions`), ai-description `product-tab.tsx`, warehouse, box-size. Unaffected.
- **MCP server**: the whole outbound path (breaks by design).
- **Tests**: see the next section.

**Consumer Count**: ~14 production files across 3 codebases (host frontend, plugins, MCP).
**Impact Scope**: High. The token format changes for every host consumer. Most consumers keep working only because the change is additive (`iss`/`aud` added, `sub`/`permissions` unchanged) and the issuer becomes configured.

---

## Test Coverage

### Test Files
- **Host, affected**:
  - `AuthIntegrationTests` (14 tests). Real login, does not import `SecurityMockMvcConfiguration`. `protectedEndpoint_expiredToken` (L102-111) calls `generateTokenWithExpiration` and breaks at compile time if the signature changes. `jwtSignedWithWrongKey` (L148-163) hand-builds tokens with jjwt.
  - `OAuth2IntegrationTests` (8 tests): metadata, DCR, the full auth-code cycle (L135-177), PKCE, invalid redirect, refresh (`tokenExchange_withRefreshToken`, a misnamed refresh test, not RFC 8693), invalid code. Uses plain Java `assert` (L162/164).
- **Host, unaffected**: every `@WithMock*` class (SecurityTestHelper, Integration, Category*, Product*, Plugin* ×5, FootprintCsvExport* ×2, FootprintController* ×2) and the no-auth classes (AjApplication, User, PluginDatabase, PluginObjectEntityBinding, pricing, footprint internal/audit). `api/ApiLayerTests` (`@WebMvcTest` slice) is affected only if a new filter is made a `@Component`.
- **MCP** (25 tests, no Docker, no host needed):
  - Delete or rewrite: `AccessTokenHolderTests` (1, delete), `RestClientConfigTests` (1, pins passthrough), `McpJwtFilterTests` (2, pins no-validation).
  - Signature changes: `ErrorMappingTests` (5), `ToolCallIntegrationTests` (5, uses `TOKEN_KEY`; its template at L50 shows how to pass a context), `ToolSchemaValidationTests` (6, L102 and L139).
  - Need test properties for the new required config: `AjMcpApplicationTests` (1), `ConfigurationTests` (2), `JacksonConfigTests` (2).
- **Frontend / plugins**: `auth.test.tsx`, `plugin-sdk-auth.test.ts` and the plugin Jest tests (unsigned `alg:none` tokens) are unaffected. `OAuth2AuthorizePage.tsx` has **no tests**.

### Coverage Assessment
- **Test count**: 22 host tests on real JWTs, 25 MCP tests, 0 on the consent page.
- **Gaps (no tests at all today)**: exchange grant, introspection endpoint, OAuth token rejected at `/api`, missing/wrong `aud` or `iss` → 401, configured vs X-Forwarded issuer, confidential-client secret check (Basic and post), refresh by a different client, viewer → `mcp:edit` escalation, MCP HTTP-chain (introspect → exchange → call), "backend 401 not retried", consent form field set.
- **Tooling**: neither app has `MockRestServiceServer`, WireMock or `src/test/resources`. `MockRestServiceServer.bindTo(RestClient.Builder)` is the natural fit for the MCP chain tests.
- **Host test command**: `./mvnw test -Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm -Dtest=AuthIntegrationTests+OAuth2IntegrationTests` (Docker required).

---

## Coding Patterns

### Naming Conventions
- **Packages**: host AS in `pl.devstyle.aj.core.oauth2`, host security in `core.security`. MCP code in `pl.devstyle.aj.mcp.{security,client,config,service,exception,controller}`.
- **Classes**: `*Filter` (`OncePerRequestFilter`, `private static final X_ENDPOINT`, explicit constructors), `*Service` (`@Service`, `@RequiredArgsConstructor`), records for data (`AuthorizationCodeData`, `ParsedToken`, proposed `OAuth2Properties`).
- **Properties**: host `app.<module>.<kebab>` with `${UPPER_SNAKE:default}` in `application.properties`. MCP `aj.*` with `AJ_*` in `application.yml`.
- **Tests**: `*Tests`, package-private, same package, `action_condition_expectedResult`, MockMvc + `jsonPath` / AssertJ, `createAndSave*` helpers.

### Architecture Patterns
- **Filters**: exact `URI.equals` + POST check, otherwise `filterChain.doFilter`. Catch-all `Exception` → `SERVER_ERROR` if `!response.isCommitted()`. Form parsing via `request.getParameter`. Registered with `addFilterAfter` in `SecurityConfiguration`.
- **Authorization**: only `requestMatchers` in `SecurityConfiguration`, no `@PreAuthorize`.
- **Config**: `@Value` everywhere. **No** `@ConfigurationProperties`, `ApplicationRunner`, `CommandLineRunner` or `ApplicationReadyEvent` exists in either app, so these are new patterns. Use a record `OAuth2Properties` + `@EnableConfigurationProperties` on a config class. `AjApplication` has no `@ConfigurationPropertiesScan`. `@ConditionalOnProperty` precedent: footprint `spi/stub`.
- **Seeding**: Liquibase `dev` context, active in tests too. `013-*` shows the idempotent `ON CONFLICT DO NOTHING` + rollback pattern. Not suitable for an env-provided secret.
- **MCP tools**: `SyncToolSpecification.builder().callHandler((ctx, req) -> …)` → `CallToolResult(text + structuredContent)`, `aj_` prefix, `McpToolException` factories, exceptions propagate to the SDK.
- **Logging**: `@Slf4j`, `"Event | key=value | reason=..."` style.
- **State**: stateless JWT, in-memory code and refresh stores.

### Anti-patterns present (do not copy)
- Token and secret logging, inline client auth duplicated ×2, error writer ×3, `getBaseUrl` ×2.
- StringBuilder JSON, Jackson 2 in the host, substring-matched error classification, unescaped `state`.
- Trust-and-forward MCP, uncleared ThreadLocal, `@Value` field injection in MCP, hard-coded scope lists ×4.
- Plain `assert` in tests.

---

## Complexity Assessment

| Factor | Value | Level |
|--------|-------|-------|
| File count | ~13 host + ~13 MCP changed/deleted, 10 new classes, 4-6 config/script files, ~8 docs | High |
| File size | Largest touched: `ProductService` 304, `OAuth2TokenFilter` 283 | Medium |
| Dependencies | jjwt, SAS types, Spring Security 7, new resource-server starter, mcp-sdk 0.18.1, two Jackson versions | High |
| Consumers | ~14 production files across SPA, plugins and MCP | High |
| Test coverage | 22 host + 25 MCP tests touch the area, but none cover the target behaviours (exchange, introspection, `aud`) | Low (for target behaviour) |

### Overall: Complex
The task spans two applications and three token types. It changes a security boundary that every frontend and plugin consumer passes through, and adds new framework patterns (`@ConfigurationProperties`, startup runner, opaque-token resource server). Each step is well understood and the design is precise, but the surface is wide and most target behaviours have no test today.

---

## Key Findings

### Strengths
- The design's change map is accurate (paths and line numbers verified). No hidden producers: `JwtTokenProvider` is the sole minter, and the host makes no outbound HTTP.
- The DB schema already supports the exchange grant and the confidential client: raw string arrays, so no migration is needed.
- DCR's grant whitelist already prevents self-registration of the exchange grant.
- `@WithMock*` isolation means most host tests are immune to token-format changes.
- `CustomUserDetailsService` gives current permissions for scope recomputation at exchange time.
- A clear filter template and a clear MCP tool/test template (`ToolCallIntegrationTests`) exist.

### Concerns
- Per-request issuer (Host / X-Forwarded-*), which the configured issuer must replace consistently. Login, OAuth issuance, metadata and MCP `aj.oauth.server-url` must be byte-identical.
- Secret/token logging at host L57-59, L152-153, L234 (TokenFilter) and L64-66 (AuthorizationFilter, includes `_token`), MCP RestClientConfig L42-43 / L79, and the MCP security DEBUG level.
- Refresh path has no client authentication, and its remove-before-validate order complicates FU-2a.
- Status-code drift (403 vs 400) and missing `WWW-Authenticate: Basic`.
- The consent UI drops `resource` (C1).
- `ex.getMessage()` wrapping in MCP services must not leak tokens from exchange errors.

### Opportunities
- Extract one `OAuth2ErrorWriter` and one configured-issuer source to remove the ×3 and ×2 duplication.
- Centralize scope lists in `ScopePolicy`, now hard-coded in Registration L32, AuthorizationFilter, TokenFilter L143-145/L172-174, the Metadata controller and MCP WellKnown.
- Replace StringBuilder JSON with a record + `tools.jackson` (also removes the hard-coded `expires_in`).
- Delete the unused `JwtTokenProvider` helpers. Rename the misnamed `tokenExchange_withRefreshToken` test.
- FU-5: make CORS origins env-overridable.

---

## Impact Assessment

- **Primary changes**:
  - Host: `OAuth2TokenFilter`, `OAuth2AuthorizationFilter`, `AuthorizationCodeService`, `RefreshTokenService`, `OAuth2MetadataController`, `OAuth2Error`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `SecurityConfiguration`, `AuthController` (issuer source), `application.properties`.
  - Host, new: `OAuth2Properties`, `ResourcePolicy`, `ScopePolicy`, `ClientAuthenticator`, `TokenExchangeGrantHandler`, `OAuth2IntrospectionFilter`, `McpClientProvisioner`, optional shared error writer.
  - MCP: `AjMcpApplication`, `ProductService`, `CategoryService`, `AjApiClient`, `RestClientConfig`, `SecurityConfig`, `McpAuthenticationEntryPoint`, `McpToolException`, `application.yml`, `pom.xml`.
  - MCP, new: `AudienceValidatingIntrospector`, `McpAccessDeniedHandler`, `ToolSecurity`, `TokenExchangeClient`.
  - MCP, delete: `McpJwtFilter`, `AccessTokenHolder`.
- **Related changes**: `OAuth2AuthorizePage.tsx` (C1), `start-dev.sh`/`.ps1`, possibly `OAuth2ErrorResponse` (Jackson).
- **Docs**:
  - CLAUDE.md:47-48, :56, :60, :79-81.
  - Standards: `standards/mcp-server/conventions.md:13-17`, `standards/backend/security.md:15-17, :26, :35-37`, `standards/backend/plugin-auth.md` (issuer caveat).
  - Project docs: `project/architecture.md:27, :45, :53, :58-64`, `project/tech-stack.md:25-26` (new dependency), `INDEX.md:77, :80, :116`.
  - Plugin docs: `plugins/CLAUDE.md:406, :413`, `plugins/ai-offer-analysis/README.md:23-25, :38, :73-81`, `plugins/sdk.ts:40`.
  - Stale comments: `McpJwtFilter.java:69-73` (deleted with the file), `AccessTokenHolder.java:5-8` (deleted with the file).
- **Test updates**:
  - Update `AuthIntegrationTests` and `OAuth2IntegrationTests`.
  - Add host classes for exchange, introspection, `/api` audience gate and refresh binding. Follow the IntegrationTests/ValidationTests split, 2-8 tests per group.
  - Rewrite or delete 3 MCP test classes, adapt 3 for signatures, add test properties to the 5 `@SpringBootTest` classes, and add a `MockRestServiceServer` chain test.

### Risk Level: Medium-High
- Every authenticated request on the host passes through the changed parse path.
- Issuer misconfiguration fails closed everywhere: plugins get 401, the MCP gets 401 on every tool call.
- The consent form gap (C1) and the refresh remove-before-check order are easy to get wrong silently.
- The MCP server's test suite is pinned to the passthrough behaviour.
- Mitigations: the change is additive to claims, the issuer is configured, the `/api` gate leaves the context empty instead of writing a 401, and the design has a precise error matrix.

---

## Recommendations

**Modifying existing security code. Implementation strategy (suggested order):**

1. **Foundation (host).**
   - Add `OAuth2Properties` (record, `@EnableConfigurationProperties`) and replace both `getBaseUrl` copies with the configured issuer.
   - Extend `JwtTokenProvider`: `iss`/`aud` on T0; `aud, client_id, jti, typ, act` on OAuth tokens; two parse modes; remove the `permissions→scopes` fallback.
   - Switch `JwtAuthenticationFilter` to API mode, including the `_token` path.
   - Fix `AuthIntegrationTests` first, so it becomes the regression net for the `/api` gate.
2. **AS hardening.**
   - Add `ClientAuthenticator` (Basic with URL-decoding, plus post, BCrypt). An unknown client fails.
   - Add `ScopePolicy` (hierarchy and intersection with current permissions; this fixes the viewer → `mcp:edit` escalation).
   - Add `ResourcePolicy` (config-backed binding), `INVALID_TARGET`, `resource` on code and refresh data, and the refresh client check before removal.
   - Extract the shared error writer with corrected statuses and the Basic challenge.
   - Remove all secret logging.
3. **New endpoints.**
   - Introspection filter: translate `scopes[]` → `scope`; return `active:false` with no detail.
   - Exchange branch: `requested_token_type`, `subject_token_type`, TTL ≤ min(300s, T1 exp), `act.sub`.
   - Metadata additions. Idempotent provisioner upsert via `findByClientId` + `RegisteredClient.from`, skipped with a WARN when the secret is blank.
4. **MCP.**
   - Resource-server starter and the `AudienceValidatingIntrospector` wrapper.
   - Entry point that distinguishes an outage from `invalid_token`; denied handler.
   - `contextExtractor` stores the `Authentication`. `ToolSecurity`. `TokenExchangeClient` on its own `RestClient`.
   - `AjApiClient` takes an explicit header. Delete the passthrough classes.
   - yml localhost defaults and fail-fast on a missing secret, with test properties.
5. **Wiring and docs.** `start-dev` env export for both apps, the C1 consent-page decision, then the doc rewrites listed above. Resolve the contradictions; do not append.

**Backward compatibility.**
- Legacy T0s fail once, then a forced re-login (acceptable per design).
- MCP tokens and refresh tokens are in-memory and already lost on restart.
- The `sub`/`permissions` claims stay, so SPA and plugin decoders keep working.

**Testing requirements.**
- Host: one integration class per new capability (exchange, introspection, audience gate, refresh binding, scope intersection). Use a **confidential DCR client** for the "non-MCP client exchange" case.
- Add one assertion that `OAuth2IntegrationTests` metadata includes `introspection_endpoint` and the exchange grant.
- MCP: unit tests for the introspector wrapper and entry point. A `MockRestServiceServer` chain test asserting outbound `Authorization` == T2 ≠ T1, and that a backend 401 is not retried.
- Run the host with `-Dskip.jooq.generation=true -Dskip.npm -Dskip.installnodenpm`.

---

## Next Steps

1. Invoke **gap-analyzer** with this report and `research-context/high-level-design.md`. Feed it the 14-item contradictions table as mandatory deltas.
2. Resolve with the user before the spec is written:
   - C1 (consent `resource` forwarding: frontend change vs token-only validation).
   - C2 (keep JSON authorize errors vs implement redirect errors).
   - The exact Boot 4 resource-server starter artifact.
3. The specification must pin:
   - Status codes per error.
   - The introspection response shape (`scope` string).
   - Claim set per token.
   - Configuration keys and their must-equal pairs across the two apps.
