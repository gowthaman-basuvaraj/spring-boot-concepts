# springboot-learn

A deliberately opinionated, fully working Spring Boot 3.5 / Java 17 project built as a refresher for
engineers who have used Spring Boot before and want the *senior* material in one place: how the
framework is extended, how persistence and transactions actually behave, and how to call an
unreliable HTTP dependency without taking your own service down.

Every claim in the comments is backed by code that runs, and the surprising ones are backed by a
test. **73 tests, clean build.**

```
springboot-learn
├── platform-starter     an in-house auto-configuration library (framework customization)
└── order-service        the application (REST, JPA, transactions, resilient HTTP)
```

## Run it

```bash
mvn clean install          # build + run all tests
cd order-service && mvn spring-boot:run
```

The `dev` profile is active by default: H2 in-memory, Flyway migrations, verbose logging, actuator
fully exposed, and a built-in flaky "payment gateway" stub so the retry machinery can be driven from
a terminal. On startup the log prints a guided tour of the endpoints.

Against a real database:

```bash
docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=orders -e POSTGRES_DB=orders postgres:16
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

## The five-minute tour

```bash
# place an order (the stub authorises it)
curl -X POST localhost:8080/stub/payments/mode?value=ok
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' -H 'X-Actor: alice' \
  -d '{"customerEmail":"a@b.com","currency":"EUR",
       "lines":[{"sku":"SKU-LAPTOP","quantity":2,"unitPrice":999.99}]}'

# watch retry with exponential backoff + jitter (stub fails twice, then succeeds)
curl -X POST 'localhost:8080/stub/payments/mode?value=flaky'
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerEmail":"a@b.com","currency":"EUR","lines":[{"sku":"SKU-MOUSE","quantity":1,"unitPrice":25.50}]}'

# open the circuit breaker, then watch health report DEGRADED
curl -X POST 'localhost:8080/stub/payments/mode?value=down'
for i in $(seq 6); do curl -s -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  -d '{"customerEmail":"a@b.com","currency":"EUR","lines":[{"sku":"SKU-MOUSE","quantity":1,"unitPrice":10.00}]}' >/dev/null; done
curl localhost:8080/actuator/health/paymentGateway

# one product, fetched five ways (RestTemplate / RestClient / WebClient / @HttpExchange / Feign)
curl localhost:8080/api/clients/SKU-LAPTOP

# why WebClient exists: concurrent fan-out vs sequential blocking calls
curl -X POST 'localhost:8080/stub/catalog/latency?millis=300'
curl 'localhost:8080/api/clients/fan-out?skus=SKU-LAPTOP,SKU-MOUSE,SKU-DOCK,SKU-LAPTOP,SKU-MOUSE,SKU-DOCK'

# the transaction laboratory
curl localhost:8080/api/tx/propagation
curl -X POST localhost:8080/api/tx/scenarios/nested-isolated
curl -X POST localhost:8080/api/tx/scenarios/checked-commits
curl -X POST localhost:8080/api/tx/scenarios/self-invocation-broken
```

## Deep dives

Start at [docs/README.md](docs/README.md) for the index and a one-hour reading order.

### Guides

| Guide | What it covers |
| --- | --- |
| [docs/01-basics.md](docs/01-basics.md) | What `@SpringBootApplication` really does, configuration precedence, DTOs, validation, ProblemDetail |
| [docs/02-customization.md](docs/02-customization.md) | Auto-configuration, starters, conditions, every extension point and when to reach for it |
| [docs/03-database.md](docs/03-database.md) | Pools, routing read replicas, Flyway, JPA mapping decisions, locking, N+1, JdbcClient |
| [docs/04-transactions.md](docs/04-transactions.md) | Propagation table, rollback rules, savepoints, proxy traps, the outbox pattern |
| [docs/05-http-resilience.md](docs/05-http-resilience.md) | Timeouts, failure classification, backoff + jitter, idempotency, circuit breakers, bulkheads |
| [docs/06-testing.md](docs/06-testing.md) | Slice tests, `ApplicationContextRunner`, context caching, the tests in this repo |
| [docs/08-http-clients.md](docs/08-http-clients.md) | All five HTTP client styles side by side: choosing, where each hides its timeouts, and how differently each one fails |
| [docs/09-extension-points.md](docs/09-extension-points.md) | The `BeanPostProcessor` family, writing a proxy-returning BPP, your own `@Enable*`, and the servlet filter chain |
| [docs/07-production-checklist.md](docs/07-production-checklist.md) | The list to run through before a service ships |

### Reference

| Page | What it covers |
| --- | --- |
| [docs/reference/annotations.md](docs/reference/annotations.md) | **Read it.** The mental model for *who processes an annotation* and the only three ways one can silently do nothing, then every family with its traps, a look-alikes table, and the proxy-defeated cheat sheet |
| [docs/reference/annotations-index.md](docs/reference/annotations-index.md) | **Look it up.** 258 annotations A-Z with their exact package and whether this project uses them; the 24 simple names that are ambiguous on this classpath (where the wrong auto-import compiles and does nothing); and a task-to-annotation finder. [Generated](docs/reference/_generator/README.md) from the real classpath, so a wrong package fails the build of the page rather than becoming a plausible-looking wrong row |

## Bugs this project found in itself

These were real defects written, observed and then fixed while building this repo. Each one is now
a comment next to the fix and, where it matters, a test. They are the most valuable part of the
project, because every one of them fails *silently*.

1. **`auto-commit` left at Hikari's default of `true`** while
   `hibernate.connection.provider_disables_autocommit=true` was set. Hibernate then never disables
   autocommit, so every statement commits as it executes and **`@Transactional` rollback becomes a
   no-op**. The transaction tests caught it; nothing else would have, until production.
   → `application.yml`
2. **A write inside an `@TransactionalEventListener(AFTER_COMMIT)` handler was silently discarded.**
   The original transaction's `EntityManager` is still bound to the thread at that point, so a
   `REQUIRED` method joins an already-committed, non-transactional session. The order stayed `NEW`
   while the log showed a successful charge. Fix: `REQUIRES_NEW`.
   → `OrderService#applyPaymentResult`
3. **`JpaTransactionManager` had no `DataSource`**, which is Boot's default. JPA and `JdbcClient`
   therefore used two different connections inside one `@Transactional` method - invisible until a
   read missed an unflushed write, or the pool deadlocked.
   → `JpaConfig#transactionManager`
4. **`Propagation.NESTED` does not work with `JpaTransactionManager` + Hibernate**, at all, ever:
   Hibernate's `JpaDialect` cannot expose a `SavepointManager`. The honest fix is a second,
   JDBC-only transaction manager.
   → `JpaConfig#jdbcTransactionManager`, `SavepointDemoService`
5. **A too-narrow `@Recover` method turned a 402 decline into `ExhaustedRetryException: Cannot
   locate recovery method`**, which bypassed the circuit breaker's fallback and lost the business
   outcome. Once a class declares any `@Recover`, non-retryable exceptions look for one too.
   → `PaymentGatewayCaller#recover`
6. **A `@ConfigurationPropertiesBinding` converter declared next to a bean that consumes bound
   properties** caused `BeanCurrentlyInCreationException`, surfacing as the baffling
   "Could not bind properties to 'ServerProperties'".
   → `PlatformConversionAutoConfiguration`
7. **Partial property binding on records**: setting one sub-property constructs the nested record
   with `0`/`null` for the rest, so a `@Min(1)` field fails validation. Defaults belong in each
   nested record's compact constructor.
   → `PlatformProperties`
8. **`@EnableJpaAuditing` on a fat `@Configuration` class** is skipped by `@DataJpaTest`, so every
   insert failed with `NULL not allowed for column "CREATED_AT"`. One concern per configuration
   class, so slices can import it.
   → `JpaAuditingConfig`
9. **A test `@RestController` inside the application's scan root**, also registered by an `@Bean`
   method, produced `Ambiguous mapping`. `@Import` of a configuration class also registers its
   nested components.
   → `com.example.testsupport.FlakyGatewayStub`
10. **Two test classes picked "a free port" independently** before either server bound, got the same
    one, and the suite failed with `PortInUseException` while passing class-by-class.
    → `com.example.testsupport.TestPorts`
11. **Importing the Spring Cloud BOM silently overrode a pinned dependency version.** The BOM
    manages resilience4j, so `resilience4j-spring-boot3` stayed at the pinned 2.3.0 while every
    other resilience4j module dropped to the BOM's 2.2.0, and startup failed with
    `NoClassDefFoundError: .../RxJava3FallbackDecorator`. The fix is not more pins - it is letting
    **one** source own a library's whole module set.
    → `pom.xml`, explained in [docs/08-http-clients.md](docs/08-http-clients.md)

## What is deliberately out of scope

Spring Security, messaging (Kafka/Rabbit), a fully reactive WebFlux stack (WebClient is here, a
reactive server is not), caching topologies beyond a local Caffeine cache, and Testcontainers. Each
is large enough to deserve its own project, and leaving them out keeps every line here readable. The
places where they would plug in are marked in the code.
