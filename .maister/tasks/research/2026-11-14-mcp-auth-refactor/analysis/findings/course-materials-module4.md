# Course Materials — Module 4 (MCP): what is taught and recommended

## TL;DR
- Module 4 states the rule plainly (M04L03 slide 7): MCP servers must NOT forward the client's token to an upstream API, to prevent confused-deputy attacks. It also requires audience binding (RFC 8707), refresh-token rotation with short access-token TTLs, and PKCE with S256.
- The exercise (M04L06): our `mcp-server` deliberately forwards the incoming `Authorization: Bearer` token to the host. The homework is to replace this with the "proper" MCP mechanism, worked out together with an agent (Maister feature development).
- The instructor's solution (M04L07) is Token Introspection of the inbound token at the MCP server plus Token Exchange (RFC 8693) for a backend token. The host gets an introspection filter and an exchange grant on its token endpoint. Tool behaviour stays the same.
- The course does NOT discuss introspection caching, latency, opaque vs JWT tokens, `act` claims, or how the AS enforces audience. These design trade-offs are left open.
- M04L05 covers the "2026-07-28" spec. It changes transport and state (stateless only, no initialize/session, `server/discover`, per-request `_meta`), replaces DCR with CIMD, and adds the Enterprise-Managed Authorization extension (ID-JAG). It says nothing new about passthrough or audience.

## Key Decisions
- Use L03 (normative rules) and L07 (the reference solution) as the course's "expected answer". L05 and L04 are constraints and context, and L01 and L02 are background only.
- Treat L07 as an outline, not a spec. It runs about 500 words, gives class names only loosely (an introspecting filter, a `TokenExchangeClient`, a host `IntrospectionFilter`, an exchange grant), and gives no parameters, claims, or caching policy.
- L07 says the instructor's design came from a Maister research run (`high-level-design.md`, task "mcp-token-security"). Per the research plan, that artifact and commit `e095025` are excluded and were NOT read. This file records only what the transcript says.

## Open Questions / Risks
- The course never says whether introspection results should be cached, nor how long exchanged tokens should live or whether they should be cached per user. Synthesis must decide this from the RFCs and industry patterns.
- The course never says which `audience`/`resource` the exchanged token should carry, whether `act` (delegation) or impersonation is intended, or how the MCP server authenticates to the AS (client credentials). These are open.
- The L03 slide labels its client-registration priority list "Draft Spec", while L05 presents CIMD-over-DCR as part of the dated 2026-07-28 spec. The external-specs gatherer must confirm the revision date and status.
- L05 claims the stateful mode is fully removed, with deprecation enforced "from August 2027". This needs checking against the published spec, because it affects whether a per-request token context (the current `AccessTokenHolder`) is the right model.
- L03 says DPoP (sender-constrained tokens) is where the ecosystem is heading. It is not required by the exercise.

---

## Sources

| File | Lesson | Words | Read |
|------|--------|-------|------|
| `_docs/AJ_M04L01_transkrypcja.pdf`, `AJ_M04L01_prezentacja.pdf` | Wprowadzenie do MCP | 1040 + 596 | skimmed (context) |
| `_docs/AJ_M04L02_transkrypcja.pdf`, `AJ_M04L02_prezentacja.pdf` | Komponenty MCP | 3203 + 1879 | auth/transport sections |
| `_docs/AJ_M04L03_transkrypcja.pdf`, `AJ_M04L03_prezentacja.pdf` | Autoryzacja | 1664 + 850 | full |
| `_docs/AJ_M04L04_transkrypcja.pdf`, `AJ_M04L04_prezentacja.pdf` | Production readiness | 1781 + 455 | full |
| `_docs/AJ_M04L05_transkrypcja.pdf` | Specyfikacja 2026-07-28 | 2060 | full |
| `_docs/AJ_M04L06_transkrypcja.pdf` | Jak działa MCP Server w praktyce (kod i flow) | 1356 | full |
| `_docs/AJ_M04L07_transkrypcja.pdf` | Nieprzekazywanie tokenów: Introspection i Exchange w praktyce | 527 | full |

The text was extracted with `pdftotext -enc UTF-8`. Positions are given as "pN" (the PDF page footer in the transcript) or "slide N" (the slide number in the presentation). Instructor: Jakub Kubryński. All summaries are paraphrased in English. Polish quotes are short (15 words or fewer).

---

## M04L03 — Autoryzacja (HIGH priority)

**Topic**: the MCP authorization architecture, the discovery flow, client registration, security requirements, an account-takeover case study, persistent access, least privilege, and DPoP.

### Key claims

1. **Authorization is optional and depends on transport** (p1, slide 2). Local stdio needs none. A remote/SaaS server almost certainly needs it. The standards cited on slide 2 are OAuth 2.1, RFC 8414 (AS metadata), RFC 7591 (DCR) and RFC 9728 (protected resource metadata). MCP reuses proven web mechanisms rather than inventing new ones.
2. **Roles** (p1, slide 3):

   | MCP role | OAuth role | What it does |
   |---|---|---|
   | MCP Server | Resource Server | validates tokens, decides whether a given tool may be used |
   | MCP Client | OAuth Client | makes requests on behalf of the user |
   | Auth Server | Authorization Server | issues tokens and manages their lifecycle; an independent component |

   The course says understanding these roles is key to configuring the components correctly.
3. **Discovery flow, 8 steps** (p1–2, slide 4):
   1. The client sends a request with no token.
   2. The server returns 401 with `WWW-Authenticate` containing the `resource_metadata` URL.
   3. The client fetches `/.well-known/oauth-protected-resource`.
   4. The client reads `authorization_servers` from that document.
   5. The client fetches `/.well-known/oauth-authorization-server`.
   6. The client runs the OAuth 2.1 flow with mandatory PKCE S256.
   7. **Resource binding**: the client adds the `resource` parameter (RFC 8707) to bind the token to its intended audience.
   8. The client sends `Authorization: Bearer` on every following request.
4. **Client registration priority, labelled "Draft Spec"** (slide 5, p2):
   1. Pre-registered credentials: the simplest and safest option, but rarely available to popular MCP clients.
   2. Client ID Metadata Documents (CIMD): recommended and new, described as the "złoty środek" (golden mean) between security and flexibility.
   3. Dynamic Client Registration: fallback only, "Degradacja z SHOULD do MAY".

   Anecdote: until recently Claude would not connect without DCR, and CIMD was available only in the Enterprise tier.
5. **DCR problem** (slide 6, p2): an open `/oauth2/register` endpoint hands out `client_id`/`client_secret` without verifying who is asking. The captured example asks for scope `"mcp mcp:read mcp:tools"` with redirect `https://claude.ai/api/mcp/auth_callback`.

### Security requirements (slide 7, "Bezpieczeństwo — Głębia"), the core normative content

- **Audience Binding (RFC 8707)**: servers MUST check that the token was issued precisely for them. Transcript p2: the MCP server should check the token was issued "dokładnie dla tego serwera" (exactly for this server).
- **Refresh token rotation**: mandatory for public clients, with short access-token TTLs. The rationale given is that a refresh is the point where revocation or withdrawn consent gets checked (p3).
- **No Token Forwarding**: "Serwery MCP NIE MOGĄ przekazywać tokenów klienta do upstream API" (MCP servers MUST NOT pass client tokens to upstream APIs). The stated purpose is preventing **Confused Deputy** attacks (slide 7). The transcript (p3) calls forwarding a temptation, "co też zobaczymy w naszej przykładowej aplikacji" (which we will also see in our sample app). The risk is greatest when the MCP server is a thin proxy ("prosta przelotka").
- **PKCE**: mandatory, S256.

### Case study: One-Click Account Takeover (Obsidian Security, July 2025; slides 8–9, p3)

Affected MCP servers included Smithery, Lutra and Glue. Root causes:
- A **hybrid proxy role**: the MCP server acted as the AS for MCP clients and at the same time as an OAuth client for upstream SaaS.
- No `state` binding: the browser session was not tied to the OAuth flow that started it.
- Generic consent screens that did not show `client_name`/`redirect_uri`.

Attack: the attacker registers a client via DCR, injects their own session cookie into the victim's browser, the victim authorizes, the code gets bound to the attacker's session, and the attacker receives the token.

Lesson: "Sama obecność OAuth/PKCE nie gwarantuje bezpieczeństwa" (OAuth/PKCE alone do not guarantee security) without session binding and clear consent (slide 9).

### Persistent access risk (slide 10, p4)

- Long refresh-token lifetimes (the "30 dni" problem) let the LLM query company data while the user is offline.
- "Centralny wyciek": tokens stored at the model provider become a single high-value breach target across thousands of organisations.

### Least privilege (slide 11, p4)

- Use granular scopes (examples: `read_only`, `single_repo`).
- Limit blast radius by running servers in dedicated containers.
- Consider a separate `read_pii` permission.
- The course notes this mitigates risk but does not fully solve it.

### DPoP (slide 12, p4)

Bearer tokens are called "najsłabsze ogniwo" (the weakest link). DPoP sender-constrains a token by having the client sign a per-request JWT proof that the server verifies. The course presents this as the main direction for raising enterprise trust in MCP. It is not part of the exercise.

### Enterprise concerns (p4–5)

Customers worry about how tokens are stored at LLM vendors, and about cross-user mix-ups, where requests run on behalf of a different user than the one logged into the agent. The instructor recommends following the MCP Discord and tickets on "enterprise readiness".

**Not covered in L03**: token introspection, token exchange, opaque vs JWT, caching.

---

## M04L07 — Nieprzekazywanie tokenów: Introspection i Exchange w praktyce (HIGH priority)

**Topic**: the instructor's solution to the L06 homework.

### How he arrived at it (p1)

- He says he did not know up front how to solve it properly, so he ran a Maister **research** on how the MCP server should handle authorization without forwarding tokens.
- The research covered the MCP spec and public vendor material on authorization services. It produced a report plus `high-level-design.md`, with business context, C4 levels 1 and 2, classes to implement, data flow, and ADRs.
- Paraphrased conclusion of that design: the MCP server currently violates two normative MUST NOT requirements of the MCP spec. The transcript does not name them. From L03 they are most likely the no-token-forwarding rule and the audience check.
- After accepting the design he ran Maister **development** ("mcp-token-security"). It produced requirements linked to the research, ADRs, scopes, a spec with goal, user stories and core requirements, and precise class/method/line-level change instructions.
- Excluded per the research plan: that artifact and the reverted commit were not read here.

### The solution as described (p1–2)

- **MCP server, inbound**: a security filter performs **Token Introspection** of the token arriving from the MCP client.
- **MCP server, outbound**: if introspection succeeds, a **`TokenExchangeClient`** calls the AS to **exchange** that token for another one (a backend token). Only the exchanged token goes on to the backend. In his words, everything else "idzie już tak samo" (works the same as before).
- **Host / backend (AS side)**: an **`IntrospectionFilter`** serves introspection, and the token endpoint supports the **exchange grant** ("z jakimś konkretnym grantem", with some specific grant).
- **Outcome**: "Cała usługa działa dokładnie tak samo, jak działała wcześniej" (the whole service works exactly as before). The change is transparent to tools and users.

### Trade-offs mentioned

None. The lesson does not mention caching of introspection results, latency or the extra round trips, opaque vs JWT tokens, `aud`/`resource` values for the exchanged token, `act` claims, or client authentication of the MCP server toward the AS.

---

## M04L05 — Specyfikacja 2026-07-28 (HIGH priority)

**Topic**: the new MCP specification "datowanej na 28 lipca 2026" (dated 28 July 2026). Why it exists: MCP outgrew local tooling and hit scalability problems.

### What changed (p1–5, as described by the instructor)

| Area | Change in 2026-07-28 |
|---|---|
| State | The stateful mode is **removed**. MCP becomes a one-directional request/response protocol. Earlier lessons recommended stateless; the spec now requires it. |
| Lifecycle | `initialize`, notifications, MCP session and `ping` are removed. |
| Per-request context | Every request must describe itself: protocol version, identity and capabilities go in `_meta` ("parametrze meta") on each call. The server validates them every time because it keeps no state. |
| Discovery | New `server/discover` method. Servers must implement it, and clients may call it to learn supported versions and capabilities. It replaces the discovery part of `initialize`. |
| Caching | `tools/list`, `prompts/list`, `resources/list` and `resources/read` responses can be cached client-side. `cacheScope` is `global` (per client and server pair) or `user` (when the list depends on the user's permissions), with a TTL in ms. There is no `list_changed` push, so the usual CDN cache-invalidation problems apply. |
| Elicitation pattern | **MRTR** (Multi Round-Trip Requests): the server returns `resultType: input_required` with the missing fields, and the client re-sends the original request with `input_responses`, repeating as needed. Server-side state comes either from the authorization (the logged-in user) or from explicit handle parameters such as `basket_id`. |
| Deprecated / removed | Sampling (the server should call its own LLM API), roots (paths go as a URI tool parameter), the logging capability (use stderr for stdio, or OpenTelemetry), and the HTTP+SSE transport (Streamable HTTP remains). |
| Client registration | DCR, called "patologiczny" (pathological) and previously forced by vendors, is replaced by **Client ID Metadata Documents**. |
| Migration timing | No need to migrate immediately: tier-1 SDKs (for example Java) are only now adopting it, and the stateful mode stays supported until **August 2027**. The instructor advises not designing new servers on deprecated features. |

### Enterprise-Managed Authorization extension (p4–5)

- Originally proposed by Okta. The problem it solves: the enterprise IdP was not part of MCP authorization, so every server ran its own consent.
- The instructor's analogy: a central security desk issues temporary building passes.
- Flow: the client (for example Claude, logged in via the ACME IdP) asks the IdP for an **ID-JAG** (Identity Assertion JWT Authorization Grant, valid about 5 minutes) for the target server. The target (for example GitHub) verifies the IdP's signature and exchanges the ID-JAG for its own access token. There is no consent screen at the target.
- Caveat: the issuer still controls token lifetime, and two months would be bad.
- The server declares support in its **OAuth Protected Resource metadata**. The client signals support in `_meta`.
- Recommendation: adopt it as soon as possible, especially for enterprise customers ("bym nie czekał", I wouldn't wait). For MRTR, wait for SDK support.

### Relevance to auth

L05 does not mention any change to the passthrough or audience rules. Those come from L03. What L05 does change bears on the auth design in three ways:
- The server must authenticate and authorize each request on its own, with no session to cache auth state in.
- Tool lists may depend on the user (`cacheScope: user`).
- ID-JAG is itself a token-exchange-style pattern (RFC 8693 / JWT bearer grant family).

---

## M04L04 — Production readiness (MEDIUM priority)

**Topic**: production pitfalls.

- **Transport differences** (slide 2, p1): stdio and HTTP differ in sampling (bidirectional vs passive), environment access (full local access vs only what is injected into the container, 12-factor), lifecycle (process vs network: LB timeouts, 502/504, `sessionId` expiry) and debugging (stderr vs distributed logs, CORS, tracing). Advice: develop on the transport you will run in production.
- **WAF/CDN/proxy** (slide 3, p1–2): Cloudflare and others classify AI-client traffic as bots. The symptom is that the well-known lookups, consent and token steps all succeed, then the MCP calls silently die with nothing in the app logs. Fix: check edge security events and User-Agent rules, and try a temporary bypass.
- **Technical pitfalls** (slide 4, p2–3):
  - Context bloat: 50 or more tools cost 30–60k tokens, which is why GitHub and Atlassian users moved to CLIs.
  - Strict schema validation: any mismatch between the schema and the returned JSON kills the tool call.
  - No cancel support: long operations can hang the chat thread.
- **Blue/green and breaking changes** (slide 5, p3): the model remembers old tool schemas. Version tools (for example `get_user_v2`) and send `notifications/tools/list_changed`. Under the 2026-07-28 spec the latter is gone (see L05).
- **Different user permissions** (slide 6, p3–4): tool lists are static, set at server start and identical for everyone. A user without permission gets an error at call time, and the agent may remember the failure across a user switch. The workaround is extra tools that list the criteria available to the user.
  - This is an auth-relevant constraint: authorization is enforced per call, downstream, using the caller's identity, which supports carrying user identity through to the backend.
- **Adoption** (slide 7, p4–5): MCP's sweet spot is internal clients (your own chatbot, plus internal systems such as Jira or a CRM), because "kontrolujesz klienta i serwer" (you control client and server). Public discovery and complex auth are then less needed. Skip MCP for simple single integrations, ultra-low-latency systems, or custom streaming.
- **Latency** comes up only here, in a general remark about JSON-RPC overhead. Nothing is said about auth round trips.

---

## M04L06 — Jak działa MCP Server w praktyce (kod i flow) (MEDIUM priority) — the exercise

**Topic**: a walkthrough of our `mcp-server` module, and the homework.

### Code tour (p1–2)

- A separate Java project using the **Stateless** MCP server transport, with tools, prompts and resources capabilities.
- Three tools: `aj_list_products`, `aj_list_categories` and `aj_add_product`, with annotations (`readOnlyHint`, `destructiveHint`) and full input/output schemas.
- Each tool handler "ustawia nam token dostępowy" (sets the access token) and then calls the host API client (for example `GET api/categories`).
- A controller serves `/.well-known/` metadata: resource, authorization server, and supported scopes.
- The host app gained OAuth 2.1 pieces: **PublicClientRegistration** (open DCR) and a **TokenFilter** handling token issuance for MCP clients.
- The deployment is exposed via a Cloudflare tunnel at `https://kuba.mcp.labs.skillpanel.com`. This matches the public `*.labs-skillpanel.com` defaults noted in the research plan.

### Live flow with Claude as custom connector (p2–3)

1. A POST to the MCP server returns 401 with the metadata header.
2. Claude fetches the PRM document (200).
3. Claude fetches the AS metadata (200).
4. Claude registers via `/oauth2/register`.
5. Claude obtains a token (after the user consents, including edit permission).
6. Claude calls the MCP server with the token.

The tools then run, with per-tool approval settings ("allow once" is used for the destructive `add_product`).

### The flaw, stated by the instructor (p4)

The current code does exactly what the lecture warned against. It takes the `Authorization: Bearer` header, stores the token, and passes the very same token on for further processing.

### Homework (exercise requirements for module 4)

1. Deploy the MCP server and connect it to your own Claude or ChatGPT.
2. Change the token-forwarding mechanism to the "prawilny mechanizm, który powinien być wykorzystywany w MCP" (the proper mechanism MCP should use). The instructor suggests Maister feature development and deliberately does not reveal the answer: "Na razie celowo nie mówię, jaki" (for now I deliberately don't say which). The student should work it out with the agent.

**Implicit acceptance criteria, from L06 and L07**:
- The MCP client's token is no longer sent to the host.
- The service behaves exactly as before.
- The flow stays spec-compliant (401 and metadata discovery, consent, scopes).

---

## M04L01 / M04L02 — context only (LOW priority)

- **L01**: MCP basics (Host, Client and Server roles; JSON-RPC 2.0; the `initialize` handshake, which L05 later says is removed). The transport layer is said to carry authentication.
- **L02** (p8–9, slide 19 onward):
  - stdio uses environment credentials. Streamable HTTP is for "zdalne serwery, wielu użytkowników" (remote servers, many users) and requires **OAuth 2.1**.
  - Stateless vs stateful: stateless drops session IDs, server-to-client requests, sampling, progress and subscriptions, but scales horizontally behind a load balancer without session affinity.
  - Transport decision tree: local → stdio; remote → Streamable HTTP; need sampling or progress → stateful with sticky sessions; need horizontal scaling → stateless.
  - Our server uses stateless (L06), so auth must be evaluated per HTTP request.

---

## Cross-lesson summary on the requested topics

| Topic | What the course says | Lesson |
|---|---|---|
| Token passthrough | MCP servers MUST NOT forward client tokens upstream. Our app currently does, deliberately, as the exercise baseline. | L03 slide 7 / p3, L06 p4 |
| Confused deputy | Named as the attack that the no-forwarding rule prevents. The hybrid "proxy = AS + upstream client" role is a root cause in the Obsidian case study. Explained only briefly. | L03 slides 7–8 |
| Audience | MCP servers MUST verify the token was issued for them (RFC 8707 audience binding). | L03 slide 7 / p2 |
| Resource indicators (RFC 8707) | The client sends the `resource` parameter during authorization (discovery step 7). | L03 slide 4 / p2 |
| Token introspection (RFC 7662) | The MCP server introspects the inbound token. The host exposes an `IntrospectionFilter`. | L07 p1–2 |
| Token exchange (RFC 8693) | The MCP server's `TokenExchangeClient` exchanges the inbound token for a backend token. The host token endpoint supports the exchange grant. ID-JAG (L05) is a related enterprise pattern. | L07 p2, L05 p5 |
| Caching / latency / opaque vs JWT | Not discussed for auth. Caching is discussed only for `tools/list` (`cacheScope`/TTL), and latency only as general JSON-RPC overhead. | L05 p2, L04 slide 7 |
| Refresh / TTL | Rotate refresh tokens for public clients. Keep access-token TTLs short. Beware 30-day refresh windows. | L03 slides 7, 10 |
| Least privilege | Granular scopes, possibly `read_pii`. The DCR example scope is `mcp mcp:read mcp:tools`. | L03 slides 6, 11 |
| Sender-constraining | DPoP is the future direction. Not required. | L03 slide 12 |
| Spec revision | "2026-07-28": stateless only, `_meta` per request, `server/discover`, list caching, MRTR, CIMD replaces DCR, Enterprise-Managed Authorization (ID-JAG), stateful supported until Aug 2027. | L05 |
| Client registration | Pre-registered, then CIMD (recommended), then DCR (fallback, SHOULD→MAY). Open `/oauth2/register` is an attack enabler. | L03 slides 5–6, L05 p1 |

---

## Implications for our design

1. **The minimum bar the course sets**: remove passthrough, so the MCP server never sends the client's token to `/api/**`, and add audience validation at the MCP server. These are the "two MUST NOTs" the L07 design addresses.
2. **The course's expected solution shape**: introspect inbound tokens at the MCP server, then do an RFC 8693 exchange for a backend token. The host needs an introspection endpoint and an exchange grant on its token endpoint. Aligning with this maximises course alignment. The comparison of alternatives should still score JWKS/local JWT validation and client-credentials variants honestly.
3. **Decisions the course leaves to us**, which synthesis must settle from the RFCs and industry patterns:
   - Caching of introspection and exchange results, given that stateless transport means every request re-authenticates.
   - The exchanged token's `aud`/`resource` (the host API) and its TTL.
   - Delegation (`act`) vs impersonation.
   - How the MCP server authenticates to the AS (a confidential client seeded with a secret from env).
   - How `mcp:*` scopes map to backend permissions so that exchange cannot escalate.
4. **Keep behaviour identical**: the L07 success criterion is that tools, consent and discovery work exactly as before. Existing tool tests and the 401/PRM discovery tests are the regression net.
5. **Stateless per-request auth** (L02, L05, L06): with no session, auth context must be resolved per HTTP request. A request-scoped holder is consistent with this, but it should hold the exchanged backend token or principal, never re-emit the inbound token.
6. **Audience binding needs the AS to honour `resource`** (L03 step 7). If the host AS ignores RFC 8707 today, inbound tokens carry no MCP-server audience to check. Either add `resource`→`aud` support to the AS, or have introspection return `aud`, so the MCP server can validate it.
7. **Adjacent hardening the course flags**, out of scope for the passthrough fix but worth listing as follow-ups:
   - The open DCR endpoint (`PublicClientRegistrationFilter`); the course recommends CIMD or pre-registration.
   - Refresh-token rotation and short TTLs.
   - Granular scopes.
   - DPoP.
   - The Enterprise-Managed Authorization extension, declared in PRM.
8. **Spec revision alignment**: the course targets 2026-07-28. Any design artefacts, such as the PRM fields and the error format, should cite the revision the external-specs gatherer confirms, and note any divergence from the course's description.
