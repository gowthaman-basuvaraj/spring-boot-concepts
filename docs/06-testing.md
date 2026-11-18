# 6. Testing

54 tests, four scopes. The point is not coverage, it is **feedback speed and failure locality**:
when a test fails you should know which layer is broken before reading the stack trace.

| Test | Scope | Starts | Proves |
| --- | --- | --- | --- |
| `PlatformAutoConfigurationTest` | `ApplicationContextRunner` | nothing | every `@Conditional` branch of the starter |
| `OrderControllerWebMvcTest` | `@WebMvcTest` | MVC only | status codes, headers, validation, problem details |
| `OrderRepositoryTest` | `@DataJpaTest` | JPA + Flyway | queries, projections, locking, bulk updates, auditing |
| `TransactionPropagationTest` | `@SpringBootTest` | everything | propagation, rollback rules, savepoints, proxy traps |
| `PaymentRetryIntegrationTest` | `@SpringBootTest(DEFINED_PORT)` | everything + a stub server | retry, backoff, classification, breaker |
| `OrderApiIntegrationTest` | `@SpringBootTest(DEFINED_PORT)` | everything | the whole flow, end to end over HTTP |
| `OrderServiceApplicationTests` | `@SpringBootTest` | everything | the context, the schema, the wiring |

## `ApplicationContextRunner` - how to test a starter

```java
new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(PlatformAutoConfiguration.class))
    .withPropertyValues("platform.audit.enabled=false")
    .run(context -> assertThat(context).doesNotHaveBean(AuditAspect.class));
```

A throwaway context per assertion, milliseconds each. It is the only sane way to cover the
combinatorics of conditions: property on/off, bean present/absent, class on/off the classpath.
`FilteredClassLoader` simulates a missing dependency, which is how you *prove* `@ConditionalOnClass`
works rather than hoping it does. Ship a starter without these tests and you will break the first
application whose classpath differs from yours.

## Slice tests

`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`, `@RestClientTest` each apply a fixed **allow-list** of
auto-configurations plus a type-exclude filter that skips regular `@Component`/`@Configuration`
beans. Two consequences that look like bugs:

* **Your own starter is switched off.** The correlation filter does not run in `@WebMvcTest` until
  you add `@ImportAutoConfiguration(PlatformAutoConfiguration.class)`.
* **Your `@Configuration` classes are not loaded.** `@EnableJpaAuditing` living in a fat config
  class means `@DataJpaTest` fails with `NULL not allowed for column "CREATED_AT"`. The fix is
  design, not configuration: one concern per configuration class (`JpaAuditingConfig`) so a slice
  can import it on its own.

`@DataJpaTest` also wraps each method in a transaction that is rolled back afterwards. Convenient -
but it means Hibernate may never flush, so `flush()` + `clear()` are not optional if you want your
assertions to round-trip through SQL, and you cannot observe commit behaviour at all.

## Mocking

`@MockitoBean` / `@MockitoSpyBean` (Boot 3.4+) replace the older `@MockBean` / `@SpyBean`. Both
**replace a bean definition in the context**, which means each distinct set of mocks produces a
different context cache key.

## Context caching - the reason your suite is slow

Spring caches application contexts by their full configuration key: classes, profiles, property
sources, customizers, mock definitions. Change any of them and you pay for another context. So:

* keep `@ActiveProfiles` and `@TestPropertySource` identical across as many tests as possible;
* prefer a shared `@SpringBootTest` base over per-class property tweaks;
* `@DirtiesContext` is a last resort - it evicts the cache entry and everything after it pays.

Contexts also stay **alive** for the whole JVM, which is why resources they hold (ports, in-memory
databases, thread pools) leak across test classes.

## Tests that pass alone and fail in the suite

Always shared mutable global state. In this repo it was a **TCP port**: two classes needed the port
before the context existed (a bean captures its base URL in its constructor, so `@LocalServerPort`
is injected too late), both ran their static initialiser during JUnit discovery, and the OS handed
out the same free port twice. `TestPorts` remembers what it has issued.

Same class of bug: static fields, files in a temp directory, a shared database table, a system
property, or a cached context whose state one test mutates.

## Testing HTTP clients

| Tool | Catches | Misses |
| --- | --- | --- |
| `MockRestServiceServer` | request shape, headers, body | **replaces the transport**, so it never catches a misconfigured timeout, request factory or interceptor |
| a stub controller on the test's own server (used here) | the real client end to end, with real timeouts | faults the servlet API cannot express |
| WireMock | connection resets mid-body, malformed chunked encoding, TLS failures | needs an extra dependency and port |

For retry assertions you need a stub that **records every attempt** - that is what makes
"exactly 3 calls, all with the same idempotency key" assertable.

## `@DynamicPropertySource`

The mechanism for pointing configuration at a value only known at runtime (a container's mapped
port, a reserved port, a temp directory). It runs **before** the context is created, which is
exactly why it works where field injection does not. The same mechanism Testcontainers uses.

## What is NOT tested here, and why

Testcontainers would be the right call for a real project: the same PostgreSQL version as
production, which catches dialect, collation and locking behaviours H2 silently differs on. It is
omitted only because it would require Docker to build this repo. The seam is ready - swap the
`app.datasource.*` properties through `@DynamicPropertySource` and nothing else changes.
