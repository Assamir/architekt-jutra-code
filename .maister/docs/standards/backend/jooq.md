## jOOQ Query Standards (Professional Edition)

### Overview

**jOOQ (Java Object Oriented Querying)** is used for complex, performance-demanding queries that require more control than JPA/Hibernate provides. We use the **Professional Edition** with advanced enterprise features.

**When to use jOOQ**:
- Complex aggregations with window functions
- Performance-critical queries requiring fine-tuned SQL
- Advanced SQL features (CTEs, recursive queries, advanced joins)
- Bulk operations requiring precise control
- Reports and analytics queries
- Queries that require database-specific optimizations

**When to use JPA** (see [queries.md](./queries.md)):
- Standard CRUD operations
- Simple entity relationships
- Queries fitting Spring Data JPA repository patterns
- When ORM abstraction benefits outweigh performance needs

---

### Design Principles

#### 1. Type Safety

**DO**: Leverage jOOQ's type-safe DSL
```java
// GOOD: Type-safe query with generated code
Result<Record3<Long, String, Integer>> result = dsl
    .select(CUSTOMER.ID, CUSTOMER.NAME, count())
    .from(CUSTOMER)
    .join(SUBSCRIPTION).on(CUSTOMER.ID.eq(SUBSCRIPTION.CUSTOMER_ID))
    .where(SUBSCRIPTION.STATUS.eq("ACTIVE"))
    .groupBy(CUSTOMER.ID, CUSTOMER.NAME)
    .fetch();
```

**DON'T**: Build SQL strings manually
```java
// BAD: SQL injection risk, no type safety
String sql = "SELECT c.id, c.name, COUNT(*) " +
             "FROM customer c JOIN subscription s " +
             "WHERE s.status = '" + status + "'";
Result<Record> result = dsl.fetch(sql);
```

#### 2. SQL Injection Prevention

**DO**: Always use bind variables
```java
// GOOD: Parameterized query
dsl.select(CUSTOMER.NAME)
   .from(CUSTOMER)
   .where(CUSTOMER.EMAIL.eq(email))  // Automatic bind variable
   .fetch();

// GOOD: Explicit bind variables
dsl.select(CUSTOMER.NAME)
   .from(CUSTOMER)
   .where("email = ?", email)  // Explicit binding
   .fetch();
```

**DON'T**: Concatenate user input
```java
// BAD: SQL injection vulnerability
dsl.select(CUSTOMER.NAME)
   .from(CUSTOMER)
   .where("email = '" + email + "'")  // VULNERABLE!
   .fetch();
```

#### 3. Code Generation

**DO**: Use generated classes for all schema objects
```java
// GOOD: Generated table and column references
dsl.selectFrom(SUBSCRIPTION)
   .where(SUBSCRIPTION.CUSTOMER_ID.eq(customerId))
   .and(SUBSCRIPTION.STATUS.in("ACTIVE", "TRIAL"))
   .fetch();
```

**DON'T**: Use string literals for table/column names
```java
// BAD: No compile-time checking, breaks on schema changes
dsl.select()
   .from("subscription")
   .where("customer_id = ?", customerId)
   .fetch();
```

#### Codegen from Liquibase via Testcontainers (Project Setup)

jOOQ classes are never hand-written or committed. `testcontainers-jooq-codegen-maven-plugin` starts `postgres:18` in `generate-sources`, applies `db.changelog-master.yaml` with Liquibase and generates the `public` schema into `pl.devstyle.aj.jooq` under `target/generated-sources/jooq` (excluding `databasechangelog*`); `build-helper-maven-plugin` adds it to the compile path. Re-run a Maven build after every migration change. Pass `-Dskip.jooq.generation=true` only when generated sources already exist. Codegen versions follow the Boot BOM (`${jooq.version}`, `${postgresql.version}`, `${testcontainers.version}`).

```bash
./mvnw test -Dskip.jooq.generation=true   # reuse existing generated sources
```

Source: pom.xml

---

### Mandatory Rules

#### N+1 Problem Prevention

**DO**: Use JOIN or MULTISET for loading related data
```java
// GOOD: Single query with MULTISET
dsl.select(
       CUSTOMER.ID,
       CUSTOMER.NAME,
       multiset(
           select(SUBSCRIPTION.ID, SUBSCRIPTION.STATUS)
           .from(SUBSCRIPTION)
           .where(SUBSCRIPTION.CUSTOMER_ID.eq(CUSTOMER.ID))
       ).as("subscriptions")
   )
   .from(CUSTOMER)
   .fetch();
```

**DON'T**: Loop over parent records and query children
```java
// BAD: N+1 queries
List<Customer> customers = dsl.selectFrom(CUSTOMER).fetch().into(Customer.class);
for (Customer customer : customers) {
    List<Subscription> subs = dsl.selectFrom(SUBSCRIPTION)
        .where(SUBSCRIPTION.CUSTOMER_ID.eq(customer.getId()))
        .fetch()
        .into(Subscription.class);
}
```

#### Query Optimization Rules

**1. Use MULTISET for nested collections (jOOQ 3.15+)**
```java
record CustomerWithSubscriptions(Long id, String name, List<Subscription> subscriptions) {}

List<CustomerWithSubscriptions> result = dsl
    .select(
        CUSTOMER.ID,
        CUSTOMER.NAME,
        multiset(
            selectFrom(SUBSCRIPTION)
            .where(SUBSCRIPTION.CUSTOMER_ID.eq(CUSTOMER.ID))
        ).as("subscriptions").convertFrom(r -> r.into(Subscription.class))
    )
    .from(CUSTOMER)
    .fetch(Records.mapping(CustomerWithSubscriptions::new));
```

**2. Project only needed columns**
```java
// GOOD: Select specific columns
dsl.select(CUSTOMER.ID, CUSTOMER.NAME, CUSTOMER.EMAIL)
   .from(CUSTOMER)
   .fetch();

// BAD: SELECT * loads all columns
dsl.selectFrom(CUSTOMER).fetch();
```

**3. Use proper result mapping**
```java
// Map to Java records
record CustomerSummary(Long id, String name, String email) {}

List<CustomerSummary> summaries = dsl
    .select(CUSTOMER.ID, CUSTOMER.NAME, CUSTOMER.EMAIL)
    .from(CUSTOMER)
    .fetch(Records.mapping(CustomerSummary::new));

// Map to POJOs
List<Customer> customers = dsl
    .selectFrom(CUSTOMER)
    .fetch()
    .into(Customer.class);
```

**4. Use EXISTS() instead of COUNT() for existence checks**
```java
// GOOD: Stops at first match
boolean hasActiveSubscription = dsl.fetchExists(
    selectOne()
    .from(SUBSCRIPTION)
    .where(SUBSCRIPTION.CUSTOMER_ID.eq(customerId))
    .and(SUBSCRIPTION.STATUS.eq("ACTIVE"))
);

// BAD: Counts all matching rows
boolean hasActiveSubscription = dsl.fetchCount(
    selectFrom(SUBSCRIPTION)
    .where(SUBSCRIPTION.CUSTOMER_ID.eq(customerId))
    .and(SUBSCRIPTION.STATUS.eq("ACTIVE"))
) > 0;
```

**5. Use LIMIT for top-N queries**
```java
List<Customer> recentCustomers = dsl
    .selectFrom(CUSTOMER)
    .orderBy(CUSTOMER.CREATED_AT.desc())
    .limit(10)
    .fetch()
    .into(Customer.class);
```

---

### Common Pitfalls (From jOOQ Documentation)

#### Don't Implement DSL Types
jOOQ types are sealed. Use composition and method extraction instead.
```java
// GOOD: Extract query logic to methods
private SelectWhereStep<Record> baseCustomerQuery() {
    return dsl.selectFrom(CUSTOMER);
}
```

#### Don't Reference Step Types
Use `var` or the most generic type instead of specific Step interfaces.
```java
// GOOD: Flexible, survives jOOQ updates
var query = dsl
    .selectFrom(CUSTOMER)
    .where(CUSTOMER.STATUS.eq("ACTIVE"));

if (nameFilter != null) {
    query = query.and(CUSTOMER.NAME.like("%" + nameFilter + "%"));
}
```

#### Don't Use SELECT DISTINCT Unnecessarily
Use SEMI JOIN (EXISTS) when checking relationships instead of DISTINCT to fix join duplicates.
```java
// GOOD: Use EXISTS for semi-join
dsl.select(CUSTOMER.ID, CUSTOMER.NAME)
   .from(CUSTOMER)
   .whereExists(
       selectOne()
       .from(SUBSCRIPTION)
       .where(SUBSCRIPTION.CUSTOMER_ID.eq(CUSTOMER.ID))
   )
   .fetch();
```

#### Don't Use NOT IN with Nullable Columns
Use NOT EXISTS instead — NOT IN returns no results if the subquery contains NULL.
```java
// GOOD: NOT EXISTS handles NULLs correctly
dsl.selectFrom(CUSTOMER)
   .whereNotExists(
       selectOne()
       .from(SUBSCRIPTION)
       .where(SUBSCRIPTION.CUSTOMER_ID.eq(CUSTOMER.ID))
   )
   .fetch();
```

#### Don't Rely on Implicit Ordering
Always specify ORDER BY when order matters.

#### Don't Use ORDER BY Column Index
Reference columns by name, not position — positions break when projections change.

#### Don't Use UNION Instead of UNION ALL
Use UNION ALL when duplicates are impossible or acceptable to avoid unnecessary DISTINCT overhead.

#### Don't Use NATURAL JOIN or JOIN USING
Always explicitly specify join conditions for clarity and resilience to schema changes.

---

### Advanced Features

#### Common Table Expressions (CTEs)
```java
var activeCustomers = name("active_customers").as(
    select(CUSTOMER.ID, CUSTOMER.NAME)
    .from(CUSTOMER)
    .where(CUSTOMER.STATUS.eq("ACTIVE"))
);

dsl.with(activeCustomers)
   .select(
       activeCustomers.field(CUSTOMER.ID),
       activeCustomers.field(CUSTOMER.NAME),
       count()
   )
   .from(activeCustomers)
   .join(SUBSCRIPTION).on(
       activeCustomers.field(CUSTOMER.ID).eq(SUBSCRIPTION.CUSTOMER_ID)
   )
   .groupBy(
       activeCustomers.field(CUSTOMER.ID),
       activeCustomers.field(CUSTOMER.NAME)
   )
   .fetch();
```

#### Window Functions
```java
dsl.select(
       CUSTOMER.NAME,
       SUBSCRIPTION.TOTAL_AMOUNT,
       rowNumber().over(
           partitionBy(CUSTOMER.ID)
           .orderBy(SUBSCRIPTION.CREATED_AT.desc())
       ).as("subscription_rank")
   )
   .from(CUSTOMER)
   .join(SUBSCRIPTION).on(CUSTOMER.ID.eq(SUBSCRIPTION.CUSTOMER_ID))
   .fetch();
```

#### Bulk Operations
```java
// Batch insert
dsl.batch(
    customers.stream()
        .map(c -> dsl.insertInto(CUSTOMER)
            .columns(CUSTOMER.NAME, CUSTOMER.EMAIL)
            .values(c.name(), c.email())
        )
        .collect(Collectors.toList())
).execute();

// Bulk insert with VALUES clause (more efficient)
dsl.insertInto(CUSTOMER)
   .columns(CUSTOMER.NAME, CUSTOMER.EMAIL)
   .valuesOfRows(
       customers.stream()
           .map(c -> row(c.name(), c.email()))
           .collect(Collectors.toList())
   )
   .execute();
```

#### Transactions
```java
dsl.transaction(configuration -> {
    DSLContext txDsl = DSL.using(configuration);

    Long customerId = txDsl.insertInto(CUSTOMER)
        .columns(CUSTOMER.NAME, CUSTOMER.EMAIL)
        .values("John Doe", "john@example.com")
        .returningResult(CUSTOMER.ID)
        .fetchOne()
        .value1();

    txDsl.insertInto(SUBSCRIPTION)
        .columns(SUBSCRIPTION.CUSTOMER_ID, SUBSCRIPTION.STATUS)
        .values(customerId, "ACTIVE")
        .execute();
});
```

#### Dynamic SQL
```java
public List<Customer> findCustomers(String name, String email, String status) {
    var conditions = new ArrayList<Condition>();

    if (name != null) {
        conditions.add(CUSTOMER.NAME.like("%" + name + "%"));
    }
    if (email != null) {
        conditions.add(CUSTOMER.EMAIL.eq(email));
    }
    if (status != null) {
        conditions.add(CUSTOMER.STATUS.eq(status));
    }

    return dsl.selectFrom(CUSTOMER)
        .where(conditions)
        .fetch()
        .into(Customer.class);
}
```

---

### Performance Checklist

- [ ] Using type-safe generated code (no string literals)?
- [ ] All user input passed via bind variables?
- [ ] Using EXISTS() instead of COUNT() > 0?
- [ ] Using LIMIT for top-N queries?
- [ ] Projecting only needed columns (no SELECT *)?
- [ ] Using MULTISET for nested collections (avoiding N+1)?
- [ ] Using UNION ALL instead of UNION when appropriate?
- [ ] Explicit ORDER BY when order matters?
- [ ] Using batch operations for bulk inserts/updates?
- [ ] No NOT IN with nullable columns?

---

### Lightweight Authorization Query Services

When authorization logic needs only a small subset of entity data (e.g., team IDs for permission checks), create a dedicated jOOQ query service instead of loading full JPA entities.

#### Pattern
```java
@Service
@RequiredArgsConstructor
public class DbPersonQueryService {

    private final DSLContext dsl;

    @Transactional(readOnly = true)
    public List<TeamId> getPersonTeams(PersonId personId, CompanyId companyId) {
        return dsl.select(PERSON_TEAM.TEAM_UUID)
            .from(PERSON)
            .join(PERSON_TEAM).on(PERSON.ID.eq(PERSON_TEAM.PERSON_ID))
            .join(COMPANY).on(PERSON.COMPANY_ID.eq(COMPANY.ID))
            .where(
                PERSON.UUID.eq(personId.uuid()),
                PERSON.REMOVED.isFalse(),
                COMPANY.UUID.eq(companyId.uuid())
            )
            .fetch(r -> new TeamId(r.get(PERSON_TEAM.TEAM_UUID)));
    }
}
```

#### When to use this pattern
- Permission/authorization checks that need team IDs, role IDs, or similar small data
- Endpoints like `/self` that aggregate data from multiple JPA entities — replace with a single jOOQ query projecting only needed columns
- Any hot path where JPA entity loading triggers excessive lazy-loaded queries

#### Key rules
1. **Always include soft-delete filters** (`PERSON.REMOVED.isFalse()`)
2. **Always include company scoping** (`COMPANY.UUID.eq(companyId.uuid())`)
3. **Project only needed columns** — don't `selectFrom()` entire tables
4. **Follow naming convention**: `Db*QueryService` for jOOQ read services

See [queries.md](./queries.md) for the lazy `Supplier` pattern that pairs with these lightweight queries.

---

### Integration with JPA

Use **JPA** for entity management, relationships, and simple CRUD. Use **jOOQ** for complex read queries, reports, and performance-critical operations.

```java
@Service
public class CustomerService {

    @Autowired
    private CustomerRepository jpaRepository;  // JPA for CRUD

    @Autowired
    private DSLContext dsl;  // jOOQ for complex queries

    public Customer save(Customer customer) {
        return jpaRepository.save(customer);
    }

    public List<CustomerRevenueSummary> getCustomerRevenueSummary() {
        return dsl.select(
                CUSTOMER.ID,
                CUSTOMER.NAME,
                sum(INVOICE.TOTAL_AMOUNT).as("total_revenue")
            )
            .from(CUSTOMER)
            .leftJoin(SUBSCRIPTION).on(CUSTOMER.ID.eq(SUBSCRIPTION.CUSTOMER_ID))
            .leftJoin(INVOICE).on(SUBSCRIPTION.ID.eq(INVOICE.SUBSCRIPTION_ID))
            .where(INVOICE.STATUS.eq("PAID"))
            .groupBy(CUSTOMER.ID, CUSTOMER.NAME)
            .orderBy(sum(INVOICE.TOTAL_AMOUNT).desc())
            .fetch(Records.mapping(CustomerRevenueSummary::new));
    }
}
```

---

### Quick Reference: jOOQ vs JPA

| Use Case | jOOQ | JPA |
|----------|------|-----|
| Simple CRUD | No | Yes |
| Entity relationships | No | Yes |
| Complex aggregations | Yes | No |
| Window functions | Yes | No |
| CTEs | Yes | No |
| Reports/Analytics | Yes | No |
| Bulk operations | Yes | Maybe |
| Performance-critical | Yes | Maybe |
| Authorization queries | Yes | No |
| Database-specific features | Yes | No |

### Common Anti-Patterns

| Anti-Pattern | Better Approach |
|---|---|
| `SELECT *` | Project only needed columns |
| `COUNT(*) > 0` | Use `EXISTS()` |
| `NOT IN` with nullable | Use `NOT EXISTS` |
| SELECT DISTINCT for join duplicates | Use EXISTS or proper join |
| Implicit ordering | Explicit ORDER BY |
| ORDER BY column index | ORDER BY column reference |
| UNION when UNION ALL works | Use UNION ALL |
| String concatenation for SQL | Use bind variables |
| Loop queries (N+1) | Use JOIN or MULTISET |

---

*Last Updated*: 2026-03-28
*Reference*: Based on jOOQ 3.19 Professional Edition documentation (Section 7.8 - Don't do this)
