# Synthesis: MCP auth refactor (replacing token passthrough)

## TL;DR
- All nine findings files agree on the diagnosis (High confidence). The MCP server validates nothing and forwards the client's token. The host accepts any token signed with its one HMAC key on every `/api/**` endpoint. No token anywhere carries an `aud`. Each of the four vulnerabilities in the brief is real and confirmed in the code.
- The specs fix the target but not the mechanism. MCP 2026-07-28 says the MCP server MUST validate the audience, MUST NOT accept or transit foreign tokens, and MUST NOT pass through the client token. How the upstream token is obtained is left to the implementer. RFC 8693 §1 names exactly this case: a resource server acting as a client.
- The course (M04L07), the team standard (`standards/mcp-server/conventions.md`) and every vendor pattern found (Auth0, Okta, Entra OBO, Keycloak V2, SAS) converge on one shape: validate inbound with `aud` = the MCP server, then exchange for a new token with `aud` = the backend. Introspection without caching is the inbound mode named by both the course and the team standard.
- The strongest independent insight: **introspection decouples the signing-key question from this refactor**. With introspection, the MCP server never holds host keys, so the HMAC to asymmetric migration becomes a follow-up rather than a prerequisite. Local JWT validation would make it a prerequisite.
- Biggest residual risks: the scope escalation bug (a `viewer` can get `mcp:edit`), which an exchange grant would carry into backend tokens unless scopes are intersected; MCP SDK threading, because tools run on `boundedElastic` and `SecurityContextHolder`-based Spring client components do not see the principal; and Spring defaults that cache exchanged tokens by principal name.

## Key Decisions
- Diagnosis confidence is High. Every vulnerability claim rests on `file:line` evidence from two independent gatherers (MCP side and host side) that agree with each other.
- Recommendation: extend the custom AS rather than migrate. SAS token exchange only accepts subject tokens issued and stored by SAS itself, so it cannot be bolted on (`external-patterns-implementations.md` §2). Migration is all or nothing, and it is out of scope.
- The team standard (`standards/mcp-server/conventions.md:15`) was written by the excluded `2026-04-13-mcp-token-security` task (its own `Source:` line says so). It is treated as corroborating team intent only. The recommendation below was derived from the spec and the patterns, and it matches the standard independently.

## Open Questions / Risks
- Several Spring integration details are Medium confidence (inferred from source, not tested): the opaque-token principal is not an `OAuth2Token`, the default authorized-client repository is keyed by principal name, and the new Boot 4 starter artifact names.
- The canonical `resource` / `aud` values (MCP URI and host API URI) and the default audience when a client omits `resource` are not settled by any source.
- Scope hierarchy (MCP-14, new MUST in 2026-07-28) interacts with the host matchers. Today an `mcp:edit`-only token cannot GET at the host (`SecurityConfiguration.java:106-107`).

---

## 1. Research question
How should the aj MCP server authenticate to the aj host backend without forwarding the MCP client's bearer token, in line with the MCP Authorization spec (rev 2026-07-28), OAuth 2.1 and RFCs 8693, 7662, 8707, 9068, 9728 and 9700?

## 2. Inputs loaded
| File | Gatherer | Role in synthesis |
|---|---|---|
| `codebase-mcp-auth-flow.md` | codebase-mcp | Inbound and outbound flow, threading, PRM, error mapping, dependencies, tests |
| `codebase-mcp-vulnerabilities-touchpoints.md` | codebase-mcp | V1–V10 and the touchpoint list (files to change) |
| `codebase-host-as-capabilities.md` | codebase-host-as | AS endpoint, grant, claim and validation inventory |
| `codebase-host-as-gaps.md` | codebase-host-as | Gaps (a)–(e) plus pre-existing defects |
| `course-materials-module4.md` | course-materials | L03 rules, L07 reference solution, L05 spec changes |
| `external-specs-requirements.md` | external-specs | ~110 normative rows (MCP-01..37, X-, I-, RI-, J-, P-, B-, O-, BT-, M-) |
| `external-specs-mcp-revision.md` | external-specs | 2026-07-28 confirmed as current; delta from 2025-11-25 |
| `external-patterns-implementations.md` | external-patterns | Spring 7.1, SAS, Keycloak, Auth0, Entra, MCP implementations, DPoP/mTLS |
| `external-patterns-caching-effort.md` | external-patterns | Caching rules, effort matrix (custom AS vs SAS vs Keycloak) |

Plus `planning/research-brief.md`, `planning/research-plan.md`, and (as intent only) `.maister/docs/standards/mcp-server/conventions.md:15`.

---

## 3. Cross-source analysis

### 3.1 Validated findings (confirmed by two or more sources)

| # | Finding | Sources that agree | Confidence |
|---|---|---|---|
| C1 | MCP server accepts any `Bearer` string with no validation | codebase-mcp (`McpJwtFilter.java:30-36`, Javadoc `:15-19`); course L06 p4 (instructor states it); brief | High |
| C2 | The client token is re-sent to the host unchanged (passthrough) | codebase-mcp (`RestClientConfig.java:76-85`); test that pins it (`RestClientConfigTests.java:26-44`); course L06 p4 | High |
| C3 | No token carries `aud`, and the host checks neither `aud` nor `iss` | codebase-host-as (`JwtTokenProvider.java:47-58, 68-83`); codebase-mcp (no `aud` comparison in `src/main`) | High |
| C4 | Host `/api/**` accepts app session tokens and MCP OAuth tokens through the same filter and key | codebase-host-as §8; codebase-mcp V3 (`SecurityConfiguration.java:106-120`) | High |
| C5 | The host AS has no introspection, exchange, `resource`, JWKS or `client_credentials` support | codebase-host-as §1, gaps (a)–(e); external-patterns effort matrix | High |
| C6 | The MCP spec forbids passthrough and requires audience validation (unchanged between 2025-11-25 and 2026-07-28) | external-specs (MCP-07, -11, -19, -29, -31, -33); course L03 slide 7 | High |
| C7 | 2026-07-28 is the current MCP revision | external-specs-mcp-revision §1 (live versioning page); course L05 | High |
| C8 | Industry pattern: validate `aud` = self, then exchange for `aud` = downstream; never forward | Auth0 OBO, Okta MCP guide, Entra OBO, Keycloak V2, SAS (external-patterns §§2–6); RFC 8693 §1 (X-01); course L07 | High |
| C9 | Spring Security 7.1 ships every client- and RS-side piece, and they are AS-agnostic | external-patterns §1 (docs plus provider source) | High |
| C10 | SAS token exchange accepts only SAS-issued, stored subject tokens | external-patterns §2 (source-verified `findByToken`) | High |
| C11 | Granted OAuth scopes are not intersected with user permissions, so a `viewer` can get `mcp:edit` | codebase-host-as §3 (`OAuth2TokenFilter.java:142-147`, `AuthorizationCodeData.permissions` never read, `OAuth2AuthorizePage.tsx:47-57`) | High (single gatherer, but direct code evidence with a grep that found no consumer) |
| C12 | Tools run on Reactor `boundedElastic`, so `SecurityContextHolder` is empty inside tool handlers | codebase-mcp §2.3 (bytecode of MCP SDK 0.18.1); external-patterns §1.4 (Spring interceptor depends on `SecurityContextHolderPrincipalResolver`) | High on the MCP SDK behaviour, Medium-High on the Spring consequence |
| C13 | Backend 401/403 reaches the MCP client as JSON-RPC `-32603` inside HTTP 200 | codebase-mcp §4 (bytecode plus code) | High |
| C14 | The PRM `resource` and AS URL default to external `*.labs-skillpanel.com` hosts | codebase-mcp §5 (`application.yml:26,28`); course L06 (Cloudflare tunnel deployment) | High |

### 3.2 Contradictions and how they were resolved

| # | Contradiction | Resolution | Confidence |
|---|---|---|---|
| K1 | `CLAUDE.md:60` and `standards/mcp-server/conventions.md:15` say the MCP server "validates ... introspection ... exchanges". The code does neither | The docs describe the target state written by the excluded task. The code is authoritative for current state. Documentation drift should be fixed in the refactor | High |
| K2 | `CLAUDE.md:48` lists "introspection" as a host AS capability | False on this branch: grep finds no `introspect` (codebase-host-as §1) | High |
| K3 | Course L03 labels the CIMD > DCR priority "Draft Spec". L05 presents it as part of the dated 2026-07-28 spec | 2026-07-28 is Current, DCR is deprecated (MAY) and CIMD is SHOULD (external-specs-mcp-revision §4 row 4). L03 slide predates publication | High |
| K4 | The course says "MUST check audience (RFC 8707)". RFC 8707 itself has no RS-side MUST | The RS-side MUST comes from MCP (MCP-07/19/29), RFC 9068 §4 (J-12), RFC 9700 §2.3 (B-04) and OAuth 2.1 §7.1.4 (O-14). RFC 8707 governs the request parameter and AS-side SHOULD (RI-03). The course is right in substance and loose in attribution | High |
| K5 | MCP-11 "MUST NOT ... transit any other tokens" vs. introspection and exchange both sending the inbound token to the AS | Sending a token back to its own issuer for validation or exchange is not transit to a resource. RFC 7662 and RFC 8693 are built for exactly that. The prohibited act is presenting it to an upstream *resource* (MCP-31, B-10). No source treats AS calls as passthrough | Medium-High (inference; no source states it verbatim) |
| K6 | The MCP spec says the upstream token is "issued by the upstream authorization server", but aj has one AS for both hops | RFC 8693 §1 allows an RS to act as an exchange client at the same AS. The separation the spec relies on is the **audience** (J-16 distinct `aud` per resource), not a separate AS (external-specs Open Questions) | Medium-High |
| K7 | MCP SC "confused deputy" MUST (per-client consent) vs. the brief's "confused deputy" vulnerability | The SC MUST targets MCP *proxy* servers with static client IDs at a third-party AS, which does not apply to aj (MCP-26). The brief's confused deputy is the BP Token Passthrough risk (MCP-34): the backend cannot tell relayed calls from direct ones. That one applies | High |
| K8 | Industry mostly validates inbound JWTs locally (Spring AI mcp-security, most RSs). The course and team standard choose introspection | Both are compliant (O-11: method out of scope). The choice is a trade-off. For aj, introspection wins because it avoids sharing the HMAC key (see Insight I2) and gives immediate revocation on a localhost hop | High on compliance, Medium-High on the preference |
| K9 | Spring `TokenExchangeOAuth2AuthorizedClientProvider` "just works" vs. aj specifics | Three pitfalls: (1) opaque-token principal is not an `OAuth2Token`, so a custom subject resolver is needed; (2) the default principal resolver reads `SecurityContextHolder`, which is empty on `boundedElastic`; (3) the default store is keyed by principal name, so scope bleeds between MCP clients of the same user. Spring client components remain usable, but not with defaults | Medium-High (source inference, not tested) |
| K10 | Codebase gatherer names `spring-boot-starter-oauth2-resource-server`; patterns gatherer says Boot 4 renamed it `spring-boot-starter-security-oauth2-resource-server` | Use the Boot 4 names. Confirm against the Boot 4.0.5 BOM at implementation time | Medium-High |
| K11 | The team standard maps scopes `mcp:read`→READ, `mcp:edit`→EDIT in the exchanged token. The host `SecurityConfiguration` already accepts `PERMISSION_mcp:*` on the same endpoints | Two valid options. Keeping `mcp:*` scopes in the backend token needs no matcher change and keeps MCP-originated calls distinguishable. Translating to READ/EDIT makes them indistinguishable from app sessions (weakens accountability, MCP-34). Lean: keep `mcp:*` plus `act`. Open decision | Medium |

### 3.3 Evidence quality
- **High**: everything grounded in `file:line` (both codebases), primary RFC and MCP text fetched on 2026-11-14, and bytecode-verified MCP SDK behaviour.
- **Medium-High**: Spring Security 7.1 behaviour inferred from docs plus provider source (not executed); Boot 4 starter names; the K5 and K6 interpretations.
- **Medium**: InfoQ-only claims about the 7.1 `.dPoP()` opt-in; OAuth 2.1 -17 section number for "confidential clients MUST authenticate"; RFC 8414 rows cited from memory by the gatherer.
- **Low**: Keycloak RFC 8707 support (third-party mailing list), AWS AgentCore and Azure APIM details. Context only; nothing in the recommendation depends on them.

---

## 4. Patterns and themes

| Pattern | Description | Evidence | Prevalence | Assessment |
|---|---|---|---|---|
| P1 Trust-and-forward proxy | MCP server as a thin relay that trusts the backend to validate | `McpJwtFilter` Javadoc; `JwtForwardingInterceptor`; L03 "prosta przelotka" | Whole MCP outbound path | Anti-pattern named by the spec, the course and vendors (Entra warning) |
| P2 Single trust domain by key | One HMAC key and one filter for every token family; identity = "signed by us" | `JwtTokenProvider.java:23-26`, `JwtAuthenticationFilter.java:28-35` | All host auth | The root enabler of P1. Without `aud` there is no notion of "token for whom" |
| P3 Hand-written AS as filters | Each OAuth endpoint is an `OncePerRequestFilter` with inline parsing and inline client auth | `OAuth2TokenFilter`, `OAuth2AuthorizationFilter`, `PublicClientRegistrationFilter` | 3 filters, 1 controller | Extensible by adding a `grant_type` branch and one new filter. No reusable client-auth component; logs secrets; issuer derived from request headers |
| P4 SAS as a type library | SAS artifact present, used only for `RegisteredClient` types | `pom.xml:69-72`, imports | 4 types | Lowers migration cost slightly; gives nothing at runtime |
| P5 Stateless per-request auth | MCP is stateless (2026-07-28, L02/L05/L06), so every request re-authenticates | external-specs delta row 1; course L05 | Protocol-wide | Favours per-request introspection (no session cache) and makes caching keyed by token hash, never by session |
| P6 Validate-then-exchange (delegation) | Inbound `aud` = self, exchange for `aud` = downstream, `act` = middle tier | Auth0, Okta, Entra, Keycloak, SAS, RFC 8693 §1, course L07 | Universal among vendors found | The target pattern |
| P7 Tests pin the vulnerability | Tests assert passthrough and trust-any-bearer as correct behaviour | `RestClientConfigTests:26-44`, `McpJwtFilterTests:20-34` | 6 MCP test classes | They must be rewritten. There is also no security-chain HTTP test on either side |
| P8 Advertised but not honoured | Metadata claims capabilities the code lacks | PRM `resource` not enforced; AS `client_secret_basic` advertised but not parsed; `CLAUDE.md` claims introspection | 3 instances | A recurring drift; worth a standard ("metadata MUST reflect implemented behaviour", with a test per advertised field) |

---

## 5. Key insights

| # | Insight | Evidence | Implication | Confidence |
|---|---|---|---|---|
| I1 | **Audience is the missing primitive; everything else follows.** Passthrough, replay and confused deputy all reduce to "no party can tell whom a token is for". | C3, C4; J-12, B-04, O-14, MCP-29 | Add `aud` at issuance (from RFC 8707 `resource`) and require it at both resource servers *before* exchange. Exchange without an `aud` check at the host would leave the reverse path open: an MCP-client token could still call `/api` directly | High |
| I2 | **Introspection decouples key management from this refactor.** With introspection, only the host verifies host-signed tokens, so HS256 can stay for now. Local JWT validation at MCP would need the shared secret (which lets MCP mint host tokens, contradicting J-02/J-13) or an asymmetric/JWKS migration first | codebase-host-as-gaps Open Q; external-specs J-02, J-13; external-patterns §1.2 | Introspection is the lower-risk, lower-effort inbound mode for aj. Asymmetric keys become a follow-up | High |
| I3 | **The exchange grant is where the scope escalation bug becomes systemic.** If exchange copies subject scopes, a `viewer`'s `mcp:edit` becomes a backend write token minted by a trusted confidential client | C11; SAS also does not intersect with subject scopes (external-patterns §2) | Exchanged scope = subject scopes ∩ user's current permissions ∩ client's allowed scopes. Fix the same intersection at the authorization-code grant | High |
| I4 | **"Only exchange tokens issued to you" needs an explicit AS rule.** Auth0 requires the caller's resource identifier to equal the subject token's `aud`. Keycloak requires the requester to be in the subject `aud` | external-patterns §§3, 4.1 | The host must map the MCP server's `client_id` to the MCP resource URI and refuse exchange (`invalid_request` or `invalid_target`) for subject tokens with any other `aud`. Otherwise any confidential client could convert any MCP token | High |
| I5 | **Threading dictates the outbound wiring.** Tools run off the servlet thread. Either pass the validated `Authentication` through `McpTransportContext` (the extractor runs on the servlet thread after the filter chain) or set `immediateExecution(true)`. Either way, the raw header extraction (V6) and the static `ThreadLocal` (V7) go | C12; codebase-mcp §2.3; external-patterns §1.4 | Design decisions D-4 and D-5 in the report. Spring's `OAuth2ClientHttpRequestInterceptor` with defaults would silently find no principal under the current threading | High on the constraint, Medium on the best option |
| I6 | **Caching is a security decision, not a performance one, on a localhost hop.** Introspection plus exchange costs two local HTTP calls per tool call. Cache errors cause revocation lag and scope bleed | RFC 7662 §4 (I-15); external-patterns-caching §2; team standard "no caching" | Start with no caching for both. If ever added: key by SHA-256 of the subject token, cap at min(exchanged `exp`, subject `exp`), bounded store, evict on backend 401 | High |
| I7 | **Delegation (`act`) is cheap to add because the AS already knows the actor.** The MCP server authenticates as a confidential client, so the AS can add `act: {sub: <mcp client_id>}` without an `actor_token` | X-02, X-15, X-16; Auth0 OBO (`act.sub` = `azp`) | The backend can tell relayed calls from direct ones (closes MCP-34 accountability) with no extra client work | Medium-High |
| I8 | **The PRM `resource` must equal the value the validator checks and the value clients send as `resource`.** Today it defaults to an external host | P-05; MCP-18; C14 | One property (`aj.mcp.base-url`) must drive PRM `resource`, the 401 `resource_metadata` URL and the introspection `aud` check. Local defaults are needed for a working local flow | High |
| I9 | **The MCP server's own 401/403 behaviour is part of compliance, not polish.** Invalid tokens MUST get 401 (MCP-08). Insufficient scope SHOULD get 403 with `insufficient_scope` (MCP-13). Today neither can happen | C13; V8, V10 | With real validation at the MCP filter, 401/403 happen before any tool runs. Backend 401 after exchange becomes a server-side failure (team standard: no retry) | High |

---

## 6. Relationships and data flow

### 6.1 Trust-boundary map (target)
| Token | Issued by | `aud` | Validated by | Key / method | May be presented to |
|---|---|---|---|---|---|
| T0 app session | host `/api/auth/login` | host API URI (new) | host `JwtAuthenticationFilter` | HS256 (unchanged) | host `/api/**` only |
| T1 MCP access token | host `/oauth2/token` (auth code + PKCE) | MCP canonical URI (from `resource`) | MCP server via host `/oauth2/introspect` | HS256, verified only by the host | MCP server only (and back to the AS for introspection/exchange) |
| T2 backend token | host `/oauth2/token` (token exchange) | host API URI | host `JwtAuthenticationFilter` | HS256 | host `/api/**` only |
| MCP client secret | seeded confidential client | n/a | host client authenticator | BCrypt | host `/oauth2/introspect`, `/oauth2/token` |

### 6.2 Dependency chain for implementation order
1. Host: stable `issuer` + `aud` claim + `resource` handling + client authenticator (Basic/post). Everything else depends on these.
2. Host: `aud` + `iss` enforcement in `JwtAuthenticationFilter`; app tokens get `aud` = host API.
3. Host: seeded MCP confidential client; introspection endpoint; exchange grant with scope intersection.
4. MCP: resource server (introspection + `aud` + scope), validated principal in context.
5. MCP: exchange client + outbound interceptor; delete passthrough.
6. Tests both sides; docs drift (`CLAUDE.md:48,60`).

---

## 7. Gaps and uncertainties
- **Not verified by running code**: no gatherer executed the Spring client or RS components against the host. Items K9 and K10 are inferences.
- **Runtime state**: `mcp-server/target/classes/application.yml` is stale (codebase-mcp Open Q). A clean build is needed before any runtime test.
- **Unknown default `resource` behaviour of real MCP clients** (Claude, ChatGPT): MCP-18 says clients MUST send `resource`, but no source confirms what Claude sends today. The AS default (J-08) must be chosen defensively.
- **DCR `grant_types` omitted → probable 500** (codebase-host-as §4, Medium, untested). Not in this refactor's path, but it touches DCR.
- **Liquibase `010` gap** was not investigated (exclusion rule).
- **Course gives no parameters, claims or caching policy** for its L07 solution. Every concrete parameter in the recommendation comes from the RFCs and vendor patterns, not the course.

---

## 8. Synthesis by framework

### 8.1 Technical (component, flow)
- Components to change: host `core/oauth2` (token filter, new introspection filter, client authenticator, metadata), host `core/security` (provider claims, filter validation, `SecurityConfiguration` matcher for introspection), MCP `config`, `security`, `service`, `AjMcpApplication`, `application.yml`, `pom.xml`.
- Flow change: one inbound hop gains validation (introspection), and one outbound hop gains a credential step (exchange). Tools keep their behaviour (L07 success criterion).

### 8.2 Requirements (compliance)
The compliance matrix (report §5) has 34 rows. Current code: 6 met, 7 partial, 19 missing or violated, 2 not applicable (no endpoint exists yet). With the in-scope changes, every MUST row on the MCP-to-host hop is met. Three pre-existing AS-wide MUSTs stay open until the follow-ups land: B-12 (tokens in host INFO logs), B-15/O-07 (refresh tokens not bound to a client) and O-09 (the `_token` query parameter). Three SHOULD/RECOMMENDED rows are deferred with rationale: asymmetric keys/JWKS, sender-constraining, and HTTPS on the local stack.

### 8.3 Literature (alternatives)
Six alternatives scored (report §6). Two are complementary requirements rather than alternatives: audience binding (A3) is mandatory under every option, and the inbound validation mode (A2) is a sub-choice. The real decision is A1 + A2(introspection) + A3, against A4 (client credentials), A5 (SAS/Keycloak) or A6 (DPoP/mTLS) as replacements or add-ons.

---

## 9. Conclusions
- **Primary**: replace passthrough with RFC 8693 token exchange (delegation, `act`) at the existing custom AS. Validate inbound tokens at the MCP server via RFC 7662 introspection plus an explicit `aud` check against its RFC 9728 `resource`. Bind every token to an audience (RFC 8707 `resource` → `aud`) and enforce `aud` at the host too. Confidence High.
- **Secondary**: the scope/permission intersection must ship with the exchange grant. Threading forces explicit principal propagation. Caching should be off. Spring client/RS components are usable but need non-default configuration, so a small hand-written `TokenExchangeClient` is a legitimate choice.
- **Deferred**: asymmetric keys/JWKS, DPoP/mTLS, SAS migration, DCR→CIMD, refresh-token persistence and binding, token logging cleanup, the `_token` parameter, external default URLs (partly needed in scope; see report).
