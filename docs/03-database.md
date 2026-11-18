# 3. Database connectivity

## Connection pool

```
pool size = ((core_count * 2) + effective_spindle_count)
```

For a web service: **small is fast**. 10 is a good starting point; 100 is almost always a
misconfiguration that moves the queue from your application into the database and makes p99 worse.

| Setting | Why |
| --- | --- |
| `auto-commit: false` | **mandatory** when `hibernate.connection.provider_disables_autocommit=true` - see below |
| `connection-timeout: 3000` | fail fast instead of piling up threads waiting for a connection |
| `max-lifetime` | must be **shorter** than any idle timeout enforced by the DB or a proxy (PgBouncer, RDS Proxy) |
| `leak-detection-threshold: 20000` | logs a stack trace for a connection held too long; free money |

> ### The autocommit trap
> `hibernate.connection.provider_disables_autocommit=true` tells Hibernate *"the pool already hands
> me connections with autocommit disabled, do not call setAutoCommit(false)"*. Hikari's default is
> `autoCommit=true`. Set the Hibernate property without the pool property and **every statement
> commits as it executes**: `@Transactional` rollback becomes a silent no-op. The two settings are a
> pair. This project shipped that bug for an hour; `TransactionPropagationTest` found it.

## Two pools, one routing DataSource

`DataSourceConfig` wires:

```
@Primary DataSource
  = LazyConnectionDataSourceProxy
      -> AbstractRoutingDataSource       key = isCurrentTransactionReadOnly() ? REPLICA : PRIMARY
           -> HikariDataSource "primary-pool"
           -> HikariDataSource "replica-pool"   (readOnly = true)
```

Any `@Transactional(readOnly = true)` method is served by the replica, with no call site aware of
it. Three details make it work:

1. **`LazyConnectionDataSourceProxy` is required.** `JpaTransactionManager` acquires a connection
   when the transaction *begins*. The lazy proxy defers `getConnection()` to the first real
   statement, so the routing decision sees fully-initialised transaction state and transactions that
   never touch the database never consume a pool slot.
2. **Flyway must be pinned to the primary** with `@FlywayDataSource` on the write pool. Migrating a
   replica fails; migrating the wrong database succeeds, which is worse.
3. **Declaring any `DataSource` bean makes `DataSourceAutoConfiguration` back off entirely**, so you
   must declare `DataSourceProperties` yourself too.

## The transaction manager needs a DataSource

Boot creates `JpaTransactionManager` **without** one. In that mode it binds no `ConnectionHolder`,
so a `JdbcTemplate`/`JdbcClient` call inside a `@Transactional` method checks out a second,
independent connection: it cannot see the JPA transaction's unflushed rows, it burns two pool slots
per request, and it can deadlock against the transaction it is nested inside.
`JpaConfig#transactionManager` calls `setDataSource(dataSource)` to fix it.

## Flyway

* Versioned migrations are **append-only**. Editing an applied migration changes its checksum and
  every environment that ran it refuses to start. Fix forward.
* `spring.jpa.hibernate.ddl-auto=validate` makes Hibernate compare its mappings against the real
  tables at startup and fail if they disagree. The cheapest guard that exists against a migration
  and an entity drifting apart. Never `update` outside a toy project.
* Keep index creation in its own migration: one day it needs `create index concurrently`, which
  cannot run inside a transaction.
* Expand/contract for zero downtime: add a nullable column, backfill, write both, switch reads, then
  drop the old column in a **later** release.
* Beware identifier case. Flyway creates `"flyway_schema_history"` with quoted lower-case
  identifiers, while unquoted identifiers in H2 (PostgreSQL mode) fold to upper case. The
  application schema in `V1__init.sql` avoids quoted identifiers entirely for that reason.

## JPA mapping decisions a reviewer will ask about

| Decision | Reasoning |
| --- | --- |
| `IDENTITY` vs `SEQUENCE` ids | IDENTITY forces an immediate INSERT on `persist()`, which **disables JDBC insert batching**. On PostgreSQL/Oracle prefer `SEQUENCE` with a pooled allocation size. |
| `@Version` | optimistic locking; map the failure to HTTP 409 and let the client retry. Doubles as an ETag. |
| `@Enumerated(EnumType.STRING)` | never ORDINAL - inserting a constant in the middle silently re-maps existing rows. Add a CHECK constraint. |
| `FetchType.LAZY` everywhere | `@ManyToOne`/`@OneToOne` default to EAGER, which quietly turns one query into twelve joins. |
| `optional = false` on `@ManyToOne` | lets Hibernate use an inner join and build a proxy instead of querying to prove existence. |
| `cascade = ALL, orphanRemoval = true` | correct for a true composition (order to lines), wrong for associations to shared entities. |
| `equals`/`hashCode` on the business key | a generated id plus a mutable collection is how entities "disappear" from a `HashSet`. Never use Lombok `@Data` on an entity. |
| bidirectional sync in `addLine()` | JPA does not maintain both sides for you. |
| `BigDecimal` with explicit precision/scale | money is never a `double`. |

## N+1 and friends

* `@EntityGraph(attributePaths = "lines")` or JPQL `join fetch` for a single aggregate.
* `@BatchSize(20)` on the collection, so loading N parents costs N/20 extra queries, not N.
* Never "fix" N+1 by making an association EAGER.
* `distinct` + pagination + a fetch join cannot be combined: Hibernate paginates in memory
  (HHH000104). `hibernate.query.fail_on_pagination_over_collection_fetch=true` turns that into an
  error instead of an OOM. Fetch ids first, then the aggregates.
* Turn `spring.jpa.open-in-view` **off**. It hides `LazyInitializationException` in development and
  then holds a connection for the entire HTTP response, including JSON serialisation. Map to DTOs
  inside the service transaction.

## Locking

| Mechanism | When |
| --- | --- |
| `@Version` (optimistic) | the default for HTTP APIs: cheap, no locks held, maps to 409 |
| `@Lock(PESSIMISTIC_WRITE)` | a hot row every request touches (inventory, balances), where optimistic retries would thrash |
| higher isolation levels | last resort - costs throughput, needs engine-specific retry handling |

Always add a lock timeout so a stuck transaction fails fast instead of exhausting the pool, and
always take locks in a consistent order to avoid deadlocks.

## Queries: the right tool per job

* **Derived methods** (`findByOrderNumber`) - parsed at startup, so a typo fails the context.
* **JPQL `@Query`** with `@Param` - for anything a method name would mangle.
* **`@Modifying` bulk updates** - one statement instead of N entities. `flushAutomatically = true`
  and `clearAutomatically = true` are not optional: without them the statement may not see pending
  changes, and stale managed entities may write the old value back. Bulk JPQL also bypasses
  optimistic locking and entity callbacks.
* **Projections** - interface projections select only the columns they declare.
* **`Specification`** - composable optional filters, instead of
  `findByStatusAndEmailAndTotalGreaterThanAndCreatedAfter`.
* **Custom fragment** (`OrderRepositoryCustom` + `...CustomImpl`) - when you need the
  `EntityManager`. The `Impl` suffix is mandatory; getting it wrong produces
  "No property 'x' found for type Order" at startup.
* **`JdbcClient`** - for reporting and list screens. JPA is for the write model: load an aggregate,
  mutate it, let dirty checking emit the UPDATE. Forcing every query through the ORM is the mistake,
  not mixing the two.

## Pagination

`Page` issues a second COUNT query. For infinite scroll prefer `Slice` (no count) or keyset
pagination - `OFFSET 50000` is a scan on every engine, and page 2 of a list that changed under you
silently skips rows. Always cap the page size with `spring.data.web.pageable.max-page-size`, or a
client will ask for `size=1000000`.
