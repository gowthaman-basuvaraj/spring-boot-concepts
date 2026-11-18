# 4. Transactions

Everything below is asserted by `TransactionPropagationTest` (18 tests) and explorable at runtime
through `POST /api/tx/scenarios/{name}`.

## Where the boundary belongs

At the **service**, one transaction per use case. Not in the controller - you would hold a database
connection while serialising JSON. Not in the repository - a transaction per repository call means
no atomicity at all.

Always `org.springframework.transaction.annotation.Transactional`, never
`jakarta.transaction.Transactional`: only the Spring one understands propagation, isolation,
`readOnly` and rollback rules.

## Propagation

| Mode | Existing transaction | No transaction | Use it for |
| --- | --- | --- | --- |
| `REQUIRED` (default) | join | start one | almost everything |
| `REQUIRES_NEW` | **suspend**, start a new physical transaction | start one | work that must survive the caller's rollback: audit rows, attempt counters, dead letters |
| `NESTED` | savepoint inside the same transaction | start one | "try this optional step; if it fails, keep the rest" |
| `MANDATORY` | join | **throw** `IllegalTransactionStateException` | a method that is a bug outside a transaction (stock reservation, outbox write) |
| `SUPPORTS` | join | run non-transactionally | cheap lookups you genuinely do not care about |
| `NOT_SUPPORTED` | **suspend**, run without one | run without one | a long report that must not hold the caller's locks |
| `NEVER` | **throw** | run without one | guarding genuinely non-transactional work |

### REQUIRES_NEW costs more than it looks

It consumes a **second connection** while the first is still held. Nested `REQUIRES_NEW` under load
is a classic pool-exhaustion deadlock. The new transaction also cannot see the suspended one's
uncommitted rows and may block on its locks - a self-deadlock that only shows up in production.

### NESTED does not work with JPA

`JpaTransactionManager` delegates savepoint support to the `JpaDialect`'s transaction-data object,
and Hibernate's `SessionTransactionData` does not implement `SavepointManager`. Even with
`nestedTransactionAllowed = true` **and** a DataSource bound, a NESTED call fails with:

```
NestedTransactionNotSupportedException: JpaDialect does not support savepoints
```

A permanent limitation, not a misconfiguration - and the source of the folklore that "NESTED does
not work in Spring". It works through `JdbcTransactionManager` (or Spring ORM's
`HibernateTransactionManager`) for work done over plain JDBC: see `SavepointDemoService`.

Declaring a second `TransactionManager` bean makes Boot's
`@ConditionalOnMissingBean(TransactionManager.class)` back off, so you must then declare the JPA one
yourself - and name it `transactionManager`, because that is the name `@Transactional` and Spring
Data resolve by default.

Savepoints are not free: each is state the database keeps, PostgreSQL degrades with thousands in one
transaction, and JTA/XA does not support them at all.

### The rollback-only trap

With `REQUIRED`, an inner failure marks the **shared** transaction rollback-only. Catching the
exception in the caller does not save you - the commit then fails with

```
UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only
```

and **nothing** is written. This is the most common "but I handled the exception!" bug. NESTED is
the fix when you genuinely want to continue; `REQUIRES_NEW` is the fix when the inner work is
independent.

## Rollback rules

| Thrown | Default behaviour |
| --- | --- |
| `RuntimeException`, `Error` | **rollback** |
| checked `Exception` | **COMMIT** |

That second row is the most surprising default in Spring (it comes from EJB). Either make domain
exceptions unchecked - which is why `InsufficientStockException extends RuntimeException` - or spell
out `@Transactional(rollbackFor = Exception.class)`. `noRollbackFor` is the inverse.

`TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` rolls back without throwing.
Prefer throwing: this API is easy to forget and invisible to callers.

## The proxy trap

Spring's transaction support is proxy-based, so `this.method()` **never** applies the annotation:

```java
@Transactional
public TxInfo selfInvocationBroken() {
    return requiresNewInternal("x");   // plain Java call - REQUIRES_NEW is IGNORED
}
```

The test asserts it: the transaction name is still the outer method's. Symptoms in the wild are
"my REQUIRES_NEW audit row disappeared", "`@Cacheable` does nothing", "`@Async` runs on the
caller's thread", "`@Retryable` never retries". One cause.

Fixes, best first: move the inner method to another bean; inject `@Lazy` self; use
`TransactionTemplate`; `AopContext.currentProxy()` with `exposeProxy = true`.

Also: private, static and final methods cannot be advised, and `@Transactional` on a non-public
method is silently ignored.

## `readOnly = true` does four distinct things

1. Hibernate switches the session to `FlushMode.MANUAL` - no dirty checking, no accidental UPDATE.
2. The JDBC connection is flagged read-only, which some drivers and proxies optimise.
3. In this project it is the routing key that selects the read replica.
4. It documents intent, so a reviewer can see at a glance that the class does not write.

## No remote calls inside a transaction

`OrderService.placeOrder` is transactional and does **not** call the payment gateway. The charge
happens in an `@TransactionalEventListener(AFTER_COMMIT)` handler. A 5-second gateway timeout inside
a transaction is a 5-second row lock plus a 5-second connection checkout - your pool is gone at 20
requests per second. The database is not a mutex for the internet.

### The AFTER_COMMIT write trap

At `AFTER_COMMIT` the transaction has committed but its `EntityManager` is **still bound to the
thread** (it is released in `afterCompletion`). A `REQUIRED` method called from there therefore
joins an already-committed, non-transactional session: the entity is mutated in memory and the
UPDATE is **silently lost**. You see an order that stays `NEW` while the logs show a successful
charge. Every database write from an AFTER_COMMIT hook needs `REQUIRES_NEW`.

## Listener semantics

| Annotation | Runs | Thread | Transaction |
| --- | --- | --- | --- |
| `@EventListener` | immediately, at publish time | publisher's | inside the publisher's transaction; throwing rolls it back |
| `@TransactionalEventListener(AFTER_COMMIT)` | after commit | publisher's | none active; writes need `REQUIRES_NEW` |
| `@Async @TransactionalEventListener` | after commit | pool thread | none; MDC/SecurityContext need a `TaskDecorator` |
| `@TransactionalEventListener(AFTER_ROLLBACK)` | on rollback | publisher's | none; do not try to "undo" anything |

If the event is published **outside** a transaction, an `AFTER_COMMIT` listener never runs at all
unless you set `fallbackExecution = true`. This bites hardest in tests.

`TransactionSynchronization` is the lower-level API the annotation is built on - register one when
you need a hook but not an event (clear a cache, release a distributed lock). Callback order is
`beforeCommit` → `afterCommit` → `afterCompletion`, asserted in the test.

## Transactional outbox

You cannot atomically commit a database transaction and publish to Kafka, and 2PC across a DB and a
broker is not worth the pain. So write the message into the **same** transaction as the business
change (`OutboxService`, propagation `MANDATORY` so it cannot accidentally get its own transaction),
and let a poller publish it and mark it sent (`OutboxPublisher`). At-least-once delivery, no lost
events, at the price of requiring idempotent consumers.

With several instances deployed, every instance runs every `@Scheduled` job. Use a lock (ShedLock, a
DB advisory lock, or `FOR UPDATE SKIP LOCKED` as the claim) or accept duplicates - which, for an
outbox with idempotent consumers, is fine. Also remember the default scheduler pool size is **one**:
set `spring.task.scheduling.pool.size`.

## Testing transactions

Do **not** annotate the test class `@Transactional`. A test wrapped in its own rolled-back
transaction cannot observe commit behaviour at all, and the usual convenience is exactly what makes
it useless. Clean up explicitly instead - see `TransactionPropagationTest`.

## Debugging

```yaml
logging.level:
  org.springframework.transaction.interceptor: TRACE   # created / joined / suspended / committed
  org.springframework.orm.jpa.JpaTransactionManager: DEBUG
```

In code, `TransactionSynchronizationManager` tells you the truth: `isActualTransactionActive()`,
`getCurrentTransactionName()`, `isCurrentTransactionReadOnly()`. `TxInfo.capture()` wraps all three,
and `GET /api/tx/propagation` prints them for every propagation mode.
