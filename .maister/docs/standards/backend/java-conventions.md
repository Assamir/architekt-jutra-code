## Java Conventions

### DTOs as Records with Create/Update/Response Naming

Request/response DTOs are Java records named `Create<X>Request`, `Update<X>Request` and `<X>Response`. Response records expose a static `from(Entity)` factory (nested responses call the nested `from()`). Services return response records to controllers, never entities.

```java
public record ProductResponse(Long id, String name, CategoryResponse category) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getName(), CategoryResponse.from(p.getCategory()));
    }
}
```

Source: src/main/java/pl/devstyle/aj/product/ProductResponse.java, category/CreateCategoryRequest.java, core/plugin/PluginObjectResponse.java

### Use Jackson 3 (tools.jackson) in the Host App

The host runs Spring Boot 4, which uses Jackson 3: import from `tools.jackson.*` (e.g. `tools.jackson.databind.ObjectMapper`), never `com.fasterxml.jackson.*`. Precedent: `DbPluginObjectQueryService`, `SecurityConfiguration`. Exception: `mcp-server/`, whose MCP SDK still requires Jackson 2.

```java
import tools.jackson.databind.ObjectMapper;   // host app
```

Source: .maister/tasks/development/2026-05-20-footprint-calc-engine/implementation/work-log.md, 2026-04-04-build-mcp-server/implementation/implementation-plan.md

### Lombok via Explicit Annotation Processor Path

Both Maven projects register Lombok explicitly in `maven-compiler-plugin` `annotationProcessorPaths` (required on Java 25). The host declares Lombok `provided` and pins `lombok.version`; `mcp-server` declares it `optional` and excludes it from the fat jar. Any new Maven module using Lombok must copy this compiler-plugin block, or annotations will silently not be processed.

```xml
<annotationProcessorPaths><path><groupId>org.projectlombok</groupId>
  <artifactId>lombok</artifactId><version>${lombok.version}</version></path></annotationProcessorPaths>
```

Source: pom.xml, mcp-server/pom.xml
