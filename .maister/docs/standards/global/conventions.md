## Development Conventions

### Predictable Structure
Organize files and directories in a logical, navigable layout.

### Up-to-Date Documentation
Keep README files current with setup steps, architecture overview, and contribution guidelines.

### Clean Version Control
Write clear commit messages, use feature branches, and add meaningful descriptions to pull requests.

### Environment Variables
Store configuration in environment variables; never commit secrets or API keys.

### Minimal Dependencies
Keep dependencies lean and up-to-date; document why major ones are included.

### Consistent Reviews
Follow a defined code review process with clear expectations for reviewers and authors.

### Testing Standards
Define required test coverage (unit, integration, etc.) before merging.

### Feature Flags
Use flags for incomplete features instead of long-lived branches.

### Changelog Updates
Maintain a changelog or release notes for significant changes.

### Build What's Needed
Avoid speculative code and "just in case" additions (see minimal-implementation.md).

### Leave Course-Material Directories Alone
`week7/`–`week10/` and `tools/` hold standalone course demos and exercises (e.g. `week9/AJ-dotnet`) that are not part of the running application. Do not modify, refactor or copy patterns from them unless the task explicitly targets them.

Source: CLAUDE.md

### Service Env Files Come from .env.example
Each component that needs secrets ships a committed `.env.example` with placeholder/local-dev values and comments; developers copy it to `.env`, which is gitignored (`litellm/.env`, `plugins/ai-description/.env`, `plugins/ai-offer-analysis/.env`). LLM provider keys live only in `litellm/.env` (the LiteLLM gateway); plugins hold only the LiteLLM proxy key (`LITELLM_API_KEY`), never provider keys. Plugins call logical model names (e.g. `gpt-4o-mini`); `litellm/config.yaml` maps them to the upstream provider: `gpt-4o-mini` (shared by ai-description and ai-offer-analysis) goes via OpenRouter (`openrouter/openai/gpt-4o-mini`) and reads `OPENAI_API_KEY`, which holds the OpenRouter key; switching to OpenAI directly means an OpenAI key in that variable and model `openai/gpt-4o-mini`. Docker Compose loads secrets with `env_file`. New services that need secrets follow the same pattern. Note: `plugins/ai-description/.env.example` still mentions `.env.local`; `.env` is the name used by `.gitignore`, README and CLAUDE.md.

```bash
cp litellm/.env.example litellm/.env
cp plugins/ai-description/.env.example plugins/ai-description/.env
cp plugins/ai-offer-analysis/.env.example plugins/ai-offer-analysis/.env
```

Source: README.md, CLAUDE.md, litellm/.env.example, litellm/config.yaml, plugins/ai-description/.env.example, plugins/ai-offer-analysis/.env.example, .gitignore, compose.yml

### Application Property Namespacing and Env-Var Overrides
Custom configuration uses an application-specific prefix with kebab-case keys: host `app.<module>.<setting>` (`app.jwt.*`, `app.cors.*`, `app.footprint.audit.retry.*`), MCP server `aj.<area>.<setting>` (`aj.backend.url`, `aj.oauth.*`). Per-environment values are overridable as `${UPPER_SNAKE_ENV:local-default}` with the env name derived from prefix and key (`APP_JWT_SECRET`, `AJ_BACKEND_URL`), so the app runs locally with zero setup. Durations and sizes carry their unit in the key (`expiration-ms`, `delay-ms`). Keep each module's format (host `application.properties`, mcp-server `application.yml`) and comment non-obvious settings.

```properties
app.footprint.audit.retry.delay-ms=${APP_FOOTPRINT_AUDIT_RETRY_DELAY_MS:200}
```

Source: src/main/resources/application.properties, mcp-server/src/main/resources/application.yml

### Docker Compose Service Conventions
`compose.yml` holds the app's Postgres (host port 5435, db/user/password `aj`) plus the AI tooling. Every service other than the app's Postgres carries the label `org.springframework.boot.ignore: "true"` so spring-boot-docker-compose manages only the app database. Every long-running service defines a healthcheck, and dependencies use `depends_on` with `condition: service_healthy` (or `service_completed_successfully` for one-shot setup containers) — `docker compose up -d --wait` relies on this. Remap host ports to avoid clashes with local installs, persist data in named volumes, and use `postgres:18` everywhere (compose, Testcontainers, jOOQ codegen).

```yaml
labels:
  org.springframework.boot.ignore: "true"
```

Source: compose.yml, src/test/java/pl/devstyle/aj/TestcontainersConfiguration.java, pom.xml, start-dev.sh

### Use the Knowledge Graph Skill for Structural Questions
For structural questions about the platform (modules, endpoints, plugins, extension points, manifests), use the `aj-kg-query` skill against the `neo4j-aj-kb` MCP server when it is available, instead of grepping the codebase broadly.

Source: CLAUDE.md, .claude/skills/aj-kg-query

### Maven Wrapper and Line-Ending Rules
Always build with the committed Maven wrapper (`./mvnw`, `mvnw.cmd`), never a system `mvn`. Both Maven projects pin the same wrapper (3.3.4, only-script) and Maven 3.9.14 distribution; the wrapper jar is gitignored. `.gitattributes` forces LF for `/mvnw` and CRLF for `*.cmd` so scripts run in bash and on Windows — keep new shell scripts LF and `.cmd` scripts CRLF.

Source: .mvn/wrapper/maven-wrapper.properties, mcp-server/.mvn/wrapper/maven-wrapper.properties, .gitattributes, .gitignore
