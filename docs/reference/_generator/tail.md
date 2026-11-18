---

## By task

| I want to... | Use |
| --- | --- |
| declare a bean of my own class | `@Component` / `@Service` / `@Repository` / `@Controller` |
| declare a bean of someone else's class | `@Bean` in a `@Configuration` |
| ship beans from a library | `@AutoConfiguration` + the `.imports` file - **never** `@Component` |
| let the application override my library's bean | `@ConditionalOnMissingBean` |
| switch a feature on or off by property | `@ConditionalOnProperty(..., matchIfMissing = true)` |
| work with or without an optional dependency | `@ConditionalOnClass` + `ObjectProvider` injection |
| bind configuration safely | `@ConfigurationProperties` + `@Validated` on a record |
| fail startup on bad configuration | `@Validated` (+ a `FailureAnalyzer` for the message) |
| bind a custom property type | a `Converter` + `@ConfigurationPropertiesBinding`, declared `static` |
| validate a request body | `@Valid @RequestBody` (+ `@Valid` again on nested collections) |
| validate a path or query parameter | the constraint **plus** `@Validated` on the class |
| return 201 with a Location header | `ResponseEntity.created(uri)` |
| map an exception to a status in one place | `@RestControllerAdvice` + `@ExceptionHandler` |
| inject something custom into a handler | a `HandlerMethodArgumentResolver` + your own `@...` annotation |
| call an HTTP API with little ceremony | `@HttpExchange` interface + `HttpServiceProxyFactory` |
| classify HTTP failures myself | a plain `RestClient` with `exchange()` - not a declarative client |
| retry transient failures | `@Retryable(retryFor = ..., backoff = @Backoff(random = true))` |
| stop hammering a broken dependency | `@CircuitBreaker` (resilience4j) |
| stop one slow dependency eating every thread | `@Bulkhead` |
| run one transaction per use case | `@Transactional` on the service method |
| make a method refuse to run without a transaction | `@Transactional(propagation = MANDATORY)` |
| keep an audit row when the caller rolls back | `@Transactional(propagation = REQUIRES_NEW)` |
| roll back part of a transaction | `Propagation.NESTED` - and a **JDBC** transaction manager |
| roll back on a checked exception | `@Transactional(rollbackFor = ...)` |
| do something only after a successful commit | `@TransactionalEventListener(AFTER_COMMIT)` (+ `REQUIRES_NEW` to write) |
| fix N+1 on one aggregate | `@EntityGraph` or a fetch join |
| fix N+1 on a list of aggregates | `@BatchSize` |
| detect concurrent modification | `@Version` → map to HTTP 409 |
| serialise access to a hot row | `@Lock(PESSIMISTIC_WRITE)` + a lock-timeout `@QueryHint` |
| update many rows in one statement | `@Modifying` with **both** `flushAutomatically` and `clearAutomatically` |
| fill created/updated columns | `@CreatedDate`/`@LastModifiedDate` + `@EnableJpaAuditing` + `AuditorAware` |
| run migrations against a specific DataSource | `@FlywayDataSource` |
| cache a method result | `@Cacheable` - with a real TTL, and mind self-invocation |
| invalidate a cache only if the write committed | evict from an `AFTER_COMMIT` listener |
| run work off the request thread | `@Async` + a **bounded** executor + a `TaskDecorator` |
| run something periodically | `@Scheduled(fixedDelayString = ...)` + a multi-instance lock |
| add my own declarative behaviour | annotation + `@Aspect` + `@Around`, with an explicit `@Order` |
| expose internal state to operators | `@Endpoint` + `@ReadOperation` |
| report a dependency's state to Kubernetes | a `HealthIndicator` that reads the breaker, never calls the dependency |
| time a method | `@Observed` (metric **and** span) or `@Timed`, plus the aspect bean |
| test the whole application | `@SpringBootTest` |
| test only the HTTP layer | `@WebMvcTest` + `@MockitoBean` (+ `@ImportAutoConfiguration` for your starter) |
| test only persistence | `@DataJpaTest` (+ `@Import` of your auditing config) |
| test every branch of an auto-configuration | `ApplicationContextRunner` + `FilteredClassLoader` - no annotation needed |
| feed a runtime value into configuration | `@DynamicPropertySource` (a `static` method) |
| observe real commit behaviour in a test | **no** `@Transactional` on the test; clean up explicitly |

---

## See also

* [annotations.md](annotations.md) - the same ground with the reasoning and the traps, including the
  mental model for who processes an annotation, and the proxy-defeated cheat sheet.
* [../04-transactions.md](../04-transactions.md) - the propagation table in full.
* [../06-testing.md](../06-testing.md) - how the test scopes were chosen here.

## Regenerating this page

This page is generated, not hand-maintained - so it cannot quietly drift from the code:

```bash
mvn -q -pl order-service dependency:build-classpath -Dmdep.outputFile=target/cp.txt -DincludeScope=test
python docs/reference/_generator/generate.py order-service/target/cp.txt
```

What the generator does:

* resolves every documented package against the **real classpath** and runs `javap` on each
  candidate, so a package that does not exist - or a name that turns out to be a class rather than
  an annotation - **fails the run** instead of becoming a plausible-looking wrong table row;
* rebuilds the ambiguity table from whatever the classpath actually makes ambiguous;
* recomputes the *Used* column by scanning `src/**/*.java` with comments stripped.

Edit these, not the generated page:

| File | Holds |
| --- | --- |
| `_generator/annotations.psv` | `Name\|package\|one-line description` |
| `_generator/ambiguous.psv` | `Name\|package-to-prefer\|why it matters` |
| `_generator/tail.md` | the "By task", "See also" and this section, appended verbatim |
