# Gap Analysis: MCP auth refactor (validate-then-exchange, P1)

## TL;DR
- Today: the MCP server accepts any bearer token and forwards it to the host. The host accepts any HS256 token at `/api`, because tokens carry no audience and a fallback turns `scopes` into permissions. A viewer can also get `mcp:edit`. P1 (ADR-001..009) plus the Phase 1 clarifications close all of this. The 14 corrections in the codebase analysis (C1–C14) are mandatory spec deltas.
- Moving the ports to 8180/8181/9181 adds about 48 functional references plus 14 test-fixture references in 19 tracked files outside the course directories. The host also needs a new `server.port` line, because the 8080 default is implicit today.
- Two **new critical** findings. (1) The JS `URL` API normalises `http://localhost:8181` to `http://localhost:8181/`, so MCP TS-SDK clients (Claude Code, MCP Inspector) will probably send a `resource` with a trailing slash. Exact matching then turns the happy path into `invalid_target`. (2) `/oauth2/authorize` accepts any token that passes the API gate as the user's session, including T2. Only an accident of `ScopePolicy`'s mapping stops T2 from being laundered into T1 plus a 24h refresh token.
- Risk: **high** (security boundary, two apps, port move, MCP test suite pinned to passthrough). Effort: **high**.

## Key Decisions
- Characteristics: `has_reproducible_defect` = true (viewer→`mcp:edit` escalation and T1 replay at `/api` both reproduce today, so they become red-first regression tests). `modifies_existing_code` = true. `creates_new_entities` = true (new endpoints, a new grant, about 10 new classes, a provisioned client row). `involves_data_operations` = true (registered client upsert, in-memory code and refresh-token records). `ui_heavy` = false: the only UI change is one hidden `<input>`.
- Change type: **modificative**. Compatibility: **moderate**. MCP tool contracts are strict (names, schemas and results stay identical). Token formats break on purpose: legacy T0, passthrough T1 and old ports.
- Port-change blast radius already resets most sessions. localStorage is per origin, so moving the SPA from `:8080` to `:8180` drops every stored T0 on local machines. The "stale T0 at consent" risk therefore applies only to tunnel or same-origin deployments.
- The Boot 4.0.5 BOM in the local `.m2` confirms the starter artifact is `spring-boot-starter-security-oauth2-resource-server` (a `-test` companion exists too). The jar itself is not in `.m2` yet, so the first MCP build needs network access.
- Jackson 2 stays on the host classpath whatever is decided: `jjwt-jackson` 0.12.6 needs it at runtime. The Jackson question is about code consistency only, not about dropping the dependency.

## Open Questions / Risks
- **Trailing-slash `resource` mismatch** (Critical decision C-1). Confidence: high on JS `URL` semantics, medium that Claude's SDK version sends `resource.href` taken from PRM.
- **T2 accepted as the user session at `/oauth2/authorize`** (Critical decision C-2).
- **`aj-mcp-server` row survives a blank secret.** ADR-008 says introspection and exchange answer `invalid_client` when the env var is missing. A row provisioned by an earlier `start-dev` run still sits in the persistent Postgres volume with a valid hash, so that claim is false today (I-4).
- **Existing tests rely on omitted parameters.** `OAuth2IntegrationTests` authorises without `scope` and refreshes without `client_id`, so P1's `invalid_scope` and FU-2a rules break it (I-2, I-3).
- **Per-run generated `APP_JWT_SECRET`** in `start-dev` logs every SPA user out on each restart. Starting the host via `start-dev` but the MCP server by hand (or the other way round) makes the client secrets differ, which fails closed with opaque 401s. Both must be documented.
- **Vite dev proxy forwards only `/api`.** In `npm run dev` mode (5173), the consent page's native POST to `/oauth2/authorize` cannot reach the host. This predates the task and is not addressed by it. The manual end-to-end test must use the host-served SPA on `:8180`.
- **Introspection outage status.** Spring Security 7 rethrows `AuthenticationServiceException` by default (expected to surface as 500), but this is unverified for 7.0.4 (I-6).
- **`aud` claim shape.** jjwt `.audience().add()` serialises an array, while Spring's introspector and the host gate must accept either array or string. The spec must pin "array, contains-match".
- **`resource=""` from the consent form.** The hidden input must render only when the parameter is present (like `state`), and the host must treat a blank value as missing, not as unknown.

---

## Summary
- **Risk Level**: High (prior phases: medium-high; widened by the port move and the two new critical findings)
- **Estimated Effort**: High (about 13 host + 13 MCP files changed, about 10 new classes, 2 deletions, 19 port-reference files, about 10 docs, 4-6 new host test classes, MCP test suite rewrite)
- **Detected Characteristics**: has_reproducible_defect, modifies_existing_code, creates_new_entities, involves_data_operations

## Task Characteristics
- Has reproducible defect: **yes**. Security defects, not crashes: scope escalation, T1 replay at `/api`, an MCP server that accepts any bearer, and token logging.
- Modifies existing code: **yes**
- Creates new entities: **yes**. New endpoint, grant, classes and provisioned client. No new DB table or column.
- Involves data operations: **yes**. Client upsert, plus in-memory auth-code and refresh-token records gaining fields.
- UI heavy: **no**. One hidden input on `OAuth2AuthorizePage.tsx`, plus optionally a one-line `AuthContext` check (I-5).

---

## Gaps Identified (current vs desired, per component)

### 1. Host authorization server (`core/oauth2`)
| Area | Current (evidence) | Desired (P1 + clarifications) | Gap |
|---|---|---|---|
| Issuer | `getBaseUrl(request)` duplicated in `OAuth2TokenFilter` L254-282 and `OAuth2MetadataController` L54-83 | `app.oauth2.issuer` (`APP_OAUTH2_ISSUER`, default `http://localhost:8180`) via an `OAuth2Properties` record | New config record (first `@ConfigurationProperties` in the app); delete both `getBaseUrl` copies |
| Grants | `authorization_code`, `refresh_token` (switch L62-69) | + `urn:ietf:params:oauth:grant-type:token-exchange` (`TokenExchangeGrantHandler`) | Missing feature |
| Introspection | none | `POST /oauth2/introspect` (`OAuth2IntrospectionFilter`), client auth required, translates `scopes[]` → `scope` string (C7), `{active:false}` with no detail | Missing feature |
| Client auth | Inline `client_secret` form field only. An unknown client skips auth (L116-131). Basic is advertised but not implemented (C13) | `ClientAuthenticator`: Basic (URL-decoded, C8) + post, both at once → `invalid_request`, unknown client → fail closed (C10) | Incomplete |
| Scope policy | `grantedScopes = data.scope()` verbatim (L142-147). `permissions` unused → viewer gets `mcp:edit` | `ScopePolicy`: expand → ∩ permission scopes → ∩ client scopes → drop unknown, empty → `invalid_scope`. Applied at the auth-code and exchange grants | Defect + missing |
| Resource / audience | `resource` ignored everywhere | `ResourcePolicy`: missing → MCP default + WARN, unknown/host-API on code/refresh → `invalid_target`, typed error (no `determineError` substring) | Missing |
| Auth code data | `AuthorizationCodeData` has no `resource` | + `resource` | Incomplete |
| Refresh | No client auth, no `client_id`. `consumeAndRotate` removes before any check (C9) | `RefreshTokenData` + `clientId`, `resource`. Check the client **before** removal. `invalid_grant` on mismatch | Incomplete (I-3) |
| Errors | `sendError` switch ×3. `UNAUTHORIZED_CLIENT` → 403, `INVALID_CLIENT` without `WWW-Authenticate` (C3). Authorize errors classified by message substring (C2) | One shared writer, correct statuses, `WWW-Authenticate: Basic` on 401, `INVALID_TARGET`. Authorize errors stay JSON with typed errors (clarification) | Behaviour change |
| Metadata | Per-request issuer, grants `[authorization_code, refresh_token]`, no introspection | Configured issuer, + token-exchange grant, `introspection_endpoint`, `introspection_endpoint_auth_methods_supported` | Incomplete |
| Provisioning | No confidential client exists | `McpClientProvisioner` (`ApplicationRunner`): `findByClientId` → `RegisteredClient.from(existing)` upsert (C11), BCrypt, skip + WARN on a blank secret | Missing (I-4 for the blank-secret path) |
| Logging | Full param map at INFO (Token L57-59, Authorize L64-66 including `_token`), tokens at L152-153, response L234 | None (FU-3). Log-capture test | Defect |
| JSON | StringBuilder JSON, hard-coded `expires_in 900`, `new com.fasterxml ObjectMapper()` ×3 | Records + Jackson 3 in new code (I-7 for touched code) | Incomplete |
| `state` | Not URL-encoded (Authorize L111-113) | Optional same-touch fix (FU-11 lists it as out of scope) | Cosmetic, no decision |

### 2. Host security / API gate (`core/security`)
| Area | Current | Desired | Gap |
|---|---|---|---|
| T0 claims | `sub, permissions, iat, exp` | + `iss` (configured), `aud = ${issuer}/api` | Incomplete |
| OAuth token claims | `iss` (per request), `sub, scopes, iat, exp` | + `aud`, `client_id`, `jti`, header `typ: at+jwt`, `act` on T2 | Incomplete |
| Parse | One `parseToken` with a `permissions → scopes` fallback (L76-79) | API mode (iss + `aud` = host API, used by `JwtAuthenticationFilter` on the header **and** the `_token` path) and AS mode (returns aud/client_id/act/exp). Fallback removed (C6) | Behaviour change |
| Gate failure | Invalid token → empty context → existing entry point 401 | Same mechanism. Do not write the 401 in the filter (keeps `@WithMock*` and `_token` working) | Preserve |
| Consent principal | Any token the filter accepts becomes the consent user (Authorize L92-102) | Not specified by P1. **Needs a decision (C-2)** | New gap |
| `SecurityConfiguration` | Filter chain Jwt → Registration → Authorization → Token | + `addFilterAfter(OAuth2IntrospectionFilter, OAuth2TokenFilter)`. Matchers unchanged (C4) | Small |
| CORS | `http://localhost:*,https://kuba-app.labs-skillpanel.com` | `${APP_CORS_ALLOWED_ORIGINS:http://localhost:*}` | FU-5 |
| Port | No `server.port` (implicit 8080) | `server.port=8180` | New line |

### 3. MCP server (`mcp-server/`)
| Area | Current | Desired | Gap |
|---|---|---|---|
| Inbound auth | `McpJwtFilter` accepts any bearer as `mcp-user` | Resource-server `opaqueToken` + `AudienceValidatingIntrospector` (Spring introspector, Basic, `aud` ∋ `aj.mcp.base-url`, `scope` → `SCOPE_mcp:*`), `/` requires an `mcp:*` scope | Missing |
| Challenges | 401 with `resource_metadata` only | + `scope="mcp:read mcp:edit"`, `error="invalid_token"` only when a token was sent. 403 `insufficient_scope` (`McpAccessDeniedHandler`). Outage → 5xx without `invalid_token` | Incomplete |
| Principal hand-off | `contextExtractor` re-parses the raw header into `TOKEN_KEY`. Static ThreadLocal never cleared | Validated `Authentication` from `SecurityContextHolder` in `McpTransportContext` (spike first, C14) | Behaviour change |
| Outbound | `JwtForwardingInterceptor` re-sends T1 | `TokenExchangeClient` (own `RestClient`, validates `issued_token_type`), `AjApiClient` takes the bearer per call, no retry on 401 | Missing |
| Per-tool authz | none | `ToolSecurity.requireScope` → `McpToolException.insufficientScope` before any exchange | Missing |
| Config | `kuba-*` defaults, ports 8081/9081, backend `localhost:8080`, security DEBUG logging | localhost defaults on 8180/8181/9181, `aj.oauth.client-id/secret/introspection-uri/token-uri`, `aj.backend.resource`, fail fast without a secret, drop DEBUG on `org.springframework.security` | Incomplete |
| Logging | Up to 8 KB of backend body at WARN, INFO per forward | No auth headers or tokens logged | Defect |
| pom | no resource-server, no `spring-security-test` | + `spring-boot-starter-security-oauth2-resource-server`, `spring-security-test` | Missing |
| Deletions | `McpJwtFilter`, `AccessTokenHolder`, `TOKEN_KEY`, `JwtForwardingInterceptor` | gone | — |

### 4. Frontend consent page and SPA
| Area | Current | Desired | Gap |
|---|---|---|---|
| `OAuth2AuthorizePage.tsx` L98-106 | Hidden inputs without `resource` | `{resource && <input type="hidden" name="resource" value={resource} />}` from `searchParams` | One line plus one `searchParams.get`. Page has 0 tests, so add one that checks the form's field set |
| `AuthContext.loadStoredAuth` | Drops only expired tokens | Optionally also drop tokens without `aud` (I-5) | Optional |
| `vite.config.ts` | Proxies `/api` → `:8080` | → `:8180` | Port |

### 5. Plugins and port configuration
Plugin code needs no auth logic change: T0 keeps `sub`/`permissions`, and the configured issuer makes the server-side forwarding safe. Only port references move (inventory below). `plugins/server-sdk.ts` `DEFAULT_HOST_BASE_URL` is the one functional default, used by `ai-description/generate.ts:41` and `ai-offer-analysis/analyze.ts:153`.

### 6. Docs and standards
Rewrite these, do not append (C12): `CLAUDE.md` (:19, :33, :41, :47-48, :56, :60, :79-81), `standards/mcp-server/conventions.md:13-17`, `standards/backend/security.md:15-17, :26, :35-37`, `standards/backend/plugin-auth.md` (issuer caveat), `standards/plugins/plugin-development.md:26, :56` (port table), `standards/frontend/data-access.md:26, :29`, `project/architecture.md:27, :45, :52-53, :58-64`, `project/tech-stack.md:25-26`, `INDEX.md:77, :80, :109, :116`, `plugins/CLAUDE.md` (6 lines + :406, :413), `plugins/ai-offer-analysis/README.md`. Also fix the dead link to `2026-04-13-mcp-token-security`.

### 7. Tests
| Suite | Current | Gap |
|---|---|---|
| Host `AuthIntegrationTests` (14) | Real JWTs. `generateTokenWithExpiration` signature used at L104 | Adapt to the new claims. Add T1→`/api` 401, legacy no-`aud` 401, wrong `iss` 401 |
| Host `OAuth2IntegrationTests` (8) | Public client, authorize **without `scope`**, refresh **without `client_id`** (L268-276), misnamed `tokenExchange_*` | Breaks under P1 rules (I-2, I-3). Rename methods. Metadata assertions |
| Host, new | none | `TokenExchangeIntegrationTests`, `IntrospectionIntegrationTests`, `ApiAudienceSecurityTests`, refresh binding, scope policy (viewer red-first), `McpClientProvisionerTests`, log capture |
| MCP (25 tests, 6 `@SpringBootTest` classes) | Pinned to passthrough | Delete `McpJwtFilterTests` and `AccessTokenHolderTests`. Rewrite `RestClientConfigTests`. Signature changes in `ErrorMappingTests`, `ToolCallIntegrationTests`, `ToolSchemaValidationTests`. `ConfigurationTests` asserts 8081/9081 → 8181/9181. Test secret for fail fast (I-8). New chain test with `MockRestServiceServer` (T2 ≠ T1, one exchange, no retry) |
| Frontend | `OAuth2AuthorizePage` untested | One test for the field set. Port fixtures optional (I-9) |

---

## Port Reference Inventory (8080 / 8081 / 9081 → 8180 / 8181 / 9181)

Scope: tracked files (`git grep`), excluding `week*/`, `tools/`, `_docs/`, `.maister/tasks/`, `node_modules`, `target`, `src/main/resources/static`, lockfiles and `baml_client`. **19 files, 48 functional and doc lines, plus 14 test-fixture lines.**

| File | Lines | Kind | Change |
|---|---|---|---|
| `src/main/resources/application.properties` | *(none: implicit default)* | config | **Add** `server.port=8180` |
| `src/main/frontend/vite.config.ts` | 13 | config | proxy target → `http://localhost:8180` |
| `mcp-server/src/main/resources/application.yml` | 2, 7, 24 (+ `server-url` / `base-url` `kuba-*` defaults) | config | 8181, 9181, `AJ_BACKEND_URL` default `http://localhost:8180`. `AJ_OAUTH_SERVER_URL` → `http://localhost:8180`, `AJ_MCP_BASE_URL` → `http://localhost:8181` |
| `mcp-server/src/test/java/pl/devstyle/aj/mcp/ConfigurationTests.java` | 19, 20, 24, 25 | test | method names + asserts → 8181 / 9181 |
| `plugins/server-sdk.ts` | 40, 46 | code + JSDoc | `DEFAULT_HOST_BASE_URL = "http://localhost:8180"` |
| `plugins/box-size/index.html` | 7, 8 | plugin shell | `plugin-sdk.js` / `plugin-ui.css` from `:8180` |
| `plugins/warehouse/index.html` | 7, 8 | plugin shell | same |
| `plugins/shipping/index.html` | 7, 8 | plugin shell | same |
| `plugins/ai-description/src/pages/_document.tsx` | 7, 8 | plugin shell | same |
| `plugins/ai-offer-analysis/src/pages/_document.tsx` | 7, 8 | plugin shell | same |
| `plugins/ai-offer-analysis/.env.example` | 8, 9 | env template | `HOST_BASE_URL` default comment |
| `src/main/frontend/public/assets/plugin-ui.css` | 5 | comment | usage example |
| `CLAUDE.md` | 19, 33, 41, 60 | doc | ports + `AJ_BACKEND_URL` default (line 60 is rewritten anyway) |
| `plugins/CLAUDE.md` | 43, 56, 57, 195, 372, 408 | doc | curl / script / link examples |
| `plugins/ai-offer-analysis/README.md` | 25, 53, 64, 73, 75, 80, 81, 126 | doc | `:8080` mentions + curl / PowerShell examples |
| `.maister/docs/standards/plugins/plugin-development.md` | 26, 56 | standard | link example, fixed-port table (host 8180, MCP 8181, management 9181) |
| `.maister/docs/standards/frontend/data-access.md` | 26, 29 | standard | Vite proxy target |
| `.maister/docs/project/architecture.md` | 52, 64 | project doc | MCP `:8181` |
| `.maister/docs/INDEX.md` | 109 | index | port summary |
| `src/main/frontend/src/test/plugin-sdk-auth.test.ts` | 77, 102, 107, 113, 119 | fixture (explicit arg, not a default) | optional (I-9) |
| `src/main/frontend/src/test/plugin-sdk.test.ts` | 17, 24, 31, 47, 70, 86, 117, 127, 142 | fixture (`hostOrigin` strings) | optional (I-9) |

Found but excluded (do not edit, or see I-9):
- `plugins/box-size/dist/index.html`, `plugins/warehouse/dist/index.html` (tracked build output, 2 lines each).
- `plugins/ai-description/.playwright-mcp/*.log` (tracked logs, about 4,900 hits).
- `plugins/ai-description/tsconfig.tsbuildinfo`.
- Gitignored local `plugins/ai-offer-analysis/.env` (a commented `HOST_BASE_URL` line; this is the developer's own file).
- `start-dev.sh` / `.ps1`, `compose.yml`, `litellm/`, Liquibase changelogs and plugin `manifest.json` files have **no** host or MCP port references. Compose host ports (5433-5435, 8822, 9900, 9010/9011, 6677, 3100, 5001/5002, 4000) do not collide with 8180/8181/9181.
- The research HLD's configuration table and examples still use 8080/8081. The spec must restate them with 8180/8181.

---

## User Journey Impact Assessment

| Persona / journey | Current | After | Assessment |
|---|---|---|---|
| **MCP client user (Claude Code / Desktop, MCP Inspector)** | MCP configured at `http://localhost:8081`. Any token works | Must **re-point the MCP config to `http://localhost:8181`**. Rediscovery, DCR and consent against issuer `http://localhost:8180`. Tools behave identically. A viewer is now refused `aj_add_product` with a tool error | ⚠️ One-time reconfiguration (document it). ❌ if C-1 is left unresolved: authorisation fails with `invalid_target` |
| **Consent (browser)** | Native POST without `resource` | Same page, plus a hidden `resource`. The consent page may still show `mcp:edit` to a viewer, but the issued token will not contain it (accepted in HLD) | ✅ Discoverability unchanged (scored 7/10 before and after: the page is reached only through the OAuth redirect) |
| **SPA user** | Login at `:8080`, T0 24h | Login at `:8180` (new origin, so the user must log in again). T0 gains `iss`/`aud`. If `start-dev` generates `APP_JWT_SECRET` per run, the user must log in again after each restart | ⚠️ Expected re-login. No flow change |
| **Plugin iframes** | Load the SDK and CSS from `:8080` | From `:8180`. `getToken()` keeps returning T0 | ✅ after the port edits. ❌ if any shell file is missed (SDK fails to load) |
| **Plugin backends** (ai-description, ai-offer-analysis) | Forward T0 to `HOST_BASE_URL` (default 8080) | Default 8180. The configured issuer makes the `iss`/`aud` check independent of the Host header | ✅ |
| **Developer, `start-dev`** | No env exported | Exports shared `AJ_OAUTH_CLIENT_ID/SECRET`, `APP_JWT_SECRET` and URL variables to both apps | ✅ fresh clone works. ⚠️ starting the two apps separately needs a matching secret, and the MCP server fails fast without one |
| **Developer, host only** (`./mvnw spring-boot:run`) | Works | Works. Provisioning skipped with a WARN (but see I-4) | ✅ |
| **Tunnel deployment (course L06)** | `kuba-*` defaults baked in | Must set the issuer, MCP base URL, server URL and client id/secret consistently on both apps | ⚠️ Documented env contract |

Reachability: no new UI. All new surfaces are protocol endpoints discovered through `.well-known` metadata, so discoverability is not a meaningful axis here beyond the metadata advertising them (H8).

---

## Data Lifecycle Analysis

No user-facing CRUD UI is expected for any of these entities. They are protocol and config artefacts, so "UI layer" is N/A by design, and the three-layer check uses backend → protocol endpoint → client reachability.

### Entity: `aj-mcp-server` registered client (`oauth2_registered_client` row)
| Operation | Backend | Endpoint / trigger | Access | Status |
|---|---|---|---|---|
| CREATE | `McpClientProvisioner` → `DatabaseRegisteredClientRepository.save` (PK upsert, C11) | App startup with `AJ_OAUTH_CLIENT_SECRET` set | Env (`start-dev`) | ❌ missing today, designed |
| READ | `findByClientId` in `ClientAuthenticator` | `/oauth2/token` (exchange), `/oauth2/introspect` | MCP server via Basic | ❌ missing today, designed |
| UPDATE (rotate secret) | Upsert re-encodes the hash | Restart with a new env | Env | ❌ missing today, designed |
| DELETE / disable | **none** | — | — | ❌ **not designed.** A stale row stays usable when the secret is blank (I-4) |

**Completeness (designed state): 75%.** Orphan: a credential with no retire path. DCR's grant whitelist stops anyone else registering token-exchange (verified, `PublicClientRegistrationFilter` L135-147).

### Entity: tokens and grants (in memory or stateless)
| Artefact | Created | Read / validated | Rotated / updated | Retired | Change |
|---|---|---|---|---|---|
| T0 | `/api/auth/login` | `JwtAuthenticationFilter` (API mode) | — | `exp` 24h, logout clears localStorage | + `iss`/`aud`. Legacy T0 → 401 once (local: the new origin already drops it) |
| Auth code | `/oauth2/authorize` | Token endpoint (one-time remove) | — | 600s TTL, `@Scheduled` cleanup | + `resource` |
| T1 | auth-code / refresh grant | MCP via introspection, AS during exchange | — | 15 min | + `aud`, `client_id`, `jti`, `typ`, scopes ∩ permissions |
| RT | with T1 | `consumeAndRotate` | Rotation (sliding 24h) | Lost on restart | + `clientId`, `resource`. Check before removal (I-3) |
| T2 | exchange grant | `/api` gate | — | ≤ min(5 min, T1 `exp`), never stored | new |

**Rollout invalidation:**
- Local origin change and restart wipe T0 in browsers and every in-memory code and RT.
- Claude-held T1s get 401 `invalid_token` from the new MCP server, and Claude re-authorises.
- DCR client rows registered against the old issuer persist and are harmless. Clients re-register when the issuer changes.
- **Overall completeness: 75%.** The only gap is the client-row retire path (I-4).

---

## Defect Analysis

### Reproduction data
1. **Scope escalation (F6).** Log in as `viewer` (READ) → DCR client → `POST /oauth2/authorize` with `scope=mcp:edit` and `_token=T0(viewer)` → `/oauth2/token` → T1 `scopes=[mcp:edit]`. Expected: no `mcp:edit`. Actual: `mcp:edit` issued (`OAuth2TokenFilter` L142-147).
2. **T1 replay at `/api`.** Use T1 from step 1 → `GET /api/products` with `Authorization: Bearer T1`. Expected 401. Actual 200 (`parseToken` fallback L76-79 → `PERMISSION_mcp:read`).
3. **MCP accepts garbage.** `POST http://localhost:8081/` with `Bearer x`. Expected 401 `invalid_token`. Actual: accepted (`McpJwtFilter`).
4. **Secret logging.** Any token request logs the full parameter map, including `client_secret` / `code_verifier`, at INFO.

### Root-cause hypothesis
No audience primitive exists, a single HS256 key signs everything, and the authorization server has no least-privilege step. The MCP server is a trust-and-forward proxy.

### Regression risk areas
- Every authenticated host request goes through the new parse path (`@WithMock*` tests are immune; the real-JWT tests are not).
- `_token` consent path.
- Plugin server-side forwarding (issuer coupling).
- The MCP tool contract (schema tests must stay green unchanged).
- The `OAuth2IntegrationTests` public-client flow (I-2, I-3).

---

## Integration Points
IP-1…IP-9 from the HLD apply unchanged, with 8180/8181 substituted. In addition:
- **IP-10** port-coupled static links: plugin shells → `http://localhost:8180/assets/plugin-sdk.js|plugin-ui.css`.
- **IP-11** MCP client configuration (outside the repo): the server URL becomes `http://localhost:8181`.
- **IP-12** `jjwt-jackson` (Jackson 2) serialises the new nested `act` claim and the array `aud`.

Patterns to follow:
- `OAuth2TokenFilter`: non-bean `OncePerRequestFilter`, `new`'d in `SecurityConfiguration`.
- `ToolCallIntegrationTests` L50: building a context.
- Footprint `spi/stub`: precedent for `@ConditionalOnProperty`.
- Changelog `013-*`: idempotency style.

New patterns: `@ConfigurationProperties` record, `ApplicationRunner`, opaque-token resource server, `MockRestServiceServer`.

---

## Issues Requiring Decisions

### Critical (must decide before the spec)
1. **C-1 Trailing-slash `resource` from JS-SDK clients.** PRM advertises `resource=http://localhost:8181`. TS-SDK clients turn that into a `URL` object and send `.href`, which is `http://localhost:8181/`. Under ADR-002's exact match (no trailing slash), the main client population gets `invalid_target`. ADR-002 explicitly deferred this "until the manual client check", and this is the evidence for it.
   - Options: (A) Minimal canonicalisation: for origin-only URIs, treat `X` and `X/` as equal at `ResourcePolicy`, and always mint `aud` in the canonical no-slash form. The MCP `aud` check is unchanged. (B) Make the canonical MCP resource `http://localhost:8181/` everywhere (PRM, `aud`, host config). This risks breaking clients that send it without the slash. (C) Keep exact match and verify manually before release.
   - Recommendation: **A**. It is a few lines, keeps exact matching for anything with a path, and makes the happy path work for Claude and MCP Inspector.
2. **C-2 Which tokens may act as the user at `/oauth2/authorize`.** Today any token accepted by `JwtAuthenticationFilter` becomes the consent principal (Authorize L92-102). After the change, T2 (`aud` = host API) also passes the gate. A stolen or relayed T2 posted as `_token` could mint a code for any DCR client. The only thing stopping that from yielding T1 plus a 24h refresh token is that `ScopePolicy` maps `READ`/`EDIT` (not `mcp:*`) to `mcp:*` scopes, which is implicit and fragile.
   - Options: (A) Authorize accepts only session tokens: an API-valid token with **no** `client_id`/`act` (T0 shape). Anything else → "User must be authenticated" (typed error). (B) Rely on the `ScopePolicy` mapping and add a test that T2 at consent yields `invalid_scope`.
   - Recommendation: **A**, plus the test from B as a regression guard.

### Important (should decide; defaults given)
1. **I-1 MCP inbound stack.** Default: the `spring-boot-starter-security-oauth2-resource-server` starter (confirmed in the 4.0.5 BOM) with an **explicitly declared** introspector bean built from `aj.oauth.*` (not the `spring.security.oauth2.resourceserver.opaquetoken.*` properties), wrapped by `AudienceValidatingIntrospector`. Alternative: hand-rolled introspection `RestClient` plus a custom filter. Rationale: P1 and the HLD already assume Spring's introspector. Explicit beans keep a single config namespace and make the C8 URL-encoding behaviour visible.
2. **I-2 Missing `scope` at authorize or token.** `OAuth2IntegrationTests` authorises without `scope`. Today that yields a T1 with no scopes. Under P1, an empty result means `invalid_scope`. The consent page itself always posts the client's scopes when none were requested (L49-51). Options: (A) missing `scope` → default to the client's registered scopes, then the normal `ScopePolicy` (RFC 6749 §3.3 default scope). (B) Missing → `invalid_scope`. Default: **A**, which matches the consent UI's behaviour.
3. **I-3 Refresh-grant client identification (FU-2a details).** Options:
   - Strictness: (A) Confidential clients must authenticate (`ClientAuthenticator`). Public (`none`) clients must send a `client_id` equal to the stored one. Missing → `invalid_request`/`invalid_client`, mismatch → `invalid_grant`. (B) Accept a missing `client_id` for public clients.
   - Ordering: (i) validate **before** `consumeAndRotate` removes the RT, so a wrong client cannot burn the legitimate RT. (ii) Remove anyway and treat it as theft.
   - Also apply the same rule to the auth-code grant's "client_id only if provided" (L103).
   - Default: **A + (i)**. Reuse detection is FU-2b. Update `OAuth2IntegrationTests` to send `client_id`.
4. **I-4 Blank `AJ_OAUTH_CLIENT_SECRET` with a stale `aj-mcp-server` row.** ADR-008 promises `invalid_client`, but a row provisioned by an earlier run stays valid in the persistent dev DB. Options: (A) Blank secret → provisioner deletes the stale row (WARN). (B) `ResourcePolicy` treats the binding as inactive when no secret is configured. (C) Leave it and document. Default: **A**, which makes ADR-008's stated behaviour true with the smallest change.
5. **I-5 Stale (legacy, no-`aud`) T0 at the consent native POST.** This produces a raw JSON error, because `client.ts` only redirects `fetch` calls. Locally the port and origin change already drops stored tokens, so this mostly affects tunnel deployments. Options: (A) `AuthContext.loadStoredAuth` also discards tokens without `aud` (one condition next to `isTokenExpired`). (B) Accept the one-time raw error. (C) Server-side redirect to `/login?returnTo=` on an unauthenticated authorize POST. Default: **A**.
6. **I-6 Introspection outage status on MCP.** Options: (A) Accept the Security 7 default (an `AuthenticationServiceException` is rethrown → 5xx) and test for `is5xxServerError()` with no `invalid_token`. (B) Custom failure handler → 503. Default: **A**. Verify in the first MCP security test, then fall back to B if Spring routes it to the entry point.
7. **I-7 Jackson in the touched host OAuth code.** New classes use Jackson 3 (decided). For existing code, options: (A) Migrate `OAuth2TokenFilter`, `OAuth2AuthorizationFilter` and `OAuth2ErrorResponse` to the injected `tools.jackson` mapper through the shared error writer and response records. `PublicClientRegistrationFilter` switches only to the shared writer and keeps its Jackson 2 request parsing. (B) Also migrate Registration fully. (C) Leave existing code on Jackson 2. Default: **A**. The explicit `jackson-databind` pom entry stays either way, because jjwt-jackson needs it.
8. **I-8 Test-context strategy for the provisioner and MCP fail fast.** Options: (A) Add `src/test/resources/config/application.properties` (host) and `config/application.yml` (MCP) with a test client secret. Boot loads `classpath:/config/` **in addition to** the main file, so it does not shadow it. A root-level `src/test/resources/application.yml` would shadow the MCP main file and break `ConfigurationTests`. This keeps one cached context per app. The provisioner's blank-secret and idempotency paths are unit-tested by instantiating the runner. (B) Per-class `@SpringBootTest(properties=…)`, which costs extra contexts. Default: **A**.
9. **I-9 Non-functional port mentions.** These are the frontend test fixtures (14 lines), the committed plugin `dist/index.html` (4 lines) and the tracked `.playwright-mcp` logs. Default: update the fixtures for a grep-clean tree, and leave `dist/` and the logs untouched (generated). Alternative: also hand-edit `dist/`, or untrack it.

---

## Recommendations
- Fold C1–C14 and C-1/C-2 into the spec as explicit requirements, each with a named test. Restate every HLD default with 8180/8181/9181.
- Write the red-first tests before changing code: viewer→`mcp:edit`, T1 at `/api`, garbage bearer at MCP, and T2 as `_token` at consent.
- Implementation order: the HLD order (a)…(h), with the port move done in step (a) (config) and step (h) (docs). Run the MCP `contextExtractor` spike first in step (e).
- Make the must-equal config pairs one table in the spec. Have `start-dev` print the effective issuer and MCP base URL (never the secrets).
- Add the manual rollout gate: Claude Code against `http://localhost:8181`, recording the `resource` value actually sent (this confirms C-1).

## Risk Assessment
- **Complexity risk: High.** Three token types, two apps, new framework patterns, and a hand-written OAuth grant and endpoint.
- **Integration risk: High.**
  - The must-equal URL pairs across apps fail closed with opaque 401s if they drift.
  - The trailing-slash mismatch (C-1).
  - Port references in plugin shells.
  - MCP client reconfiguration outside the repo.
- **Regression risk: Medium-High.**
  - Every authenticated request uses the new parse path.
  - The MCP test suite is pinned to passthrough.
  - `OAuth2IntegrationTests` relies on omitted `scope` and `client_id`.
  - Mitigation: `@WithMock*` isolation, additive T0 claims, the gate leaving the context empty instead of writing a 401, and an unchanged tool contract.
