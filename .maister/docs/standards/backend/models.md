## JPA Entity Modeling Standards

### Design Principles

#### Model Simplicity

Prefer primitives (String, enum) over entities when possible. Use `@ElementCollection` for simple value types (strings, enums, embeddables). Only create entities when you need identity, lifecycle, or complex relationships.

```java
// GOOD: Simple value collection
@ElementCollection
@CollectionTable(name = "customer_tags", joinColumns = @JoinColumn(name = "customer_id"))
@Column(name = "tag")
private Set<String> tags = new HashSet<>();

@Enumerated(EnumType.STRING)
private PaymentMethod paymentMethod;
```

```java
// BAD: Unnecessary entity for simple values
@OneToMany(mappedBy = "customer")
private Set<Tag> tags;  // Tag entity just has id + String value
```

#### Naming Conventions

Use singular names for entities, plural for tables.
```java
@Entity
@Table(name = "customers")  // Plural table name
public class Customer {     // Singular entity name
```

#### Data Integrity

Enforce data rules at database level with constraints: `NOT NULL` for required fields, `UNIQUE` for business keys, foreign keys for referential integrity.
```java
@Column(nullable = false, unique = true)
private String email;

@Column(nullable = false, length = 100)
private String name;
```

#### Data Types

Choose appropriate data types: `LocalDateTime` for timestamps, `BigDecimal` for money (never float/double), fixed-length `@Column(length = N)` for codes.

#### Indexes

Index foreign keys and frequently queried columns. Don't over-index — it impacts INSERT/UPDATE performance.
```java
@Table(
    name = "orders",
    indexes = {
        @Index(name = "idx_customer_id", columnList = "customer_id"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_created_at", columnList = "created_at")
    }
)
```

#### Normalization

Normalize to 3NF for transactional data. Consider denormalization for reporting queries. Use `@Embedded` for value objects that belong together.

---

### Entity Structure

#### Table Naming
Use `@Table(name = "table_name")` to override table names. Don't use `@Entity(name = "...")` — it affects JPQL queries.

#### Field Access
Use field access (annotations on fields). Don't use property access unless you need computed fields.

#### BaseEntity Pattern

Extend from `@MappedSuperclass` base entity for common fields:
```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "base_seq")
    private Long id;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Version  // Hibernate manages this automatically
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
```

Each entity declares its sequence at class level:
```java
@Entity
@Table(name = "customers")
@SequenceGenerator(name = "base_seq", sequenceName = "customer_seq", allocationSize = 1)
public class Customer extends BaseEntity {
    // No need to override getId() - inherits from BaseEntity!
}
```

- Don't use `@LastModifiedDate` on the `@Version` field — Hibernate manages it
- Don't override `getId()` in each entity — unnecessary boilerplate
- `@Version` with `LocalDateTime` provides both optimistic locking and audit tracking in one field

#### When to Extend BaseEntity (Project Practice)

Only entities with generated `Long` ids extend `BaseEntity` (with the per-entity `@SequenceGenerator(name = "base_seq", sequenceName = "<table>_seq", allocationSize = 1)`, `@Getter/@Setter/@NoArgsConstructor` and business-key `equals/hashCode`). Entities with externally assigned ids (`PluginDescriptor` with `String pluginId`, `RegisteredClientEntity` with UUID) do not extend `BaseEntity` and use that natural id as their equality key. Map JSONB columns as `Map<String, Object>`:

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
private Map<String, Object> pluginData;
```

Source: src/main/java/pl/devstyle/aj/product/Product.java, user/User.java, core/plugin/PluginDescriptor.java

---

### Primary Keys

Use `GenerationType.SEQUENCE` with `allocationSize = 1`. Define in BaseEntity once, declare entity-specific sequence at class level.

- Don't use `GenerationType.IDENTITY` — it disables JDBC batching
- Use `Long` for generated IDs (not primitive `long` — nullability indicates new entity)

---

### Enumerations

Always use `@Enumerated(EnumType.STRING)`. Never use `EnumType.ORDINAL` — it breaks when enum order changes.
```java
@Enumerated(EnumType.STRING)
@Column(length = 20)
private SubscriptionStatus status;
```

---

### Relationships

#### Fetch Types
Make all relationships `LAZY` by default. Override `@OneToOne` and `@ManyToOne` (which default to EAGER) to LAZY.

#### Ownership
Always use `mappedBy` on the non-owning side. Forgetting `mappedBy` creates duplicate foreign keys or join tables.

#### Bidirectional OneToMany
Use helper methods to maintain both sides:
```java
@OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true)
private Set<Order> orders = new HashSet<>();

public void addOrder(Order order) {
    order.setCustomer(this);
    orders.add(order);
}
```

#### OrphanRemoval
Use `orphanRemoval = true` when child has no meaning without parent.

#### ManyToMany
Use explicit join entity instead of `@ManyToMany` when you need extra fields on the relationship.

---

### Collections

Use `Set<?>` for collections (best performance). Don't use `List<?>` without `@OrderColumn` — becomes Bag with poor performance.

Initialize collections in field declarations:
```java
private Set<Order> orders = new HashSet<>();
```

Return unmodifiable collections from getters:
```java
public Set<Order> getOrders() {
    return Collections.unmodifiableSet(orders);
}
```

---

### Embedded Objects

Always implement `equals()` and `hashCode()` in `@Embeddable` classes — prevents excessive dirty checking.

Use `@AttributeOverrides` for multiple embedded objects of the same type:
```java
@Embedded
@AttributeOverrides({
    @AttributeOverride(name = "street", column = @Column(name = "billing_street")),
    @AttributeOverride(name = "city", column = @Column(name = "billing_city"))
})
private Address billingAddress;
```

---

### Cascade Operations

Only cascade when it makes business sense — use for composition relationships (Order -> OrderLine), not independent entities (Order -> Customer).

Use specific cascade types, avoid `CascadeType.ALL` unless justified:
```java
cascade = {CascadeType.PERSIST, CascadeType.REMOVE}
```

---

### Entity Identity

Use business key (natural ID) for `equals()` and `hashCode()`. Don't use `@Id` field — it changes during entity lifecycle.

```java
@Column(unique = true, nullable = false)
private String email;

@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Customer)) return false;
    return Objects.equals(email, ((Customer) o).email);
}

@Override
public int hashCode() {
    return Objects.hash(email);
}
```

Alternative: Use UUID business key if no natural ID exists:
```java
@Column(unique = true, nullable = false, updatable = false)
private UUID businessKey = UUID.randomUUID();
```

---

### Proxy Requirements

Provide protected no-arg constructor. Don't make entity classes or methods `final` — breaks proxying.

---

### Auditing

Use Spring Data JPA auditing annotations. Don't manually manage timestamps with `@PrePersist` / `@PreUpdate`.

Optional: Add `@CreatedBy` and `@LastModifiedBy` for user tracking:
```java
@MappedSuperclass
public abstract class AuditedEntity extends BaseEntity {
    @CreatedBy private String createdBy;
    @LastModifiedBy private String lastModifiedBy;
}
```

---

### Cross-Module References

When referencing entities from other bounded contexts (modules), use type-safe ID wrappers instead of JPA relationships (DDD bounded context principle).

```java
// DON'T: Cross-module entity reference
@ManyToOne
@JoinColumn(name = "company_id")
private Company company;

// DO: Just the ID, no entity reference
@Column(name = "company_id")
private CompanyId companyId;
```

Benefits: no cross-module coupling, no unnecessary repository dependencies, no entity loading overhead, easier to test.

When to load cross-module data: use Facades (e.g., `OrganizationFacade`), only load when actually required, for read operations jOOQ query services can join tables when needed.

Service layer impact — no cross-module repository needed:
```java
// Before:
private final CompanyRepository companyRepository;
Company company = companyRepository.getById(request.companyId());
workflow.setCompany(company);

// After:
workflow.setCompanyId(request.companyId());
```

---

### Project-Specific Patterns

#### Lombok Usage
```java
@Entity
@Table(name = "customers")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Customer extends BaseEntity {
    // fields
}
```

#### Soft Delete Pattern
```java
@Entity
@Where(clause = "deleted_at IS NULL")
public class Customer {
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
```

---

### Quick Reference

#### Collection Types Performance

| Type | Use When | Performance |
|------|----------|-------------|
| **Set** | Default choice | Best |
| **List + @OrderColumn** | Order matters | Poor |
| **List (Bag)** | Duplicates needed | Worst |

#### Fetch Type Defaults

| Annotation | Default | Override To |
|------------|---------|-------------|
| `@OneToMany` | LAZY | Keep LAZY |
| `@ManyToMany` | LAZY | Keep LAZY |
| `@OneToOne` | EAGER | **Change to LAZY** |
| `@ManyToOne` | EAGER | **Change to LAZY** |

#### Primary Key Strategies

| Strategy | Pros | Cons | Use? |
|----------|------|------|------|
| **SEQUENCE** | Fast, supports batching | Requires sequence | Yes |
| IDENTITY | Simple | Disables batching | No |
| TABLE | Portable | Slowest | No |
| AUTO | Provider chooses | Unpredictable | No |

---

*Last Updated*: 2026-03-28
