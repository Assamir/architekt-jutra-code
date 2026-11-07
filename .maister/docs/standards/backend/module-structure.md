## Backend Module Structure

### Package-by-Feature Module Layout

Each domain module is a flat package named after the feature (`product`, `category`, `user`, `core/plugin`) holding its entity, `<X>Controller`, `<X>Service`, `<X>Repository` (plain `JpaRepository` interface), `Db<X>QueryService` (jOOQ reads), `Create<X>Request` / `Update<X>Request` / `<X>Response` records and module-specific exceptions. No technical layer packages (`controller/`, `service/`, `dto/`).

```text
product/{Product, ProductController, ProductService, ProductRepository,
         DbProductQueryService, CreateProductRequest, UpdateProductRequest, ProductResponse}.java
```

Source: src/main/java/pl/devstyle/aj/{product,category,user,core/plugin}/

### Hexagonal Self-Contained Module Layout

A self-contained domain module (e.g. `footprint`) is split into:
- `api` — public facade interface (`FootprintFacade`), records and `api/exception`; the only types callers may use.
- `internal` — package-private implementation classes (`DefaultFootprintFacade`, builders, policies); `internal/ports` for SPI interfaces.
- `spi/stub` — in-memory adapters, kept outside `internal/` so a real adapter can replace them without package-private collisions. Activate them only via `@ConditionalOnProperty` (`app.footprint.adapters=in-memory`), which production must change or unset.
- The module's web exception handler is package-private too.

```java
class DefaultFootprintFacade implements FootprintFacade { ... } // package-private
```

Source: CLAUDE.md, src/main/java/pl/devstyle/aj/footprint/, .maister/tasks/development/2026-05-20-footprint-calc-engine/implementation/spec.md, work-log.md
