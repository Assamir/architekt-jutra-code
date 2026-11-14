# External specs: MCP specification revision and authorization delta

## TL;DR
- The **current** MCP protocol version is **2026-07-28**. The live versioning page confirms it, so the course reference (M04L05) is correct. The previous revision is 2025-11-25.
- For the MCP-server-as-resource-server and the MCP-server-to-upstream hop, the 2026-07-28 normative text is **unchanged** from 2025-11-25. Both revisions require audience validation, `MUST NOT accept or transit any other tokens`, and `MUST NOT pass through the token it received from the MCP client`.
- What changed is mostly on the client and AS side: RFC 9207 `iss` validation, DCR deprecated in favour of CIMD, a new Refresh Tokens section, scope-union step-up, scope-hierarchy handling, and stateless MCP (no `Mcp-Session-Id`). The authorization spec was also split into sub-pages, and the token-passthrough rationale moved to the Security Best Practices page.
- The OAuth 2.1 draft the MCP spec cites is **-13**. The latest IETF draft is **-17** (2026-10-31, active WG document, not yet an RFC). The section numbers MCP relies on (5.1.1, 5.2, 5.3) are unchanged in -17.

## Key Decisions
- Cite the **2026-07-28** pages as the normative baseline, because it is the current version per https://modelcontextprotocol.io/specification/versioning.
- Treat the Security Best Practices page as **supporting, not core spec**. In 2026-07-28 it lives under `/docs/2026-07-28/tutorials/security/`, not `/specification/`. Its MUSTs still bind, because the normative Security Considerations page links to it ("why token passthrough is explicitly forbidden") and the authorization spec says implementations MUST follow Security Considerations.

## Open Questions / Risks
- The MCP spec never says **how** an MCP server should obtain the upstream token. It only says the server "may act as an OAuth client" and that the upstream token "is a separate token, issued by the upstream authorization server". Token exchange, client credentials and other options are all left to the implementer, so the design choice is ours and must be justified with the RFCs.
- The spec's "Confused Deputy" MUST (per-client consent) is scoped to **MCP proxy servers that use static client IDs against a third-party AS**. The aj MCP server does not run its own authorize/consent flow; the host AS does. That MUST is therefore largely N/A for aj. The passthrough-related confused-deputy risk (Security Best Practices, Token Passthrough, dimension 2) does apply.
- ext-auth extensions (Enterprise-Managed Authorization, OAuth Client Credentials) cover only the MCP-client-to-MCP-server hop. Neither covers MCP server to upstream API.

---

## 1. Revision confirmation

**Source**: https://modelcontextprotocol.io/specification/versioning (fetched 2026-11-14)
> "The **current** protocol version is **2026-07-28**."

Status semantics on that page: Draft (in progress), Current (ready, may receive backwards-compatible changes), Final (frozen). 2026-07-28 is Current. The page points to `2025-11-25` and earlier as the "handshake-based protocol revisions".

**Confidence**: High (100%). The primary source says it directly.

## 2. Page structure in 2026-07-28 (for citing anchors)

| Topic | URL |
|---|---|
| Authorization (core) | https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization |
| Authorization Server Discovery (RFC 9728 / RFC 8414) | https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery |
| Client Registration (CIMD, pre-registration, DCR) | https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration |
| Security Considerations (normative) | https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations |
| Security Best Practices (token passthrough, confused deputy, state handles, scope minimization) | https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices |
| Changelog | https://modelcontextprotocol.io/specification/2026-07-28/changelog |

In 2025-11-25, all of the above except Best Practices were a single page (`/specification/2025-11-25/basic/authorization`), and Best Practices lived at `/specification/2025-11-25/basic/security_best_practices`.

## 3. Standards the 2026-07-28 authorization spec builds on

From the authorization page, under Standards Compliance: OAuth 2.1 draft-ietf-oauth-v2-1-13, **RFC 6750** (new in the list), RFC 8414, RFC 7591, **RFC 8707** (new in the list; 2025-11-25 already referenced it in the body), RFC 9728, **RFC 9207** (new), the CIMD draft -00, OIDC Discovery 1.0, and OIDC Dynamic Registration 1.0.

The list does **not** include RFC 8693, RFC 7662 or RFC 9068 as building blocks. RFC 9068 appears only as an example of audience claim validation in Best Practices, under Token Passthrough. Upstream-call mechanics are out of scope for MCP.

## 4. Delta 2025-11-25 → 2026-07-28 relevant to auth

Source: https://modelcontextprotocol.io/specification/2026-07-28/changelog, plus a side-by-side read of both authorization pages.

| # | Change | Normative effect | Relevance to aj MCP→host hop |
|---|---|---|---|
| 1 | Protocol-level sessions and `Mcp-Session-Id` removed; MCP is stateless (SEP-2567, SEP-2575) | Every request is independent. Token Requirements drops "even if they are part of the same logical session", but "authorization MUST be included in every HTTP request" remains | Supports per-request token validation and per-request outbound credential acquisition. No session-bound token cache keyed by session ID |
| 2 | Best Practices "Session Hijacking" replaced by **State Handle Hijacking** | MCP servers that implement authorization **MUST verify all inbound requests**, **MUST NOT treat possession of a state handle as authentication**, and **SHOULD** bind handles to the user ID derived from the verified token | aj tools are stateless today. If handles are added, bind them to the validated `sub` |
| 3 | RFC 9207 `iss` in authorization responses (SEP-2468) | AS **SHOULD** emit `iss` and **MUST** advertise `authorization_response_iss_parameter_supported` if it does. Clients **MUST** validate | Host AS gap (AS side), not part of the MCP→host hop |
| 4 | DCR **deprecated** in favour of CIMD (PR #2858) | AS and clients **SHOULD** support CIMD; DCR is **MAY**, retained for backward compatibility | The host AS implements DCR (`PublicClientRegistrationFilter`). Still allowed, but deprecated |
| 5 | `application_type` required in DCR (SEP-837) | Client-side | Low |
| 6 | Credentials bound to the issuing AS (SEP-2352) | Clients **MUST** key credentials by issuer | Client-side |
| 7 | New **Refresh Tokens** section | MCP servers **SHOULD NOT** include `offline_access` in the `WWW-Authenticate` scope or in `scopes_supported` | Check the MCP server's RFC 9728 `scopes_supported` |
| 8 | Scope challenge: **all** required scopes in a single challenge (SHOULD); clients compute the scope **union** (SHOULD); servers **MUST account for scope hierarchies** | New MUST for servers: a broader scope satisfies narrower ones | If `mcp:edit` implies `mcp:read`, the MCP server must honour that when it checks scopes |
| 9 | AS metadata validation: the `issuer` in the document **MUST** be identical to the issuer used to build the well-known URL (Authorization Server Discovery page) | Client-side MUST | The host AS metadata `issuer` must match its public URL exactly |
| 10 | Token Handling, Access Token Privilege Restriction, Token Audience Binding | **Unchanged** text | Core requirements for this research. See `external-specs-requirements.md` |
| 11 | Token-passthrough "two dimensions" text (audience validation failures, token passthrough) | Moved from the authorization page (2025-11-25, Access Token Privilege Restriction) to Best Practices, Token Passthrough. Content unchanged | Cite the Best Practices anchor for 2026-07-28 |

**Confidence**: High. The changelog and both page texts were fetched and compared.

## 5. MCP authorization extensions (applicability check)

Source: https://github.com/modelcontextprotocol/ext-auth (both extensions are marked Stable, versioned 2026-07-28)

- **Enterprise-Managed Authorization** (`specification/stable/enterprise-managed-authorization/2026-07-28/...`). The MCP client exchanges an enterprise IdP assertion for an ID-JAG (RFC 8693), then presents that to the MCP AS with the JWT bearer grant (RFC 7523). Key MUSTs: the token-exchange `audience` MUST be the Resource AS issuer, and the issued access token MUST be audience-restricted to the MCP Server. Hop: MCP client to MCP server only.
- **OAuth Client Credentials** (`specification/stable/oauth-client-credentials/2026-07-28/...`). Machine-to-machine MCP client to MCP server. `private_key_jwt` (RFC 7523 §2.2) is recommended over a client secret. Hop: MCP client to MCP server only.
- **Neither extension covers the MCP server calling an upstream API.** They do show the MCP ecosystem using RFC 8693 and RFC 7523, and audience-restricting each issued token, which supports the same pattern on the aj upstream hop.

**Confidence**: Medium-High. Summarised from the raw `.mdx` via WebFetch; full text not reviewed line by line.

## 6. OAuth 2.1 draft version

Source: https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/ (fetched 2026-11-14)
- Latest: **draft-ietf-oauth-v2-1-17**, dated 2026-10-31, expires 2027-05-04. State: WG Document, IESG submission planned for December 2026, not an RFC.
- MCP 2026-07-28 cites **-13** throughout. Its Refresh Tokens section cites -14 §4.3.
- Sections checked in -17 that MCP relies on: §1.5 Communication Security, §5.1 and §5.1.1 bearer header, §5.2 validation, §5.3 errors, §4.3.1 refresh token rotation, §7.1.4 privilege restriction. All exist with the same meaning. Details are in `external-specs-requirements.md`.
