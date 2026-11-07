## Database Queries

### Parameterized Queries
Always use parameterized queries or ORM methods; never interpolate user input into SQL.

### Avoid N+1
Use eager loading or joins to fetch related data in one query.

### Select Only Needed Columns
Request only the columns you need rather than SELECT *.

### Index Strategic Columns
Index columns used in WHERE, JOIN, and ORDER BY clauses.

### Transactions
Wrap related operations in transactions to maintain consistency.

### Query Timeouts
Set timeouts to prevent runaway queries from impacting performance.

### Cache Expensive Queries
Cache results of complex or frequent queries when appropriate.

### Method-Level @Transactional in Services
Put transaction boundaries on public service methods, never on controllers or repositories. Use `@Transactional(readOnly = true)` on every read method (including `Db*QueryService` methods) and plain `@Transactional` on writes. Writes persist with `saveAndFlush` and return a mapped response record.

```java
@Transactional(readOnly = true)
public ProductResponse findById(Long id) { ... }

@Transactional
public ProductResponse create(CreateProductRequest request) { ... return ProductResponse.from(productRepository.saveAndFlush(product)); }
```

Source: src/main/java/pl/devstyle/aj/product/ProductService.java, core/plugin/PluginObjectService.java, product/DbProductQueryService.java
