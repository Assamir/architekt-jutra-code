# Technology Stack

## Overview
Technology choices for **aj**, a microkernel (plugin-based) platform: a Spring Boot host application with a React frontend, iframe-based plugins, and a separate MCP server.

## Languages

### Java (25)
- **Usage**: host backend (`src/main/java`) and MCP server (`mcp-server/`)
- **Key features used**: records, pattern matching, Lombok-reduced boilerplate on JPA entities

### TypeScript (~5.9)
- **Usage**: host frontend (`src/main/frontend`), plugin SDK, plugins (`plugins/*`)

## Frameworks

### Backend (host, `pom.xml`)
| Module | Purpose |
|---|---|
| Spring Boot 4.0.5 (`starter-webmvc`) | REST API |
| `starter-data-jpa` | CRUD on entities |
| `starter-jooq` | type-safe complex reads, JSONB filtering (`Db*QueryService`) |
| `starter-liquibase` | schema migrations |
| `starter-validation` | request validation |
| `starter-security` + jjwt 0.12.6 | stateless JWT auth, permission-based authorization |
| `spring-security-oauth2-authorization-server` | host acts as OAuth2 AS for the MCP server |
| `spring-boot-docker-compose` | auto-starts compose services in dev |
| spring-retry 2.0.12 + `spring-aspects` | retrying async audit in `footprint` |
| commons-csv 1.10.0 | CSV export in `footprint` |
| micrometer-core | metrics |

### Backend (MCP server, `mcp-server/pom.xml`)
- Spring Boot 4.0.5 (`starter-web`, `starter-security`, `starter-actuator`)
- `mcp-spring-webmvc` 0.18.1 (MCP Java SDK)
- `json-schema-validator` 2.0.0

### Frontend (host)
- React 19.2, React Router 7, Chakra UI v3, lucide-react icons
- Vite 8 (app build + separate IIFE build of the plugin SDK via `vite.sdk.config.ts`)

### Plugins
| Plugin | Stack | Dev port |
|---|---|---|
| `warehouse` | React 19 + Vite 8 | 3001 |
| `box-size` | React 19 + Vite 8 | 3002 |
| `ai-description` | Next.js 15 + BAML 0.220 (LLM via LiteLLM) | 3003 |
| `shipping` | React 19 + Vite 8 | 3010 |
| `ai-offer-analysis` | Next.js 15 + BAML 0.220 (LLM via LiteLLM) | 3011 |

Port rule: existing plugins use 3001–3003, 3004–3009 are for small demo and utility plugins, and 3010+ is for business modules (shipping 3010, ai-offer-analysis 3011, next business module 3012).

### Testing
- **Backend**: JUnit 5, Spring Boot test starters (JPA/jOOQ/Liquibase/WebMVC), `spring-security-test`, TestContainers (PostgreSQL 18)
- **Host frontend**: Vitest 3, @testing-library/react, jest-dom, jsdom
- **ai-description and ai-offer-analysis plugins**: Jest 29

## Database

### PostgreSQL 18
- **Type**: relational, with JSONB for plugin data (`products.plugin_data`, `plugin_objects`)
- **Access**: JPA for CRUD, jOOQ for complex reads; jOOQ classes generated into `pl.devstyle.aj.jooq` by `testcontainers-jooq-codegen-maven-plugin` against a throwaway `postgres:18` container
- **Migrations**: Liquibase YAML in `src/main/resources/db/changelog/2026/NNN-*.yaml` (context `dev` loads sample data)

## Build Tools & Package Management
- **Maven** with wrapper (`mvnw`), separate wrapper in `mcp-server/`
- **frontend-maven-plugin** 1.15.1 installs Node v22.14.0 and builds the frontend into `src/main/resources/static` during the Maven build
- **npm** for frontend and plugins

## Infrastructure

### Containerization (`compose.yml`)
- `postgres:18` — application database
- AI tooling for `ai-description` and `ai-offer-analysis`: LiteLLM (+ its Postgres), Langfuse + worker (+ Postgres, ClickHouse, MinIO via `pgsty/minio`, Redis 7), Presidio analyzer/anonymizer

### Local orchestration
- `start-dev.sh` / `start-dev.ps1` start compose services, host app, MCP server and every plugin with a `package.json`

### CI/CD
- Not configured (no `.github/workflows`)

## Development Tools
- **Linting**: ESLint 9 + typescript-eslint, react-hooks and react-refresh plugins (frontend); no Java linter/formatter configured
- **Type checking**: `tsc -b` in the frontend build; Java compiler

## Version Management
- Spring Boot BOM manages Java dependency versions; explicit versions only for jjwt, spring-retry, commons-csv, MCP SDK
- Node version pinned for Maven builds via frontend-maven-plugin

---
*Last Updated*: 2026-11-07
*Auto-detected*: from `pom.xml`, `mcp-server/pom.xml`, `package.json` files, `compose.yml`, Vite configs
