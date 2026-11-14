# Research Report: MCP auth refactor, replacing token passthrough

**Research type**: Mixed (codebase analysis + standards/literature + course materials) | **Date**: 2026-11-14 | **Branch**: `feat/mcp-auth-refactor` (HEAD `bd4de70`) | **Researcher**: maister research-synthesizer | **Overall confidence**: High (diagnosis and direction), Medium-High (Spring integration details)

## TL;DR
Replace passthrough with **RFC 8693 token exchange (delegation)** at the existing hand-written host AS. The MCP server validates inbound tokens with **RFC 7662 introspection** (no cache) and **checks that `aud` equals its RFC 9728 `resource`**. It then exchanges the token for a short-lived backend token with `aud` = host API, `client_id` and `act` = MCP server, and scope = subject scopes ∩ user permissions. The host must issue `aud` (from RFC 8707 `resource`) on every token and **reject tokens at `/api/**` whose `aud` is not the host API**. Do not migrate to SAS or Keycloak now. Defer DPoP/mTLS and asymmetric keys: introspection means the MCP server never needs the host's signing key.

## Key Decisions
- **Outbound: RFC 8693 token exchange, delegation flavour.** This is the only option that keeps the user's identity and permissions downstream and adds the MCP server as a distinguishable actor. The course (M04L07), the team standard and every vendor pattern found (Auth0, Okta, Entra OBO, Keycloak, SAS) use it.
- **Inbound: RFC 7662 introspection with no caching, plus an explicit `aud` check at the MCP server.** It needs no shared HMAC secret and no JWKS migration, revocation is immediate, and the local hop costs little. Local JWT validation with RS256 + JWKS stays the upgrade path.
- **Audience binding on both resource servers.** This is mandatory under every option, not an alternative. Without a host-side `aud` check, an MCP client token could still be replayed directly at `/api`.
- **Extend the custom AS; no SAS or Keycloak migration in this task.** SAS token exchange only accepts subject tokens that SAS itself issued and stored, so it cannot be added piecemeal.
- **Ship the scope ∩ permission intersection with the exchange grant.** Otherwise the existing `viewer`→`mcp:edit` escalation turns into backend write tokens minted for a trusted client.
- **Client credentials + propagated user header (A4) is rejected.** It recreates the confused deputy.

## Open Questions / Risks
- **Design decisions still open**: the canonical `aud` values, the default audience when `resource` is omitted, how tool handlers receive the validated principal (threading), a hand-written `TokenExchangeClient` vs Spring's `TokenExchangeOAuth2AuthorizedClientProvider`, whether `mcp:edit` implies `mcp:read`, and the confidential-client seeding mechanism. See §10.
- **Spring defaults are unsafe here** (Medium-High, not executed). The opaque-token principal is not an `OAuth2Token`. The default `OAuth2ClientHttpRequestInterceptor` reads `SecurityContextHolder`, which is empty on the Reactor `boundedElastic` threads where tools run. The default authorized-client cache is keyed by principal name, so scope leaks between MCP clients of the same user.
- **Three pre-existing AS-wide MUST violations remain** unless follow-ups are bundled: tokens and secrets logged at INFO (RFC 9700 §4.9.3), refresh tokens not bound to a client (RFC 9700 §4.14 / OAuth 2.1 §3.2.3), and bearer tokens accepted in the `_token` query parameter (OAuth 2.1 §5.1). Open DCR also stays (allowed as MAY under 2026-07-28, where CIMD is SHOULD).
- **The PRM `resource` currently defaults to an external host** (`kuba-mcp.labs-skillpanel.com`). Once `aud` is enforced, a local run fails unless that default (or the `start-dev` scripts) points at the local MCP URI.

---

## Table of Contents
1. [Executive Summary](#1-executive-summary)
2. [Research Objectives](#2-research-objectives)
3. [Methodology](#3-methodology)
4. [Findings](#4-findings)
5. [Compliance Matrix (current vs target)](#5-compliance-matrix)
6. [Alternatives Comparison](#6-alternatives-comparison)
7. [Recommendation and Target Design](#7-recommendation-and-target-design)
8. [Gap List per Component (in scope)](#8-gap-list-per-component-in-scope)
9. [Follow-ups (out of scope)](#9-follow-ups-out-of-scope)
10. [Open Design Questions for the Decision Phase](#10-open-design-questions-for-the-decision-phase)
11. [Analysis and Insights](#11-analysis-and-insights)
12. [Conclusions](#12-conclusions)
- [Appendix A: Sources](#appendix-a-sources)
- [Appendix B: Gaps and Uncertainties](#appendix-b-gaps-and-uncertainties)
- [Appendix C: Contradictions Resolved](#appendix-c-contradictions-resolved)

---

## 1. Executive Summary

**What was researched.** How the aj MCP server (`mcp-server/`, Spring Boot 4, MCP Java SDK 0.18.1, port 8081) should authenticate to the aj host REST API (`:8080`) without forwarding the MCP client's bearer token. The question covers the MCP server's inbound validation, its outbound credential, and the host's hand-written OAuth2 authorization server (`core/oauth2`) and JWT security (`core/security`).

**How.** Five parallel gatherers produced nine findings files: two on the MCP codebase, two on the host AS, one on course module 4 (11 PDFs), two on normative specs (MCP 2026-07-28, OAuth 2.1 -17, RFCs 8693, 7662, 8707, 9068, 9728, 9700, 6750, plus 9449, 8705, 7523 for applicability), and two on industry patterns (Spring Security 7.1, SAS, Keycloak, Auth0, Entra, Okta, Cloudflare, Spring AI). Synthesis joined the spec requirements against the code into a compliance matrix, scored six alternatives, and derived a gap list.

**Key findings.**
- The MCP server performs **no validation** (`McpJwtFilter.java:30-36` accepts any `Bearer` string). It forwards the raw client token through a never-cleared static `ThreadLocal` (`RestClientConfig.java:76-85`). Tests pin this behaviour.
- The host issues **no `aud`** in any token and checks **neither `aud` nor `iss`** (`JwtTokenProvider.java:47-83`). App session tokens and MCP OAuth tokens are accepted alike on `/api/**` (`SecurityConfiguration.java:106-120`). That is why passthrough "works", and why replay is possible in both directions.
- The host AS supports only `authorization_code` + PKCE and `refresh_token`. It has no introspection, exchange, `resource`, JWKS, `client_credentials` or seeded confidential client. It also lets a READ-only `viewer` obtain `mcp:edit` (privilege escalation).
- MCP 2026-07-28 (confirmed current) **requires** audience validation by the MCP server and **forbids** accepting, transiting or passing through foreign tokens. It does not prescribe how to obtain the upstream token. RFC 8693 §1 describes exactly this case.

**Main conclusion.** Adopt **introspection inbound + RFC 8693 exchange outbound + `aud` enforcement at both resource servers**, implemented by extending the custom AS. This eliminates all four brief vulnerabilities (§7.4), matches the course's reference solution (M04L07) and the team standard (`standards/mcp-server/conventions.md:15`), and keeps tools behaving exactly as before. Estimated effort: **medium**. Most of it is host-AS work (new grant, new endpoint, claims, client auth). The MCP side is mostly deletions plus standard Spring Security wiring.

---

## 2. Research Objectives

**Primary question.** How should the aj MCP server authenticate to the aj host backend without forwarding the MCP client's bearer token, in line with the MCP Authorization spec (rev 2026-07-28), OAuth 2.1 and RFCs 8693, 7662, 8707, 9068, 9728 and 9700?

**Sub-questions** (from the research plan)
1. Current state, MCP: the token flow, validation, principal, 401 and PRM behaviour, tests.
2. Current state, host AS: endpoints, grants, claims, scope→permission mapping, missing RFC features.
3. Spec requirements for an MCP server as resource server and as client to upstream APIs.
4. What course module 4 teaches (L03 rules, L05 spec revision, L07 solution).
5. Industry practice (Spring Security 7 / SAS, Keycloak, Auth0, Entra, MCP reference servers, DPoP/mTLS).
6. Comparison of the six alternatives and the minimal change set.

**Scope.** Included: MCP inbound validation, MCP→host outbound auth, host AS capabilities, course module 4, the listed specs. Excluded: implementation, frontend and plugin auth, `week7/`–`week10/`, `tools/`, reverted commit `e095025`, and the `2026-04-13-mcp-token-security` task artifacts. SAS or Keycloak migration is evaluated but not planned.

---

## 3. Methodology

| Aspect | Detail |
|---|---|
| Type | Mixed: technical (code), requirements (spec MUST/SHOULD), literature (vendor patterns), course review |
| Codebase sources | `mcp-server/`: 18 main classes, `application.yml`, `pom.xml`, 9 test classes; MCP SDK 0.18.1 behaviour checked by `javap` disassembly. Host: 9 classes in `core/oauth2`, 4 in `core/security`, migrations 008/009, `application.properties`, `pom.xml`, `OAuth2IntegrationTests`, `AuthIntegrationTests` |
| Course sources | `_docs/AJ_M04L01..L07_transkrypcja.pdf` + `AJ_M04L01..L04_prezentacja.pdf` (11 PDFs, about 16k words), extracted with `pdftotext` |
| Normative sources | 20 primary pages fetched 2026-11-14: MCP versioning, authorization (+3 sub-pages), Security Best Practices, changelog, 2025-11-25 authorization (for the delta), ext-auth, RFCs 8693, 7662, 8707, 9068, 9728, 9700, 6750, 9449, 8705, 7523, OAuth 2.1 datatracker and -17 |
| Pattern sources | About 30 vendor and framework pages: Spring Security 7.1.2 reference and provider source, SAS, Keycloak 26, Auth0, Entra and MSAL, Okta, Cloudflare, Spring AI mcp-security, AWS and Azure (low confidence) |
| Frameworks applied | Trust-boundary map; requirement compliance matrix (§5); six-alternative scoring (§6); per-component gap list (§8) |
| Confidence scale | High = direct code or primary-spec evidence, corroborated. Medium-High = single primary source or source-code inference. Medium = secondary source or untested inference. Low = snippets or conflicting sources |

---

## 4. Findings

### F1. The MCP server accepts any bearer token: Security, High
`McpJwtFilter.java:30-36` sets `UsernamePasswordAuthenticationToken("mcp-user", null, List.of())` whenever the header starts with `"Bearer "`. Its Javadoc (`:15-19`) says "No JWT validation — trust-and-forward model". The principal has no `sub`, scopes or audience. No scope check exists, so a `mcp:read` token can call `aj_add_product`, and only the host's 403 stops it.
**Implication**: it violates MCP-06, -07, -10, -11, -27, -29, -33 and -35, and makes the MCP server an open relay.

### F2. The raw client token is forwarded to the host (passthrough): Security, High
The token is parsed a second time in the transport `contextExtractor` (`AjMcpApplication.java:42-46`, independent of the filter). It is put into `McpTransportContext`, copied into a static `ThreadLocal` by each handler (`ProductService.java:31,43`, `CategoryService.java:29`) and re-sent by `JwtForwardingInterceptor` (`RestClientConfig.java:78-80`). The `ThreadLocal` is never cleared in production code. `RestClientConfigTests.java:26-44` asserts the passthrough header.
**Implication**: it violates MCP-31, the explicit MUST NOT. The course (L06 p4) presents it as the exercise baseline.

### F3. Tool handlers run off the servlet thread: Architecture constraint, High
MCP SDK 0.18.1 `StatelessSyncSpecification` defaults `immediateExecution=false`, so sync handlers run under `subscribeOn(Schedulers.boundedElastic())`. The servlet `SecurityContext` is invisible to them.
**Implication**: the replacement must pass the validated principal explicitly (via `McpTransportContext`) or set `immediateExecution(true)`. Spring's default `OAuth2ClientHttpRequestInterceptor` (which resolves the principal from `SecurityContextHolder`) would silently find nothing.

### F4. No token has an audience, and the host checks neither `aud` nor `iss`: Security, High
`JwtTokenProvider.generateOAuth2Token` (`:47-58`) emits `iss` (derived from request headers), `sub`, `scopes`, `iat`, `exp`. App login tokens (`:29-43`) carry `sub`, `permissions`, `iat`, `exp`. `parseToken` (`:68-83`) verifies only the signature and `exp`, and merges `permissions` and `scopes` into one `PERMISSION_*` namespace (`JwtAuthenticationFilter.java:28-35`). `SecurityConfiguration.java:106-120` accepts `PERMISSION_mcp:read|edit` and `PERMISSION_READ|EDIT` on the same endpoints.
**Implication**: MCP tokens work at `/api` directly, and app tokens would work through MCP. Replay crosses services in both directions (fails B-04, O-14, J-12).

### F5. The host AS lacks every building block for a no-passthrough design: Capability, High
Grants are only `authorization_code` and `refresh_token` (`OAuth2TokenFilter.java:62-69`). There is no `/oauth2/introspect`, no token-exchange branch, no `resource` parameter, no JWKS, no `client_credentials`. DCR rejects other grant types (`PublicClientRegistrationFilter.java:135-147`). No confidential client is seeded (only migration 009 creates the table). Client authentication is inline, reads only the `client_secret` form field (`:82, :116-131`), and ignores the advertised `client_secret_basic`.
**Implication**: most of the work is on the host. The cheapest path is a new `grant_type` branch, a new introspection filter, and a shared client authenticator.

### F6. Scope escalation: a READ-only user can obtain `mcp:edit`: Security, High
`OAuth2AuthorizationFilter.java:98-109` stores the user's permissions in `AuthorizationCodeData`, but `OAuth2TokenFilter.java:142-147` issues `data.scope()` and never reads them. The consent UI (`OAuth2AuthorizePage.tsx:47-57`) does not filter either. A `viewer` therefore gets a token that maps to `PERMISSION_mcp:edit` (write access through `SecurityConfiguration.java:115-120`).
**Implication**: an exchange grant that copies subject scopes would propagate the escalation into backend tokens. The intersection must ship with the exchange (I3).

### F7. Single HS256 key with a committed default: Security, High
`Keys.hmacShaKeyFor(...)` (`JwtTokenProvider.java:23-26`) uses `${APP_JWT_SECRET:i/WZn...}` (`application.properties:4`), which nothing overrides (`compose.yml`, `start-dev.*`). There is no `kid`, no rotation, no `typ`.
**Implication**: local JWT validation at the MCP server would require sharing this secret, which lets the MCP server mint host tokens and contradicts RFC 9068 J-02/J-13. Introspection avoids the problem entirely (I2).

### F8. MCP 2026-07-28 is current; MCP-server auth MUSTs unchanged since 2025-11-25: Spec, High
The versioning page lists 2026-07-28 as current. Token Handling, Access Token Privilege Restriction and Audience Binding text is unchanged. The token-passthrough rationale moved to Security Best Practices. New in this revision: the scope-hierarchy MUST (MCP-14), stateless protocol (no `Mcp-Session-Id`), RFC 9207 `iss`, DCR deprecated (MAY) in favour of CIMD (SHOULD), and a Refresh Tokens section.
**Implication**: the course's revision is correct. Stateless means every HTTP request is authenticated independently, so no session-scoped auth cache.

### F9. The MCP spec defines the end state, not the mechanism: Spec, High
MCP-30 says the server "may act as an OAuth client" and that the upstream token "is a separate token". RFC 8693/7662/9068 are not in the MCP compliance list. RFC 8693 §1 (X-01) describes a resource server exchanging a received token for a backend token. OAuth 2.1 §5.2 (O-11) leaves the validation method open (introspection or JWT).
**Implication**: the mechanism choice must be justified on security and cost. That is what §6 does.

### F10. The industry converges on validate-then-exchange: Patterns, High
Auth0 OBO (`aud` = downstream, `azp` and `act` = middle tier; only the resource-server client whose identifier equals the subject `aud` may exchange). Okta MCP guide (exchange + ID-JAG for cross-domain). Entra OBO (incoming `aud` must be the middle tier). Keycloak V2 (requester must be in the subject `aud`, exchanged `exp` capped at the subject's). SAS (exchange with `act`). Cloudflare (tokens bound to the MCP resource, never forwarded).
**Implication**: the recommended design is mainstream, not bespoke.

### F11. Spring Security 7.1 covers the MCP side, with non-default configuration: Patterns, Medium-High
Available: `opaqueToken()` introspection, `JwtDecoder` with audience and `at+jwt` validators, `TokenExchangeOAuth2AuthorizedClientProvider` (6.3+), `OAuth2ClientHttpRequestInterceptor`. Pitfalls: a custom subject-token resolver is needed for opaque principals; the principal resolver depends on `SecurityContextHolder`; the default store is keyed by principal name; `expires_in` is required or tokens are cached forever. Spring's introspection does not check `aud`, so a custom introspector wrapper is needed. Boot 4 renames the starters to `spring-boot-starter-security-oauth2-{resource-server,client}`.
**Implication**: Spring is the right inbound tool. For outbound, a small hand-written `TokenExchangeClient` is a legitimate choice (D-4).

### F12. SAS cannot be added just for exchange; Keycloak is an IdP swap: Patterns, High
SAS exchange looks up the subject via `authorizationService.findByToken(...)`, so the subject must be issued and stored by SAS. SAS does not handle `resource`/`aud` natively (an `OAuth2TokenCustomizer` is needed) and does not intersect requested scopes with subject scopes. Keycloak V2 has no `resource` parameter, delegation is in preview, and adopting it moves users and login to Keycloak.
**Implication**: migration is all or nothing (high or very high effort) and buys nothing the custom-AS extension cannot deliver for this hop.

### F13. The course expects introspection + exchange and leaves the parameters open: Course, High
L03 slide 7: no token forwarding (confused deputy), audience binding, refresh rotation, PKCE S256. L07: an introspecting filter at MCP, a `TokenExchangeClient`, a host `IntrospectionFilter` and an exchange grant, with the success criterion "the service works exactly as before". Not covered: caching, `aud` values, `act`, MCP client authentication, opaque vs JWT.
**Implication**: the recommendation is course-aligned. Every parameter comes from the RFCs and vendor patterns.

### F14. Error semantics and discovery defaults are broken at the MCP server: Compliance, High
Backend 401/403 surface as JSON-RPC `-32603` inside HTTP 200 (`RestClientConfig.java:39-53` + SDK handler). The 401 challenge has no `error` or `scope`, and there is no 403 handler. The PRM `resource` and AS URL default to `*.labs-skillpanel.com` (`application.yml:26,28`), which `start-dev.*` does not override.
**Implication**: MCP-08/12/13 and P-05 must be fixed alongside. Local defaults are a prerequisite for a working `aud` check on a local run.

### Findings summary

| # | Finding | Category | Confidence | Sources |
|---|---|---|---|---|
| F1 | MCP accepts any bearer | Security | High | 3 (code, course L06, brief) |
| F2 | Raw token forwarded (passthrough) | Security | High | 3 (code, test, course) |
| F3 | Tools run on `boundedElastic` | Constraint | High | 2 (bytecode, Spring docs) |
| F4 | No `aud`; host checks no `aud`/`iss` | Security | High | 2 (host code, MCP code) |
| F5 | AS lacks introspection, exchange, `resource`, JWKS, seeded client | Capability | High | 2 (host code, effort matrix) |
| F6 | `viewer` can obtain `mcp:edit` | Security | High | 1 (host code, grep-verified) |
| F7 | One HS256 key, committed default | Security | High | 2 (code, RFC 9068) |
| F8 | 2026-07-28 current; MCP-RS MUSTs unchanged | Spec | High | 2 (MCP site, course L05) |
| F9 | Spec fixes end state, not mechanism | Spec | High | 3 (MCP SC, RFC 8693, OAuth 2.1) |
| F10 | Industry: validate-then-exchange | Patterns | High | 6 vendors |
| F11 | Spring covers MCP side with caveats | Patterns | Medium-High | Spring docs + source |
| F12 | SAS/Keycloak = full migration | Patterns | High | SAS source, Keycloak docs |
| F13 | Course expects introspection + exchange | Course | High | L03, L07 |
| F14 | Error semantics and default URLs broken | Compliance | High | code, config |

---

## 5. Compliance Matrix

Legend: **Met**, **Partial**, **Missing**, **Violated** (actively does the forbidden thing), **N/A**. "Target" = after the in-scope changes in §8. "FU-n" = follow-up in §9. Requirement IDs refer to `analysis/findings/external-specs-requirements.md`.

| # | Req ID(s) | Keyword | Applies to | Requirement | Current | Evidence | Target |
|---|---|---|---|---|---|---|---|
| 1 | MCP-02 | MUST | MCP-RS | RFC 9728 PRM with `authorization_servers` | Met | `WellKnownController.java:20-28` | Met (points at local issuer) |
| 2 | MCP-03 | MUST | MCP-RS | PRM discovery via `WWW-Authenticate resource_metadata` | Met | `McpAuthenticationEntryPoint.java:21-23` | Met |
| 3 | P-05 | MUST | MCP-RS | PRM `resource` identical to the canonical URI clients use | Partial | right shape, external default `application.yml:28` | Met |
| 4 | MCP-04 | SHOULD | MCP-RS | `scope` in the 401 challenge | Missing | entry point has no `scope` | Met |
| 5 | MCP-06, O-11 | MUST | MCP-RS | Validate token per OAuth 2.1 §5.2 (unexpired, for this resource, right scope) | Missing | `McpJwtFilter.java:31-36` | Met (introspection) |
| 6 | MCP-07, -19, -29, J-12 | MUST | MCP-RS | Token issued for this server (`aud`) | Missing | no `aud` anywhere | Met |
| 7 | MCP-10, -11, -33 | MUST / MUST NOT | MCP-RS | Accept only own tokens; never accept or transit others | Violated | any bearer accepted and forwarded | Met |
| 8 | MCP-27 | MUST | MCP-RS | Validate **before** processing | Missing | tools run on unvalidated input | Met |
| 9 | MCP-08, O-12, BT-02 | MUST | MCP-RS | 401 + `WWW-Authenticate` (`invalid_token`) on invalid or expired token | Partial | 401 only when header absent | Met |
| 10 | MCP-12, -13, O-13 | MUST / SHOULD | MCP-RS | 403 `insufficient_scope` with `scope` | Missing | no `AccessDeniedHandler`; backend 403 → `-32603` in HTTP 200 | Met |
| 11 | MCP-14 | MUST | MCP-RS | Account for scope hierarchies | Missing | no scope checks at all | Met (per D-6) |
| 12 | MCP-15 | SHOULD NOT | MCP-RS | No `offline_access` in `scopes_supported` | Met | `["mcp:read","mcp:edit"]` | Met |
| 13 | MCP-31 | **MUST NOT** | MCP-C | Pass through the client's token | **Violated** | `RestClientConfig.java:78-80` | Met (exchange) |
| 14 | MCP-35 | MUST | MCP-RS | Verify all inbound requests | Missing | F1 | Met |
| 15 | MCP-36 | guidance | MCP-RS | Server-side authorization, not just trusting token scopes | Missing | read token reaches `aj_add_product` | Met (per-tool scope check) |
| 16 | MCP-20, B-12 | MUST | MCP-RS, AS | Treat tokens as secrets (no logging, no lingering storage) | Partial | MCP logs only length, but `ThreadLocal` never cleared; host logs tokens/secrets at INFO (`OAuth2TokenFilter.java:57-59,152-153,234`) | Partial: MCP fixed; host logs = FU-3 |
| 17 | MCP-21, BT-05 | SHOULD | AS | Short-lived access tokens | Met | OAuth AT 15 min (`JwtTokenProvider.java:45`) | Met (backend token ≤ 5 min) |
| 18 | MCP-22, O-08 | MUST | AS | Rotate refresh tokens for public clients | Met (no reuse detection) | `RefreshTokenService.java:31-50` | Met; reuse detection = FU-2 |
| 19 | B-15, O-07 | MUST | AS | Refresh tokens bound to consented scope, client and resource | Missing | `RefreshTokenData` has no client or resource | Partial: resource in scope; client binding = FU-2 |
| 20 | MCP-24 | MUST | AS | PKCE S256 | Met | `OAuth2TokenFilter.java:183-214` | Met |
| 21 | MCP-25 | MUST | AS | Exact redirect URI match | Met | `OAuth2AuthorizationFilter.java:130-134` | Met |
| 22 | MCP-37, M-03 | MUST | AS | Metadata `issuer` stable and identical to the discovery URL | Partial | issuer derived from `X-Forwarded-*` (`OAuth2MetadataController.java:54-83`) | Met (configured issuer) |
| 23 | RI-03, B-03, J-07, J-08 | SHOULD / MUST | AS | Audience-restrict tokens to `resource`; default `aud` when absent | Missing | `resource` never read | Met |
| 24 | J-04 | REQUIRED | AS | Claims `iss`, `exp`, `aud`, `sub`, `client_id`, `iat`, `jti` | Partial | `aud`, `client_id`, `jti` missing | Met |
| 25 | J-03 | MUST / SHOULD | AS | `typ: at+jwt` | Missing | jjwt default header | Met (recommended, low cost) |
| 26 | J-02, J-09 | RECOMMENDED / SHOULD | AS | Asymmetric signing; `jwks_uri` | Missing | HS256 only (F7) | Deferred: FU-6 (not needed with introspection) |
| 27 | B-04, O-14 | **MUST** | BE-RS | Host refuses tokens not meant for it | Missing | no `aud` check (F4) | Met |
| 28 | X-03…X-13 | REQUIRED / MUST | AS, MCP-C | Token-exchange grant semantics (subject validation, `issued_token_type`, errors) | Missing | `unsupported_grant_type` | Met |
| 29 | X-10, B-07, O-05 | SHOULD / MUST | MCP-C, AS | MCP server authenticates as a confidential client | Missing | no client identity (V4) | Met (`client_secret_basic`); asymmetric = FU-7 |
| 30 | I-03, I-12 | MUST | AS | Introspection requires an authenticated caller | N/A | no endpoint | Met |
| 31 | I-15 | MUST NOT | MCP-RS | No caching beyond `exp` | N/A | no introspection | Met (no cache) |
| 32 | O-09 | MUST | BE-RS | Ignore tokens in the query string | Violated | `_token` accepted on any path (`JwtAuthenticationFilter.java:47-51`) | Violated until FU-4 |
| 33 | B-01, O-02 | SHOULD | AS, RS | Sender-constrained tokens | Missing | — | Deferred: FU-8 |
| 34 | MCP-23, O-03 | MUST | AS | HTTPS for AS endpoints (loopback exempt for redirects) | Partial | local stack is plain HTTP | Unchanged (local-only; production via TLS) |

**Totals.** Current: **6 Met, 7 Partial, 19 Missing/Violated, 2 N/A** (34 rows). Target: every MUST on the MCP↔host hop is met. Three pre-existing AS-wide MUSTs remain open until follow-ups land: B-12 host token logging (FU-3), B-15/O-07 refresh-token client binding (FU-2), O-09 `_token` (FU-4). Three SHOULD/RECOMMENDED rows are deferred (rows 26, 33, 34).

---

## 6. Alternatives Comparison

The brief lists six alternatives. Two of them are **sub-decisions or complements**, not substitutes: audience binding (A3) is required under every option, and the inbound validation mode (A2) pairs with any outbound choice. They are still scored individually so each can be judged on its own terms.

Scale: Security and spec compliance from ●●●●● (best) to ●○○○○. Complexity: Low, Medium, High, Very high (effort for aj). Fit = fit with the hand-written AS + Spring Security 7. Course = alignment with M04L03/L07.

| Alternative | Security | Complexity (host / MCP) | Spec compliance | Fit with custom AS + Spring 7 | Course alignment | Verdict |
|---|---|---|---|---|---|---|
| **A1 RFC 8693 token exchange** (delegation, `act`) | ●●●●● Removes passthrough; backend token is audience-bound, short-lived, carries user `sub` + actor | Medium / Low-Medium | ●●●●● Satisfies MCP-31, X-01..X-16, B-03/B-07 | Good: new `grant_type` branch in `OAuth2TokenFilter.java:62-69`; Spring client provider exists (6.3+) but needs custom resolver and cache | ●●●●● Exactly L07 | **Recommended (outbound)** |
| **A2a Inbound: RFC 7662 introspection** | ●●●●○ Immediate revocation; MCP never holds keys; one more round trip | Low-Medium / Low | ●●●●● O-11, I-01..I-16 | Good: one new filter + `opaqueToken()`; `aud` check via introspector wrapper | ●●●●● L07 + team standard | **Recommended (inbound)** |
| A2b Inbound: local JWT, shared HMAC | ●●○○○ MCP can mint host tokens; same key for all token families | Low / Low | ●●○○○ Contradicts J-02/J-13 RECOMMENDED/MUST "keys provided by the AS" | Easy (`NimbusJwtDecoder.withSecretKey`), but algorithm pinning needed | ●●○○○ Not taught | Rejected |
| A2c Inbound: local JWT, RS256 + JWKS | ●●●●○ No network call; revocation only at `exp` (15 min) | Medium / Low | ●●●●● RFC 9068 native | Medium: jjwt → RS256, JWKS endpoint, `kid`; Spring `jwt()` + `createAtJwtValidator()` | ●●●○○ Valid but not L07 | Acceptable upgrade path (FU-6) |
| **A3 RFC 8707 `resource` + `aud` validation** (both RS) | ●●●●● Closes replay MCP↔host in both directions | Low-Medium / Low | ●●●●● Mandatory: MCP-07/29, B-04, O-14, J-12 | Good: authorize/token param + claim + `requireAudience` | ●●●●● L03 slide 7 | **Required in every option** |
| A4 Client credentials + propagated user context | ●○○○○ Backend must trust MCP for user identity (confused deputy); unsigned header is forgeable by any holder of the client secret; loses per-user consent and scope; signed-assertion variant reinvents exchange | Low-Medium / Low | ●●○○○ Removes literal passthrough (MCP-31) but fails the BP accountability rationale (MCP-34) and least privilege | Needs a `client_credentials` grant plus a custom header trust rule in `JwtAuthenticationFilter` | ●○○○○ Contradicts L03 per-user model and L04 per-call authorization | Rejected |
| A5 Migrate to SAS / Keycloak | ●●●●● Maintained, RFC-complete AS (exchange, introspection, JWKS, DPoP, mTLS) | **High** (SAS: replace 3 filters, JDBC schema, keys, re-test DCR/PKCE) / **Very high** (Keycloak: IdP swap) / MCP side identical | ●●●●○ Same end state; SAS lacks native `resource`; Keycloak lacks `resource` | SAS exchange only for SAS-issued tokens, so no partial adoption | ●●○○○ Course builds on the custom AS | Not now; candidate for a separate ADR (FU-9) |
| A6 Sender-constrained tokens (DPoP / mTLS) | ●●●●● vs token theft (add-on, not a replacement) | High: no Spring DPoP *client*; mTLS needs PKI on a plain-HTTP local stack | ●●●○○ Fulfils SHOULD B-01/O-02 only | Poor today: custom proof generation (Nimbus), host RS DPoP support, replay cache | ●●○○○ L03 slide 12: "future direction" | Deferred (FU-8) |

**Why the recommended combination wins.** A1 + A2a + A3 is the only combination that (a) satisfies every MUST on the hop, (b) keeps per-user authorization at the host, which L04 insists on, (c) adds MCP-server accountability through `client_id`/`act`, (d) needs no signing-key migration, and (e) matches the course and the team standard. A2c is the natural evolution once the host moves to asymmetric keys, for reasons unrelated to this refactor.

---

## 7. Recommendation and Target Design

### 7.1 Recommendation
1. **Audience everywhere (host AS + host RS).** Read RFC 8707 `resource` at `/oauth2/authorize` and `/oauth2/token`. Store it with the code and the refresh token. Issue `aud`, plus `client_id`, `jti` and `typ: at+jwt`, from a **configured** issuer. Give app login tokens `aud` = host API. Make `JwtAuthenticationFilter` require `iss` and `aud` = host API.
2. **Inbound at MCP: introspection, no cache.** `oauth2ResourceServer().opaqueToken()` against the host's new `/oauth2/introspect`, authenticated as the seeded MCP confidential client. Reject unless `active` is true and `aud` contains the MCP canonical URI (`aj.mcp.base-url`, the same value as the PRM `resource`). Map `scope` to authorities. Enforce per-tool scope (`mcp:edit` for `aj_add_product`). Emit 401 `invalid_token` and 403 `insufficient_scope` challenges.
3. **Outbound at MCP: RFC 8693 exchange per tool call, no cache.** `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token` = validated inbound token, `subject_token_type=...:access_token`, `resource` = host API URI, client authentication = MCP confidential client. Send the resulting token to the host. Never send the inbound token.
4. **Exchange policy at the AS.** The caller must be the confidential client registered for the MCP resource. The subject `aud` must be that resource (I4). Issued scope = subject scopes ∩ user's current permissions ∩ client's allowed scopes (I3). `aud` = host API, `sub` = user, `client_id` = MCP server, `act` = `{ "sub": "<mcp client_id>" }`, `exp` ≤ min(now + 5 min, subject `exp`). No refresh token. Return `issued_token_type`.
5. **Keep HS256 for now.** Only the host verifies host tokens. Override the committed default secret in dev scripts (FU-5).

### 7.2 Token profiles (target)

| | T0 app session | T1 MCP access token | T2 backend (exchanged) token |
|---|---|---|---|
| Issued at | `POST /api/auth/login` | `POST /oauth2/token` (auth code + PKCE, refresh) | `POST /oauth2/token` (token exchange) |
| `iss` | configured host issuer | configured host issuer | configured host issuer |
| `aud` | host API URI (new) | MCP canonical URI (from `resource`) | host API URI |
| `sub` | user | user | user |
| `client_id` | — | MCP client (e.g. Claude's DCR id) | MCP server confidential client |
| `act` | — | — | `{sub: <mcp server client_id>}` |
| Rights | `permissions` | `scopes` ⊆ user permissions (fix F6) | `scopes` ⊆ T1 scopes ∩ user permissions ∩ client scopes |
| TTL | 24 h (unchanged) | 15 min | ≤ 5 min, capped at T1 `exp` |
| Validated by | host `JwtAuthenticationFilter` | MCP via host introspection | host `JwtAuthenticationFilter` |

### 7.3 Token flow (target)

```
MCP client (Claude)            MCP server :8081                         Host :8080 (AS + /api)
      |  POST /  Bearer T1          |                                          |
      |---------------------------->|                                          |
      |                             | BearerTokenAuthenticationFilter          |
      |                             |  POST /oauth2/introspect token=T1        |
      |                             |  Authorization: Basic mcp-server:secret  |
      |                             |----------------------------------------->| ClientAuthenticator
      |                             |<-----------------------------------------| {active, sub, scope, aud, client_id, exp}
      |                             | aud ∋ aj.mcp.base-url ?  else 401 invalid_token
      |                             | contextExtractor: validated Authentication -> McpTransportContext
      |                             | tool handler (boundedElastic): scope check -> else 403 insufficient_scope
      |                             |  POST /oauth2/token grant_type=token-exchange
      |                             |   subject_token=T1, resource=<host API URI> (Basic auth)
      |                             |----------------------------------------->| validate T1 (sig, exp, aud=MCP URI,
      |                             |                                          |  caller owns MCP resource)
      |                             |<-----------------------------------------| T2 {aud=host API, act, scope∩perm, exp<=5m}
      |                             |  GET /api/products  Bearer T2            |
      |                             |----------------------------------------->| JwtAuthenticationFilter: sig, exp,
      |                             |                                          |  iss, aud=host API -> PERMISSION_mcp:*
      |<----------------------------| CallToolResult                           |
```

### 7.4 How each brief vulnerability is eliminated

| Vulnerability (brief) | Root cause (evidence) | Eliminated by | Residual risk |
|---|---|---|---|
| **Token passthrough** | `JwtForwardingInterceptor` re-sends T1 (`RestClientConfig.java:78-80`); host accepts it (F4) | T1 never leaves the MCP server except back to its issuer (introspection, exchange). The host gets T2 only. `JwtForwardingInterceptor`, `AccessTokenHolder` and the raw `TOKEN_KEY` are deleted. The host would reject T1 anyway (`aud` ≠ host API) | None on this hop. A regression test asserts the outbound header ≠ inbound token |
| **Confused deputy** | Host cannot tell relayed calls from direct ones; MCP has no identity (V4) | The MCP server authenticates as a confidential client. T2 carries `client_id` and `act.sub` = MCP server. The exchange is allowed only for subject tokens whose `aud` is the MCP resource. Scope is capped at user permissions (F6 fix), so the deputy cannot exceed the user | Host matchers do not yet *require* `act` for `PERMISSION_mcp:*` (optional hardening, D-8) |
| **No audience restriction / replay** | No `aud` on any token; host checks none (F4) | T0, T1 and T2 each get a distinct `aud` (J-16). The MCP server rejects anything not for it (T0 and T2 fail). The host rejects anything not for it (T1 fails). T2 lives ≤ 5 min | Bearer theft within audience and TTL remains (DPoP = FU-8) |
| **No validation at MCP** | `McpJwtFilter` trusts any bearer (F1); tools read the raw header (V6) | Spring `BearerTokenAuthenticationFilter` + introspection + `aud` check before any tool runs (MCP-27). Tools receive only the validated `Authentication` from the security layer. Per-tool scope checks run at the MCP server | None. Introspection outage means 401 (fail closed) |

Also resolved as a side effect: V6 (parsing split), V7 (stale `ThreadLocal`), V8/V10 (401/403 semantics). V9 (external defaults) is partly resolved, see D-2.

---

## 8. Gap List per Component (in scope)

### 8.1 Host authorization server (`src/main/java/pl/devstyle/aj/core/oauth2`)
| # | Gap | Location | Change |
|---|---|---|---|
| H1 | Issuer derived from request headers | `OAuth2TokenFilter.java:146,254-282`; `OAuth2MetadataController.java:39,54-83` | Configured `app.oauth2.issuer` (env override) used for `iss` and metadata |
| H2 | No RFC 8707 `resource` | `OAuth2AuthorizationFilter.java:67-73`; `AuthorizationCodeService.java:64-73`; `OAuth2TokenFilter.java:78-82`; `RefreshTokenService.java:75-80` | Read and validate `resource` against a registry of known resources (MCP URI, host API URI). Store it in the code and refresh data. Default per D-3. `invalid_target` for unknown values |
| H3 | No reusable client authentication; Basic advertised but ignored | `OAuth2TokenFilter.java:82,116-131` | Extract a `ClientAuthenticator` supporting `client_secret_basic` and `client_secret_post` (BCrypt). Use it in auth-code, exchange and introspection |
| H4 | No confidential client for the MCP server | `db/changelog/2026/*` (only 009) | Seed `aj-mcp-server` (grant `token-exchange`, scopes `mcp:read mcp:edit`, bound to the MCP resource) with its secret from env (D-9) |
| H5 | No introspection endpoint | new filter, e.g. `OAuth2IntrospectionFilter`; matcher next to `SecurityConfiguration.java:91-94` | `POST /oauth2/introspect`, authenticated caller (I-03), parse T1 with the existing key, return `active`, `sub`, `scope`, `aud`, `client_id`, `iss`, `exp`, `iat`, `token_type`. `active:false` on any failure, with no detail (I-07). Optionally `active:false` when `aud` is not the caller's resource (I-06) |
| H6 | No token-exchange grant | `OAuth2TokenFilter.java:62-69` (dispatch), `:216-237` (response builder) | New branch per §7.1 item 4. Response with `issued_token_type`, real `expires_in`, no refresh token. Errors `invalid_request` / `invalid_target` / `unauthorized_client` |
| H7 | Scope not intersected with permissions (F6) | `OAuth2TokenFilter.java:142-147`; `AuthorizationCodeData.permissions` unused | Intersect at auth-code issuance and at exchange (`mcp:read`↔`READ`, `mcp:edit`↔`EDIT`) |
| H8 | Metadata missing new capabilities | `OAuth2MetadataController.java:41-51` | Add `introspection_endpoint`, `introspection_endpoint_auth_methods_supported`, token-exchange in `grant_types_supported`. Advertise only methods that are implemented |
| H9 | New code must not copy token logging | `OAuth2TokenFilter.java:57-59,152-153,234` pattern | New branches log no tokens or secrets. Removing the existing logs = FU-3 (recommended to bundle) |

### 8.2 Host token issuance and API filter (`core/security`, `SecurityConfiguration`)
| # | Gap | Location | Change |
|---|---|---|---|
| S1 | OAuth token lacks `aud`, `client_id`, `jti`, `typ` | `JwtTokenProvider.java:47-58` | `generateOAuth2Token(sub, scopes, aud, clientId, act?)`; header `typ: at+jwt`; `jti` UUID |
| S2 | App token lacks `iss`, `aud` | `JwtTokenProvider.java:29-43` | Add configured `iss` and `aud` = host API |
| S3 | No `iss`/`aud` validation | `JwtTokenProvider.java:68-83` | `requireIssuer(...)` and require `aud` to contain host API. Return `aud`, `client_id`, `act` in `ParsedToken` for logging and audit |
| S4 | Matchers | `SecurityConfiguration.java:91-94, 106-120` | Add `permitAll` for `/oauth2/introspect` (client auth inside, like `/oauth2/token`). Keep `PERMISSION_mcp:*` matchers: after S3 they are reachable only with `aud` = host API tokens, i.e. T2. Optionally require an MCP actor (D-8) |

### 8.3 MCP server (`mcp-server/`)
| # | Gap | Location | Change |
|---|---|---|---|
| M1 | Dependencies | `pom.xml:22-65` | Add `spring-boot-starter-security-oauth2-resource-server` (Boot 4 name; confirm), `spring-security-test` (test). Add `spring-boot-starter-security-oauth2-client` only if D-4 picks Spring's provider |
| M2 | Trust-any-bearer filter | `security/McpJwtFilter.java`; `config/SecurityConfig.java:21-40` | Delete the filter. `oauth2ResourceServer(o -> o.opaqueToken(...))` with an introspector wrapper that checks `aud` ∋ `aj.mcp.base-url` and maps scopes. Keep `/.well-known/**` permitAll |
| M3 | 401/403 semantics | `security/McpAuthenticationEntryPoint.java:18-24`; `SecurityConfig.java:32-35` | 401 with `resource_metadata` + `scope` (+ `error="invalid_token"` only when a token was sent, BT-03). New `AccessDeniedHandler` returning 403 `insufficient_scope` |
| M4 | Raw token in transport context | `AjMcpApplication.java:42-46` | `contextExtractor` stores the validated `Authentication` from `SecurityContextHolder` (servlet thread), not the header. Remove `TOKEN_KEY`. Or `immediateExecution(true)` (D-5) |
| M5 | Passthrough holder and interceptor | `security/AccessTokenHolder.java`; `config/RestClientConfig.java:28-38,64-86` | Delete both. New `TokenExchangeClient` (or Spring provider, D-4) and a per-call mechanism that attaches T2 to `AjApiClient` requests. No static `ThreadLocal`; if a holder is kept, clear it in `finally` |
| M6 | Token parameters in services; no scope enforcement | `service/ProductService.java:26-31,42-43,69-71,183-185`; `service/CategoryService.java:24-29,43` | Remove `String token` parameters. Read the principal from `ctx`. Require `mcp:read` for list tools and `mcp:edit` for `aj_add_product` |
| M7 | Backend 401/403 mapping | `config/RestClientConfig.java:39-53` | After exchange, a backend 401 is a server credential failure: `apiError`, no retry (team standard). 403 stays "insufficient permissions". Never echo backend bodies containing tokens |
| M8 | Config | `src/main/resources/application.yml:22-34` | Add introspection URI, token URI, `AJ_OAUTH_CLIENT_ID`/`AJ_OAUTH_CLIENT_SECRET`, host API resource URI. Ensure `aj.mcp.base-url` drives PRM, challenge and `aud` check (D-2) |
| M9 | PRM | `controller/WellKnownController.java:14-28` | `resource` = canonical URI checked by M2; `authorization_servers` = configured host issuer; optionally `resource_name` |

### 8.4 Tests
| Side | Rewrite | Add |
|---|---|---|
| MCP | `McpJwtFilterTests`, `AccessTokenHolderTests`, `RestClientConfigTests` (asserts passthrough), `ToolCallIntegrationTests`, `ErrorMappingTests`, `ToolSchemaValidationTests` (`String token` signatures) | MockMvc: 401 without a token (header format), 401 inactive or foreign-`aud`, 403 `insufficient_scope`. PRM JSON. End-to-end tool call with `MockRestServiceServer`/WireMock asserting the outbound header is T2 ≠ T1. Exchange failure → `apiError` |
| Host | Rename `OAuth2IntegrationTests.tokenExchange_*` (`:232`, `:280`), which actually test refresh/auth code | Introspection: authenticated vs unauthenticated, active/inactive. Exchange: happy path, wrong `aud` subject, wrong client, scope intersection (viewer → no `mcp:edit`). `/api` rejects T1 and wrong-`iss` tokens. Claims of issued tokens. Basic and post client authentication |

### 8.5 Documentation drift
`CLAUDE.md:48` (claims introspection exists) and `CLAUDE.md:60` (claims validation and exchange) become true only after this work. Update them in the same change. `McpJwtFilter` Javadoc disappears with the class.

---

## 9. Follow-ups (out of scope)

| ID | Item | Why deferred | Evidence | Priority |
|---|---|---|---|---|
| FU-1 | **DCR openness** → CIMD / pre-registration; also the probable 500 when `grant_types` is omitted | DCR is MAY in 2026-07-28 and still used by Claude; unrelated to passthrough | `PublicClientRegistrationFilter.java:47-120,136-138`; L03 slides 5–6; MCP delta row 4 | Medium |
| FU-2 | **Refresh-token storage and binding**: persistence (in-memory today), client binding, reuse detection with family revocation, absolute lifetime | AS-wide; needs a storage decision | `RefreshTokenService.java:21,31-50,75-80`; B-15, O-07, RFC 9700 §4.14.2 | High (MUST) |
| FU-3 | **Token logging**: tokens, codes, verifiers and secrets at INFO | Mechanical, but touches existing flows. Recommended to bundle, since H6 edits the same file | `OAuth2TokenFilter.java:57-59,152-153,234`; `OAuth2AuthorizationFilter.java:64-66`; B-12 | High (MUST) |
| FU-4 | **`_token` request parameter** accepted on every path | The consent page depends on it; the fix is to restrict it to `POST /oauth2/authorize` | `JwtAuthenticationFilter.java:47-51`; `OAuth2AuthorizePage.tsx:97-99`; O-09 | High (MUST) |
| FU-5 | **External default URLs and committed secret**: `*.labs-skillpanel.com` defaults, host CORS entry, `APP_JWT_SECRET` default | Config hygiene. The `aud` part is needed in scope (D-2) | `application.yml:26,28`; `start-dev.sh:28-30`; `application.properties:4,8` | Medium (partly in scope) |
| FU-6 | **Asymmetric signing + JWKS** (RS256/ES256, `kid`, rotation) and optionally inbound local JWT (A2c) | Not required once the MCP server uses introspection | J-02, J-09; `JwtTokenProvider.java:23-26` | Medium |
| FU-7 | **`private_key_jwt` client auth** for the MCP server | RECOMMENDED (B-07/O-04); depends on FU-6 | RFC 7523 §2.2 | Low |
| FU-8 | **DPoP / mTLS** | No Spring DPoP client; local plain-HTTP stack; SHOULD only | D-01, D-02; external-patterns §7; L03 slide 12 | Low |
| FU-9 | **SAS migration ADR** (build vs buy) | High effort, all or nothing | F12 | Low |
| FU-10 | **CIMD / Enterprise-Managed Authorization (ID-JAG)** | Concerns the MCP client↔MCP server hop only | ext-auth; L05 | Low |
| FU-11 | Other hardening: unauthenticated `/api/oauth2/client-info`, host 401 without `WWW-Authenticate`, RFC 9207 `iss` in authorization responses, MCP `Origin` validation (`securityValidator` NOOP), DEBUG security logging, unencoded `state` | Not on the hop | codebase-host-as defects 5–7; codebase-mcp §6 | Low |

---

## 10. Open Design Questions for the Decision Phase

| ID | Question | Options | Lean (confidence) |
|---|---|---|---|
| D-1 | **Inbound validation mode** | (a) introspection, no cache; (b) introspection + short TTL cache keyed by SHA-256(token), capped at `exp`; (c) local JWT RS256 + JWKS | (a). Course, team standard and I2 agree; revisit with FU-6 (High) |
| D-2 | **`aud` / `resource` values** | MCP: `aj.mcp.base-url` (default `http://localhost:8081`, no trailing slash, the endpoint is `/`). Host API: `${issuer}/api` vs `${issuer}` | MCP = `aj.mcp.base-url` with a local default. Host API = `${issuer}/api`, kept distinct from the issuer (J-16) (Medium-High) |
| D-3 | **Default audience when a client omits `resource`** (J-08 MUST pick a default) | (a) default to the MCP URI for `/oauth2/*` flows, since `mcp:*` scopes only have meaning there (J-05); (b) reject with `invalid_target` | (a) for compatibility with clients that do not send it yet; log a warning (Medium) |
| D-4 | **Outbound client implementation** | (a) hand-written `TokenExchangeClient` on `RestClient` (course name); (b) Spring `TokenExchangeOAuth2AuthorizedClientProvider` + `OAuth2ClientHttpRequestInterceptor` with custom subject resolver, `resource` parameter converter, non-caching or hash-keyed repository, explicit principal | (a). Fewer hidden defaults (K9); one small class (a single form POST and response parse); easy to test (Medium) |
| D-5 | **Principal propagation to tools** | (a) validated `Authentication` in `McpTransportContext` via `contextExtractor`; (b) `immediateExecution(true)` and `SecurityContextHolder` | (a). Keeps the SDK's async model and removes V6. (b) is fine if D-4(b) is chosen (Medium) |
| D-6 | **Scope hierarchy** (MCP-14 MUST) | (a) independent scopes, documented; (b) `mcp:edit` ⊃ `mcp:read` at MCP and host | Decide explicitly. Today an `mcp:edit`-only token cannot GET at the host (`SecurityConfiguration.java:106-107`). (b) is friendlier but needs a matcher change (Medium) |
| D-7 | **Delegation vs impersonation** | (a) delegation: AS adds `act: {sub: mcp client_id}` with no `actor_token`; (b) impersonation: `client_id` only | (a). Cheap, auditable, X-15 compliant (Medium-High) |
| D-8 | **Should `/api` stop accepting MCP-audience tokens?** And should `PERMISSION_mcp:*` require an MCP actor? | Rejecting `aud` ≠ host API is required (B-04). Requiring `act`/`client_id` = MCP server on `mcp:*` matchers is optional | Reject T1 at `/api`: yes, required (High). Actor requirement: optional hardening (Medium) |
| D-9 | **MCP server client authentication and seeding** | `client_secret_basic` (Spring default for introspection) vs post; seed via Liquibase (BCrypt hash in a `dev` changeset) vs a startup seeder reading `MCP_CLIENT_SECRET` | Basic + post supported by H3. Startup seeder from env, so no hash is committed and it works in every context (Medium) |
| D-10 | **HMAC vs asymmetric keys now** | keep HS256 (only the host verifies) vs migrate | Keep HS256 in this task; FU-6 later (High, given D-1(a)) |
| D-11 | **Backend token scope representation** | keep `mcp:*` scopes in T2 (existing matchers) vs translate to READ/EDIT (team standard wording) | Keep `mcp:*`: no matcher change, and T2 stays distinguishable from T0 (Medium) |
| D-12 | **Exchanged-token caching** | none vs Auth0/MSAL-style cache keyed by SHA-256(T1) + `client_id`, TTL ≤ min(T2 `exp`, T1 `exp`), evict on backend 401 | None in this task (High) |
| D-13 | **Scope-vs-permission intersection placement** | at auth-code issuance only, at exchange only, or both | Both. Exchange uses the user's *current* permissions (High) |

---

## 11. Analysis and Insights

### 11.1 Patterns identified
| Pattern | Type | Prevalence | Assessment |
|---|---|---|---|
| Trust-and-forward proxy | Integration anti-pattern | Entire MCP outbound path | Forbidden by MCP-31; named by course and vendors |
| One key, one filter, no audience | Security architecture | All host auth | Root enabler of passthrough and replay |
| Hand-written AS as servlet filters | Architectural | 3 filters + controller | Extensible for one grant and one endpoint; inline client auth and secret logging must not be copied |
| SAS as a type library | Dependency | 4 imported types | Lowers SAS migration cost slightly; no runtime value |
| Stateless per-request auth | Protocol | MCP 2026-07-28 | Per-request introspection fits; no session caches |
| Validate-then-exchange (delegation) | Industry | 6 vendors + RFC 8693 + course | Target pattern |
| Tests pin the vulnerability | Testing | 6 MCP test classes | Must be rewritten; no HTTP-level security tests exist on either side |
| Advertised but not honoured | Drift | PRM `resource`, `client_secret_basic`, `CLAUDE.md` | Candidate standard (see 11.4) |

### 11.2 Key insights
1. **Audience is the missing primitive** (High). Every brief vulnerability reduces to "no party knows whom a token is for". Exchange without host-side `aud` enforcement would leave the reverse path open.
2. **Introspection decouples key management** (High). The MCP server never needs the HMAC key, so the asymmetric migration is no longer on the critical path.
3. **The exchange grant is where scope escalation becomes systemic** (High). Intersection must ship with it.
4. **"Only exchange tokens issued to you" must be an explicit AS rule** (High). The Auth0 and Keycloak precedent: tie the MCP server's `client_id` to the MCP resource URI.
5. **Threading dictates outbound wiring** (High constraint, Medium on the option). Spring defaults silently fail on `boundedElastic`.
6. **On a localhost hop, caching is a security decision, not a performance one** (High). Start with none.
7. **Delegation costs nothing extra** (Medium-High). The AS already knows the authenticated actor, so it can add `act` without an `actor_token`.

### 11.3 SWOT (recommended approach)
| Strengths | Weaknesses |
|---|---|
| Satisfies every MCP-hop MUST; course- and standard-aligned; keeps users, permissions and filters; MCP side is mostly deletions plus Spring wiring; immediate revocation | More hand-written security code to maintain in the custom AS; two extra local HTTP calls per tool call; HS256 remains |
| **Opportunities** | **Threats** |
| Fixes the `viewer`→`mcp:edit` escalation along the way; clean path to JWKS/A2c, `private_key_jwt`, DPoP; `act` enables audit of MCP-originated writes; good teaching artefact | Spring defaults (cache key, principal resolver) can quietly reintroduce scope bleed; clients that omit `resource` get a defaulted `aud`; external default URLs break local `aud` checks; pre-existing AS MUST violations (logging, refresh binding, `_token`) remain until follow-ups |

### 11.4 Candidate standards (Standards Evolution)
- *"OAuth/PRM metadata must only advertise implemented behaviour, with a test per advertised field"* (pattern P8).
- *"Every JWT the host issues carries `iss` and `aud`; every resource server validates both"* (would extend `standards/backend/security.md`).
- *"Security-sensitive values (tokens, codes, verifiers, secrets) are never logged, at any level"*.

---

## 12. Conclusions

### Primary conclusions
1. **The MCP server should validate inbound tokens via RFC 7662 introspection with an explicit `aud` check against its RFC 9728 `resource`, and obtain a separate backend token via RFC 8693 token exchange (delegation) from the existing host AS.** Confidence: High.
2. **Audience binding (RFC 8707 `resource` → `aud`) must be implemented at the AS and enforced at both resource servers.** It is the mechanism that actually eliminates replay and confused-deputy paths. Confidence: High.
3. **Extend the custom AS; do not migrate to SAS or Keycloak for this task.** Confidence: High.
4. **The exchange must intersect scopes with the user's permissions.** Otherwise the existing escalation becomes systemic. Confidence: High.

### Secondary conclusions
- Introspection removes the need for asymmetric keys in this refactor (High).
- Client credentials + propagated user context is not an acceptable replacement (High).
- DPoP/mTLS are valuable later but disproportionate for a local-only showcase (Medium-High).
- Spring Security 7 components are usable on the MCP side, but outbound defaults must be overridden, which makes a hand-written exchange client a reasonable choice (Medium).

### Direct answer to the research question
The aj MCP server should stop being a relay and become a proper OAuth resource server plus OAuth client. It **accepts only tokens whose audience is itself**, verified by introspection at the host AS. It **authenticates to the AS as its own confidential client** and **exchanges the user's token (RFC 8693) for a short-lived token audience-restricted to the host API** that carries the user as `sub` and the MCP server as `act`, with scopes no broader than the user's permissions. The host **rejects any token whose audience is not the host API**. This meets MCP 2026-07-28 (MCP-07, -11, -29, -31), OAuth 2.1 §5.2/§7.1.4, RFC 9068 §4 claim validation, RFC 9700 §2.3 and RFC 8707. It is the solution shape the course teaches in M04L07.

---

## Appendix A: Sources

**Findings files** (`analysis/findings/`): `codebase-mcp-auth-flow.md`, `codebase-mcp-vulnerabilities-touchpoints.md`, `codebase-host-as-capabilities.md`, `codebase-host-as-gaps.md`, `course-materials-module4.md`, `external-specs-requirements.md`, `external-specs-mcp-revision.md`, `external-patterns-implementations.md`, `external-patterns-caching-effort.md`.

**Key code** (repo root): `mcp-server/src/main/java/pl/devstyle/aj/mcp/{AjMcpApplication, config/SecurityConfig, config/RestClientConfig, security/McpJwtFilter, security/AccessTokenHolder, security/McpAuthenticationEntryPoint, controller/WellKnownController, service/ProductService, service/CategoryService}.java`, `mcp-server/src/main/resources/application.yml`, `mcp-server/pom.xml`. `src/main/java/pl/devstyle/aj/core/oauth2/{OAuth2TokenFilter, OAuth2AuthorizationFilter, PublicClientRegistrationFilter, AuthorizationCodeService, RefreshTokenService, DatabaseRegisteredClientRepository, OAuth2MetadataController}.java`, `core/security/{JwtTokenProvider, JwtAuthenticationFilter, SecurityConfiguration}.java`, `src/main/resources/application.properties`, migrations `008`, `009`.

**Normative**: MCP 2026-07-28 authorization, security considerations, AS discovery, changelog, versioning (modelcontextprotocol.io); MCP Security Best Practices (2026-07-28 tutorials); MCP ext-auth; RFC 8693, 7662, 8707, 9068, 9728, 9700, 6750, 8414, 9449, 8705, 7523 (rfc-editor.org); draft-ietf-oauth-v2-1-17 (MCP cites -13).

**Patterns**: Spring Security 7.1.2 reference (opaque token, JWT, authorization grants, authorized clients, DPoP) and provider source; SAS in Spring Security 7 (blog, protocol endpoints, `OAuth2TokenExchangeAuthenticationProvider` source); Keycloak 26 token exchange; Auth0 OBO and Custom Token Exchange; Microsoft Entra OBO and MSAL; Okta MCP token exchange guide; Cloudflare Agents authorization; Spring AI mcp-security; AWS AgentCore and Azure APIM (low confidence).

**Course**: `_docs/AJ_M04L01..L07_transkrypcja.pdf`, `AJ_M04L01..L04_prezentacja.pdf` (priority L03, L07, L05).

**Team intent**: `.maister/docs/standards/mcp-server/conventions.md:15`. It originates from the excluded `2026-04-13-mcp-token-security` task, so it is treated as corroboration only.

## Appendix B: Gaps and Uncertainties
- Spring client/RS behaviour (K9: subject resolver, principal resolver, cache key) is inferred from docs and source, not executed. **Medium-High.**
- Boot 4 starter artifact names: confirm against the 4.0.5 BOM. **Medium-High.**
- What real MCP clients (Claude, ChatGPT) send as `resource` today is unverified. This drives D-3.
- `mcp-server/target/classes/application.yml` is stale; a clean build is needed before runtime verification.
- The DCR null path (probable 500) is inferred, not tested.
- The course specifies no parameters, claims or caching for its solution. All concrete values here come from RFCs and vendor docs.
- The OAuth 2.1 -17 section for "confidential clients MUST authenticate" is uncertain (§2.4 or §3.2.1). The content is certain.
- No load or latency measurement was taken. The "cheap on localhost" claim for two extra calls per tool call is reasoned, not measured.

## Appendix C: Contradictions Resolved
| Contradiction | Resolution |
|---|---|
| `CLAUDE.md:48,60` and the team standard describe introspection and exchange that do not exist | Docs = target intent; code = current state; fix the docs in the refactor |
| Course L03 "Draft Spec" for CIMD vs L05 "2026-07-28" | 2026-07-28 is current; DCR MAY, CIMD SHOULD |
| "RFC 8707 requires the RS to check audience" (course) | The RS-side MUST comes from MCP, RFC 9068 §4, RFC 9700 §2.3 and OAuth 2.1 §7.1.4; RFC 8707 covers the request/AS side |
| MCP-11 "MUST NOT transit tokens" vs sending T1 to the AS | Returning a token to its issuer for introspection or exchange is not transit to a resource (Medium-High inference) |
| "Upstream AS" vs a single AS in aj | Separation is by audience (J-16), and RFC 8693 §1 allows an RS to exchange at the same AS |
| SC confused-deputy MUST vs the brief's confused deputy | The SC MUST covers proxy servers with static client IDs (N/A); the brief's risk is the BP token-passthrough accountability risk (applies) |
| Industry favours local JWT; course and standard favour introspection | Both compliant; introspection preferred for aj because of the HMAC key (I2) |
| Team standard "map `mcp:read`→READ" vs keep `mcp:*` | Open (D-11); lean keep `mcp:*` for distinguishability |
