# External specs: normative requirements for MCP server ↔ host auth

## TL;DR
- MCP 2026-07-28 requires the MCP server, acting as a resource server, to **validate every inbound token, including its audience** (the token must be issued *for this MCP server*). The server **MUST NOT accept or transit any other tokens**, and **MUST NOT pass through** the client's token to upstream APIs. Upstream calls need a **separate token** from the upstream AS. Today's `McpJwtFilter` and `JwtForwardingInterceptor` violate all three.
- The MCP spec does not say how the upstream token is obtained. RFC 8693 explicitly covers "a resource server acting as client" exchanging a received token for one aimed at a backend. That makes **token exchange with `resource`/`audience` = host API** the standards-native fit, with client authentication (RFC 9700 §2.5 SHOULD, asymmetric RECOMMENDED) and **delegation semantics (`act`)** for auditability.
- Inbound validation can be local JWT validation (RFC 9068: `typ=at+jwt`, `iss` exact match, `aud` must contain this RS, signature with AS-published keys, `exp`) or **RFC 7662 introspection**. Introspection requires the caller to authenticate, returns `active:false` for anything not usable, and responses MUST NOT be cached beyond `exp`.
- The AS side must audience-restrict tokens: RFC 8707 SHOULD, RFC 9068 §3 `aud`=`resource`, RFC 9700 §2.3 SHOULD. Each backend must refuse tokens not meant for it (RFC 9700 §2.3 MUST, OAuth 2.1 §7.1.4 MUST). RFC 9068 RECOMMENDS asymmetric signing with keys published via `jwks_uri`, which argues against sharing the host HMAC secret.
- DPoP, mTLS and RFC 7523 are optional hardening (sender-constraining SHOULD, asymmetric client authentication RECOMMENDED). None is required by MCP for this hop.

## Key Decisions
- Roles used in the table: **MCP-RS** (MCP server as resource server toward MCP clients), **MCP-C** (MCP server as OAuth client toward the host AS and API), **AS** (host authorization server), **BE-RS** (host REST API as resource server), **MCP-Client** (Claude or another MCP client; context only).
- Keywords are the RFC 2119 uppercase ones exactly as they appear in the source. Lowercase "should" or "must" in RFC prose is marked `(lc)` and is non-normative, as RFC 8707 §1.1 and RFC 2119 define.
- Requirements are paraphrased, with a short quote where wording matters. Every row cites the URL and section so the synthesizer can build the compliance matrix directly.

## Open Questions / Risks
- **Which inbound validation mode is "MUST"-compliant is not fixed by the specs.** OAuth 2.1 §5.2 says validation methods are out of scope, and both JWT (RFC 9068) and introspection (RFC 7662) are acceptable. The choice is a design trade-off (revocation latency vs. latency and coupling), not a compliance question.
- **The aj topology collapses roles.** The MCP server's AS and the "upstream AS" are the same host AS, and the upstream RS is the host itself. RFC 8693 §1 explicitly allows an RS to act as an exchange client. The MCP spec's "separate token, issued by the upstream authorization server" is still satisfied as long as the new token has a **different audience** (host API) and is not the client's token.
- **Delegation vs. impersonation.** RFC 8693 leaves composite (`act`) tokens to AS policy. MCP Best Practices (Token Passthrough, Accountability) argues for being able to distinguish MCP clients and the MCP server in downstream logs, which favours delegation with `act` (sub = user, act.sub = MCP server client_id). This is an inference, not a MUST.
- **HMAC shared secret.** RFC 9068 §2.1 and §4 RECOMMEND asymmetric signing and say the RS "MUST use the keys provided by the authorization server". A symmetric key shared with the MCP server would let the MCP server mint host tokens. That is not prohibited, but it contradicts the RECOMMENDED profile and weakens the trust boundary RFC 9700 §2.3 relies on.
- **OAuth 2.1 -17 section attribution for "Confidential clients MUST authenticate".** The WebFetch extraction placed it near §4.3.1. The intended section is probably §2.4 / §3.2.1 (Medium confidence on the section number, High on the content).
- RFC 8414 rows (AS metadata) are cited from the stable RFC text from memory, not re-fetched in this session (Medium-High confidence).

---

## 1. Requirement table (compliance-matrix input)

### 1.1 MCP Authorization spec 2026-07-28

Base URL: `https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization` (A), `.../authorization/security-considerations` (SC), `.../authorization/authorization-server-discovery` (ASD). Best Practices: `https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices` (BP).

| ID | Source + section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| MCP-01 | A #protocol-requirements | SHOULD | MCP-RS | HTTP-transport implementations SHOULD conform to the authorization spec. Authorization itself is OPTIONAL |
| MCP-02 | A #overview item 4; ASD #authorization-server-location | MUST | MCP-RS | Implement RFC 9728 Protected Resource Metadata. The document MUST include `authorization_servers` with at least one AS |
| MCP-03 | ASD #protected-resource-metadata-discovery-requirements | MUST (one of) | MCP-RS | Provide PRM discovery via `WWW-Authenticate: Bearer resource_metadata="…"` on 401, **or** at a well-known URI (path-inserted `/.well-known/oauth-protected-resource/<path>` or root) |
| MCP-04 | A #scope-selection-strategy | SHOULD | MCP-RS | Include `scope` in the `WWW-Authenticate` challenge (RFC 6750 §3) to indicate the required scopes |
| MCP-05 | A #token-requirements | MUST | MCP-Client | Send `Authorization: Bearer <token>` on every HTTP request. Never put tokens in the query string |
| MCP-06 | A #token-handling | **MUST** | MCP-RS | Validate access tokens as described in OAuth 2.1 §5.2 |
| MCP-07 | A #token-handling | **MUST** | MCP-RS | Validate that access tokens were issued specifically for this server as the intended audience, per RFC 8707 §2 |
| MCP-08 | A #token-handling | MUST | MCP-RS | On validation failure, respond per OAuth 2.1 §5.3. Invalid or expired tokens MUST get HTTP 401 |
| MCP-09 | A #token-handling | MUST NOT | MCP-Client | Send only tokens issued by the MCP server's own AS |
| MCP-10 | A #token-handling | **MUST** | MCP-RS | "only accept tokens that are valid for use with their own resources" |
| MCP-11 | A #token-handling | **MUST NOT** | MCP-RS | "accept or transit any other tokens" |
| MCP-12 | A #error-handling | MUST | MCP-RS | Use 401 (missing or invalid token), 403 (insufficient scope or permission), 400 (malformed request) |
| MCP-13 | A #runtime-insufficient-scope-errors | SHOULD | MCP-RS | Insufficient scope: 403 + `WWW-Authenticate: Bearer error="insufficient_scope", scope="…", resource_metadata="…"`. Put all scopes required for the operation in a single challenge |
| MCP-14 | A #step-up-authorization-flow | MUST | MCP-RS | Account for scope hierarchies (a broader scope implies narrower ones) when deciding whether a token suffices. *New in 2026-07-28* |
| MCP-15 | A #refresh-tokens | SHOULD NOT | MCP-RS | Do not include `offline_access` in the `WWW-Authenticate` scope or in PRM `scopes_supported` |
| MCP-16 | A #security-considerations | MUST | all | Follow the normative Security Considerations page |
| MCP-17 | SC (intro) | MUST | all | Follow OAuth 2.1 §7 Security Considerations |
| MCP-18 | SC #token-audience-binding-and-validation | MUST | MCP-Client | Include `resource` in authorization and token requests |
| MCP-19 | SC #token-audience-binding-and-validation | **MUST** | MCP-RS | Validate that presented tokens were specifically issued for this server's use |
| MCP-20 | SC #token-theft | MUST | MCP-RS, MCP-Client | Implement secure token storage per OAuth 2.1 §7.1 (tokens cached or logged on the server are a theft vector) |
| MCP-21 | SC #token-theft | SHOULD | AS | Issue short-lived access tokens |
| MCP-22 | SC #token-theft | MUST | AS | Rotate refresh tokens for public clients (OAuth 2.1 §4.3.1) |
| MCP-23 | SC #communication-security | MUST | AS | All AS endpoints over HTTPS. Redirect URIs must be localhost or HTTPS |
| MCP-24 | SC #authorization-code-protection | MUST | MCP-Client, AS | PKCE S256. An AS using OIDC discovery MUST publish `code_challenge_methods_supported` |
| MCP-25 | SC #open-redirection | MUST | AS | Exact redirect URI matching against pre-registered values |
| MCP-26 | SC #confused-deputy-problem | MUST | MCP proxy w/ static client ID | Obtain user consent for each dynamically registered client before forwarding to a third-party AS. **N/A to aj as built** (the MCP server does not proxy an authorize flow) |
| MCP-27 | SC #access-token-privilege-restriction | **MUST** | MCP-RS | Validate tokens **before** processing the request, ensure the token is issued specifically for this server, and take all necessary steps so no data is returned to unauthorized parties |
| MCP-28 | SC #access-token-privilege-restriction | MUST | MCP-RS | Follow OAuth 2.1 §5.2 to validate inbound tokens |
| MCP-29 | SC #access-token-privilege-restriction | **MUST / MUST** | MCP-RS | Only accept tokens intended for itself, and **reject tokens that do not include it in the audience claim** or otherwise verify that it is the intended recipient |
| MCP-30 | SC #access-token-privilege-restriction | (descriptive) | MCP-C | When calling upstream APIs the server "may act as an OAuth client". The upstream token "is a separate token, issued by the upstream authorization server" |
| MCP-31 | SC #access-token-privilege-restriction | **MUST NOT** | MCP-C | "The MCP server MUST NOT pass through the token it received from the MCP client." |
| MCP-32 | SC #access-token-privilege-restriction | MUST | MCP-Client | Use RFC 8707 `resource` (aligns with RFC 9728 §7.4) |
| MCP-33 | BP #token-passthrough, Mitigation | **MUST NOT** | MCP-RS | "accept any tokens that were not explicitly issued for the MCP server" |
| MCP-34 | BP #token-passthrough, Risks | (rationale) | MCP-RS, MCP-C, BE-RS | Passthrough risks: circumvention of security controls (rate limits and validation tied to audience); broken accountability (the server cannot distinguish clients, and downstream logs show the wrong source); the server becomes an exfiltration proxy for stolen tokens; trust-boundary breach across services; future-compatibility risk |
| MCP-35 | BP #state-handle-hijacking | MUST / MUST NOT / SHOULD | MCP-RS | Verify all inbound requests. Never treat a state handle as authentication. Bind handles to the user ID **derived from the verified token** |
| MCP-36 | BP #scope-minimization | (guidance) | MCP-RS | Keep `scopes_supported` minimal and avoid omnibus scopes. "Treating claimed scopes in token as sufficient without server-side authorization logic" is listed as a common mistake |
| MCP-37 | ASD #authorization-server-metadata-discovery | MUST | MCP-Client (affects AS) | The `issuer` in AS metadata MUST be identical to the issuer used to build the well-known URL, otherwise the metadata is rejected |

### 1.2 RFC 8693: OAuth 2.0 Token Exchange
URL: https://www.rfc-editor.org/rfc/rfc8693

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| X-01 | §1 | (descriptive) | MCP-C | "An OAuth resource server, for example, might assume the role of the client during token exchange" to trade a received token for one suited to a backend service. This is **the aj MCP→host case** |
| X-02 | §1.1 | (definitions) | AS, MCP-C | **Impersonation**: A is given B's rights and is indistinguishable from B. **Delegation**: A keeps its own identity and acts on behalf of B. Composite tokens are at AS discretion and policy |
| X-03 | §2.1 | REQUIRED | MCP-C | `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, POSTed to the token endpoint |
| X-04 | §2.1 | REQUIRED | MCP-C | `subject_token` (the party on whose behalf the request is made) and `subject_token_type` (for example `urn:ietf:params:oauth:token-type:access_token`) |
| X-05 | §2.1 | OPTIONAL / REQUIRED-if | MCP-C | `actor_token` (the acting party). `actor_token_type` is REQUIRED when `actor_token` is present and MUST NOT be sent otherwise |
| X-06 | §2.1 | OPTIONAL + MUST | MCP-C | `resource`: absolute URI of the target, which MUST NOT include a fragment. Multiple values are allowed |
| X-07 | §2.1 | OPTIONAL | MCP-C | `audience`: logical name of the target service. Values must be unique within the AS |
| X-08 | §2.1 | OPTIONAL | MCP-C | `scope` and `requested_token_type`. If the type is omitted, the issued type is at AS discretion |
| X-09 | §2.1 | **MUST** | AS | "the authorization server MUST perform the appropriate validation procedures for the indicated token type" for `subject_token`, and likewise for `actor_token` |
| X-10 | §2.1 | (policy + warning) | AS, MCP-C | Client authentication uses normal OAuth mechanisms (RFC 7523 is an example). The AS decides whether unauthenticated clients are allowed, but "omitting client authentication allows for a compromised token to be leveraged via an STS", and authentication "allows for additional authorization checks by the STS" |
| X-11 | §2.1.1 | (lc should) | MCP-C | Requested rights are the Cartesian product of targets × scopes, so clients "should exercise discretion" in breadth. The AS can return `invalid_target` for too-broad requests |
| X-12 | §2.2.1 | REQUIRED | AS | Response includes `access_token`, `issued_token_type`, `token_type` (for example `Bearer`). `expires_in` is RECOMMENDED. `scope` is REQUIRED if it differs from the request. `refresh_token` is OPTIONAL |
| X-13 | §2.2.2 | MUST / SHOULD | AS | Invalid or unacceptable request or token → `invalid_request` (MUST). Cannot issue for the target → `invalid_target` (SHOULD) |
| X-14 | §3 | (registry) | MCP-C, AS | Token type URIs: `…:access_token`, `…:refresh_token`, `…:id_token`, `…:jwt`, `…:saml1`, `…:saml2` |
| X-15 | §4.1 | **MUST** | BE-RS | `act` claim: the outermost `act` is the current actor, and nested `act` records prior actors. For access control the consumer "MUST only consider the token's top-level claims and the party identified as the current actor"; nested actors are informational only |
| X-16 | §4.3 | (claim) | AS | The `client_id` claim identifies the client that requested the token |
| X-17 | §4.4 | (claim) | AS | `may_act` names who is allowed to act for the subject. The AS can use it to authorize the exchange |
| X-18 | §5 | (guidance) | AS | Delegation and impersonation carry abuse potential. Use `scope` and limits such as short lifetimes to mitigate |
| X-19 | §6 | MUST | all | Tokens MUST only be transmitted over encrypted channels (TLS) |

### 1.3 RFC 7662: OAuth 2.0 Token Introspection
URL: https://www.rfc-editor.org/rfc/rfc7662

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| I-01 | §2 | MUST | AS | The introspection endpoint MUST be protected by TLS |
| I-02 | §2.1 | (format) | MCP-RS | HTTP POST, `application/x-www-form-urlencoded`. `token` is REQUIRED and `token_type_hint` is OPTIONAL (the AS MAY ignore it, and MUST extend its search if the hint misses) |
| I-03 | §2.1 | **MUST** | AS | "the endpoint MUST also require some form of authorization" to prevent token scanning, via client authentication or a separate bearer token |
| I-04 | §2.1 | SHOULD | AS | If the AS cannot determine state without more context, return `active:false` |
| I-05 | §2.2 | REQUIRED | AS | `active` boolean. OPTIONAL: `scope`, `client_id`, `username`, `token_type`, `exp`, `iat`, `nbf`, `sub`, **`aud`**, `iss`, `jti` |
| I-06 | §2.2 | MUST | AS | A token that is expired, revoked, unknown or **not allowed for this caller** → response with `active:false` |
| I-07 | §2.2 | SHOULD NOT | AS | Include no extra information about inactive tokens |
| I-08 | §2.2 | MAY | AS | Respond differently to different protected resources (for example, limit the returned scopes per caller) |
| I-09 | §2.2 | MAY | MCP-RS | Cache the response |
| I-10 | §2.3 | (status) | AS | Invalid caller credentials → 401 (RFC 6749 §5.2). An inactive token is **not** an error (200 + `active:false`) |
| I-11 | §4 | MUST | AS | Perform all applicable checks on the token's state (exp, nbf, revocation, signature, **resource-server restrictions**) |
| I-12 | §4 | **MUST** / SHOULD | AS | Require authentication of protected resources calling the endpoint (MUST). SHOULD require them to be specifically authorized to introspect |
| I-13 | §4 | MUST | AS, MCP-RS | TLS 1.2 support. The caller MUST check the server certificate |
| I-14 | §4 | MAY | AS | Disallow GET (keeps tokens out of logs) |
| I-15 | §4 | MUST / MUST NOT | MCP-RS | Implementers MUST consider caching trade-offs (revocation latency). A response MUST NOT be cached beyond its `exp`. Sensitive deployments may disable caching |
| I-16 | §5 | MUST | AS | Prevent disclosure of privacy-sensitive response data to unintended parties (for example, service-specific opaque user IDs) |

### 1.4 RFC 8707: Resource Indicators
URL: https://www.rfc-editor.org/rfc/rfc8707

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| RI-01 | §2 | MAY (client) / MUST (value) | MCP-Client, MCP-C | `resource` parameter: absolute URI (MUST), no fragment (MUST NOT), no query (SHOULD NOT). Multiple values MAY be sent |
| RI-02 | §2 | SHOULD | MCP-Client, MCP-C | Provide the most specific URI possible, or the API base URI when nothing more specific is known |
| RI-03 | §2 | **SHOULD** | AS | Audience-restrict issued access tokens to the indicated resource(s). The AS may use the exact value as `aud` or map it to a more general identifier |
| RI-04 | §2.1 | MAY | AS | Process requests without `resource` using a default resource, or require `resource` and fail with `invalid_target` |
| RI-05 | §2.2 | (lc) | AS | Applies at the token endpoint for all grant types (including token exchange and refresh). Acceptable resources are at the AS's sole discretion, and the AS "should" downscope and "must" (lc) report the effective scope if it differs |
| RI-06 | §3 | (lc guidance) | AS, MCP-C | A single `resource` per token is encouraged. Multi-audience bearer tokens require high trust between those audiences. Note: RFC 8707 itself contains **no RS-side MUST** to check `aud`; that comes from MCP (MCP-07), RFC 9068 §4 and RFC 9700 §2.3 |

### 1.5 RFC 9068: JWT Profile for OAuth 2.0 Access Tokens
URL: https://www.rfc-editor.org/rfc/rfc9068

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| J-01 | §2.1 | MUST / MUST NOT | AS | JWT access tokens MUST be signed, and MUST NOT use `alg: none` |
| J-02 | §2.1 | RECOMMENDED / MUST | AS | Asymmetric signing is RECOMMENDED. The AS MUST support RS256 |
| J-03 | §2.1 | MUST / SHOULD | AS | `typ` header MUST carry the media type and SHOULD be `at+jwt` |
| J-04 | §2.2 | REQUIRED | AS | Claims `iss`, `exp`, **`aud`**, `sub`, **`client_id`**, `iat`, `jti` |
| J-05 | §2.2.3 | SHOULD / MUST | AS | Include `scope` if one was requested (SHOULD). Every scope string MUST have meaning for the resources in `aud` |
| J-06 | §2.2.3.1 | SHOULD | AS | Use `groups`, `roles` and `entitlements` for authorization attributes. Arbitrary attributes MAY be added (§2.2.2), which covers a custom `permissions` claim |
| J-07 | §3 | SHOULD | AS | When `resource` is present, `aud` SHOULD equal it |
| J-08 | §3 | MUST | AS | Without `resource`, use a **default** resource indicator in `aud` (MUST). MUST NOT issue a JWT access token if the grant would be ambiguous. SHOULD reject conflicting scopes with `invalid_scope` |
| J-09 | §4 | SHOULD | AS | Publish keys via `jwks_uri` and the `iss` via AS metadata (RFC 8414) |
| J-10 | §4 | **MUST** | MCP-RS, BE-RS | Verify `typ` is `at+jwt` or `application/at+jwt`, and reject anything else |
| J-11 | §4 | **MUST** | MCP-RS, BE-RS | The expected issuer identifier MUST exactly match `iss` |
| J-12 | §4 | **MUST** | MCP-RS, BE-RS | **Reject the token if `aud` does not contain a resource indicator of the current resource server** |
| J-13 | §4 | MUST | MCP-RS, BE-RS | Validate the signature of every token "using the keys provided by the authorization server". Reject `alg: none` |
| J-14 | §4 | MUST | MCP-RS, BE-RS | Current time before `exp` (leeway MAY) |
| J-15 | §4 | MUST | MCP-RS, BE-RS | On failure, use RFC 6750 §3.1 errors (`invalid_token`) |
| J-16 | §5 | MUST | AS | Use a **distinct `aud` identifier per resource** to avoid cross-JWT confusion |
| J-17 | §5 | (lc should) | AS | Prevent clients from influencing `sub` |
| J-18 | §6 | MUST NOT | MCP-Client, MCP-C | The client MUST NOT inspect the content of the access token. The MCP server, *as a client* of the host, should treat the exchanged token as opaque |

### 1.6 RFC 9728: OAuth 2.0 Protected Resource Metadata
URL: https://www.rfc-editor.org/rfc/rfc9728

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| P-01 | §2 | REQUIRED | MCP-RS | `resource` (the RS identifier) |
| P-02 | §2 | OPTIONAL / RECOMMENDED | MCP-RS | `authorization_servers` (OPTIONAL in RFC 9728, but MUST in MCP via MCP-02). `scopes_supported` RECOMMENDED. `resource_name` RECOMMENDED. `bearer_methods_supported`, `jwks_uri` (https MUST), `dpop_bound_access_tokens_required` and `tls_client_certificate_bound_access_tokens` are OPTIONAL |
| P-03 | §3, §3.1 | MUST | MCP-RS | Serve at `/.well-known/oauth-protected-resource` inserted between host and path, via GET |
| P-04 | §3.2 | MUST | MCP-RS | 200 + `application/json`. Omit empty parameters |
| P-05 | §3.3 | **MUST** | MCP-Client (constrains MCP-RS) | The `resource` value MUST be identical to the resource identifier used to build the URL (or to the requested URL when the metadata URL came from `WWW-Authenticate`), otherwise the metadata MUST NOT be used. **The advertised `resource` must equal the MCP server's public canonical URI** |
| P-06 | §5.1 | MAY | MCP-RS | `resource_metadata` parameter in `WWW-Authenticate` (MCP elevates this to one of two required discovery options) |
| P-07 | §7.3 | MUST | MCP-Client | TLS certificate checks. The resource identifier prefix must match `resource` |
| P-08 | §7.4 | SHOULD / RECOMMENDED | MCP-Client, AS | Clients talking to multiple RSs SHOULD request audience-restricted tokens (RFC 8707). The AS SHOULD support this |
| P-09 | §7.7 | SHOULD | MCP-Client | SSRF precautions |

### 1.7 RFC 9700: OAuth 2.0 Security BCP
URL: https://www.rfc-editor.org/rfc/rfc9700

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| B-01 | §2.2.1 | SHOULD | AS, BE-RS, MCP-RS | Use sender-constrained access tokens (mTLS RFC 8705 or DPoP RFC 9449) |
| B-02 | §2.2.2, §4.14 | MUST | AS | Refresh tokens for public clients MUST be sender-constrained or rotated |
| B-03 | §2.3 | **SHOULD** | AS | Access tokens SHOULD be audience-restricted to a specific RS (or a small set) |
| B-04 | §2.3 | **MUST** | BE-RS, MCP-RS | "the resource server MUST refuse to serve the respective request" if the token is not meant for it |
| B-05 | §2.3 | MAY / SHOULD | AS | `aud` MAY be used for the restriction. Tokens SHOULD be restricted to certain resources and actions (`scope`, `authorization_details`) |
| B-06 | §2.4 | MUST NOT | AS | The ROPC (password) grant MUST NOT be used |
| B-07 | §2.5 | SHOULD / RECOMMENDED | AS, MCP-C | Enforce client authentication where feasible (SHOULD). Asymmetric methods (mTLS, `private_key_jwt`) are RECOMMENDED |
| B-08 | §2.6 | RECOMMENDED | AS | Publish RFC 8414 AS metadata |
| B-09 | §2.6, §4.15 | SHOULD NOT / MUST | AS | Do not let clients influence `client_id` or `sub` so that they can be confused with a resource owner. If unavoidable, MUST give the RS another way to distinguish |
| B-10 | §4.9.1 | (threat) | MCP-RS | A compromised or counterfeit RS that receives a token "may use that token to access other services on behalf of the resource owner". **This is the passthrough threat model.** |
| B-11 | §4.9.3 | SHOULD | AS, RS | Sender-constraining and audience restriction SHOULD be used to prevent replay at other RSs |
| B-12 | §4.9.3 | **MUST** | MCP-RS, MCP-C | "The resource server MUST treat access tokens like other sensitive secrets", which means no plaintext storage or transfer, and no logging |
| B-13 | §4.10 | SHOULD | AS | Ensure access tokens are sender-constrained **and** audience-restricted |
| B-14 | §4.13 | MUST | BE-RS, MCP-RS | Reverse proxies MUST sanitize security-relevant inbound headers. The proxy-to-app link MUST be protected |
| B-15 | §4.14 | MUST / SHOULD / MAY | AS | Risk-assess refresh token issuance (MUST). Bind refresh tokens to the consented scope and resource servers (MUST). Refresh tokens SHOULD expire on inactivity. The AS MAY revoke them on security events |

### 1.8 OAuth 2.1 (draft-ietf-oauth-v2-1-17; MCP cites -13)
URL: https://www.ietf.org/archive/id/draft-ietf-oauth-v2-1-17.html

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| O-01 | §1.4 | MUST | all | Access tokens are kept confidential in transit and storage |
| O-02 | §1.4.3 | SHOULD | AS, RS | Sender-constrain access tokens (DPoP, mTLS) |
| O-03 | §1.5 | MUST | all | Communication authentication, integrity and confidentiality. All OAuth URLs are https except loopback redirects |
| O-04 | §2.4 | RECOMMENDED | AS, MCP-C | Asymmetric client authentication (mTLS, private_key_jwt). `client_secret_post` is supported, and Basic is MAY |
| O-05 | §2.4 / §3.2.1 (see Open Q) | MUST | MCP-C | Confidential clients MUST authenticate with the AS |
| O-06 | §4.2 | MUST | AS | The client credentials grant MUST only be used by confidential clients |
| O-07 | §3.2.3 | MUST | AS | Refresh tokens are bound to the consented scope and resource servers |
| O-08 | §4.3.1 / §4.3.2 | MUST | AS / client | Public clients: detect refresh token replay through sender-constraining or rotation (AS MUST). The client MUST discard the old refresh token after rotation |
| O-09 | §5.1 | MUST NOT / MUST | clients / RS | Clients MUST NOT send tokens in the query string, and resource servers MUST ignore tokens found there |
| O-10 | §5.1.1 | MUST | RS | Support the `Authorization: Bearer` header |
| O-11 | §5.2 | **MUST** | MCP-RS, BE-RS | The RS MUST check that the token is unexpired, **authorized for the requested resource**, and issued with the appropriate scope, plus local policy. Methods are out of scope (examples: RFC 7662 introspection, RFC 9068 JWT) |
| O-12 | §5.3, §5.3.1 | SHOULD / MUST | MCP-RS, BE-RS | Inform the client of errors. MUST include `WWW-Authenticate` when credentials are missing or invalid |
| O-13 | §5.3.2 | SHOULD | MCP-RS, BE-RS | `invalid_request` → 400, `invalid_token` → 401, `insufficient_scope` → 403 (MAY add `scope`) |
| O-14 | §7.1.4 | SHOULD / MUST | AS / RS | Access tokens SHOULD be audience-restricted, preferably to one RS. The RS MUST refuse a request whose token is not meant for it |

### 1.9 RFC 6750: Bearer Token Usage
URL: https://www.rfc-editor.org/rfc/rfc6750

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| BT-01 | §2.1 | SHOULD / MUST | client / RS | Use the Authorization header. The RS MUST support it |
| BT-02 | §3 | MUST | MCP-RS, BE-RS | Include `WWW-Authenticate: Bearer` when credentials are missing or invalid. `realm` and `scope` appear at most once |
| BT-03 | §3 | SHOULD NOT | MCP-RS | When the request had **no** credentials, do not include `error` or error details |
| BT-04 | §3.1 | SHOULD | MCP-RS, BE-RS | `invalid_request` 400, `invalid_token` 401, `insufficient_scope` 403 |
| BT-05 | §5.3 | (rec.) / MUST | AS, all | Issue short-lived (≤1h) and **audience-restricted (scoped)** tokens. Do not put tokens in URLs. "Client implementations MUST ensure that bearer tokens are not leaked to unintended parties" |

### 1.10 RFC 8414: AS Metadata (cited from stable RFC text, not re-fetched this session)
URL: https://www.rfc-editor.org/rfc/rfc8414

| ID | Section | Keyword | Applies to | Requirement (paraphrased) |
|---|---|---|---|---|
| M-01 | §2 | REQUIRED | AS | `issuer` (https, no query or fragment), `token_endpoint` (unless only implicit), `response_types_supported` |
| M-02 | §2 | OPTIONAL | AS | `jwks_uri`, `grant_types_supported` (would list `urn:ietf:params:oauth:grant-type:token-exchange`), `token_endpoint_auth_methods_supported`, `introspection_endpoint`, `introspection_endpoint_auth_methods_supported`, `revocation_endpoint`, `code_challenge_methods_supported` |
| M-03 | §3.3 | MUST | client | The `issuer` value returned MUST be identical to the issuer used to build the metadata URL (MCP-37 repeats this) |

---

## 2. Applicability-only specs (sender-constraining and assertion auth)

| ID | Spec | What it gives | Relevance to the aj MCP→host hop | Required? |
|---|---|---|---|---|
| D-01 | RFC 9449 DPoP (https://www.rfc-editor.org/rfc/rfc9449) | Binds a token to the client key (`cnf.jkt`). The RS validates the `DPoP` proof (`typ dpop+jwt`, `htm`, `htu`, `iat`, `ath`) and MUST check that the proof key matches `cnf` (§4.3, §6, §7.1). A DPoP-bound token sent as Bearer is rejected (§7.2). `cnf.jkt` can come from introspection (§6.2) | Works for confidential backend clients ("regardless of the client authentication method", §1). Would make a leaked exchanged host token useless without the MCP server's private key. Needs DPoP support on both the host AS and the host RS | No. Fulfils SHOULD B-01/B-13/O-02. Optional hardening |
| D-02 | RFC 8705 mTLS (https://www.rfc-editor.org/rfc/rfc8705) | `tls_client_auth` / `self_signed_tls_client_auth` client authentication (§2). Certificate-bound tokens with `cnf.x5t#S256`, where the RS MUST verify the certificate matches the token (§3). TLS termination at a proxy is allowed, but forwarding the certificate is out of scope (§6.5) | Strong for server-to-server traffic, but needs PKI and TLS on the local dev stack (currently plain HTTP on localhost). High operational cost for a showcase | No. Fulfils B-07 (asymmetric authentication) and B-01 |
| D-03 | RFC 7523 JWT bearer (https://www.rfc-editor.org/rfc/rfc7523) | §2.2 `private_key_jwt` client authentication: `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`. For client authentication, `sub` MUST = `client_id` (§3.2), `aud` MUST identify the AS and the AS MUST reject others (§3.3), `exp` MUST (§3.4), signed or MACed MUST (§3.9). §2.1 JWT bearer as a grant (`urn:ietf:params:oauth:grant-type:jwt-bearer`) | Most relevant as the **client authentication method** for the MCP server at the host token and introspection endpoints (RECOMMENDED asymmetric, B-07/O-04). It also underlies the MCP Enterprise-Managed Authorization extension (ID-JAG). `client_secret_basic`/`client_secret_post` is acceptable but weaker | No. A `client_secret` satisfies X-10/I-03/O-05 |

---

## 3. How the requirements constrain the aj design (gatherer's reading, for synthesis)

1. **Inbound (MCP-RS)**. MCP-06/07/10/11/19/27/29/33 together with O-11, J-10..J-15 and B-04 mean the MCP server must reject any token whose audience is not the MCP server's canonical URI (the same value it advertises as `resource` in RFC 9728, P-05), and must check expiry, issuer and signature (JWT) or `active` (introspection) **before** any tool runs. The current "any Bearer accepted" filter fails MCP-06, MCP-07, MCP-19, MCP-27, MCP-29 and MCP-33.
2. **Outbound (MCP-C)**. MCP-11 ("transit") and MCP-31 ban forwarding. The replacement must be a **different token** whose audience is the host API (B-03, RI-03, J-07, J-16 distinct `aud`). The standards-native mechanism for "an RS acting as client for a downstream service, on behalf of the user" is RFC 8693 (X-01) with `subject_token` = the validated inbound token, `resource` (or `audience`) = host API identifier, client authentication of the MCP server (X-10, B-07), and optionally `actor_token` or an AS-added `act` for delegation (X-02, X-15). Client credentials alone (O-06) would lose the user identity and break the per-user permission model, so it is not an equivalent replacement. That is an inference for synthesis.
3. **Host backend (BE-RS)**. B-04, O-14 and J-12 mean the host API must start rejecting tokens whose `aud` is the MCP server, which closes the confused-deputy and replay path in both directions. If `act` is used, X-15 says authorization decisions use the top-level claims plus the current actor only.
4. **Host AS**. To support items 1 to 3: emit `aud` from `resource`, with a default when it is absent (J-07, J-08, RI-03); emit `client_id`, `iss`, `jti` and `typ at+jwt` (J-03, J-04); add the token-exchange grant with validation of `subject_token` (X-09, X-12, X-13) and/or an authenticated introspection endpoint (I-01..I-16); advertise both in RFC 8414 metadata (M-02); prefer asymmetric signing with `jwks_uri` (J-02, J-09). Short token lifetimes (MCP-21, X-18, BT-05).
5. **Caching**. Introspection results MUST NOT be cached beyond `exp` (I-15). Exchanged tokens are themselves bearer secrets (B-12, MCP-20) and must not be logged. MCP being stateless now (2026-07-28) means any cache is keyed by the token or user, not by session.
6. **Errors**. Inbound 401 with `WWW-Authenticate: Bearer resource_metadata=…` (plus `error="invalid_token"` only when a token was presented, BT-03), and 403 `insufficient_scope` with `scope` (MCP-13). A failed exchange (`invalid_request`/`invalid_target`) or a host 401 is a server-side failure for the MCP client. The specs do not say how to surface it; that is a design choice.

## 4. Sources fetched (2026-11-14)
- https://modelcontextprotocol.io/specification/versioning
- https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization.md
- https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations.md
- https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/authorization-server-discovery.md
- https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices.md
- https://modelcontextprotocol.io/specification/2026-07-28/changelog.md
- https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization.md (for the delta)
- https://github.com/modelcontextprotocol/ext-auth (+ raw `.mdx` of both stable extensions)
- https://www.rfc-editor.org/rfc/rfc8693.html, rfc7662.html, rfc8707.html, rfc9068.html, rfc9728.html, rfc9700.html, rfc6750.html, rfc9449.html, rfc8705.html, rfc7523.html
- https://datatracker.ietf.org/doc/draft-ietf-oauth-v2-1/ and https://www.ietf.org/archive/id/draft-ietf-oauth-v2-1-17.html
