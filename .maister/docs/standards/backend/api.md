## API Design

### RESTful Principles
Use resource-based URLs with appropriate HTTP methods (GET, POST, PUT, PATCH, DELETE).

### Consistent Naming
Use lowercase, hyphenated or underscored names consistently across endpoints.

### Versioning
Implement versioning (URL path or headers) to manage breaking changes.

### Plural Nouns
Use plural nouns for resources (`/users`, `/products`).

### Limited Nesting
Keep URL nesting to 2-3 levels maximum for readability.

### Query Parameters
Use query parameters for filtering, sorting, and pagination.

### Proper Status Codes
Return appropriate HTTP status codes (200, 201, 400, 404, 500).

### Rate Limit Headers
Include rate limit information in response headers.

### Controllers Return DTOs Directly with @ResponseStatus
REST controllers are mapped at `/api/<plural-resource>` and return response records/lists directly. POST uses `@ResponseStatus(HttpStatus.CREATED)`; DELETE uses `@ResponseStatus(HttpStatus.NO_CONTENT)` returning `void`. Reserve `ResponseEntity` for auth/OAuth2/footprint endpoints that need custom headers or variable status. Plugin sub-resources nest under `/api/plugins/{pluginId}/...`.

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
public ProductResponse create(@Valid @RequestBody CreateProductRequest request) { ... }
```

Source: src/main/java/pl/devstyle/aj/product/ProductController.java, category/CategoryController.java, core/plugin/PluginObjectController.java
