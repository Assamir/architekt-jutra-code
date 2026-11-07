## Error Handling

### Clear User Messages
Show helpful, actionable messages without exposing internal details or security-sensitive information.

### Fail Fast
Validate inputs and check preconditions early; reject invalid data before it causes deeper issues.

### Typed Exceptions
Use specific exception types instead of generic ones to enable precise error handling.

### Centralized Handling
Catch and process errors at appropriate boundaries (controllers, API layers) rather than scattering try-catch throughout.

### Graceful Degradation
When non-critical services fail, continue operating with reduced functionality rather than crashing entirely.

### Retry with Backoff
Use exponential backoff for transient failures when calling external services.

### Resource Cleanup
Always release resources (file handles, connections) in finally blocks or equivalent cleanup mechanisms.

### Typed Domain Exceptions and GlobalExceptionHandler
Domain errors are unchecked exceptions from `core/error`: `EntityNotFoundException(entityType, id)` → 404 and `BusinessConflictException` (subclassed per domain, e.g. `CategoryHasProductsException`) → 409. `GlobalExceptionHandler` (`@RestControllerAdvice`) maps them, plus `MethodArgumentNotValidException` (400 with `fieldErrors`), `IllegalArgumentException` (400), `DataIntegrityViolationException` (409), `AccessDeniedException` (403) and a logged catch-all 500, to the `ErrorResponse` record `{status, error, message, fieldErrors, timestamp}`.

```java
.orElseThrow(() -> new EntityNotFoundException("Product", id));
public class CategoryHasProductsException extends BusinessConflictException { ... }
```

Source: src/main/java/pl/devstyle/aj/core/error/GlobalExceptionHandler.java, category/CategoryHasProductsException.java

### Module-Scoped Problem+JSON Handlers with Explicit Ordering
`GlobalExceptionHandler` + `ErrorResponse` stays authoritative for every module. Problem+JSON (RFC 9457 `ProblemDetail`) is used only for footprint domain errors, via a package-scoped handler (consumed by frontend `api/problem.ts`), and never for 401/403 auth errors. A package-scoped `@RestControllerAdvice` must be `@Order(Ordered.HIGHEST_PRECEDENCE)`, otherwise the global catch-all `@ExceptionHandler(Exception.class)` shadows it. Set `ProblemDetail.instance` to `request.getRequestURI()` (path only, never scheme/host) and never leave it null.

```java
@RestControllerAdvice(basePackages = "pl.devstyle.aj.footprint")
@Order(Ordered.HIGHEST_PRECEDENCE)
class FootprintExceptionHandler { ... }
```

Source: src/main/java/pl/devstyle/aj/footprint/web/FootprintExceptionHandler.java, .maister/tasks/development/2026-05-20-footprint-calc-engine/implementation/spec.md, work-log.md
