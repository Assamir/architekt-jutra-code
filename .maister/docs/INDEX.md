# Documentation Index

**IMPORTANT**: Read this file at the beginning of any development task to understand available documentation and standards.

## Quick Reference

### Project Documentation
Project-level documentation covering architecture and technology choices for **aj** — a plugin-based microkernel platform built on Spring Boot.

### Technical Standards
Coding standards, conventions, and best practices organized by domain: global, backend, frontend, plugins, MCP server, and testing.

---

## Project Documentation

Located in `.maister/docs/project/`

### Tech Stack (`project/tech-stack.md`)
Java 25 and TypeScript ~5.9. Host backend: Spring Boot 4.0.5 (WebMVC, JPA for CRUD, jOOQ for complex reads/JSONB filtering, Liquibase, Validation, Security + jjwt, OAuth2 Authorization Server, docker-compose support, spring-retry, commons-csv, Micrometer). MCP server: Spring Boot 4.0.5 + MCP Java SDK (`mcp-spring-webmvc`). Host frontend: React 19, React Router 7, Chakra UI v3, Vite 8 (app + IIFE plugin SDK bundle). Plugins: `warehouse`/`box-size` (React + Vite), `ai-description` (Next.js 15 + BAML via LiteLLM). PostgreSQL 18 with JSONB, jOOQ codegen via TestContainers, frontend-maven-plugin (Node 22). Testing: JUnit 5 + TestContainers, Vitest + Testing Library, Jest. Infrastructure: `compose.yml` (Postgres, LiteLLM, Langfuse, Presidio), `start-dev` scripts; no CI/CD.

### Architecture (`project/architecture.md`)
Microkernel with iframe plugins on a modular-monolith Spring host. Documents the plugin runtime (manifest registration, per-product JSONB `plugin_data` and `plugin_objects` storage, jOOQ `Db*QueryService` filtering), centralized JWT security and the host's OAuth2 authorization server, domain modules (product, category, user, api), the hexagonal `footprint` module, the pricing archetype, host frontend extension points and postMessage plugin SDK, plugins, the MCP server, data flow, database schema, configuration, and local-only deployment.

---

## Technical Standards

### Global Standards

Located in `.maister/docs/standards/global/`

#### Error Handling (`standards/global/error-handling.md`)
Clear user messages, fail-fast validation, typed exceptions, centralized handling, graceful degradation, retry with backoff, resource cleanup; typed domain exceptions (`EntityNotFoundException` → 404, `BusinessConflictException` subclasses → 409) mapped by `GlobalExceptionHandler` to the `ErrorResponse` record; module-scoped Problem+JSON (`ProblemDetail`) handlers only for footprint, annotated `@Order(Ordered.HIGHEST_PRECEDENCE)`, with path-only `instance`.

#### Validation (`standards/global/validation.md`)
Server-side validation, client-side feedback, early input checking, specific error messages, and allowlists over blocklists.

#### Development Conventions (`standards/global/conventions.md`)
Predictable file structure, up-to-date documentation, clean version control, environment variables, minimal dependencies, reviews, feature flags, changelog; leave course-material directories (`week7/`–`week10/`, `tools/`) alone; secrets via gitignored `.env` copied from committed `.env.example` (provider keys only in `litellm/.env`); `app.*` / `aj.*` kebab-case property namespacing with `${UPPER_SNAKE_ENV:default}` overrides and unit-suffixed keys; Docker Compose conventions (`org.springframework.boot.ignore` label, healthchecks + `service_healthy`, remapped ports, single `postgres:18`); use the `aj-kg-query` knowledge-graph skill for structural questions; always use the Maven wrapper, LF for `mvnw`/shell scripts and CRLF for `*.cmd`.

#### Coding Style (`standards/global/coding-style.md`)
Naming consistency, automatic formatting, descriptive names, focused functions, and uniform indentation.

#### Commenting (`standards/global/commenting.md`)
Let code speak through structure and naming, comment sparingly for non-obvious logic, avoid change-log comments.

#### Minimal Implementation (`standards/global/minimal-implementation.md`)
Build only what is needed, clear purpose for every method, delete exploration artifacts, no future stubs or speculative abstractions.

### Backend Standards

Located in `.maister/docs/standards/backend/`

#### API Design (`standards/backend/api.md`)
RESTful principles, consistent naming, versioning, plural nouns for resources, limited nesting, query parameters, proper status codes; controllers at `/api/<plural>` return DTO records directly with `@ResponseStatus(CREATED)` on POST and `@ResponseStatus(NO_CONTENT)` on DELETE, `ResponseEntity` only for auth/OAuth2/footprint endpoints, plugin sub-resources under `/api/plugins/{pluginId}/...`.

#### Module Structure (`standards/backend/module-structure.md`)
Package-by-feature module layout (Entity, Controller, Service, Repository, `Db*QueryService`, request/response records in one flat feature package, no technical layer packages); hexagonal self-contained module layout (`api` public facade, package-private `internal` with `internal/ports`, `spi/stub` in-memory adapters gated by `@ConditionalOnProperty`, callers use `api` only).

#### Java Conventions (`standards/backend/java-conventions.md`)
DTOs as records named `Create<X>Request` / `Update<X>Request` / `<X>Response` with static `from(entity)` mappers, services never return entities; Jackson 3 `tools.jackson.*` imports in the host app (Jackson 2 only in mcp-server); Lombok registered via explicit `annotationProcessorPaths` in every Maven module.

#### JPA Entity Modeling (`standards/backend/models.md`)
Comprehensive JPA entity modeling standards: BaseEntity with @MappedSuperclass pattern (id, createdAt, @Version updatedAt), extend BaseEntity only for sequence-id entities (natural String/UUID-keyed entities like `PluginDescriptor` use the id as equality key), JSONB columns as `Map<String, Object>` with `@JdbcTypeCode(SqlTypes.JSON)`, SEQUENCE primary key generation (allocationSize=1), EnumType.STRING for all enumerations, LAZY fetch default for all relationship types, Set-based collections, bidirectional relationship helper methods, orphanRemoval for owned collections, business key equals/hashCode (never entity id), @Embeddable value objects with equals/hashCode, specific cascade types (never CascadeType.ALL), cross-module references via type-safe ID wrappers (DDD bounded contexts), Lombok entity annotations (@Getter/@Setter/@NoArgsConstructor, no @Data/@EqualsAndHashCode), soft delete with @Where and deleted flag, collection type performance guide (Set vs List vs Map), and fetch type defaults per relationship.

#### Database Queries (`standards/backend/queries.md`)
Parameterized queries, N+1 avoidance with eager loading, select only needed columns, strategic indexing, transactions, query timeouts, caching; method-level `@Transactional` on public service methods only (`readOnly = true` for reads including `Db*QueryService`, plain on writes, `saveAndFlush` then map to a response record).

#### jOOQ Query Standards (`standards/backend/jooq.md`)
jOOQ Professional Edition query standards: type-safe DSL usage, SQL injection prevention via bind variables, code generation (project setup: codegen from the Liquibase changelog via Testcontainers `postgres:18` into `pl.devstyle.aj.jooq` under `target/generated-sources/jooq`, never committed, re-run after migrations, `-Dskip.jooq.generation=true` only when sources exist), N+1 prevention with MULTISET, query optimization (EXISTS over COUNT, column projection, result mapping), common pitfalls from jOOQ docs (no DSL type implementation, no Step type references, no SELECT *, no DISTINCT for join fixes, no NOT IN with nullables, explicit ordering, UNION ALL preference, explicit joins), advanced features (CTEs, window functions, bulk operations, transactions, dynamic SQL), lightweight authorization query services (Db*QueryService pattern), and JPA integration guidelines (JPA for CRUD, jOOQ for complex reads).

#### Database Migrations (`standards/backend/migrations.md`)
Reversible migrations, small focused changes, zero-downtime awareness, separate schema and data migrations, careful indexing, descriptive names, never modify deployed migrations; Liquibase YAML naming `db/changelog/2026/NNN-<verb>-<description>.yaml` picked up by `includeAll`, sample data in `dev` context, regenerate jOOQ after changes.

#### Security (`standards/backend/security.md`)
JWT authentication pattern with centralized SecurityFilterChain authorization (no @PreAuthorize), custom AuthenticationEntryPoint/AccessDeniedHandler for JSON 401/403 responses, BCryptPasswordEncoder for password storage, JWT token claims structure (sub, permissions, iat, exp); `SecurityConfiguration` matcher conventions (GET → `PERMISSION_READ`/`PERMISSION_mcp:read`, writes → `PERMISSION_EDIT`/`PERMISSION_mcp:edit`, plugin lifecycle → `PERMISSION_PLUGIN_MANAGEMENT`, no redundant matchers, single-segment `*`, MCP scope mapping); cross-reference to MCP no-token-passthrough rule.

#### Plugin Authentication (`standards/backend/plugin-auth.md`)
Browser SDK auth via hostApp.getToken() and postMessage, server-side auth with createServerSDK forwarding JWT from request headers, and permission checking in plugins by decoding JWT permissions claim.

### Frontend Standards

Located in `.maister/docs/standards/frontend/`

#### CSS (`standards/frontend/css.md`)
Consistent methodology, work with the framework, design tokens, minimize custom CSS, production optimization; theme tokens, never inline hex colors (prefer `brand.*` / `accent.*` / semantic tokens / CSS vars from `src/theme/index.ts`; raw Chakra palette values like `gray.*` allowed; px literals in Chakra style props acceptable).

#### Components (`standards/frontend/components.md`)
Single responsibility, reusability with configurable props, composability, clear interfaces, encapsulation, local state, minimal props; named `export function` components with no default exports, pages in `pages/` with `Page` suffix, reusable components in `components/<area>/`, `import type` for type-only imports.

#### Data Access (`standards/frontend/data-access.md`)
One API module per resource in `src/api/` (interfaces mirroring backend records, plain async functions over the shared `api/client.ts` which adds the Bearer token, redirects on 401 and throws `ApiError`; no react-query/axios); `use<Resource>` hooks returning `{data, loading, error, refetch, mutations}` with `as apiX` import aliases; relative `/api` URLs via the Vite dev proxy, never hardcoded hosts.

#### Tooling (`standards/frontend/tooling.md`)
TypeScript strict compiler settings (`strict`, `noUnusedLocals/Parameters`, `erasableSyntaxOnly` → no enums, `verbatimModuleSyntax` → `import type`, bundler resolution, ES2023); ESLint 9 flat config for the host (typescript-eslint, react-hooks, react-refresh: component files export only components; run `npm run lint`); type-check gated builds (`tsc -b` before `vite build`), host build into `resources/static` followed by the IIFE plugin SDK bundle, generated static output never committed.

#### Accessibility (`standards/frontend/accessibility.md`)
Semantic HTML, keyboard navigation, color contrast (4.5:1), alt text and labels, and screen reader testing.

#### Responsive Design (`standards/frontend/responsive.md`)
Mobile-first approach, standard breakpoints, fluid layouts, cross-device testing, touch-friendly targets, mobile performance, readable typography, content priority; relative units (rem/em) optional — px in Chakra style props is acceptable (team decision).

### Plugin Standards

Located in `.maister/docs/standards/plugins/`

#### Plugin Development (`standards/plugins/plugin-development.md`)
Plugin structure (`plugins/<id>/` npm project named `<id>-plugin` with a `dev` script, `manifest.json`, `src/main.tsx`, `src/domain.ts`, `src/pages/`; shared `plugins/sdk.ts` imported via `../../sdk`, never copied); mandatory host `plugin-ui.css` with `tc-*` classes (`tc-plugin`, `tc-primary-button`, ...), inline style only for layout; manifest as single source of truth (`PUT /api/plugins/{id}/manifest` with `PERMISSION_PLUGIN_MANAGEMENT`, id/url/name validation, re-register on changes); storage choice (per-product `getData/setData` replaces the whole node and feeds filters/badges, `objects` for collections with composite ids and paired entity binding, `jsonPath:op:value` filters); fixed dev ports (host 8080, MCP 8081, warehouse 3001, box-size 3002, ai-description 3003, demo/utility plugins 3004–3009, shipping 3010, next business module 3011, `strictPort`); extension point conventions (Lucide kebab-case icons, lower priority first, compact `product.detail.info`, native `product.list.filters` via `filterKey`/`filterType`); SDK usage (sync `thisPlugin` context shortcuts, `hostApp.fetch` restrictions, try/catch every call and surface errors).

### MCP Server Standards

Located in `.maister/docs/standards/mcp-server/`

#### MCP Server Conventions (`standards/mcp-server/conventions.md`)
MCP tool conventions (stateless proxy over `AjApiClient`, `aj_` tool-name prefix, programmatic `SyncToolSpecification` registration, `structuredContent` results, `McpToolException` static factories, validation stays in the backend); no token passthrough (RFC 7662 introspection without caching, RFC 8693 exchange for an audience-restricted backend token, scope mapping, no retry on backend 401, OAuth client credentials from env vars).

### Testing Standards

Located in `.maister/docs/standards/testing/`

#### Backend Testing (`standards/testing/backend-testing.md`)
Integration test infrastructure with TestContainers and real PostgreSQL 18, integration-first testing strategy (over unit tests), what NOT to test (auto-generated repos, Lombok getters/setters, private methods), test data isolation with @Transactional rollback, MockMvc with jsonPath()/Hamcrest for HTTP assertions, test class naming (*Tests suffix, package-private, same package as production), test method naming (action_condition_expectedResult pattern), private createAndSave*() helper methods with saveAndFlush(), integration vs validation test class split, test scope guidelines (2-8 tests per feature, CRUD plus edge cases), MockMvc security integration requiring SecurityMockMvcConfiguration import for Spring Boot 4/Security 7, custom security test annotations (@WithMockEditUser, @WithMockAdminUser), Spring Security 7 PathPattern constraints (single-segment `*` vs multi-segment `**`), and AFTER_COMMIT/async listener behaviour tested in separate non-`@Transactional` test classes (no `@Disabled` placeholders).

#### Frontend Testing (`standards/testing/frontend-testing.md`)
Vitest with globals and jsdom environment, @testing-library/react for component rendering and queries, @testing-library/jest-dom for extended DOM matchers, per-file renderWithProviders() helper wrapping ChakraProvider and MemoryRouter, API module mocking with vi.mock() factory functions and vi.resetAllMocks() in beforeEach, vi.mocked() for type-safe mock configuration, describe blocks named after pages/features, test files in src/test/ directory, and querying Chakra `asChild` links with `container.querySelector('a[href=...]')` under jsdom.

---

## How to Use This Documentation

1. **Start Here**: Always read this INDEX.md first to understand what documentation exists
2. **Project Context**: Read relevant project documentation before starting work
3. **Standards**: Reference appropriate standards when writing code
4. **Keep Updated**: Update documentation when making significant changes
5. **Customize**: Adapt all documentation to your project's specific needs

## Updating Documentation

- Project documentation should be updated when goals, tech stack, or architecture changes
- Technical standards should be updated when team conventions evolve
- Always update INDEX.md when adding, removing, or significantly changing documentation
