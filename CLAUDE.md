# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

**aj** — a microkernel (plugin-based) showcase: a Spring Boot 4 / Java 25 host app with a React frontend, iframe-based plugins, and a separate MCP server. The repo doubles as course material: `week7/`–`week10/` and `tools/` hold standalone demos and course exercises (knowledge-graph demos, a .NET port in `week9/AJ-dotnet`, etc.) that are not part of the running application — leave them alone unless the task targets them.

## Commands

Prerequisites: JDK 25, Docker (Postgres, TestContainers, and jOOQ codegen all need it), Node.js.

```bash
# Full local stack: docker compose services + host app + MCP server + every plugin with a package.json
./start-dev.sh                # bash
./start-dev.ps1               # Windows PowerShell

# Host app only (needs Postgres from `docker compose up -d`; spring-boot-docker-compose can also start it)
./mvnw spring-boot:run        # http://localhost:8180

# Backend tests (integration tests use TestContainers with real PostgreSQL 18)
./mvnw test
./mvnw test -Dtest=ProductIntegrationTests                  # single class
./mvnw test -Dtest=ProductIntegrationTests#createProduct_*  # single method / pattern
```

Every Maven build (including `test`) runs two expensive steps in `generate-sources`/`generate-resources`:
1. **jOOQ codegen** — spins up a `postgres:18` container, applies the Liquibase changelog, generates `pl.devstyle.aj.jooq` into `target/generated-sources/jooq`. Re-run after changing migrations. Skip with `-Dskip.jooq.generation=true` once sources exist.
2. **Frontend build** — `frontend-maven-plugin` installs Node 22 locally and runs `npm install && npm run build` in `src/main/frontend`, writing into `src/main/resources/static`. Skip with `-Dskip.npm -Dskip.installnodenpm`.

Host frontend (`src/main/frontend`, React 19 + Chakra UI v3 + Vite):
```bash
npm run dev          # Vite dev server, proxies /api to :8180
npm run build        # app + plugin SDK bundle (vite.sdk.config.ts → static/assets/plugin-sdk.js)
npm run lint
npm test             # vitest run; single file: npx vitest run src/test/pages.test.tsx
```

Plugins (`plugins/<name>`): `npm run dev`. `warehouse` (:3001), `box-size` (:3002) and `shipping` (:3010) are Vite apps; `ai-description` (:3003) is Next.js + BAML (`npm run generate` regenerates `baml_client`; tests via Jest) and needs `plugins/ai-description/.env` plus `litellm/.env` (copy from `.env.example`). `ai-offer-analysis` (:3011) is also Next.js + BAML (tests via Jest, `npm test`) and needs `plugins/ai-offer-analysis/.env` plus `litellm/.env`; see `plugins/ai-offer-analysis/README.md` for registration and the LiteLLM verification procedure.

MCP server (`mcp-server/`, own Maven wrapper, port 8181, management 9181): `cd mcp-server && ./mvnw spring-boot:run` / `./mvnw test`. It refuses to start without `AJ_OAUTH_CLIENT_SECRET`.

`start-dev` wires the OAuth settings both apps share: it exports `APP_OAUTH2_ISSUER`, `AJ_OAUTH_SERVER_URL`, `AJ_BACKEND_URL`, `AJ_MCP_BASE_URL` and `AJ_OAUTH_CLIENT_ID`, takes `AJ_OAUTH_CLIENT_SECRET` from the environment, else from the gitignored `.aj-dev.env` in the repo root, else generates it once and stores it there (never printed), passes it only to the host and MCP processes (not to plugins), and inherits `APP_JWT_SECRET` (never generates it). When you start the host and the MCP server by hand, export the same `AJ_OAUTH_CLIENT_SECRET` in both shells (reuse the value from `.aj-dev.env`): the host provisions the `aj-mcp-server` client from it at startup, and a mismatch fails closed — the host answers `401 invalid_client`, which the MCP client sees as a 5xx (introspection) or a tool error (token exchange).

## Architecture

### Host backend (`src/main/java/pl/devstyle/aj`)
- `core/plugin` — the microkernel. Plugins are registered by uploading a manifest (`PUT /api/plugins/{id}/manifest`, stored as `PluginDescriptor`). Plugins persist data two ways: per-product JSONB under `products.plugin_data -> {pluginId}` (`PluginDataService`), and plugin-owned collections in `plugin_objects` keyed by `(pluginId, objectType, objectId)` with optional entity binding to PRODUCT/CATEGORY (`PluginObjectService`). JSONB filtering (`jsonPath:op:value`) is done in jOOQ query services (`Db*QueryService`).
- `core/security` — stateless JWT auth (`JwtAuthenticationFilter`, `JwtTokenProvider`). Every host JWT carries the fixed issuer `app.oauth2.issuer` (`APP_OAUTH2_ISSUER`, default `http://localhost:8180`) and an `aud`; `/api` accepts only tokens whose `aud` contains `${issuer}/api` (login session tokens, and exchanged tokens minted for the MCP server). Session tokens map their `permissions` claim to authorities, delegated OAuth tokens their `scopes`. **All authorization rules live in `SecurityConfiguration`** as `requestMatchers` on `PERMISSION_*` authorities (READ, EDIT, PLUGIN_MANAGEMENT, plus `mcp:read`/`mcp:edit` for exchanged MCP tokens) — add new endpoints there, not with `@PreAuthorize`. CORS origins come from `APP_CORS_ALLOWED_ORIGINS` (default `http://localhost:*`).
- `core/oauth2` — the host is also an OAuth2 authorization server used by the MCP server: dynamic client registration, `/oauth2/authorize` (session tokens only, RFC 8707 `resource`, a missing resource means the MCP server), `/oauth2/token` (authorization code + PKCE, refresh tokens bound to client and resource, and RFC 8693 token exchange only for the `aj-mcp-server` client), RFC 7662 `/oauth2/introspect` (client authentication required, `active` only for tokens whose `aud` contains the caller's resource) and `.well-known` metadata built from the configured issuer. Granted scopes are `mcp:read`/`mcp:edit`, with `mcp:edit` implying `mcp:read`, intersected with the user's permissions. `McpClientProvisioner` upserts the `aj-mcp-server` client from `AJ_OAUTH_CLIENT_ID`/`AJ_OAUTH_CLIENT_SECRET` at startup (a blank secret removes the stale row and logs a WARN). Tokens, codes and secrets are never logged. Unexpected OAuth errors are logged at ERROR with `error_type` only; to get their stack traces set `logging.level.pl.devstyle.aj.core.oauth2=DEBUG` (env `LOGGING_LEVEL_PL_DEVSTYLE_AJ_CORE_OAUTH2=DEBUG`).
- `product`, `category`, `user`, `api` — domain modules: JPA for CRUD, jOOQ for complex reads.
- `footprint` — a self-contained hexagonal module: public `api` (`FootprintFacade`), `internal` implementation with `ports`, `spi/stub` in-memory adapters activated by `app.footprint.adapters=in-memory`, async audit via events + spring-retry, CSV export. Keep callers on `api` only.
- `archetype/pricing` — pricing archetype domain model (components, calculators, validity).
- Migrations: Liquibase YAML in `src/main/resources/db/changelog/2026/NNN-*.yaml`, included from `db.changelog-master.yaml`; context `dev` loads sample data.

### Host frontend and plugin runtime
- `src/main/frontend/src/plugins` renders plugin extension points (`menu.main`, `product.detail.tabs`, `product.detail.info` as iframes; `product.list.filters` rendered natively by the host from manifest metadata) and handles postMessage RPC from iframes (`PluginMessageHandler`).
- `src/main/frontend/src/plugin-sdk` is the browser SDK that plugins load from `/assets/plugin-sdk.js`; it is built as a separate IIFE bundle. `plugins/sdk.ts` holds the shared TypeScript types plugins import; `plugins/server-sdk.ts` is the HTTP equivalent for plugin backends (forwards the user's JWT).
- **Read `plugins/CLAUDE.md` before touching plugins** — it defines manifest format, extension points, SDK API, storage patterns, and the mandatory `plugin-ui.css` (`tc-*` classes) styling rules. Note: manifest upload requires a token with `PERMISSION_PLUGIN_MANAGEMENT`.

### MCP server (`mcp-server/`)
Separate Spring Boot app (OAuth2 resource server) exposing aj products/categories as MCP tools. It validates bearer tokens via RFC 7662 introspection against the host (audience = its own URL, `AJ_MCP_BASE_URL`), exchanges them via RFC 8693 for a ≤5-min host-API token on every tool call, and never forwards the client token; it needs `AJ_OAUTH_CLIENT_ID`/`AJ_OAUTH_CLIENT_SECRET`. It calls the host REST API via `AjApiClient` (`AJ_BACKEND_URL`, default `http://localhost:8180`). Configure MCP clients at `http://localhost:8181`.

### Docker Compose (`compose.yml`)
Besides the app's Postgres, it runs the AI tooling for `ai-description` and `ai-offer-analysis`: LiteLLM (LLM gateway, config in `litellm/`), Langfuse (+ ClickHouse, MinIO, Redis), and Presidio (PII anonymization).

## Testing conventions (summary — full rules in `.maister/docs/standards/testing/`)
- Integration-first: `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`; MockMvc tests must also import `SecurityMockMvcConfiguration` (Spring Boot 4 / Security 7). Use `@WithMockEditUser` / `@WithMockAdminUser` or `@WithMockUser(authorities = ...)`.
- Test classes end in `*Tests`, package-private, same package as production; methods named `action_condition_expectedResult`.
- Frontend tests live in `src/main/frontend/src/test/`, use a per-file `renderWithProviders()` and `vi.mock()` for API modules.

## Coding Standards & Conventions

Read @.maister/docs/INDEX.md before starting any task. It indexes the project's coding standards and conventions:
- Coding standards organized by domain (frontend, backend, testing, etc.)
- Project vision, tech stack, and architecture decisions

Follow standards in `.maister/docs/standards/` when writing code — they represent team decisions. If standards conflict with the task, ask the user.

### Standards Evolution

When you notice recurring patterns, fixes, or conventions during implementation that aren't yet captured in standards — suggest adding them. Examples:
- A bug fix reveals a pattern that should be standardized (e.g., "always validate X before Y")
- PR review feedback identifies a convention the team wants enforced
- The same type of fix is needed across multiple files
- A new library/pattern is adopted that should be documented

When this happens, briefly suggest the standard to the user. If approved, invoke `/maister:standards-update` with the identified pattern.

## Maister Workflows

This project uses the maister plugin for structured development workflows. When any `/maister:*` command is invoked, execute it via the Skill tool immediately — do not skip workflows for "straightforward" tasks. The user chose the workflow intentionally; complexity assessment is the workflow's job.

Workflow artifacts (research, product design, implementation logs) are committed under `.maister/tasks/`.

## Knowledge graph

The project skill `aj-kg-query` (`.claude/skills/`) answers structural questions about the platform (modules, endpoints, plugins, extension points) from a Neo4j knowledge graph via the `neo4j-aj-kb` MCP server — use it when that server is available instead of grepping broadly.
