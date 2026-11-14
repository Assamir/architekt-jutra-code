# Solution Exploration: MCP auth refactor, replacing token passthrough

## TL;DR
- The overall direction is settled: introspection inbound, RFC 8693 exchange outbound, `aud` enforced at both resource servers, custom AS extended. What is left is the parameters. Thirteen open questions (D-1..D-13) are merged here into **8 decision areas** with 29 alternatives.
- **Recommended combination: "course-aligned, secure minimum".** URI audiences with a lenient default. Host-API audience mintable **only** through exchange. Hierarchical `mcp:*` scopes normalised at issuance and kept in T2, with the intersection applied at both grants. A hand-written `TokenExchangeClient` plus the principal in `McpTransportContext`. No caching and HS256 kept. Delegation via an implicit `act`. An env-seeded confidential client. Bundle three same-touch follow-ups (FU-3 logs, FU-5 local defaults, refresh-token client binding).
- One new gap found while brainstorming (DA-2): if the AS lets the authorization-code grant request `resource = host API`, an MCP client can mint a token that `/api` accepts directly. The recommendation closes it at issuance.
- Confidence: High overall. Medium on DA-1 (real clients' `resource` behaviour) and DA-4 (wiring style).

## Key Decisions
- DA-1 Audience model: **URI audiences, lenient default.** MCP `aud` = `aj.mcp.base-url` (local default `http://localhost:8081`). Host API `aud` = `${issuer}/api`. A missing `resource` defaults to the MCP URI with a warning. This satisfies P-05/J-08/J-16 without breaking clients that omit `resource`.
- DA-2 Host enforcement: **audience gate + per-grant resource policy.** `/api` requires `iss` and `aud` = host API. Only the exchange grant can mint the host-API audience. This closes the auth-code→`/api` shortcut without a matcher change.
- DA-3 Scope model: **hierarchical `mcp:*`, normalised at issuance, kept in T2, intersected at both grants.** MCP-14 is met with no host matcher change. T2 stays distinguishable from T0, and the `viewer`→`mcp:edit` escalation (F6) is fixed in both grants.
- DA-4 Outbound + principal: **hand-written `TokenExchangeClient` + validated `Authentication` in `McpTransportContext`.** One small class with no hidden Spring defaults. It respects the SDK's `boundedElastic` model and matches the course name (L07).
- DA-5 Validation, caching, keys: **introspection without cache, exchange without cache, HS256 kept.** This gives immediate revocation, means the MCP server never holds a key, and is what the course and team standard prescribe. JWKS stays the upgrade path (FU-6).
- DA-6 Delegation: **implicit `act: {sub: <mcp client_id>}` added by the AS.** It costs nothing extra (I7) and is auditable (X-15).
- DA-7 Client auth + seeding: **`client_secret_basic` + `client_secret_post` through one `ClientAuthenticator`, client upserted at startup from `AJ_OAUTH_CLIENT_ID/SECRET`.** No committed hash, works in every Liquibase context, one client registry.
- DA-8 Bundled follow-ups: **"same-touch hygiene"**: FU-3 (token logging, same file as the exchange branch), FU-5 (local default URLs and env wiring, needed for `aud`), FU-2a (refresh-token client binding only, same struct that gains `resource`).

## Open Questions / Risks
- **What Claude actually sends as `resource`** is unverified (Appendix B of the report). If it always sends it, DA-1 could go strict. If it sends a value with a trailing slash or a path, exact-match comparison breaks. Normalisation rules need a test.
- **DA-2 gap (new inference, Medium-High).** It applies only if the resource registry (H2) accepts the host-API URI on `/oauth2/authorize`. Verify against the final H2 design.
- **Scope normalisation (DA-3) changes the token response's `scope` field** (a client asking for `mcp:edit` receives `mcp:read mcp:edit`). This is allowed by OAuth 2.1 (the AS may issue a different scope and must then return it), but has not been checked against Claude's client behaviour.
- **The principal hand-off in DA-4 relies on the MCP SDK's `contextExtractor` running on the servlet thread after the security filter chain** (I5, High on the SDK bytecode, untested end-to-end).
- **Two extra local HTTP calls per tool call** (introspect + exchange) are reasoned to be cheap but were not measured.
- **The exercise baseline (`bd4de70`) is pinned by tests.** Six MCP test classes assert passthrough and must be rewritten in the same change.

---

## Problem Reframing

### Research Question
How should the aj MCP server authenticate to the aj host backend without forwarding the MCP client's bearer token, in line with the MCP Authorization spec (2026-07-28), OAuth 2.1 and RFCs 8693, 7662, 8707, 9068, 9728 and 9700?

**Settled by Phase 1 (not reopened here):** validate-then-exchange (A1 + A2a + A3). A4 (client credentials + user header), A2b (shared-HMAC local validation) and A5 (SAS/Keycloak migration) are rejected. A6 (DPoP/mTLS) is deferred. Research report §6–§7.

### How Might We Questions
| # | HMW | Source evidence | Decision area |
|---|---|---|---|
| HMW-1 | How might we give every token a single, unambiguous audience while staying compatible with MCP clients that may not send `resource` yet? | I1, I8, P-05, J-08, J-16, App. B | DA-1 |
| HMW-2 | How might we make the host refuse every token that was not minted for it, without rewriting the centralised `SecurityConfiguration` matchers? | F4, B-04, O-14, D-8 | DA-2 |
| HMW-3 | How might we satisfy the new scope-hierarchy MUST and close the `viewer`→`mcp:edit` escalation, while keeping MCP-originated calls distinguishable at the host? | F6, I3, MCP-14, K11, D-6/11/13 | DA-3 |
| HMW-4 | How might we get the validated principal and a fresh backend token into tool handlers that run on `boundedElastic`, without static state or hidden framework defaults? | F2, F3, I5, K9, D-4/5 | DA-4 |
| HMW-5 | How might we validate inbound tokens and obtain backend tokens with immediate revocation, at minimum key-management cost? | I2, I6, F7, D-1/10/12 | DA-5 |
| HMW-6 | How might we let the backend tell relayed calls from direct ones at the lowest client-side cost? | I7, X-15, MCP-34, D-7 | DA-6 |
| HMW-7 | How might we give the MCP server a confidential-client identity that works in every environment without committing secrets? | F5, F7, H3/H4, D-9 | DA-7 |
| HMW-8 | How might we use the files this change already touches to close cheap pre-existing MUST violations, without turning the task into an AS-wide hardening sweep? | §9 FU-2..FU-5, compliance rows 16, 19, 32 | DA-8 |

### Mapping of open questions to decision areas
| Decision area | Covers | Why merged |
|---|---|---|
| DA-1 Audience model | D-2, D-3 | Values and defaults are one choice: the default must be one of the canonical values |
| DA-2 Host-side enforcement | D-8 | Kept separate: it is the reverse-path guarantee (I1) |
| DA-3 Scope model | D-6, D-11, D-13 | Hierarchy, T2 representation and intersection placement all change the same scope-computation function |
| DA-4 Outbound client + principal | D-4, D-5 | The Spring provider only works cleanly with `immediateExecution(true)`, so they are coupled |
| DA-5 Validation, caching, keys | D-1, D-10, D-12 | Introspection is what makes HS256 acceptable (I2). Both caches share the same revocation trade-off (I6) |
| DA-6 Delegation semantics | D-7 | Standalone |
| DA-7 Client auth + seeding | D-9 | Standalone |
| DA-8 Bundled follow-ups | FU-2..FU-5 | Scope decision for the implementation task |

**Evaluation perspectives** used in every area. **Feasibility**: effort on host and MCP, technology maturity. **User impact**: MCP-client end users (Claude), course students and developers running `start-dev`. **Simplicity**: moving parts, hidden defaults, maintenance. **Risk**: security residue, regressions, reversibility. **Scalability/extensibility**: the path to JWKS, more resources, more MCP servers, production. Ratings use High / Med / Low (for Risk, Low is good).

---

## Explored Alternatives

### DA-1 Audience model (D-2 + D-3)
**Why it matters.** Audience is the missing primitive (I1). The value in PRM `resource`, the value clients send as RFC 8707 `resource`, the T1 `aud` and the MCP introspection check must be byte-identical (P-05, I8). J-08 requires the AS to pick a default when `resource` is absent. J-16 requires distinct audiences per resource.

**1A. URI audiences, lenient default (recommended).** MCP `aud` = `aj.mcp.base-url` (no trailing slash, local default `http://localhost:8081`). Host API `aud` = `${issuer}/api`. When `resource` is omitted on `/oauth2/authorize`/`/oauth2/token`, the AS defaults to the MCP URI (the only resource where `mcp:*` scopes mean anything, J-05) and logs a warning. Unknown values return `invalid_target`.
- Strengths: spec-native (RFC 8707 requires absolute URIs, and PRM `resource` is a URI). Works with clients that do not send `resource`. One property drives PRM, the 401 challenge and the `aud` check.
- Weaknesses: the default is implicit, so a misconfigured client still gets an MCP token. URI comparison needs normalisation rules (trailing slash, case of host).
- Best when: real-client behaviour is unknown, and a local-first showcase must work out of the box.
- Evidence: report D-2/D-3 leans; I8; J-05, J-08, J-16; F14 (external defaults).
- Effort: Low (host), Low (MCP).

**1B. URI audiences, strict (`invalid_target` when `resource` is missing).** Same values, but no default: every client must send `resource`.
- Strengths: no implicit behaviour. Matches MCP-18 (clients MUST send `resource`). Easiest to reason about in an audit.
- Weaknesses: any client that does not send `resource` yet fails at authorisation. That is a breaking change for the course demo if Claude omits it (unverified). J-08 still asks for a defined default policy, and a reject policy is a defensible reading.
- Best when: real clients are verified to send `resource`, or after a deprecation window.
- Evidence: MCP-18; App. B (client behaviour unknown).
- Effort: Low / Low.

**1C. Logical audience identifiers (`aj-mcp`, `aj-api`) mapped from URIs.** The AS keeps a registry `resource URI → logical aud`. Tokens carry stable logical names that are independent of host and port.
- Strengths: tokens survive URL changes (tunnels, Cloudflare). It mirrors Auth0's "API identifier" style.
- Weaknesses: an extra mapping layer. The MCP server must compare against a value that differs from its PRM `resource`, which weakens the "one property" invariant (I8). Spring's audience validators assume a URI or string equality, so the custom mapping lives on both sides.
- Best when: the deployment URL changes often (public tunnel), or several URLs front one logical resource.
- Evidence: F10 (Auth0 identifiers); C14 (tunnel deployment in L06).
- Effort: Medium / Low.

**1D. Host API audience = issuer (`${issuer}`), MCP = `aj.mcp.base-url`.** One fewer configured value: the host's own origin serves as both issuer and API audience.
- Strengths: the simplest configuration, and natural for a single-origin monolith.
- Weaknesses: it conflates the AS identity with the RS identity (J-16 distinctness is weakened). If the AS ever moves out (SAS, Keycloak; FU-9), every T0/T2 `aud` changes.
- Best when: the AS and API are permanently co-located and never split.
- Evidence: report D-2 (`${issuer}` vs `${issuer}/api`); J-16.
- Effort: Low / Low.

### DA-2 Host-side enforcement strictness (D-8)
**Why it matters.** Without a host-side `aud` check, exchange is pointless: T1 could still be replayed at `/api` (I1, B-04). D-8 also asks whether `PERMISSION_mcp:*` should require an MCP actor. While brainstorming one more path surfaced. H2 introduces a resource registry containing **both** the MCP URI and the host-API URI. If `/oauth2/authorize` accepts `resource = host API`, an MCP client can obtain a token with `aud` = host API and `mcp:*` scopes. `/api` would accept it, and the MCP server would be bypassed entirely (inference from H2 + S3, Medium-High).

**2A. Audience gate only.** `JwtAuthenticationFilter` requires a configured `iss` and `aud` ∋ host API. T0 gets `aud` = host API. Matchers are unchanged.
- Strengths: required by B-04/O-14 in every option. Minimal change.
- Weaknesses: leaves the auth-code→host-API shortcut above open if the registry allows it. Accountability rests only on `act` being present by convention.
- Effort: Low.

**2B. Audience gate + per-grant resource policy (recommended).** 2A, plus the AS rule "the host-API audience can only be issued by the token-exchange grant, to the MCP confidential client; authorization-code and refresh grants accept only the MCP resource". T0 is minted by `/api/auth/login`, which is not an OAuth grant, so it is unaffected.
- Strengths: closes the shortcut at issuance, the cheapest place. It needs no matcher or authority-mapping change and generalises (each resource lists which grants may target it). It mirrors I4: the exchange rule already ties the MCP client to the MCP resource.
- Weaknesses: one more policy table in the AS. A future first-party OAuth client that legitimately needs host-API tokens would need an explicit entry.
- Effort: Low.

**2C. Audience gate + actor requirement on `mcp:*`.** 2A, plus `JwtAuthenticationFilter` grants `PERMISSION_mcp:*` only when the token has `act.sub` (or `client_id`) = the MCP server client. Scopes in any other token are dropped.
- Strengths: defence at the resource itself. It holds even if issuance policy is misconfigured, and directly encodes "MCP scopes are only valid when relayed by the MCP server" (MCP-34).
- Weaknesses: couples the host RS to a specific client id (config). It is less general than 2B for future resources. It changes authority mapping, which is security-critical code with no HTTP-level tests today (P7).
- Effort: Low-Medium.

**2D. 2B + 2C (defence in depth).** Both rules.
- Strengths: either rule alone closes the shortcut, so a bug in one is caught by the other.
- Weaknesses: two places to keep consistent. Disproportionate for a local-only showcase.
- Effort: Medium.

### DA-3 Scope model (D-6 + D-11 + D-13)
**Why it matters.** MCP-14 (new MUST in 2026-07-28) requires the server to account for scope hierarchies. Today an `mcp:edit`-only token cannot GET at the host (`SecurityConfiguration.java:106-107`). The intersection with user permissions must ship with exchange, or F6 becomes systemic (I3). The team standard says "map `mcp:read`→READ", while the lean is "keep `mcp:*`" (K11).

**Common to all alternatives (High confidence, D-13):** intersect scopes with the user's permissions at **both** the authorization-code grant (fixes F6 at the source) and the exchange grant (uses the user's *current* permissions, so a permission revoked after consent takes effect within one tool call). Mapping: `mcp:read`↔`READ`, `mcp:edit`↔`EDIT`. Unknown scopes are dropped.

**3A. Hierarchical `mcp:*`, normalised at issuance, kept in T2 (recommended).** Document `mcp:edit` ⊃ `mcp:read`. The AS expands `mcp:edit` to `mcp:read mcp:edit` when issuing T1 and T2 (after intersection), and returns the issued `scope`. The MCP per-tool check and the host matchers stay flat exact-match checks. T2 keeps `mcp:*`, so it remains distinguishable from T0.
- Strengths: meets MCP-14 explicitly. No matcher change. The hierarchy lives in one function in the AS. Edit-only tokens can list again. Keeps accountability (K11).
- Weaknesses: issued `scope` differs from requested, which is legal but visible to clients. It diverges from the team-standard wording (`mcp:*`→READ/EDIT), so the standard needs a one-line update.
- Evidence: MCP-14, K11, `SecurityConfiguration.java:106-120`.
- Effort: Low (host), Low (MCP).

**3B. Independent scopes, documented; T2 keeps `mcp:*`.** No hierarchy: tools require exact scopes, and a client must request `mcp:read mcp:edit` to get both. The MCP docs and PRM say so.
- Strengths: zero hierarchy logic, and arguably MCP-14 compliant ("accounted for: there is none").
- Weaknesses: edit-only tokens cannot list products, which is surprising UX. Compliance rests on documentation, not behaviour. The course's least-privilege examples assume read-for-edit.
- Effort: Very low.

**3C. Translate to app permissions in T2 (team-standard wording).** The exchange issues T2 with `permissions: [READ, EDIT]` (as in T0) derived from scopes ∩ permissions. Host `mcp:*` alternatives in the matchers can eventually be removed. The hierarchy is handled by the permission model.
- Strengths: matches the written team standard. The host has one authority vocabulary. Matchers get simpler over time.
- Weaknesses: T2 becomes indistinguishable from T0 at the authority level, so accountability rests solely on `act`/`client_id` (MCP-34). It needs matcher clean-up to deliver its benefit, and the hierarchy at the MCP server must still be coded separately.
- Evidence: `standards/mcp-server/conventions.md:15`; K11.
- Effort: Low-Medium (host).

**3D. Hierarchy enforced at the resource servers (not at issuance).** Tokens carry exactly what was granted. The MCP server treats `mcp:edit` as satisfying `mcp:read`. The host adds `PERMISSION_mcp:edit` to every GET matcher.
- Strengths: tokens stay minimal and literal. The hierarchy is visible where it is enforced.
- Weaknesses: the hierarchy is duplicated in two codebases. Host matchers grow, against the standard's "no redundant matchers". Easy to drift.
- Effort: Low-Medium.

### DA-4 Outbound client + principal propagation (D-4 + D-5)
**Why it matters.** Tools run on `boundedElastic` (F3), so `SecurityContextHolder` is empty. The current static `ThreadLocal` is never cleared (V7). Spring's client defaults have three pitfalls: an opaque principal is not an `OAuth2Token`, the principal resolver reads `SecurityContextHolder`, and the cache is keyed by principal name, which leaks scope between MCP clients of one user (K9).

**4A. Hand-written `TokenExchangeClient` + validated `Authentication` in `McpTransportContext` (recommended).** The `contextExtractor` (servlet thread, after the filter chain) stores the validated `Authentication`, including the inbound token value as a credential and never a header string. Tool handlers read it from the context, check scope, call `TokenExchangeClient.exchange(subjectToken)` (one form POST on `RestClient`), and pass T2 explicitly to the `AjApiClient` call.
- Strengths: no hidden defaults or caches, and one small, testable class. It keeps the SDK's async model. Explicit data flow makes the "outbound ≠ inbound" regression test trivial. It uses the course's class name (L07).
- Weaknesses: hand-written OAuth client code (error parsing, `issued_token_type` check). It touches `AjApiClient` signatures or adds a per-call token parameter.
- Evidence: K9, I5, F3; report D-4(a)/D-5(a).
- Effort: Low-Medium (MCP).

**4B. Spring `TokenExchangeOAuth2AuthorizedClientProvider` + `OAuth2ClientHttpRequestInterceptor`, with `immediateExecution(true)`.** Tools run on the servlet thread, so `SecurityContextHolder` works. Configure a custom subject-token resolver, a `resource` parameter converter, and a non-caching or token-hash-keyed authorized-client repository.
- Strengths: framework-supported grant mechanics and error handling. Least custom OAuth code. Familiar to Spring developers.
- Weaknesses: three non-default overrides, any of which silently reintroduces a bug if missed (K9). It changes the SDK execution model (blocking servlet threads). Spring behaviour is inferred, not tested (Medium-High). It adds the client starter dependency.
- Evidence: F11, K9, C12.
- Effort: Medium (MCP).

**4C. Hybrid: Spring provider invoked explicitly, principal from `McpTransportContext`.** Keep 4A's propagation, but call `OAuth2AuthorizedClientManager.authorize(...)` with an explicit principal and attributes instead of a hand-written POST. No interceptor.
- Strengths: framework grant mechanics without depending on `SecurityContextHolder`.
- Weaknesses: you still need the custom subject resolver and the non-caching repository, so most of 4B's configuration surface remains for little gain over one form POST.
- Effort: Medium.

**4D. Hand-written client + `immediateExecution(true)` + request-scoped holder cleared in `finally`.** This keeps the current code's shape (a holder read by an interceptor), but the holder contains T2 and is always cleared.
- Strengths: the smallest diff to services and `RestClientConfig`. Interceptor-based, so `AjApiClient` is untouched.
- Weaknesses: keeps implicit thread-bound state, which is the pattern that caused V7. It changes the execution model, and a missed `finally` leaks T2 between requests on pooled threads.
- Effort: Low.

### DA-5 Inbound validation, caching and keys (D-1 + D-10 + D-12)
**Why it matters.** Introspection decouples key management from this refactor (I2). On a localhost hop, caching is a security decision, not a performance one (I6). MCP 2026-07-28 is stateless, so every HTTP request re-authenticates (F8).

**5A. Introspection, no cache; exchange, no cache; HS256 kept (recommended).** `opaqueToken()` against `/oauth2/introspect` with an `aud`-checking introspector wrapper, and one exchange per tool call.
- Strengths: immediate revocation, and the MCP server never holds a key. It is exactly the course (L07) and the team standard. Nothing to invalidate.
- Weaknesses: two local HTTP calls per tool call (unmeasured). The host is a hard dependency, so an outage means 401 (fail closed, which is intended).
- Evidence: I2, I6, I-15, F13; report D-1(a), D-10, D-12.
- Effort: Low-Medium (host endpoint), Low (MCP).

**5B. Introspection + short TTL cache; exchange cache keyed by SHA-256(T1) + `client_id`.** Cache TTL of a few seconds, capped at `exp`. Bounded store, evicted on backend 401.
- Strengths: fewer round trips under bursty tool use (multi-call agent turns). Mirrors Auth0 and MSAL.
- Weaknesses: revocation lag. Two cache-key designs to get right, and a wrong key reintroduces scope bleed. Contradicts the team standard "no caching". It solves a latency problem nobody has measured.
- Evidence: I6, external-patterns-caching §2.
- Effort: Medium (MCP).

**5C. Local JWT validation (RS256 + JWKS) at the MCP server; host migrates signing now.** jjwt switches to RS256 with a `kid`, the AS gets a `/oauth2/jwks` endpoint, and the MCP server uses `jwt()` + `createAtJwtValidator()` + an audience validator. Exchange stays uncached.
- Strengths: no network call on the inbound path. RFC 9068 native. The industry-typical RS pattern (K8). Unlocks `private_key_jwt` (FU-7).
- Weaknesses: revocation only at `exp` (15 min). It pulls a key migration into the critical path, touching T0 issuance and every host test, and is not what L07 teaches.
- Evidence: A2c, F7, J-02/J-09, FU-6.
- Effort: Medium (host), Low (MCP).

### DA-6 Delegation semantics (D-7)
**Why it matters.** The confused-deputy vulnerability is that the backend cannot tell relayed calls from direct ones (MCP-34). RFC 8693 offers delegation (`act`) and impersonation.

**6A. Implicit delegation: the AS adds `act: {sub: <mcp client_id>}` from the authenticated client; no `actor_token` (recommended).**
- Strengths: costs nothing extra, since the AS already knows the caller (I7). Matches Auth0 OBO (`act.sub` = `azp`) and SAS. Auditable at the host. X-15 compliant.
- Weaknesses: the actor is asserted by client authentication rather than a separate proof. For a single confidential client these are equivalent.
- Effort: Very low.

**6B. Impersonation: `sub` = user, `client_id` = MCP server, no `act`.**
- Strengths: the simplest token. The host sees an ordinary user token.
- Weaknesses: accountability rests on `client_id` alone, a non-standard signal for "acting on behalf of". Tooling and audit that look for `act` find nothing.
- Effort: Very low.

**6C. Explicit delegation with `actor_token`.** The MCP server first gets its own token via `client_credentials` and sends it as `actor_token` in the exchange. The AS validates it and copies its `sub` into `act`.
- Strengths: the most literal RFC 8693 delegation. The actor proof is a token, which generalises to multi-hop chains (nested `act`).
- Weaknesses: needs a new `client_credentials` grant (F5: none today) and a second token request per exchange or a cached actor token. A lot of machinery for the same claim 6A produces.
- Effort: Medium (host + MCP).

### DA-7 MCP server client authentication and provisioning (D-9)
**Why it matters.** Introspection must require an authenticated caller (I-03), and exchange must be restricted to the client bound to the MCP resource (I4). Today client authentication is inline and reads only the `client_secret` form field, even though `client_secret_basic` is advertised (F5, P8). No confidential client is seeded. The committed default `APP_JWT_SECRET` (F7) shows how secrets drift into the repo.

**7A. `ClientAuthenticator` (Basic + post, BCrypt) and a startup upsert from `AJ_OAUTH_CLIENT_ID/SECRET` (recommended).** One reusable authenticator for the auth-code, exchange and introspection paths. On startup the host idempotently upserts `aj-mcp-server` (grant: token-exchange, scopes `mcp:read mcp:edit`, bound to the MCP resource), hashing the env secret. `start-dev` sets the same env var for both apps.
- Strengths: no hash committed, works in every Liquibase context, one client registry. It honours the advertised `client_secret_basic` (Spring's introspection default). Matches the team standard's env var names.
- Weaknesses: a startup writer is security code with side effects. Behaviour when the env var is missing must be defined (skip with a warning, or fail fast).
- Effort: Low-Medium (host).

**7B. Liquibase `dev`-context changeset with the BCrypt hash of a known dev secret.**
- Strengths: declarative and follows the existing sample-data convention (context `dev`). No startup code.
- Weaknesses: a known secret ends up in the repo (the F7 pattern again). Non-`dev` contexts need a separate mechanism. Rotating means a new changeset.
- Effort: Low.

**7C. Internal clients from configuration (not the database).** `app.oauth2.internal-clients.aj-mcp-server.{secret,resource,grants}` comes from env. The `ClientAuthenticator` checks configured clients before the DCR repository.
- Strengths: no DB row, no seeding, and the resource binding (I4) is declared next to the secret. The simplest at runtime.
- Weaknesses: two client registries (config + DB) to keep consistent. DCR could register a colliding `client_id` unless reserved. It diverges from `RegisteredClient` as the single model.
- Effort: Low.

*Not offered: `private_key_jwt` (RECOMMENDED by B-07/O-04). It depends on asymmetric keys (FU-6) and is deferred as FU-7.*

### DA-8 Scope of bundled follow-ups
**Why it matters.** Three pre-existing AS-wide MUSTs stay violated after the hop is fixed: B-12 token logging, B-15/O-07 refresh tokens unbound to a client, and O-09 `_token`. Some of them sit in code this task rewrites anyway, so fixing them later means touching the same lines twice.

**8A. Hop only.** Only §8 items. New code logs no tokens (H9). FU-5 is limited to what `aud` needs.
- Strengths: the smallest, most reviewable change, and the most faithful to the brief's scope.
- Weaknesses: `OAuth2TokenFilter` keeps logging tokens next to brand-new code that doesn't, which is inconsistent within one file. `RefreshTokenData` gains `resource` but not `client_id`.
- Effort: baseline.

**8B. Hop + same-touch hygiene (recommended).** 8A plus: **FU-3** (remove token, code, verifier and secret logging in the `core/oauth2` filters, the same file as H6); **FU-5** (local defaults for `aj.mcp.base-url`/AS URL, `start-dev` env for the client secret and `APP_JWT_SECRET`, which `aud` and DA-7 need anyway); **FU-2a** (store `client_id` in the refresh data next to `resource` and reject refresh by a different client; persistence and reuse detection stay deferred).
- Strengths: closes B-12 and the client-binding half of B-15/O-07 at almost no marginal cost, because each item edits a structure the hop already changes. All three are mechanical.
- Weaknesses: a larger diff and a few more tests. FU-3 touches existing log lines that someone may rely on for debugging.
- Effort: +Low.

**8C. 8B + `_token` restriction (FU-4).** Accept `_token` only on `POST /oauth2/authorize`.
- Strengths: closes every pre-existing MUST on the AS (O-09).
- Weaknesses: the consent page (`OAuth2AuthorizePage.tsx:97-99`) depends on it, so there is regression risk in a flow the brief scopes out (host frontend auth). It is not on the MCP hop.
- Effort: +Low-Medium.

**8D. Full AS hardening sweep** (adds FU-1 DCR/CIMD, FU-2 persistence and reuse detection, FU-11).
- Strengths: one hardening pass.
- Weaknesses: scope creep. It turns a hop refactor into an AS rewrite, and the storage decisions (FU-2) deserve their own task.
- Effort: High.

---

## Trade-Off Analysis

Legend: Feasibility/User impact/Simplicity/Scalability: High = good. Risk: Low = good. **Bold row** = recommended.

### DA-1 Audience model
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| **1A URI, lenient default** | High | High (works if `resource` omitted) | High | Low-Med (implicit default) | High | Compatibility over strictness |
| 1B URI, strict | High | Low-Med (breaks non-sending clients) | High | Low | High | Strictness over compatibility |
| 1C Logical ids | Med | High | Low (mapping layer) | Med (breaks "one property") | High (URL-agnostic) | Portability over the invariant |
| 1D Host aud = issuer | High | High | High | Med (AS/RS conflation) | Low (breaks on AS split) | Fewer values over separability |

### DA-2 Host-side enforcement
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| 2A Audience gate only | High | Neutral | High | Med (auth-code→API shortcut) | Med | Minimal but leaves a hole |
| **2B + per-grant resource policy** | High | Neutral | High | Low | High (per-resource rules) | Fix at issuance |
| 2C + actor requirement | High | Neutral | Med | Low | Med (hard-wired client id) | Fix at the resource |
| 2D 2B + 2C | Med | Neutral | Low | Very low | Med | Defence in depth vs two rules |

### DA-3 Scope model
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| **3A Hierarchical, normalised at issuance** | High | High (edit implies read) | High (one function) | Low | High | Issued scope ≠ requested |
| 3B Independent, documented | High | Low (edit-only can't list) | Very high | Med (compliance by docs) | Med | Simplicity over UX |
| 3C Translate to READ/EDIT | Med | High | Med (until matcher clean-up) | Med (T2 ≈ T0 by authority) | High | One vocabulary over distinguishability |
| 3D Hierarchy at the resource servers | Med | High | Low (duplicated) | Med (drift) | Low | Literal tokens over single source |

### DA-4 Outbound client + principal
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| **4A Hand-written + `McpTransportContext`** | High | Neutral (same tool behaviour) | High | Low | Med (add cache later if measured) | Own code over framework |
| 4B Spring provider + `immediateExecution` | Med | Neutral | Low (3 overrides) | Med-High (silent defaults) | High | Framework over explicitness |
| 4C Hybrid explicit provider | Med | Neutral | Med | Med | High | Half the framework, most of its configuration |
| 4D Hand-written + holder in `finally` | High | Neutral | Med | Med (thread-bound state) | Med | Smallest diff over clean flow |

### DA-5 Validation, caching, keys
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| **5A Introspect + exchange, no cache, HS256** | High | High (instant revocation) | High | Low | Med (2 calls per tool call) | Freshness over latency |
| 5B Short-TTL caches | Med | Med (revocation lag) | Low | Med (key design) | High | Latency over freshness |
| 5C Local JWT + JWKS now | Med | Med (revocation at `exp`) | Med | Med (key migration on the critical path) | High | Offline validation over scope |

### DA-6 Delegation semantics
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| **6A Implicit `act`** | High | Neutral | High | Low | High | Actor by client auth |
| 6B Impersonation | High | Neutral | Very high | Med (weak accountability) | Med | Simplicity over audit |
| 6C `actor_token` | Low-Med | Neutral | Low | Low | High (multi-hop) | Literal RFC over effort |

### DA-7 Client auth + provisioning
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| **7A Authenticator + env upsert** | High | High (dev: one env var) | Med | Low | High | Startup code over committed secret |
| 7B Liquibase dev changeset | High | High (zero setup) | High | Med (known secret in repo) | Low (dev only) | Convenience over hygiene |
| 7C Config-defined clients | High | High | High | Med (two registries, id collision) | Med | Simplicity over a single model |

### DA-8 Bundled follow-ups
| Alt | Feasibility | User impact | Simplicity | Risk | Scalability | Key trade-off |
|---|---|---|---|---|---|---|
| 8A Hop only | High | Neutral | High | Med (3 MUSTs open, inconsistent file) | — | Focus over completeness |
| **8B + same-touch hygiene** | High | Neutral | High | Low | — | Small diff growth, 1.5 MUSTs closed |
| 8C + `_token` | Med | Med (consent page risk) | Med | Med | — | All MUSTs over brief scope |
| 8D Full sweep | Low | Med | Low | High (scope creep) | — | Completeness over delivery |

### Overall packages (how the area choices combine)
| Package | Composition | Feasibility | User impact | Simplicity | Risk | Scalability | Course/standard alignment |
|---|---|---|---|---|---|---|---|
| **P1 Course-aligned secure minimum (recommended)** | 1A, 2B, 3A, 4A, 5A, 6A, 7A, 8B | High (medium overall effort) | High | High | Low | Med-High | L07 + team standard (minus the 3A wording) |
| P2 Spring-native | 1A, 2A, 3C, 4B, 5A, 6A, 7A, 8A | Med | High | Low (framework overrides) | Med (K9 defaults, 2A hole) | High | Standard wording; not the L07 class shape |
| P3 Maximum hardening | 1B, 2D, 3A, 4A, 5C, 6C, 7A, 8C | Low-Med (high effort) | Med (strict `resource`, revocation at `exp`) | Low | Low on the hop, Med on delivery | High | Beyond the course |

---

## Recommended Approach

**Package P1: course-aligned secure minimum** = 1A + 2B + 3A + 4A + 5A + 6A + 7A + 8B.

**Primary rationale.** P1 meets every MUST on the MCP↔host hop (report §5 target column) plus the client-binding half of B-15 and all of B-12. It does so with the least hidden behaviour: no caches, no framework defaults to override, no key migration. It reproduces the course's L07 reference shape (introspecting filter, `TokenExchangeClient`, host introspection filter, exchange grant) and keeps tool behaviour unchanged, which is L07's success criterion. Each area choice was the lowest-risk option that still fully closes its gap. The one higher-effort choice, 2B's per-grant policy, closes a bypass that the other options leave open.

**Decision-area summary**
| Area | Recommended | Confidence |
|---|---|---|
| DA-1 Audience model | 1A URI audiences (`aj.mcp.base-url`, `${issuer}/api`), lenient default to the MCP URI + warning | Medium-High |
| DA-2 Host enforcement | 2B audience gate + only exchange may mint host-API audience | Medium-High |
| DA-3 Scope model | 3A hierarchical `mcp:*` normalised at issuance, kept in T2, intersection at both grants | Medium-High |
| DA-4 Outbound + principal | 4A hand-written `TokenExchangeClient` + `Authentication` in `McpTransportContext` | Medium |
| DA-5 Validation/caching/keys | 5A introspection + exchange, no caches, HS256 kept | High |
| DA-6 Delegation | 6A implicit `act: {sub: <mcp client_id>}` | Medium-High |
| DA-7 Client auth + seeding | 7A Basic+post `ClientAuthenticator`, env-driven startup upsert | Medium |
| DA-8 Bundled follow-ups | 8B FU-3 + FU-5 + FU-2a | Medium-High |

**Trade-offs accepted**
- Two local HTTP calls per tool call (5A), in exchange for immediate revocation and keyless MCP.
- An implicit audience for clients that omit `resource` (1A), in exchange for not breaking the demo.
- The issued `scope` can be wider than requested within the hierarchy (3A). The team-standard wording `mcp:*`→READ/EDIT must be updated to "T2 keeps `mcp:*`".
- Hand-written OAuth client and authenticator code to maintain (4A, 7A), in exchange for explicit, testable behaviour.
- `_token` (O-09), refresh-token persistence and reuse detection, DCR openness and HS256 remain until their follow-ups.

**Key assumptions (if wrong, revisit)**
1. The MCP SDK's `contextExtractor` runs on the servlet thread after Spring Security, so the validated `Authentication` is available there (I5). *If wrong:* 4D or 4B (`immediateExecution(true)`).
2. Claude either sends `resource` equal to the PRM value or omits it. *If it sends a different form:* add URI normalisation to 1A. *If it always sends it:* 1B becomes viable.
3. Local introspection + exchange latency is negligible for interactive tool use. *If measured otherwise:* 5B with SHA-256-keyed caches.
4. One AS and one MCP server. *If more MCP servers appear:* 2B's per-resource grant policy and 7A's client→resource binding already generalise. 1C becomes interesting if URLs churn.
5. No production deployment is planned in this task. *If one is:* revisit 5C/FU-6 (JWKS), FU-7 and FU-8.

## Why Not Others
| Alternative | Why not (for this task) |
|---|---|
| 1B Strict `resource` | Breaks clients that omit `resource` (unverified for Claude) for no security gain over 1A, since the default only yields an MCP-audience token |
| 1C Logical audiences | Adds a mapping layer and breaks the "PRM `resource` = checked `aud`" invariant (I8) to solve URL churn that a local stack does not have |
| 1D Host aud = issuer | Conflates AS and API identities (J-16). Every T0/T2 audience changes if the AS ever moves (FU-9) |
| 2A Audience gate only | Leaves the auth-code→host-API shortcut open if the H2 registry accepts the API URI |
| 2C Actor requirement | Valid, but couples the host RS to one client id. 2B closes the same hole at issuance more generally. Keep as optional stretch |
| 2D Both | Two rules to keep consistent; disproportionate for a local showcase |
| 3B Independent scopes | Edit-only tokens cannot list; MCP-14 met only on paper |
| 3C Translate to READ/EDIT | Makes T2 authority-identical to T0 and needs matcher clean-up to pay off (K11) |
| 3D Hierarchy at resource servers | Duplicates the rule in two codebases and grows matchers against the standard |
| 4B Spring provider + `immediateExecution` | Three non-default overrides (K9), any of which silently reintroduces scope bleed or a missing principal. Changes the SDK execution model |
| 4C Hybrid | Keeps most of 4B's configuration surface to replace a single form POST |
| 4D Holder + `finally` | Keeps the thread-bound pattern that produced V7 |
| 5B Caches | Optimises an unmeasured cost at the price of revocation lag and cache-key risk. Contradicts the team standard |
| 5C JWKS now | Puts a signing-key migration on the critical path that introspection makes unnecessary (I2). Revocation only at `exp` |
| 6B Impersonation | Drops the standard accountability signal (`act`) for no saving |
| 6C `actor_token` | Needs a `client_credentials` grant and extra round trips to produce the same `act` claim |
| 7B Liquibase hash | Commits a known secret, repeating F7; dev-context only |
| 7C Config clients | Two client registries and a `client_id` collision risk with DCR |
| 8A Hop only | Leaves token logging in the very file being rewritten; misses a near-free refresh-binding fix |
| 8C + `_token` | Touches the consent-page flow (host frontend auth, outside the brief); not on the hop |
| 8D Full sweep | Scope creep; FU-2 persistence deserves its own design |

## Deferred Ideas

**Scope classification**
| Item | Class | Rationale |
|---|---|---|
| All §8 gaps H1–H9, S1–S4, M1–M9 and test rewrites | In-scope | Required by the research question |
| FU-3 token logging, FU-5 local defaults/env, FU-2a refresh client binding | In-scope (bundled, DA-8) | Same structures are edited anyway |
| 2C actor requirement on `mcp:*` | Stretch | Cheap extra defence. Add if review asks for defence in depth |
| URI normalisation for `resource` comparison | Stretch | Needed only if assumption 2 fails |
| FU-4 `_token` restriction | Stretch | MUST, but consent-page coupling puts it outside the brief |
| FU-1 DCR → CIMD / pre-registration | Out-of-scope | MCP client↔MCP server hop |
| FU-2b refresh-token persistence, reuse detection, absolute lifetime | Out-of-scope | Needs a storage decision |
| FU-6 asymmetric keys + JWKS (and 5C) | Out-of-scope | Unnecessary with introspection |
| FU-7 `private_key_jwt` | Out-of-scope | Depends on FU-6 |
| FU-8 DPoP / mTLS | Out-of-scope | No Spring DPoP client; local HTTP stack |
| FU-9 SAS migration ADR | Out-of-scope | Build-vs-buy decision of its own |
| FU-10 CIMD / ID-JAG | Out-of-scope | Other hop |
| FU-11 misc hardening (client-info endpoint, host 401 header, RFC 9207 `iss`, MCP `Origin`, DEBUG logs, `state` encoding) | Out-of-scope | Not on the hop |

**New ideas surfaced during brainstorming (deferred)**
- **Audit log of MCP-originated writes keyed on `act.sub`.** 6A makes it possible; the host could record the actor on product and category writes. Worth it once there are multiple MCP servers.
- **Introspection/exchange latency metric (Micrometer timer)** to test assumption 3 cheaply before anyone considers 5B.
- **PRM `resource_name`** and a `scope` hint per tool in `tools/list` descriptions, to improve the consent UX.
- **Candidate standards** (from report §11.4): "metadata advertises only implemented behaviour, with a test per field"; "every host JWT carries and every RS validates `iss` + `aud`"; "security-sensitive values are never logged". Plus one from this exploration: "each OAuth resource declares which grants may target it" (DA-2).
- **Reserved `client_id` namespace for internal clients** in DCR (relevant if 7C is ever chosen).
