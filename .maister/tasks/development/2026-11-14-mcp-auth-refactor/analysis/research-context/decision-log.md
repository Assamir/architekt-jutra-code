# Decision Log: MCP auth refactor (no token passthrough)

## TL;DR
- 9 ADRs, all **Accepted**. ADR-001 records the overall direction (validate-then-exchange). ADR-002…ADR-009 record one decision area each (DA-1…DA-8) from package **P1 "course-aligned secure minimum"**.
- The most consequential: **ADR-003** (only the exchange grant can mint the host-API audience, closing the auth-code→`/api` shortcut), **ADR-004** (scopes ∩ user permissions at both grants, fixing `viewer`→`mcp:edit`) and **ADR-006** (introspection with no cache and HS256 kept: the MCP server never holds a key).
- Every ADR links back to its alternatives in [solution-exploration.md](solution-exploration.md) and to the design in [high-level-design.md](high-level-design.md).

## Key Decisions
- ADR-001 validate-then-exchange — the only option that meets every MUST on the hop and keeps per-user authorisation.
- ADR-002 URI audiences with a lenient default — works with clients that omit `resource`. The default only ever yields an MCP-audience token.
- ADR-003 `/api` audience gate plus per-grant policy — fixes the hole at issuance without touching matchers.
- ADR-004 hierarchical `mcp:*` kept in T2 — MCP-14 met with no matcher change, and T2 stays distinguishable from T0.
- ADR-005 hand-written exchange client and explicit principal — no hidden Spring defaults, no thread-bound state.
- ADR-006 no caches, HS256 — immediate revocation, keyless MCP server.
- ADR-007 implicit `act` — accountability at zero client cost.
- ADR-008 `ClientAuthenticator` plus env upsert — no committed secret, one client registry.
- ADR-009 same-touch hygiene — closes B-12 and refresh client binding in files already being edited.

## Open Questions / Risks
- ADR-002 depends on what `resource` value real clients send (unverified). Exact match only.
- ADR-005 depends on the `contextExtractor` seeing the post-filter `SecurityContext` on the servlet thread (spike first). Fallback: `immediateExecution(true)`.
- ADR-004 makes the issued `scope` differ from the requested one. Legal, but client handling is unverified.

---

<a id="adr-001"></a>
## ADR-001: Validate-then-exchange replaces token passthrough

### Status
Accepted (2026-11-14)

### Context
The MCP server accepts any `Bearer` string (`McpJwtFilter`) and re-sends the client's token to the host (`JwtForwardingInterceptor` + a static `AccessTokenHolder`). The host accepts any token signed with its one HMAC key, and no token carries an audience. MCP 2026-07-28 requires audience validation by the MCP server and forbids accepting, transiting or passing through foreign tokens (MCP-07/-11/-29/-31), but leaves the upstream-token mechanism open. RFC 8693 §1 describes exactly this case: a resource server acting as a client.

### Decision Drivers
- Every MUST on the MCP↔host hop (research-report §5)
- Per-user authorisation must stay at the host (course L04)
- The backend must be able to tell relayed calls from direct ones (MCP-34)
- Course L07 reference shape and the team standard
- Effort proportionate to a local-only showcase

### Considered Options
1. **A1 + A2a + A3:** RFC 7662 introspection inbound, RFC 8693 exchange outbound, `aud` enforced at both resource servers, by extending the custom AS
2. A4: client credentials + a propagated user header
3. A2b/A2c: local JWT validation at the MCP server (shared HMAC, or RS256 + JWKS)
4. A5: migrate to Spring Authorization Server or Keycloak
5. A6: sender-constrained tokens (DPoP/mTLS) as the main fix

### Decision Outcome
Chosen option: **1**, because it is the only combination that meets every hop MUST, keeps the user's identity and permissions downstream, makes the MCP server a distinguishable actor and needs no signing-key migration. It is what L07, the team standard and every vendor pattern found (Auth0 OBO, Okta, Entra OBO, Keycloak, SAS) use. See [solution-exploration.md § Problem Reframing](solution-exploration.md) and research-report §6–§7.

### Consequences
#### Good
- Closes all four brief vulnerabilities (passthrough, confused deputy, replay, no MCP validation). See [high-level-design.md § How Each Vulnerability Is Eliminated](high-level-design.md).
- The MCP server becomes mostly deletions plus standard Spring Security wiring.
- Tool behaviour is unchanged for users.

#### Bad
- More hand-written security code in the custom AS (a new grant, a new endpoint, a client authenticator).
- Two extra local HTTP calls per tool call.
- A4 is rejected outright: it recreates the confused deputy. A5 is left for a separate build-vs-buy ADR (FU-9). A6 is deferred (FU-8).

---

<a id="adr-002"></a>
## ADR-002: URI audiences with a lenient default (DA-1 → 1A)

### Status
Accepted (2026-11-14)

### Context
Audience is the missing primitive (I1). The PRM `resource`, the RFC 8707 `resource` clients send, the T1 `aud` and the MCP server's `aud` check must be byte-identical (P-05, I8). J-08 requires the AS to choose a default when `resource` is omitted, and J-16 requires distinct audiences per resource. Whether Claude sends `resource` today is unverified.

### Decision Drivers
- One property drives PRM, the 401 challenge and the `aud` check (I8)
- Compatibility with clients that omit `resource`
- AS identity distinct from API identity (J-16), to survive an AS split (FU-9)
- Works on a fresh local clone

### Considered Options
1. **1A** URI audiences (MCP = `aj.mcp.base-url`, host API = `${issuer}/api`). A missing `resource` defaults to the MCP URI with a warning. An unknown one gets `invalid_target`
2. 1B the same URIs, strict (`invalid_target` when `resource` is missing)
3. 1C logical audience identifiers mapped from URIs
4. 1D host API audience = `${issuer}`

### Decision Outcome
Chosen option: **1A**, because it is spec-native (RFC 8707 absolute URIs), keeps the PRM-equals-`aud` invariant, and does not break clients that omit `resource`. The default can only produce an MCP-audience token, the one resource where `mcp:*` means anything. Exact string comparison, no trailing slash. Local defaults: `http://localhost:8081` and `http://localhost:8080/api`. See [solution-exploration.md § DA-1](solution-exploration.md).

### Consequences
#### Good
- A single configured value per resource on each side (see the HLD configuration table).
- Misconfigured deployments fail loudly at authorisation (`invalid_target`) rather than as silent 401s.

#### Bad
- An implicit default for non-compliant clients (mitigated by the WARN log).
- No URI normalisation (stretch). A client sending a trailing slash would get `invalid_target`. Revisit after the manual client check.
- Tunnel deployments must set the URL env vars consistently on both apps.

---

<a id="adr-003"></a>
## ADR-003: Host `/api` audience gate plus a per-grant resource policy (DA-2 → 2B)

### Status
Accepted (2026-11-14)

### Context
Without a host-side `aud` check, exchange is pointless: T1 could still be replayed at `/api` (I1, B-04, O-14). Introducing a resource registry that knows both the MCP URI and the host-API URI opens a new path. If `/oauth2/authorize` accepted `resource = host API`, an MCP client could mint a token `/api` accepts directly and bypass the MCP server (brainstorming finding, Medium-High).

### Decision Drivers
- B-04/O-14 MUST: the host refuses tokens not meant for it
- Close the auth-code→host-API shortcut
- No changes to the centralised `SecurityConfiguration` matchers (security standard)
- Generalises to future resources

### Considered Options
1. 2A audience gate only (`iss` + `aud` = host API on `/api`)
2. **2B** audience gate + per-grant resource policy (the host-API audience can be minted only by the token-exchange grant, called by the confidential client bound to the subject's resource. `authorization_code` and `refresh_token` may target only the MCP resource)
3. 2C audience gate + an actor requirement on `PERMISSION_mcp:*`
4. 2D both 2B and 2C

### Decision Outcome
Chosen option: **2B**, because it closes the shortcut at issuance, the cheapest and most general place, with no matcher or authority-mapping change. T0 (from `/api/auth/login`, not an OAuth grant) gets `aud` = host API directly. The policy and the client→resource binding are configuration-backed (`ResourcePolicy` + `app.oauth2.*`), so no schema migration is needed. See [solution-exploration.md § DA-2](solution-exploration.md).

| Resource | `authorization_code` | `refresh_token` | `token-exchange` | login (T0) |
|---|---|---|---|---|
| MCP (`aj.mcp.base-url`) | allowed (default when omitted) | allowed, must equal stored | not allowed | — |
| Host API (`${issuer}/api`) | `invalid_target` | `invalid_target` | allowed only for the client bound to the subject's `aud` (`aj-mcp-server`) | always |

### Consequences
#### Good
- Replay is closed in both directions. `/api` accepts only T0 and T2.
- Existing `PERMISSION_mcp:*` matchers become reachable only by T2.

#### Bad
- One more policy table in the AS. A future first-party OAuth client needing host-API tokens needs an explicit entry.
- Issuance is the single guard. A bug there is not caught at `/api` (2C stays an optional stretch).
- Legacy T0s without `aud` are rejected once (re-login).

---

<a id="adr-004"></a>
## ADR-004: Hierarchical `mcp:*` scopes, normalised at issuance, kept in T2, intersected at both grants (DA-3 → 3A)

### Status
Accepted (2026-11-14)

### Context
MCP-14 (new MUST in 2026-07-28) requires accounting for scope hierarchies. Today an `mcp:edit`-only token cannot GET at the host. The AS never intersects requested scopes with user permissions, so a READ-only `viewer` can obtain `mcp:edit` (F6). An exchange that copied subject scopes would make that systemic (I3). The team standard says "map `mcp:read`→READ, `mcp:edit`→EDIT", while the research lean was to keep `mcp:*` (K11).

### Decision Drivers
- MCP-14 compliance by behaviour, not just by documentation
- Close F6 at the source and at exchange
- No matcher changes
- Keep MCP-originated calls distinguishable from app sessions (MCP-34)

### Considered Options
1. **3A** `mcp:edit` ⊃ `mcp:read`, expanded by the AS when issuing T1 and T2, issued `scope` returned. T2 keeps `mcp:*`. Intersect with permissions at both grants
2. 3B independent scopes, documented
3. 3C translate to READ/EDIT permissions in T2 (team-standard wording)
4. 3D hierarchy enforced at the resource servers

### Decision Outcome
Chosen option: **3A**, because the hierarchy lives in one function (`ScopePolicy`), the MCP per-tool checks and host matchers stay flat exact-match checks, and T2 stays distinguishable from T0. The order is fixed: **expand → ∩ permission scopes (`READ`↔`mcp:read`, `EDIT`↔`mcp:edit`) → ∩ client scopes → drop unknown**. An empty result means `invalid_scope`. The auth-code grant uses consent-time permissions. The exchange grant uses the user's **current** permissions. See [solution-exploration.md § DA-3](solution-exploration.md).

### Consequences
#### Good
- A viewer can never hold `mcp:edit`, in T1 or T2. A permission revoked after consent takes effect on the next tool call.
- Edit-scoped clients can list again.

#### Bad
- The issued `scope` can be wider than requested (legal under OAuth 2.1, client handling unverified).
- The team-standard wording in `standards/mcp-server/conventions.md` and `standards/backend/security.md` must be updated in the same change ("T2 keeps `mcp:*`").
- The consent UI may still show `mcp:edit` to a viewer (frontend out of scope).

---

<a id="adr-005"></a>
## ADR-005: Hand-written `TokenExchangeClient`; validated principal via `McpTransportContext` (DA-4 → 4A)

### Status
Accepted (2026-11-14)

### Context
MCP SDK 0.18.1 runs sync tool handlers on Reactor `boundedElastic`, so `SecurityContextHolder` is empty there (F3). The current static `AccessTokenHolder` ThreadLocal is never cleared (V7). Spring's `TokenExchangeOAuth2AuthorizedClientProvider` + `OAuth2ClientHttpRequestInterceptor` would need three non-default overrides (subject resolver, principal resolver, non-caching store keyed by token). Missing any one silently reintroduces a bug (K9).

### Decision Drivers
- No static or thread-bound credential state
- No hidden framework defaults or caches on a security path
- Keep the SDK's async execution model
- The "outbound ≠ inbound" property must be trivially testable
- Course L07 names a `TokenExchangeClient`

### Considered Options
1. **4A** hand-written `TokenExchangeClient` (one form POST on `RestClient`, validates `issued_token_type`). The `contextExtractor` stores the validated `Authentication` (with the subject token as credential) in `McpTransportContext`. Tools check scope, exchange, and pass T2 explicitly to `AjApiClient`
2. 4B Spring provider + interceptor with `immediateExecution(true)`
3. 4C hybrid: Spring provider called explicitly
4. 4D hand-written client + request-scoped holder cleared in `finally`

### Decision Outcome
Chosen option: **4A**, because it has the fewest moving parts, keeps data flow explicit (T2 lives only in the handler's stack frame), keeps the SDK's model, and matches L07. `AccessTokenHolder`, `JwtForwardingInterceptor`, `McpJwtFilter` and `TOKEN_KEY` are deleted. `AjApiClient` methods take the bearer value per call. A per-tool scope shortfall is reported as `McpToolException.insufficientScope(...)` before any exchange. HTTP-level 403 is reserved for tokens with no `mcp:*` scope at all. See [solution-exploration.md § DA-4](solution-exploration.md).

### Consequences
#### Good
- Concurrent tool calls cannot leak tokens across threads or users.
- Unit and integration tests can assert the outbound header directly.
- No `spring-security-oauth2-client` dependency.

#### Bad
- Hand-written OAuth client code to maintain (error parsing, `issued_token_type` check).
- `AjApiClient` signatures change (tool contracts do not).
- Relies on assumption I5 (the extractor runs after the filter chain on the servlet thread). If a spike disproves it, fall back to 4D/4B with `immediateExecution(true)`.
- MCP-13 (SHOULD) is only partly met for per-tool shortfalls.

---

<a id="adr-006"></a>
## ADR-006: Introspection and exchange without caches; HS256 kept; T2 ≤ 5 min (DA-5 → 5A)

### Status
Accepted (2026-11-14)

### Context
Introspection decouples key management from this refactor (I2). Local JWT validation at the MCP server would need either the shared HMAC secret (letting the MCP server mint host tokens) or an RS256/JWKS migration first. On a localhost hop, caching is a security decision, not a performance one: revocation lag and cache-key scope bleed (I6). MCP 2026-07-28 is stateless, so every request re-authenticates. The team standard says "no caching".

### Decision Drivers
- The MCP server must never hold a host signing key (J-02/J-13)
- Immediate revocation
- No new key infrastructure on the critical path
- Course L07 and the team standard

### Considered Options
1. **5A** RFC 7662 introspection with no cache, exchange per tool call with no cache, HS256 kept
2. 5B short-TTL introspection cache + exchange cache keyed by SHA-256(T1) + `client_id`
3. 5C local JWT validation with RS256 + JWKS, migrating host signing now

### Decision Outcome
Chosen option: **5A**, because it is the simplest compliant design with immediate revocation and a keyless MCP server. The host adds `POST /oauth2/introspect`: caller authentication required (I-03), and `{active:false}` with no detail for any unusable token, including tokens whose `aud` is not the caller's bound resource. The MCP server additionally checks `aud` ∋ `aj.mcp.base-url` itself. T2 `exp` = min(now + 5 min, T1 `exp`), and the exchange response carries `expires_in` and `issued_token_type`. See [solution-exploration.md § DA-5](solution-exploration.md).

### Consequences
#### Good
- Revocation, expiry and permission changes take effect on the next tool call.
- Nothing to invalidate. No cache-key design to get wrong.
- JWKS (FU-6) stays a clean upgrade path.

#### Bad
- Two local HTTP calls per tool call (unmeasured. A Micrometer timer is a deferred idea).
- The host is a hard dependency: an outage means requests are rejected (fail closed, intended).
- HS256 remains (RECOMMENDED asymmetric keys deferred).

---

<a id="adr-007"></a>
## ADR-007: Implicit delegation via `act` (DA-6 → 6A)

### Status
Accepted (2026-11-14)

### Context
The confused-deputy risk in the brief is the BP token-passthrough accountability gap: the backend cannot tell relayed calls from direct ones (MCP-34). RFC 8693 supports delegation (`act`) and impersonation. The MCP server authenticates as a confidential client at the exchange, so the AS already knows the actor (I7).

### Decision Drivers
- Auditable accountability at the host (X-15)
- No extra client-side machinery or round trips
- Alignment with Auth0 OBO and SAS

### Considered Options
1. **6A** the AS adds `act: {sub: <mcp client_id>}` from the authenticated caller, no `actor_token`
2. 6B impersonation (`client_id` only, no `act`)
3. 6C explicit delegation with an `actor_token` from a new `client_credentials` grant

### Decision Outcome
Chosen option: **6A**, because it yields the standard delegation claim at zero client cost. For a single confidential client, the client authentication is the actor proof. T2 carries `sub` = user, `client_id` = `aj-mcp-server`, `act.sub` = `aj-mcp-server`. See [solution-exploration.md § DA-6](solution-exploration.md).

### Consequences
#### Good
- The host can audit or later enforce (2C) MCP-originated calls.
- No `client_credentials` grant is needed.

#### Bad
- The actor is asserted by client authentication, not by a separate token. Multi-hop chains (nested `act`) would need 6C later.
- The host does not yet *require* `act` for `mcp:*` authorities (2C deferred).

---

<a id="adr-008"></a>
## ADR-008: One `ClientAuthenticator`; env-driven startup upsert of the MCP confidential client (DA-7 → 7A)

### Status
Accepted (2026-11-14)

### Context
Introspection must require an authenticated caller (I-03), and exchange must be restricted to the client bound to the MCP resource (I4). Today client authentication is inline, reads only the `client_secret` form field, and ignores the advertised `client_secret_basic` (which Spring's introspector uses by default) (F5, P8). No confidential client exists. The committed `APP_JWT_SECRET` default (F7) shows how secrets drift into the repo.

### Decision Drivers
- Honour the advertised `client_secret_basic` and keep `client_secret_post`
- No committed secret or hash
- Works in every Liquibase context
- A single client registry (`oauth2_registered_client`)
- A fresh clone works through `start-dev`

### Considered Options
1. **7A** a reusable `ClientAuthenticator` (Basic + post, BCrypt) used by the token, exchange and introspection paths, plus `McpClientProvisioner` that idempotently upserts `aj-mcp-server` from `AJ_OAUTH_CLIENT_ID`/`AJ_OAUTH_CLIENT_SECRET`
2. 7B Liquibase `dev` changeset with the BCrypt hash of a known dev secret
3. 7C internal clients defined in configuration, outside the DB

### Decision Outcome
Chosen option: **7A**, because it keeps one client model, commits nothing secret, and runs in every context. The upserted client has grant `token-exchange`, auth methods Basic + post and scopes `mcp:read mcp:edit`. Its binding to the MCP resource lives in `ResourcePolicy` config (`app.oauth2.mcp-client-id`), so no schema change is needed. **Missing env:** the host skips provisioning with a WARN and keeps serving the app (introspection and exchange answer `invalid_client`). The MCP server fails fast at startup. `start-dev.sh`/`.ps1` export the same id and secret (generated per run unless already set) to both apps. Sending both Basic and post credentials at once is `invalid_request`. See [solution-exploration.md § DA-7](solution-exploration.md).

### Consequences
#### Good
- Fixes the advertised-but-ignored Basic method (P8).
- Secret rotation = change the env and restart (the upsert rewrites the hash).

#### Bad
- A startup writer is security code with side effects (covered by idempotency tests).
- DCR-generated ids could in theory collide with `aj-mcp-server`. DCR does not choose ids that way, and a reserved namespace is a deferred idea.
- `private_key_jwt` (RECOMMENDED) waits for FU-6/FU-7.

---

<a id="adr-009"></a>
## ADR-009: Bundle same-touch hygiene: FU-3, FU-5, FU-2a (DA-8 → 8B)

### Status
Accepted (2026-11-14)

### Context
Pre-existing AS-wide MUST violations remain after the hop is fixed. B-12: tokens, codes, verifiers and secrets logged at INFO. B-15/O-07: refresh tokens not bound to a client. O-09: `_token` in the query. External `kuba-*.labs-skillpanel.com` defaults break the local `aud` check. Some of these sit in exactly the code this change rewrites.

### Decision Drivers
- Avoid touching the same lines twice
- Consistency within a file (new code logs no tokens, so old code should not either)
- Stay within the brief: no AS-wide hardening sweep

### Considered Options
1. 8A hop only
2. **8B** hop + FU-3 (remove token/code/verifier/secret/param-map logging in `core/oauth2`), FU-5 (local default URLs, env wiring in `start-dev` including `APP_JWT_SECRET`, CORS origins env-overridable), FU-2a (`client_id` and `resource` in refresh data, reject refresh by a different client)
3. 8C 8B + FU-4 `_token` restriction
4. 8D full AS hardening sweep

### Decision Outcome
Chosen option: **8B**, because each item edits a structure the hop already changes (`OAuth2TokenFilter`, `RefreshTokenData`, config), closing B-12 and half of B-15/O-07 at almost no marginal cost. FU-4 stays a stretch, because the consent page depends on `_token`. FU-2b (persistence, reuse detection) needs its own storage decision. See [solution-exploration.md § DA-8](solution-exploration.md).

### Consequences
#### Good
- No secrets in logs anywhere in the AS (asserted by a log-capture test).
- A fresh clone works locally. Tunnel deployments configure via env.

#### Bad
- A larger diff and a few more tests.
- Debug habits relying on the old log lines break.
- The committed `APP_JWT_SECRET` default remains as a fallback for tests and ad-hoc runs. O-09 (`_token`) stays violated until FU-4.
