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
- **Purpose**: stateless JWT auth (`JwtAuthenticationFilter`, `JwtTokenProvider`); all authorization rules centralized in `SecurityConfiguration` on `PERMISSION_*` authorities (READ, EDIT, PLUGIN_MANAGEMENT) plus `mcp:read`/`mcp:edit`. The host is also an OAuth2 authorization server (dynamic client registration, token, introspection, `.well-known` metadata) used by the MCP server.

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
- **Location**: `plugins/<name>` — `warehouse` (:3001), `box-size` (:3002), `ai-description` (:3003, Next.js + BAML)

### MCP server
- **Location**: `mcp-server/` (port 8081)
- **Purpose**: exposes products/categories as MCP tools; validates bearer tokens (JWT or introspection against the host AS), exchanges them and calls the host REST API via `AjApiClient` (`AJ_BACKEND_URL`)

## Data Flow

```
Browser ── host React app ──REST+JWT──▶ Spring host ──▶ PostgreSQL
              │  ▲
     iframe   │  │ postMessage RPC (plugin-sdk.js)
              ▼  │
          Plugin UI (:300x) ──(optional) plugin backend ──server-sdk, user JWT──▶ host REST

AI agent ──MCP──▶ mcp-server (:8081) ──token exchange (OAuth2 AS on host)──▶ host REST
```

## External Integrations
- PostgreSQL 18 (application data)
- LiteLLM gateway, Langfuse tracing, Presidio PII anonymization — used by `ai-description`

## Database Schema
- Liquibase changelogs: `src/main/resources/db/changelog/2026/` (categories, products, plugins, plugin_data, plugin_objects + entity binding, users, OAuth2 registered clients, footprint audit log), included from `db.changelog-master.yaml`
- jOOQ generated sources: `target/generated-sources/jooq`

## Configuration
- `src/main/resources/application.properties`
- Environment variables for MCP server (`AJ_BACKEND_URL`) and plugins (`.env` in `plugins/ai-description`, `litellm/`)

## Deployment Architecture
- Local only: `compose.yml` for infrastructure, `start-dev.sh`/`start-dev.ps1` for all processes
- Host serves the built frontend from `src/main/resources/static`
- No CI/CD or production deployment configured

---
*Based on codebase analysis performed 2026-11-07*
