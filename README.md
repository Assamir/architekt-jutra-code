# aj — Microkernel Architecture Showcase

A showcase application demonstrating the **microkernel (plugin-based) architecture** pattern built with Java 25 and Spring Boot 4.

The core application acts as a lightweight host that manages plugin lifecycle, shared services (database, API routing), and cross-cutting concerns. Plugins are independently developed modules — each with its own frontend (Vite + TypeScript) — that extend the platform with domain-specific functionality.

## Project Structure

```
├── src/                        # Host application (Spring Boot)
│   └── main/java/pl/devstyle/aj/
│       ├── core/               # Microkernel core (base entity, plugin framework, error handling)
│       ├── api/                # REST controllers
│       ├── product/            # Domain: product management
│       └── category/           # Domain: categories
├── plugins/                    # Independent plugin modules
│   ├── warehouse/              # Warehouse management plugin (Vite + TS)
│   ├── box-size/               # Box size calculator plugin (Vite + TS)
│   ├── shipping/               # Shipping carriers and per-product restrictions (Vite + TS, :3010)
│   └── ai-offer-analysis/      # AI review of description, category fit and price (Next.js + BAML via LiteLLM, :3011)
├── mcp-server/                 # MCP server exposing products/categories to AI agents (:8181)
├── compose.yml                 # Docker Compose (PostgreSQL 18)
├── start-dev.sh                # Single script to run everything locally
└── .maister/                   # AI-assisted development artifacts
```

Plugin dev ports: existing plugins use 3001–3003, 3004–3009 are for small demo and utility plugins, and 3010+ is for business modules (shipping 3010, ai-offer-analysis 3011, next business module 3012).

## Built with AI (Skillpanel / Maister Plugin)

This project was created using the **[Maister](https://github.com/SkillPanel/maister)** plugin for Claude Code — an AI SDLC framework that provides structured workflows for research, product design, and development.

All workflow artifacts are committed in `.maister/tasks/` so you can review the full AI-assisted development process:

- **`.maister/tasks/research/`** — Research phases
- **`.maister/tasks/product-design/`** — Product design sessions and briefs
- **`.maister/tasks/development/`** — Implementation task logs and work reports

The coding standards and conventions discovered/defined during the project live in `.maister/docs/`.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 25 |
| Framework | Spring Boot 4.0.5 (WebMVC, JPA, jOOQ, Liquibase) |
| Database | PostgreSQL 18 |
| Plugins | Vite + TypeScript (each plugin is a standalone frontend app) |
| Build | Maven (with wrapper) |
| Testing | JUnit 5, TestContainers (real PostgreSQL) |

## Running Locally

### Prerequisites

- Java 25+
- Docker (for PostgreSQL)
- Node.js (for plugins)

### Environment Setup

Some services require environment variables. Copy the example files and fill in your API keys:

```bash
cp litellm/.env.example litellm/.env
cp plugins/ai-description/.env.example plugins/ai-description/.env
cp plugins/ai-offer-analysis/.env.example plugins/ai-offer-analysis/.env
```

Edit each `.env` file and replace placeholder values with your actual API keys. Provider keys go only in `litellm/.env`; the plugins hold just the LiteLLM proxy key. `gpt-4o-mini` (used by ai-description and ai-offer-analysis) goes via OpenRouter (`openrouter/openai/gpt-4o-mini` in `litellm/config.yaml`) and reads its key from `OPENAI_API_KEY` (set it to your OpenRouter key); to call OpenAI directly, put an OpenAI key there and switch the model to `openai/gpt-4o-mini`. Plugins always call the logical model name `gpt-4o-mini`. The service URLs (Langfuse, Presidio) are pre-configured for Docker and typically don't need changes.

### Quick Start

```bash
# Install plugin dependencies
(cd plugins/warehouse && npm install)
(cd plugins/box-size && npm install)
(cd plugins/shipping && npm install)
(cd plugins/ai-offer-analysis && npm install)

# Start everything (PostgreSQL + Spring Boot + all plugins)
./start-dev.sh
```

The `start-dev.sh` script (or `start-dev.ps1` on Windows) will:
1. Start PostgreSQL via Docker Compose (or reuse if already running)
2. Export the OAuth settings shared by the host and the MCP server: `APP_OAUTH2_ISSUER`, `AJ_OAUTH_SERVER_URL`, `AJ_BACKEND_URL`, `AJ_MCP_BASE_URL` and `AJ_OAUTH_CLIENT_ID` (default `aj-mcp-server`). `AJ_OAUTH_CLIENT_SECRET` is not exported: it is resolved from the environment (an inherited value wins), else from the gitignored `.aj-dev.env` in the repo root, and only when that file has none is it generated once and written there (never printed). It is then passed only to the host and the MCP server processes, never to plugin processes. `APP_JWT_SECRET` is inherited when set and never generated.
3. Start the Spring Boot host application on http://localhost:8180
4. Start the MCP server on http://localhost:8181 (management port 9181)
5. Start all plugins that have a `package.json`

Press `Ctrl+C` to stop everything.

Point MCP clients at `http://localhost:8181`. They log in through the host's OAuth2 authorization server; the MCP server validates their tokens by introspection and exchanges them for short-lived host-API tokens, so the client token never reaches the host API.

### Manual Start

If you prefer to start services individually:

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Export one shared client secret in every shell that starts the host or the MCP server
#    (reuse the one start-dev stored in .aj-dev.env, if any, so both apps keep matching)
export AJ_OAUTH_CLIENT_SECRET=<value of AJ_OAUTH_CLIENT_SECRET in .aj-dev.env, or any random value>

# 3. Start the host app (http://localhost:8180)
./mvnw spring-boot:run

# 4. Start the MCP server (http://localhost:8181, in a separate terminal)
cd mcp-server && ./mvnw spring-boot:run

# 5. Start a plugin (in a separate terminal)
cd plugins/warehouse && npm run dev
```

Both apps need the same `AJ_OAUTH_CLIENT_SECRET`: the host registers the `aj-mcp-server` client with it at startup, and the MCP server authenticates with it for introspection and token exchange. A mismatch fails closed: the host rejects the MCP server with `401 invalid_client`, which the MCP client sees as a 5xx when introspection fails and as a tool error when token exchange fails (every MCP request is rejected). The MCP server refuses to start when the secret is not set.

`.aj-dev.env` (repo root, gitignored, `KEY=VALUE` lines) is where `start-dev` keeps the generated `AJ_OAUTH_CLIENT_SECRET`, so you can stop and restart the host or the MCP server on its own with the same secret. Delete the file to rotate the secret; the next `start-dev` run generates a new one (restart both apps afterwards).

#### Troubleshooting

The OAuth2 endpoints (`/oauth2/*`) log an unexpected `server_error` at ERROR with only `error_type`; the stack trace is logged at DEBUG, which is off by default. To see it, start the host with `logging.level.pl.devstyle.aj.core.oauth2=DEBUG` (for example `LOGGING_LEVEL_PL_DEVSTYLE_AJ_CORE_OAUTH2=DEBUG ./mvnw spring-boot:run`). Keep it off otherwise: exception messages can contain request-derived text.

A host/MCP secret mismatch shows in the host log as rejected introspection or token-exchange calls with `client_id=aj-mcp-server | error=invalid_client` (the claimed client id is logged even when authentication fails; the secret never is).
