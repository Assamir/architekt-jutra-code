# Phase 1 Clarifications

## TL;DR
- Issuer becomes a fixed configured value (`app.oauth2.issuer`), not request-derived.
- Ports move off occupied 8080/8081/9081: host **8180**, MCP **8181**, MCP management **9181**; default issuer `http://localhost:8180`, MCP resource `http://localhost:8181`.
- Consent page forwards `resource` via a hidden input; RFC 8707 validation at both `/oauth2/authorize` and `/oauth2/token`.
- `/oauth2/authorize` keeps the JSON error contract, with explicitly typed errors (no message-substring classification).

## Key Decisions
- Fixed issuer `app.oauth2.issuer` (env `APP_OAUTH2_ISSUER`, default `http://localhost:8180`) used for T0 login, OAuth tokens, metadata and `/api` iss/aud checks — per-request issuer would break plugin backends forwarding T0 to the host under another Host header. MCP `aj.oauth.server-url` must equal it byte-for-byte.
- Port change (user: 8080/8081 occupied on the dev machine by other java processes, 9081 too): host `server.port=8180`, MCP `server.port=8181`, MCP `management.server.port=9181`. All references must move: Vite proxy, plugin `HOST_BASE_URL` defaults / server-sdk, MCP `aj.backend.url`/`aj.oauth.server-url`/`aj.mcp.base-url` defaults, `start-dev.sh`/`.ps1`, CORS defaults if any, CLAUDE.md, `.maister/docs/standards/plugins/plugin-development.md` fixed-port table, plugin docs/READMEs, tests asserting ports (MCP `ConfigurationTests`). ~54 references found outside course dirs.
- Consent page (`src/main/frontend/src/pages/OAuth2AuthorizePage.tsx`) adds a hidden `resource` input from the query string (small in-scope frontend change, overrides "frontend out of scope" in the design).
- Authorize errors stay JSON; new errors (e.g. `invalid_target`) mapped via typed errors rather than `determineError` substring matching.

## Open Questions / Risks
- Port change widens the diff beyond auth (plugins, Vite, docs). Course dirs `week7/`–`week10/`, `tools/` must not be touched even if they mention 8080.
- Port 8080/8081 processes on the user's machine are unidentified java processes (possibly an older aj instance); not touched.

## Q&A
1. **Issuer source?** → Fixed `app.oauth2.issuer` (option 1), "but a different port because 8080 is taken — find a nice free one".
2. **Ports?** Checked: 8080, 8081, 9081 listening (java); 8180, 8181, 9181 free → **Host 8180, MCP 8181** (management 9181 follows the same +100 rule).
3. **Where to validate `resource`?** → Hidden input on the consent page; validate at authorize and token.
4. **Authorize error style?** → Keep JSON, typed errors.
