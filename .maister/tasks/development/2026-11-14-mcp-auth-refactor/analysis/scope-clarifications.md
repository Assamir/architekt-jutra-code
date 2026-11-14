# Phase 2 Scope Clarifications

## TL;DR
All 11 gap-analysis decisions resolved with the recommended option. Two critical fixes added to P1: origin-only `resource` URIs compare equal with or without a trailing slash (aud minted canonical, no slash), and only session tokens (T0) may authenticate the user at `/oauth2/authorize`. No scope expansion beyond P1 + Phase 1 clarifications.

## Key Decisions
- C-1 Trailing slash — `X` and `X/` equal for origin-only URIs; `aud` always minted in canonical no-slash form (JS SDK clients send `new URL(resource).href`).
- C-2 Consent principal — only session tokens (API-valid, no `client_id`/`act`) authenticate at `/oauth2/authorize`; otherwise "User must be authenticated"; plus a regression test that T2 posted as `_token` cannot mint a code.
- I-1 MCP inbound — `spring-boot-starter-security-oauth2-resource-server` + explicitly declared `SpringOpaqueTokenIntrospector` bean from `aj.oauth.*`, wrapped by `AudienceValidatingIntrospector`.
- I-2 Missing scope — default to the client's registered scopes, then `ScopePolicy` (hierarchy ∩ permissions).
- I-3 Refresh binding (FU-2a) — confidential clients via `ClientAuthenticator`; public clients must send `client_id` equal to the stored one; validate BEFORE removing the RT; same rule for auth-code `client_id`.
- I-4 Blank secret — provisioner deletes a stale `aj-mcp-server` row with a WARN.
- I-5 Stale T0 — `AuthContext.loadStoredAuth` drops stored tokens without `aud` (forces re-login).
- I-6 Introspection outage — Spring Security 7 default (5xx, never `invalid_token`); verify in first test, fall back to a custom 503 handler if needed.
- I-7 Jackson — migrate touched host OAuth code (Token/Authorization filters, `OAuth2ErrorResponse`) to Jackson 3 via a shared error writer and records; Registration filter only switches to the shared writer. `jjwt-jackson` keeps Jackson 2 on the runtime classpath.
- I-8 Test config — `src/test/resources/config/application.(properties|yml)` with a test secret (loaded in addition to main config, one cached context) for host and MCP; provisioner blank-secret/idempotency paths unit-tested.
- I-9 Port mentions — update the 14 frontend test-fixture lines; leave committed `plugins/*/dist/` and `.playwright-mcp` logs untouched.

## Open Questions / Risks
- First MCP build needs network to fetch the resource-server starter (not in local `.m2`).
- Per-run `APP_JWT_SECRET` in start-dev would force SPA re-login each restart — spec should pick a stable dev default via env.
- jjwt serialises `aud` as an array — checks must be contains-match.
- Users must re-point MCP client config to `http://localhost:8181`.
