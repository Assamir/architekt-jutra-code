# Research Plan: MCP auth refactor (replacing token passthrough)

## TL;DR
Mixed research: codebase analysis of the current MCP-to-host hop and the custom host AS, read against the MCP Authorization spec (2026-07-28 revision, per course lesson M04L05) and the OAuth RFCs, plus the course module 4 materials. Five parallel gatherers: `codebase-mcp`, `codebase-host-as`, `course-materials`, `external-specs`, `external-patterns`. Synthesis then builds a requirement matrix (spec MUST/SHOULD vs current code), a comparison of the 6 alternatives, a gap list, and a high-level design with ADRs.

## Key Decisions
- Mixed methodology, specs first — the spec defines the requirements (MUST/SHOULD), the code shows where we fall short, the course shows the expected teaching path, and the industry patterns show how much it costs.
- Five gatherers, split by source type, each with its own file prefix — the source types do not overlap and the corpora are small (module 4 is about 16k words across 11 PDFs), so no category needs splitting.
- The reverted commit `e095025` ("Module 4 hardening") and everything under `.maister/tasks/**/2026-04-13-mcp-token-security` are excluded from all gatherers — the user excluded that task, and both the code diff and the artifacts in that commit come from it. Using them would anchor the research on the earlier answer.
- `standards/mcp-server/conventions.md` and `standards/backend/security.md` are treated as statements of team intent (the target state), not as descriptions of the current code. Gatherers describe the actual code on `feat/mcp-auth-refactor`.
- The current MCP spec revision is checked live at modelcontextprotocol.io. Course lesson M04L05 names "2026-07-28", which needs confirming against the published spec.

## Open Questions / Risks
- The MCP spec revision may have moved since the course was recorded. The `external-specs` gatherer must record the exact revision date and the section anchors for every requirement it cites.
- The host `pom.xml` declares `spring-security-oauth2-authorization-server`, but the AS is hand-written as servlet filters. The research needs to establish whether that dependency is used at all, since it affects how much migrating to Spring Authorization Server would cost.
- The host JWT is HMAC with a default secret committed in `application.properties` (`APP_JWT_SECRET` fallback). Validating tokens locally in the MCP server would mean sharing that secret, which is a risk the comparison must weigh.
- `mcp-server/application.yml` defaults point at public `*.labs-skillpanel.com` hosts. The research must confirm which issuer and resource URLs the RFC 9728 metadata actually advertises.
- The PDFs cannot be read with the Read tool here (no `pdftoppm`). Gatherers must use `pdftotext -enc UTF-8 <file> -` through Bash, which is verified to work.

---

## 1. Research Overview

**Question**: How should the aj MCP server authenticate to the aj host backend without forwarding the MCP client's bearer token, in line with the MCP Authorization spec, OAuth 2.1 and RFCs 8693, 7662, 8707, 9068, 9728 and 9700?

**Type**: Mixed (technical codebase analysis + literature/standards + course requirements).

**Sub-questions**
1. Current state (MCP). How exactly does a token flow from the MCP client, through `McpJwtFilter`, `AccessTokenHolder` and `JwtForwardingInterceptor`, to the host? What does the server validate, and what principal or context does it build? How is a 401 / `WWW-Authenticate` / RFC 9728 metadata produced? What do the tests pin down?
2. Current state (host AS). Which endpoints, grant types, client auth methods, token formats and claims (`iss`, `aud`, `sub`, `scope`, `permissions`, `client_id`, `act`) does the custom AS issue? How do `mcp:*` scopes map to `PERMISSION_*`? Is there an introspection endpoint, a token-exchange grant, `resource` parameter support, JWKS, or asymmetric keys?
3. Spec requirements. What does the current MCP Authorization spec require of an MCP server acting as a resource server, and as a client to upstream APIs: audience validation, the token passthrough prohibition, RFC 8707 `resource`, RFC 9728 metadata, error responses? What do RFCs 8693, 7662, 8707, 9068, 9700 and OAuth 2.1 prescribe for each mechanism?
4. Course position. What does module 4 (L01–L07) teach about MCP authorization, production readiness, the 2026-07-28 spec changes, and the introspection plus exchange approach shown in L07? What trade-offs and pitfalls does it name?
5. Industry practice. How do Spring Security 7 / Spring Authorization Server, Keycloak (standard token exchange), Auth0, Microsoft Entra (on-behalf-of) and reference MCP servers implement delegated downstream calls? How mature is DPoP / mTLS support in Spring?
6. Comparison and design. How do the 6 alternatives from the brief compare on security, complexity and fit with the custom AS? What is the minimal set of changes for the host AS and the MCP server?

**Scope**: included and excluded as defined in `research-brief.md`. Out of scope: implementation, frontend/plugin auth, `week7/`–`week10/`, `tools/`, commit `e095025`, and the `2026-04-13-mcp-token-security` task.

## 2. Methodology

**Primary approach**
- Codebase gatherers: read the code with Read, Grep and Glob, then trace the request flow end to end. Every claim cites `file:line`.
- Spec gatherer: fetch the primary sources (modelcontextprotocol.io, rfc-editor.org / datatracker.ietf.org) with WebFetch. Extract normative requirements with their RFC 2119 keyword and section number.
- Course gatherer: extract text with `pdftotext -enc UTF-8 <pdf> -` via Bash, summarize per lesson in English, and quote briefly in Polish where precision matters. Cite as `M04Lxx transcript/presentation, approx. position`.
- Patterns gatherer: WebSearch and WebFetch of official vendor and framework docs, preferring docs over blogs. Record the version and date for each source.

**Fallback strategies**
- If modelcontextprotocol.io is unreachable, use the spec source on GitHub (`modelcontextprotocol/modelcontextprotocol`, `docs/specification/<rev>/basic/authorization.mdx` and `security_best_practices`).
- If `pdftotext` fails on a file, use Python `pdfplumber` (installed). Write the script to the scratchpad and pass the PDF path as an argument.
- If the `neo4j-aj-kb` MCP server is available, codebase gatherers may use the `aj-kg-query` skill for structural lookups (endpoints, modules). Grep is the default otherwise.

**Analysis framework** (applied in synthesis)
- Technical: component inventory, then token-flow diagram, then trust-boundary map (who issues, who validates, which audience, which key).
- Requirements: a requirement matrix. Rows are spec and RFC MUST/SHOULD statements; columns are current code status (met / partial / missing), evidence, and the gap.
- Literature: a comparison of the 6 alternatives, rated on security (passthrough, confused deputy, audience, replay, revocation), complexity (host AS changes, MCP changes, ops), spec compliance, fit with the custom AS and Spring Security 7, and teaching value against the course.
- Decision: a recommendation, a gap list, a high-level design (sequence diagram for the token flow), and ADRs.

## 3. Data Sources (summary)
Full manifest in `planning/sources.md`. Verified to exist:
- **MCP server**: 18 main classes and `application.yml`, plus 9 test classes under `mcp-server/src/test`.
- **Host AS and security**: 9 classes in `core/oauth2`, 4 in `core/security`, migrations `008` and `009`, tests `OAuth2IntegrationTests` and `AuthIntegrationTests`.
- **Configuration**: `src/main/resources/application.properties` (JWT secret and expiry), `mcp-server/src/main/resources/application.yml`, both `pom.xml` files, `compose.yml`.
- **Documentation**: `.maister/docs/project/*` and the standards `backend/security.md`, `backend/plugin-auth.md`, `mcp-server/conventions.md` and `testing/backend-testing.md` (treated as intent).
- **Course**: `_docs/AJ_M04L01..L07_transkrypcja.pdf` and `AJ_M04L01..L04_prezentacja.pdf`.
- **External**: the MCP spec (authorization and security best practices), RFCs 8693, 7662, 8707, 9068, 9728, 9700, 6750, 9449 (DPoP), 8705 (mTLS), 7523, the OAuth 2.1 draft, and Spring, Keycloak, Auth0 and Entra docs.

## 4. Research Phases

**Phase 1: Parallel gathering** (5 gatherers, see Gathering Strategy)
- Each gatherer writes `analysis/findings/<prefix>-*.md`, opening with the TL;DR / Key Decisions / Open Questions block.
- Each finding carries its evidence: `file:line`, a URL with section anchor, or a lesson reference.

**Phase 2: Cross-reference** (synthesis)
- Build the requirement matrix by joining the `external-specs` requirements against the `codebase-mcp` and `codebase-host-as` facts.
- Line up the course recommendations (L03, L05, L07) against the spec requirements. Flag disagreements and anything the course simplifies.
- Map each of the 6 alternatives to the patterns found in `external-patterns`, with the Spring support level for each.

**Phase 3: Evaluation**
- Score the alternatives in a comparison table. Choose a recommended approach plus an acceptable minimal variant (for example, introspection plus exchange with an `aud`-bound backend token, as against a JWKS-based design).
- Write the gap list per component: host AS endpoints, grants, claims, client registration and seeding, `SecurityConfiguration` matchers; MCP filter, outbound client, metadata, config and tests.

**Phase 4: Design outputs**
- `outputs/research-report.md`, the high-level design (C4 context/container, plus a sequence diagram for the token flow), and ADRs (MADR) covering inbound validation, outbound credential, audience binding, signing keys, and AS build-vs-buy.

## 5. Gathering Strategy

### Instances: 5

| # | Category ID | Focus Area | Tools | Output Prefix |
|---|-------------|-----------|-------|---------------|
| 1 | codebase-mcp | MCP server: current inbound and outbound auth flow and its tests | Glob, Grep, Read | codebase-mcp |
| 2 | codebase-host-as | Host OAuth2 AS and JWT security: capabilities, claims, gaps | Glob, Grep, Read | codebase-host-as |
| 3 | course-materials | Course module 4 (Polish PDFs): what is taught and recommended | Bash (`pdftotext`), Read | course-materials |
| 4 | external-specs | MCP Authorization spec (current revision) and RFCs: normative requirements | WebFetch, WebSearch | external-specs |
| 5 | external-patterns | Industry implementations: Spring Security 7 / SAS token exchange, Keycloak, Auth0, Entra OBO, DPoP/mTLS, reference MCP servers | WebSearch, WebFetch | external-patterns |

### Per-gatherer scope

**1. codebase-mcp** (output e.g. `analysis/findings/codebase-mcp-auth-flow.md`)
- Read in full: `mcp-server/src/main/java/pl/devstyle/aj/mcp/security/{McpJwtFilter,AccessTokenHolder,McpAuthenticationEntryPoint}.java`, `config/{SecurityConfig,RestClientConfig}.java` (including `JwtForwardingInterceptor`), `controller/WellKnownController.java`, `client/AjApiClient.java`, `service/{ProductService,CategoryService}.java`, `AjMcpApplication.java`, `src/main/resources/application.yml`, `pom.xml`.
- Tests: `security/McpJwtFilterTests`, `security/AccessTokenHolderTests`, `config/RestClientConfigTests`, `service/ToolCallIntegrationTests`, `service/ErrorMappingTests`, `ConfigurationTests`.
- Deliver: a request-to-backend token sequence covering the thread-local or request-scope handling of the token, the principal, the 401 / `WWW-Authenticate` / `resource_metadata` behaviour, the RFC 9728 document fields, how backend 401/403 errors are mapped, the MCP SDK transport (stateless or streamable) and its threading implications, the dependencies present (no `oauth2-resource-server` and no `oauth2-client` today), and configuration properties and env vars.
- Do NOT use `git show e095025` or anything under `.maister/tasks/**/2026-04-13-*`.

**2. codebase-host-as** (output e.g. `analysis/findings/codebase-host-as-capabilities.md`)
- Read in full: `src/main/java/pl/devstyle/aj/core/oauth2/*` (`OAuth2TokenFilter`, `OAuth2AuthorizationFilter`, `PublicClientRegistrationFilter`, `AuthorizationCodeService`, `RefreshTokenService`, `DatabaseRegisteredClientRepository`, `RegisteredClientEntity`, `OAuth2MetadataController`, `OAuth2Error`), `core/security/*` (`JwtTokenProvider`, `JwtAuthenticationFilter`, `SecurityConfiguration`, `CustomUserDetailsService`), migrations `src/main/resources/db/changelog/2026/008-create-users-table.yaml` and `009-create-oauth2-registered-client-table.yaml`, `src/main/resources/application.properties`, the host `pom.xml` security dependencies.
- Tests: `src/test/java/pl/devstyle/aj/core/oauth2/OAuth2IntegrationTests.java` and `core/security/AuthIntegrationTests.java`.
- Deliver: an AS capability inventory covering endpoints, grant types (today `authorization_code` and `refresh_token`), PKCE, client auth methods, confidential vs public clients, token format (HMAC via jjwt, `app.jwt.secret`), the exact claims issued and validated (check for `aud`, `iss` and `client_id`), scope-to-permission mapping and the `SecurityConfiguration` matchers for `PERMISSION_mcp:*`, refresh token storage, AS metadata fields, and whether `spring-security-oauth2-authorization-server` is actually used. Then a gap list against introspection (RFC 7662), token exchange (RFC 8693), the `resource` parameter (RFC 8707), the JWT profile (RFC 9068) with `typ: at+jwt`, JWKS and asymmetric signing, and confidential-client seeding.

**3. course-materials** (output e.g. `analysis/findings/course-materials-module4.md`)
- Extract with `pdftotext -enc UTF-8 "<path>" -` (the Read tool cannot render PDFs here). Files: `_docs/AJ_M04L01..L07_transkrypcja.pdf` and `_docs/AJ_M04L01..L04_prezentacja.pdf`. Priority: L03 (Autoryzacja), L07 (Nieprzekazywanie tokenów: Introspection i Exchange), L05 (Specyfikacja 2026-07-28), L04 (Production readiness), L06 (code and flow of the aj MCP server). L01 and L02 for context only.
- Deliver, per lesson: key claims, recommendations, named anti-patterns (passthrough, confused deputy, audience), the spec revision referenced, how the instructor arrived at introspection plus exchange in L07 and what trade-offs he names, and any statements about caching, scopes, transport, or production pitfalls. Summaries in English with short Polish quotes (15 words or fewer) where wording matters.

**4. external-specs** (output e.g. `analysis/findings/external-specs-requirements.md`)
- Sources: MCP Authorization spec (current revision; record its date and compare against 2026-07-28), MCP Security Best Practices (token passthrough, confused deputy), RFC 8693, 7662, 8707, 9068, 9728, 9700, 6750 (`WWW-Authenticate`), OAuth 2.1 draft (latest `draft-ietf-oauth-v2-1-*`), and for applicability only, RFC 9449 (DPoP), RFC 8705 (mTLS) and RFC 7523 (JWT bearer assertions).
- Deliver: a normative requirement list with ID, source, section, keyword (MUST/SHOULD/MAY), requirement, and who it applies to (MCP server as RS / MCP server as client / AS / backend RS). Cover specifically: audience validation by the MCP server, the passthrough prohibition, `resource` parameter handling, RFC 8693 parameters (`subject_token`, `actor_token`, `requested_token_type`, `audience`/`resource`, the `act` claim, delegation vs impersonation), introspection request and response (`active`, `aud`, `scope`, `client_id`) and protecting the introspection endpoint, RFC 9068 claims and validation, RFC 9700 guidance (audience restriction, sender-constraining, refresh token rotation), and any spec change between revisions.

**5. external-patterns** (output e.g. `analysis/findings/external-patterns-implementations.md`)
- Sources: Spring Security 7 reference (resource server JWT and opaque-token introspection, `OAuth2AuthorizedClientManager` with `TokenExchangeOAuth2AuthorizedClientProvider`, RestClient `OAuth2ClientHttpRequestInterceptor`), Spring Authorization Server (token exchange grant, introspection, JWKS, and its current integration status with Spring Security 7 / Boot 4), Keycloak standard token exchange (v26+), Auth0 Custom Token Exchange, Microsoft Entra on-behalf-of flow, Spring AI / MCP Java SDK auth guidance and other reference MCP server implementations (for example Cloudflare, AWS or Azure MCP gateway patterns), and DPoP support in Spring Security.
- Deliver: for each pattern, the mechanism, the claims produced (`aud`, `act`, `azp`), how revocation and expiry propagate, caching guidance, a Spring Boot 4 support level with version, and the effort to adopt with a custom AS versus SAS versus Keycloak.

### Rationale
The research question sits where three things meet: what the code does today (two codebases with distinct trust roles, so two gatherers), what the standards require, and what the course teaches. Specs and industry patterns are separate gatherers because normative requirements and implementation practice need different source discipline (primary RFC text versus vendor docs) and feed different columns of the comparison. No separate `configuration` gatherer: the relevant config is small and is owned by the two codebase gatherers.

## 6. Success Criteria
- All 6 sub-questions are answered, with evidence for each claim (`file:line`, URL plus section, or lesson reference).
- A requirement matrix covers at least the MCP auth spec, RFC 8693, 7662, 8707, 9068 and 9700 requirements relevant to the MCP-to-host hop, each marked met / partial / missing against the current code.
- A comparison table covers all 6 alternatives from the brief, across security, complexity, spec compliance, fit with the custom AS and Spring Security 7, and course alignment.
- A clear recommendation with rationale. Each of the four brief vulnerabilities (passthrough, confused deputy, no audience, no MCP-layer validation) is shown to be eliminated.
- A gap list per component (host AS, host `SecurityConfiguration`, MCP server inbound, MCP server outbound, config and secrets, tests).
- A high-level design and ADRs that `/maister:development` can use directly.
- The current MCP spec revision is identified, and any difference from the course's 2026-07-28 content is noted.

## 7. Expected Outputs
- `analysis/findings/{codebase-mcp,codebase-host-as,course-materials,external-specs,external-patterns}-*.md`
- `analysis/synthesis.md` (requirement matrix, cross-reference)
- `outputs/research-report.md` (answer, comparison, recommendation, gap list)
- `outputs/high-level-design.md` and ADRs (`outputs/decision-log.md`), as input to `/maister:development`
