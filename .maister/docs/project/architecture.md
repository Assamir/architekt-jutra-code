# System Architecture

## Overview
**aj** is a microkernel platform: a Spring Boot host provides the core domain (products, categories, users), security and a plugin runtime; features are delivered by independently deployed web plugins rendered in iframes. A separate MCP server exposes the platform to AI agents.

The repository also contains course material (`week7/`–`week10/`, `tools/`) that is not part of the running application.

## Architecture Pattern
**Pattern**: Microkernel with iframe plugins + modular monolith host

- The core knows nothing about specific plugins. A plugin registers itself by uploading a manifest (`PUT /api/plugins/{id}/manifest`, stored as `PluginDescriptor`) that declares its URL and extension points.
- Plugins run as separate web apps (own port), loaded by the host frontend into iframes and talking to the host via postMessage RPC through the browser plugin SDK.
- Inside the host, domain modules are plain Spring packages; `footprint` is a hexagonal module with a public `api` boundary.

## System Structure

### Microkernel / plugin runtime (backend)
- **Location**: `src/main/java/pl/devstyle/aj/core/plugin`
- **Purpose**: plugin registry (manifests), plugin data storage
- **Storage options for plugins**:
  - per-product JSONB under `products.plugin_data -> {pluginId}` (`PluginDataService`)
  - plugin-owned collections in `plugin_objects` keyed by `(pluginId, objectType, objectId)`, optionally bound to a PRODUCT/CATEGORY (`PluginObjectService`)
  - JSONB filtering (`jsonPath:op:value`) in jOOQ `Db*QueryService` classes

### Security
- **Location**: `core/security`, `core/oauth2`
- **Purpose (`core/security`)**: stateless JWT auth (`JwtAuthenticationFilter`, `JwtTokenProvider`). Every JWT carries the fixed issuer `app.oauth2.issuer` and an `aud`; `/api` accepts only tokens whose `aud` contains `${issuer}/api` (login session tokens and exchanged MCP tokens). Session tokens map `permissions`, delegated OAuth tokens map `scopes` to authorities. All authorization rules are centralized in `SecurityConfiguration` on `PERMISSION_*` authorities (READ, EDIT, PLUGIN_MANAGEMENT) plus `mcp:read`/`mcp:edit`.
- **Purpose (`core/oauth2`)**: the host is the OAuth2 authorization server for the MCP server: dynamic client registration, `/oauth2/authorize` (session tokens only, RFC 8707 `resource`, `mcp:edit` ⊃ `mcp:read` intersected with the user's permissions), `/oauth2/token` (authorization code + PKCE, refresh tokens bound to client and resource, RFC 8693 token exchange for the `aj-mcp-server` client only), RFC 7662 `/oauth2/introspect` (client authentication via `ClientAuthenticator`, Basic or form post) and `.well-known` metadata advertising the introspection endpoint and the token-exchange grant. `McpClientProvisioner` upserts the `aj-mcp-server` client from `AJ_OAUTH_CLIENT_ID`/`AJ_OAUTH_CLIENT_SECRET` at startup.

### Domain modules
- **Location**: `product`, `category`, `user`, `api`
- **Purpose**: core CRUD domain — JPA for writes, jOOQ for complex reads

### Footprint (hexagonal module)
- **Location**: `footprint/`
- **Structure**: public `api` (`FootprintFacade`), `internal` implementation with `ports`, `spi/stub` in-memory adapters (`app.footprint.adapters=in-memory`), async audit via events + spring-retry, CSV `export`, `web` controllers. Callers depend on `api` only.

### Pricing archetype
- **Location**: `archetype/pricing`
- **Purpose**: pricing domain model (components, calculators, validity)

### Host frontend & plugin runtime (browser)
- **Location**: `src/main/frontend/src`
- `plugins/` — renders extension points: `menu.main`, `product.detail.tabs`, `product.detail.info` as iframes; `product.list.filters` rendered natively from manifest metadata; `PluginMessageHandler` handles postMessage RPC
- `plugin-sdk/` — browser SDK served as `/assets/plugin-sdk.js` (separate IIFE bundle)
- Shared types: `plugins/sdk.ts`; server-side SDK for plugin backends forwarding the user's JWT: `plugins/server-sdk.ts`
- Plugin conventions (manifest, SDK, `plugin-ui.css` `tc-*` styling): `plugins/CLAUDE.md`

### Plugins
- **Location**: `plugins/<name>` — `warehouse` (:3001), `box-size` (:3002), `ai-description` (:3003, Next.js + BAML), `shipping` (:3010), `ai-offer-analysis` (:3011, Next.js + BAML)

### MCP server
- **Location**: `mcp-server/` (port 8181, management 9181)
- **Purpose**: exposes products/categories as MCP tools. It is an OAuth2 resource server: it validates bearer tokens by RFC 7662 introspection against the host, requiring `aud` to contain its own URL (`AJ_MCP_BASE_URL`), checks the tool's scope, exchanges the client token via RFC 8693 for a ≤5-min host-API token on every tool call (no cache) and calls the host REST API via `AjApiClient` (`AJ_BACKEND_URL`). The client token is never forwarded. Protected resource metadata is served by Spring Security's `OAuth2ProtectedResourceMetadataFilter`.

## Data Flow

```
Browser ── host React app ──REST+JWT──▶ Spring host ──▶ PostgreSQL
              │  ▲
     iframe   │  │ postMessage RPC (plugin-sdk.js)
              ▼  │
          Plugin UI (:300x) ──(optional) plugin backend ──server-sdk, user JWT──▶ host REST

AI agent ──MCP + T1──▶ mcp-server (:8181) ──introspect T1──▶ host /oauth2/introspect
                              │            ──exchange T1───▶ host /oauth2/token → T2 (aud ${issuer}/api, ≤5 min)
                              └──REST + T2──▶ host /api
```

## External Integrations
- PostgreSQL 18 (application data)
- LiteLLM gateway, Langfuse tracing, Presidio PII anonymization — used by `ai-description` and `ai-offer-analysis`

## Database Schema
- Liquibase changelogs: `src/main/resources/db/changelog/2026/` (categories, products, plugins, plugin_data, plugin_objects + entity binding, users, OAuth2 registered clients, footprint audit log), included from `db.changelog-master.yaml`
- jOOQ generated sources: `target/generated-sources/jooq`

## Configuration
- `src/main/resources/application.properties` (host, port 8180): `APP_OAUTH2_ISSUER` (default `http://localhost:8180`), `APP_JWT_SECRET` (committed dev fallback), `APP_CORS_ALLOWED_ORIGINS` (default `http://localhost:*`), and the values shared with the MCP server: `AJ_MCP_BASE_URL`, `AJ_OAUTH_CLIENT_ID`, `AJ_OAUTH_CLIENT_SECRET`
- `mcp-server/src/main/resources/application.yml` (port 8181, management 9181): `AJ_BACKEND_URL`, `AJ_OAUTH_SERVER_URL` (default `http://localhost:8180`), `AJ_MCP_BASE_URL` (default `http://localhost:8181`), `AJ_OAUTH_CLIENT_ID`, `AJ_OAUTH_CLIENT_SECRET` (required; startup fails without it)
- Plugins: `.env` in `plugins/ai-description`, `plugins/ai-offer-analysis` (optional `HOST_BASE_URL`, default `http://localhost:8180`) and `litellm/`

## Deployment Architecture
- Local only: `compose.yml` for infrastructure, `start-dev.sh`/`start-dev.ps1` for all processes; the scripts export the shared OAuth variables; `AJ_OAUTH_CLIENT_SECRET` comes from the environment, else from the gitignored `.aj-dev.env` (generated once and reused across restarts), and is passed only to the host and MCP processes, never to plugins
- Host serves the built frontend from `src/main/resources/static`
- No CI/CD or production deployment configured

---
*Based on codebase analysis performed 2026-11-07; security, OAuth2 and MCP sections updated 2026-11-14*
