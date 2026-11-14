window.MAISTER_DATA = {
  "generated": "2026-11-14T15:40:47Z",
  "task": {
    "title": "MCP auth refactor - replace token passthrough",
    "type": "research",
    "status": "completed",
    "description": "Research a standards-compliant replacement for the MCP server's token passthrough to the aj host backend (module 4 exercise).",
    "path": ".maister/tasks/research/2026-11-14-mcp-auth-refactor",
    "current_activity": null
  },
  "characteristics": {
    "research_type": "mixed",
    "confidence_level": "high"
  },
  "phases": [
    {
      "id": "phase-1",
      "name": "Research foundation (init, plan, gather, synthesize)",
      "icon_hint": "analysis",
      "status": "completed",
      "started": "2026-11-14T14:23:16Z",
      "completed": "2026-11-14T14:58:17Z",
      "skip_reason": null,
      "summary": "Recommend RFC 8693 token exchange (delegation) at the hand-written host AS, RFC 7662 introspection plus aud check at the MCP server, and aud binding enforced at host /api; no SAS/Keycloak migration; confidence high.",
      "decisions": [
        {
          "decision": "Outbound: RFC 8693 token exchange, delegation flavour",
          "rationale": "This is the only option that keeps the user's identity and permissions downstream and adds the MCP server as a distinguishable actor. The course (M04L07), the team standard and every vendor pattern found (Auth0, Okta, Entra OBO, Keycloak, SAS) use it."
        },
        {
          "decision": "Inbound: RFC 7662 introspection with no caching, plus an explicit `aud` check at the MCP server",
          "rationale": "It needs no shared HMAC secret and no JWKS migration, revocation is immediate, and the local hop costs little. Local JWT validation with RS256 + JWKS stays the upgrade path."
        },
        {
          "decision": "Audience binding on both resource servers",
          "rationale": "This is mandatory under every option, not an alternative. Without a host-side `aud` check, an MCP client token could still be replayed directly at `/api`."
        },
        {
          "decision": "Extend the custom AS; no SAS or Keycloak migration in this task",
          "rationale": "SAS token exchange only accepts subject tokens that SAS itself issued and stored, so it cannot be added piecemeal."
        },
        {
          "decision": "Ship the scope ∩ permission intersection with the exchange grant",
          "rationale": "Otherwise the existing `viewer`→`mcp:edit` escalation turns into backend write tokens minted for a trusted client."
        },
        {
          "decision": "Client credentials + propagated user header (A4) is rejected",
          "rationale": "It recreates the confused deputy."
        }
      ],
      "risks": [
        "**Design decisions still open**: the canonical `aud` values, the default audience when `resource` is omitted, how tool handlers receive the validated principal (threading), a hand-written `TokenExchangeClient` vs Spring's `TokenExchangeOAuth2AuthorizedClientProvider`, whether `mcp:edit` implies `mcp:read`, and the confidential-client seeding mechanism. See §10.",
        "**Spring defaults are unsafe here** (Medium-High, not executed). The opaque-token principal is not an `OAuth2Token`. The default `OAuth2ClientHttpRequestInterceptor` reads `SecurityContextHolder`, which is empty on the Reactor `boundedElastic` threads where tools run. The default authorized-client cache is keyed by principal name, so scope leaks between MCP clients of the same user.",
        "**Three pre-existing AS-wide MUST violations remain** unless follow-ups are bundled: tokens and secrets logged at INFO (RFC 9700 §4.9.3), refresh tokens not bound to a client (RFC 9700 §4.14 / OAuth 2.1 §3.2.3), and bearer tokens accepted in the `_token` query parameter (OAuth 2.1 §5.1). Open DCR also stays (allowed as MAY under 2026-07-28, where CIMD is SHOULD).",
        "**The PRM `resource` currently defaults to an external host** (`kuba-mcp.labs-skillpanel.com`). Once `aud` is enforced, a local run fails unless that default (or the `start-dev` scripts) points at the local MCP URI."
      ],
      "artifacts": [
        {
          "path": "planning/research-brief.md",
          "label": "Research brief",
          "html": null
        },
        {
          "path": "planning/research-plan.md",
          "label": "Research plan",
          "html": null
        },
        {
          "path": "planning/sources.md",
          "label": "Sources",
          "html": null
        },
        {
          "path": "analysis/synthesis.md",
          "label": "Synthesis",
          "html": null
        },
        {
          "path": "outputs/research-report.md",
          "label": "Research report",
          "html": "outputs/research-report.html"
        }
      ],
      "gate": {
        "question": "Continue to brainstorming evaluation?",
        "answer": "Tak, kontynuuj"
      },
      "progress": null
    },
    {
      "id": "phase-2",
      "name": "Evaluate brainstorming value",
      "icon_hint": "plan",
      "status": "completed",
      "started": "2026-11-14T14:58:17Z",
      "completed": "2026-11-14T14:59:38Z",
      "skip_reason": null,
      "summary": null,
      "decisions": [],
      "risks": [],
      "artifacts": [],
      "gate": {
        "question": "Brainstorming / design?",
        "answer": "Both enabled"
      },
      "progress": null
    },
    {
      "id": "phase-3",
      "name": "Generate solution alternatives",
      "icon_hint": "spec",
      "status": "completed",
      "started": "2026-11-14T14:59:38Z",
      "completed": "2026-11-14T15:07:25Z",
      "skip_reason": null,
      "summary": "8 decision areas (29 alternatives) built from D-1..D-13; recommended package P1 course-aligned secure minimum (1A+2B+3A+4A+5A+6A+7A+8B).",
      "decisions": [
        {
          "decision": "DA-1 Audience model",
          "rationale": "**URI audiences, lenient default.** MCP `aud` = `aj.mcp.base-url` (local default `http://localhost:8081`). Host API `aud` = `${issuer}/api`. A missing `resource` defaults to the MCP URI with a warning. This satisfies P-05/J-08/J-16 without breaking clients that omit `resource`."
        },
        {
          "decision": "DA-2 Host enforcement",
          "rationale": "**audience gate + per-grant resource policy.** `/api` requires `iss` and `aud` = host API. Only the exchange grant can mint the host-API audience. This closes the auth-code→`/api` shortcut without a matcher change."
        },
        {
          "decision": "DA-3 Scope model",
          "rationale": "**hierarchical `mcp:*`, normalised at issuance, kept in T2, intersected at both grants.** MCP-14 is met with no host matcher change. T2 stays distinguishable from T0, and the `viewer`→`mcp:edit` escalation (F6) is fixed in both grants."
        },
        {
          "decision": "DA-4 Outbound + principal",
          "rationale": "**hand-written `TokenExchangeClient` + validated `Authentication` in `McpTransportContext`.** One small class with no hidden Spring defaults. It respects the SDK's `boundedElastic` model and matches the course name (L07)."
        },
        {
          "decision": "DA-5 Validation, caching, keys",
          "rationale": "**introspection without cache, exchange without cache, HS256 kept.** This gives immediate revocation, means the MCP server never holds a key, and is what the course and team standard prescribe. JWKS stays the upgrade path (FU-6)."
        },
        {
          "decision": "DA-6 Delegation",
          "rationale": "**implicit `act: {sub: <mcp client_id>}` added by the AS.** It costs nothing extra (I7) and is auditable (X-15)."
        },
        {
          "decision": "DA-7 Client auth + seeding",
          "rationale": "**`client_secret_basic` + `client_secret_post` through one `ClientAuthenticator`, client upserted at startup from `AJ_OAUTH_CLIENT_ID/SECRET`.** No committed hash, works in every Liquibase context, one client registry."
        },
        {
          "decision": "DA-8 Bundled follow-ups",
          "rationale": "**\"same-touch hygiene\"**: FU-3 (token logging, same file as the exchange branch), FU-5 (local default URLs and env wiring, needed for `aud`), FU-2a (refresh-token client binding only, same struct that gains `resource`)."
        }
      ],
      "risks": [
        "**What Claude actually sends as `resource`** is unverified (Appendix B of the report). If it always sends it, DA-1 could go strict. If it sends a value with a trailing slash or a path, exact-match comparison breaks. Normalisation rules need a test.",
        "**DA-2 gap (new inference, Medium-High).** It applies only if the resource registry (H2) accepts the host-API URI on `/oauth2/authorize`. Verify against the final H2 design.",
        "**Scope normalisation (DA-3) changes the token response's `scope` field** (a client asking for `mcp:edit` receives `mcp:read mcp:edit`). This is allowed by OAuth 2.1 (the AS may issue a different scope and must then return it), but has not been checked against Claude's client behaviour.",
        "**The principal hand-off in DA-4 relies on the MCP SDK's `contextExtractor` running on the servlet thread after the security filter chain** (I5, High on the SDK bytecode, untested end-to-end).",
        "**Two extra local HTTP calls per tool call** (introspect + exchange) are reasoned to be cheap but were not measured.",
        "**The exercise baseline (`bd4de70`) is pinned by tests.** Six MCP test classes assert passthrough and must be rewritten in the same change."
      ],
      "artifacts": [
        {
          "path": "outputs/solution-exploration.md",
          "label": "Solution exploration",
          "html": "outputs/solution-exploration.html"
        }
      ],
      "gate": null,
      "progress": null
    },
    {
      "id": "phase-4",
      "name": "Evaluate brainstorming alternatives",
      "icon_hint": "plan",
      "status": "completed",
      "started": "2026-11-14T15:07:25Z",
      "completed": "2026-11-14T15:25:37Z",
      "skip_reason": null,
      "summary": "User chose the recommended alternative in all 8 areas = package P1 course-aligned secure minimum (1A+2B+3A+4A+5A+6A+7A+8B).",
      "decisions": [
        {
          "decision": "1A URI audiences, lenient default",
          "rationale": "User choice in DA-1 Audience model"
        },
        {
          "decision": "2B audience gate + per-grant resource policy",
          "rationale": "User choice in DA-2 Host-side enforcement"
        },
        {
          "decision": "3A hierarchical mcp:* normalised at issuance, kept in T2, intersected at both grants",
          "rationale": "User choice in DA-3 Scope model"
        },
        {
          "decision": "4A hand-written TokenExchangeClient + Authentication in McpTransportContext",
          "rationale": "User choice in DA-4 Outbound client + principal"
        },
        {
          "decision": "5A introspection + exchange without cache, HS256 kept",
          "rationale": "User choice in DA-5 Validation, caching, keys"
        },
        {
          "decision": "6A implicit act {sub: mcp client_id}",
          "rationale": "User choice in DA-6 Delegation semantics"
        },
        {
          "decision": "7A ClientAuthenticator (Basic+post) + startup upsert from AJ_OAUTH_CLIENT_ID/SECRET",
          "rationale": "User choice in DA-7 Client auth + provisioning"
        },
        {
          "decision": "8B hop + same-touch hygiene (FU-3, FU-5, FU-2a)",
          "rationale": "User choice in DA-8 Bundled follow-ups"
        }
      ],
      "risks": [],
      "artifacts": [],
      "gate": {
        "question": "Continue to high-level design?",
        "answer": "Tak, generuj design (P1)"
      },
      "progress": null
    },
    {
      "id": "phase-5",
      "name": "Design high-level architecture",
      "icon_hint": "spec",
      "status": "completed",
      "started": "2026-11-14T15:26:46Z",
      "completed": "2026-11-14T15:40:47Z",
      "skip_reason": null,
      "summary": "Validate-then-exchange design on the custom host AS: 10 components, 9 MADR ADRs (all accepted), 9 integration points, no schema migration, tool behaviour unchanged.",
      "decisions": [
        {
          "decision": "Validate-then-exchange replaces passthrough. This is the RFC 8693 §1 case and what the course (L07), the team standard and every vendor pattern found use ([ADR-001](decision-log.md#adr-001)).",
          "rationale": ""
        },
        {
          "decision": "URI audiences",
          "rationale": "MCP = `aj.mcp.base-url`, host API = `${issuer}/api`. A missing `resource` defaults to the MCP URI with a warning. An unknown one gets `invalid_target` ([ADR-002](decision-log.md#adr-002))."
        },
        {
          "decision": "`/api` requires `iss` + `aud` = host API. Only the exchange grant, called by the client bound to the MCP resource, may mint that audience ([ADR-003](decision-log.md#adr-003)).",
          "rationale": ""
        },
        {
          "decision": "`mcp:edit` ⊃ `mcp:read`, normalised at issuance and kept in T2. Scopes ∩ user permissions at both the auth-code and exchange grants ([ADR-004](decision-log.md#adr-004)).",
          "rationale": ""
        },
        {
          "decision": "A hand-written `TokenExchangeClient`. The validated `Authentication` travels in `McpTransportContext` and T2 is passed explicitly to `AjApiClient`. The `AccessTokenHolder` ThreadLocal is deleted ([ADR-005](decision-log.md#adr-005)).",
          "rationale": ""
        },
        {
          "decision": "Introspection (`/oauth2/introspect`, caller auth required) with no cache, an exchange per tool call with no cache, and HS256 kept ([ADR-006](decision-log.md#adr-006)).",
          "rationale": ""
        },
        {
          "decision": "Implicit delegation",
          "rationale": "the AS adds `act: {sub: <mcp client_id>}` ([ADR-007](decision-log.md#adr-007))."
        },
        {
          "decision": "One `ClientAuthenticator` (Basic + post, BCrypt). The `aj-mcp-server` confidential client is upserted at startup from env ([ADR-008](decision-log.md#adr-008)).",
          "rationale": ""
        },
        {
          "decision": "Same-touch hygiene is bundled in",
          "rationale": "no token logging (FU-3), local default URLs and env wiring (FU-5), refresh tokens bound to their client (FU-2a) ([ADR-009](decision-log.md#adr-009))."
        }
      ],
      "risks": [
        "**What `resource` value Claude sends is unverified.** Exact string match is used, with no URI normalisation (a stretch item). A trailing slash would cause `invalid_target`. The test plan covers the omitted and exact cases. A manual check with a real client is part of rollout.",
        "**The `contextExtractor` must run on the servlet thread after the security filter chain** (ADR-005, assumption I5: High confidence from bytecode, but untested end to end). The first implementation task should be a spike test proving this. Fallback: `immediateExecution(true)`.",
        "**MCP-13 (403 `insufficient_scope`, SHOULD) is only partly met.** HTTP 403 is returned when a valid token carries no `mcp:*` scope at all. A per-tool shortfall (an `mcp:read` token calling `aj_add_product`) is reported as a tool error, because all tools share one JSON-RPC endpoint. Inspecting the JSON-RPC body to emit HTTP 403 is deferred.",
        "**The issued `scope` can be wider than requested** (`mcp:edit` → `mcp:read mcp:edit`). OAuth 2.1 allows this, but Claude's handling of it has not been checked.",
        "**Two extra local HTTP calls per tool call** (introspect + exchange). Unmeasured, reasoned to be cheap.",
        "**Existing sessions are invalidated at rollout.** T0s and T1s issued before the change have no `aud`, so users log in again and Claude re-authorises. This is expected and documented in Migration.",
        "**The committed `APP_JWT_SECRET` default stays in place** as a fallback for tests and ad-hoc runs. `start-dev` overrides it. Removing the default is outside P1."
      ],
      "artifacts": [
        {
          "path": "outputs/high-level-design.md",
          "label": "High-level design",
          "html": "outputs/high-level-design.html"
        },
        {
          "path": "outputs/decision-log.md",
          "label": "Decision log (ADRs)",
          "html": "outputs/decision-log.html"
        }
      ],
      "gate": {
        "question": "Continue to summary?",
        "answer": "Tak, podsumuj"
      },
      "progress": null
    },
    {
      "id": "phase-6",
      "name": "Summarize research and suggest next steps",
      "icon_hint": "done",
      "status": "completed",
      "started": "2026-11-14T15:40:47Z",
      "completed": "2026-11-14T15:40:47Z",
      "skip_reason": null,
      "summary": "Research completed; outputs handed to /maister:development (embedded run).",
      "decisions": [],
      "risks": [],
      "artifacts": [],
      "gate": null,
      "progress": null
    }
  ],
  "verification": null
};
