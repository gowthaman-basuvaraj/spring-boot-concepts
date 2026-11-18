# Reference: annotations

Every annotation this project uses, plus the ones you will meet next to them. Each entry gives the
**package** (because half of all annotation bugs are importing the wrong one), what it actually
does, the **trap**, and the file here that uses it.

Versions: Spring Boot 3.5.16, Spring Framework 6.2.19, Jakarta EE 10, Hibernate 6.6.

> Looking for a quick lookup rather than an explanation? See
> [annotations-index.md](annotations-index.md) - 256 annotations A-Z with their exact package, and
> the list of names that are ambiguous on this classpath.

---

## 0. The mental model

An annotation on its own does nothing. Something has to **find** it and **act** on it, and there are
only five such mechanisms in Spring:

| Mechanism | Finds | Acts at |
| --- | --- | --- |
| Component scanning | `@Component` and its stereotypes | context startup |
| `@Configuration` class parsing | `@Bean`, `@Import`, `@PropertySource`, `@Conditional` | context startup |
| Auto-configuration | classes listed in `AutoConfiguration.imports` | context startup, after user config |
| `BeanPostProcessor` | `@Autowired`, `@Value`, `@PostConstruct`, `@ConfigurationProperties` | per bean, during creation |
| **AOP proxies** | `@Transactional`, `@Cacheable`, `@Async`, `@Retryable`, `@Observed`, your own | per **external** method call |

That last row causes most of the confusion in Spring, so state it precisely:

> Spring wraps the bean in a proxy. The proxy intercepts calls that arrive **from outside**. A call
> from one method of the bean to another (`this.other()`) goes straight to the target object and is
> **never intercepted**. The annotation is not "ignored" - it was never consulted.

So there are exactly three ways an annotation silently does nothing:

1. **Nobody found it.** A `@Component` in a package that is not scanned; a library class that needs
   auto-configuration instead; `RetentionPolicy.CLASS` on an annotation read reflectively.
2. **The proxy was bypassed.** Self-invocation; `new MyService()` instead of injection; a `private`
   method; a call from a constructor (the proxy is not ready yet).
3. **The feature is switched off.** Missing `@EnableRetry`/`@EnableCaching`/`@EnableAsync`; a slice
   test that excluded the auto-configuration; a `@ConditionalOnProperty` that did not match.

When something does not work, walk those three in order. It is one of the three.

### Proving which one

```bash
--debug                       # CONDITIONS EVALUATION REPORT: why each auto-config did or did not apply
/actuator/conditions          # the same report, as JSON
/actuator/beans               # is the bean there at all, and is it a $$SpringCGLIB$$ proxy?
/actuator/configprops         # did the property actually bind?
/actuator/mappings            # which handler method owns this URL
```

In a debugger, `AopUtils.isAopProxy(bean)` and `AopProxyUtils.ultimateTargetClass(bean)` answer
"am I holding a proxy or the raw object?".

---

## 1. Bootstrap

### `@SpringBootApplication`
`org.springframework.boot.autoconfigure` · TYPE

Three annotations in one; see [01-basics](../01-basics.md) for the table. The part people forget:
`@ComponentScan` has no `basePackages`, so it scans **the annotated class's package and below**.
Put the application class in `com.example` and you scan the world; put it in
`com.example.orders.config` and you scan almost nothing.

**Trap:** test classes are on the classpath that component scanning walks. A `@RestController` in
`src/test/java` under the scan root is registered in **every** `@SpringBootTest` context. Boot's
`TypeExcludeFilter` excludes `@TestConfiguration` classes but not arbitrary test components - which
is why `FlakyGatewayStub` lives in `com.example.testsupport`, outside the scan root.

**Here:** `OrderServiceApplication`

### `@SpringBootConfiguration`
Marks **the** configuration root. There must be exactly one reachable from a test, and tests find it
by walking **up** the package tree - the reason your test package must mirror your main package.

### `@EnableAutoConfiguration`
Imports every class listed in every
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` on the classpath,
then filters them through their conditions. `exclude`/`excludeName` switch individual ones off; so
does `spring.autoconfigure.exclude`.

### `@AutoConfiguration`
`org.springframework.boot.autoconfigure` · TYPE

A `@Configuration(proxyBeanMethods = false)` that is processed **after** all user configuration and
can declare ordering: `before`, `after`, `beforeName`, `afterName` (the `*Name` variants take
strings, for classes that may be absent).

**Trap 1:** it is only active if listed in the `.imports` file. Adding the annotation and forgetting
the file is the single most common starter bug - and it fails **silently**, because nothing refers
to the class.

**Trap 2:** ordering is not cosmetic. A `RestClientCustomizer` registered after Boot has built its
`RestClient.Builder` is never applied, which is why `PlatformHttpAutoConfiguration` says
`@AutoConfiguration(before = RestClientAutoConfiguration.class)`.

**Here:** `PlatformAutoConfiguration`, `PlatformHttpAutoConfiguration`,
`PlatformConversionAutoConfiguration`

### `@Configuration`
`org.springframework.context.annotation` · TYPE

`proxyBeanMethods = true` (the default) makes the class a CGLIB proxy so that calling one `@Bean`
method from another returns the **singleton**. `proxyBeanMethods = false` ("lite mode") skips the
proxy: faster startup, no CGLIB, but an inter-bean method call creates a **new object** every time.

**Trap:** lite mode plus an `AsyncConfigurer`-style interface that returns a `@Bean` method result
silently creates a second, unmanaged instance. `AsyncConfig` documents exactly that case and avoids
it by letting Spring look the bean up instead.

Prefer `proxyBeanMethods = false` and pass dependencies as method **parameters**.

### `@Import` / `@ImportAutoConfiguration`
`@Import` pulls in a configuration class, an `ImportSelector`, or an `ImportBeanDefinitionRegistrar`.
`@ImportAutoConfiguration` applies specific **auto**-configurations - the escape hatch for slice
tests, which otherwise allow-list only Boot's own.

**Trap:** `@Import` of a configuration class **also registers its nested component classes**. Import
a `@TestConfiguration` whose nested class is a `@RestController` *and* declare an `@Bean` for it, and
you get two registrations and `IllegalStateException: Ambiguous mapping`.

**Here:** `OrderControllerWebMvcTest` (`@ImportAutoConfiguration`), `OrderRepositoryTest`
(`@Import(JpaAuditingConfig.class)`)

### `@Enable*`
`@EnableRetry`, `@EnableCaching`, `@EnableAsync`, `@EnableScheduling`, `@EnableJpaAuditing`,
`@EnableJpaRepositories`, `@EnableTransactionManagement`, `@EnableWebSecurity`, `@EnableWebMvc`...

Each is `@Import` with a nicer name: it registers the infrastructure (usually a post-processor or an
advisor) that makes a family of annotations work. **No `@Enable`, no behaviour** - `@Retryable`
without `@EnableRetry` is a comment.

Boot auto-configures the equivalents of `@EnableTransactionManagement`,
`@EnableJpaRepositories`, `@EnableConfigurationProperties`
(`ConfigurationPropertiesAutoConfiguration`) and MVC, so you rarely write those. You **do** write
`@EnableRetry`, `@EnableCaching`, `@EnableAsync`, `@EnableScheduling` and `@EnableJpaAuditing`.

**Trap:** two of them replace Boot's defaults rather than extending them.
`@EnableWebMvc` disables `WebMvcAutoConfiguration` entirely (never use it in Boot - implement
`WebMvcConfigurer`). `@EnableJpaRepositories`/`@EntityScan` **replace** the auto-configured package
set, so once you add one you must list every package.

**Here:** `OrderServiceApplication`, `JpaAuditingConfig`

---

## 2. Beans and injection

### `@Component` and its stereotypes
`@Component` · `@Service` · `@Repository` · `@Controller` · `@RestController` · `@Configuration`

All are `@Component` underneath; the others exist for intent and for one piece of real behaviour:

* **`@Repository`** adds exception translation - a `PersistenceExceptionTranslationPostProcessor`
  converts `SQLException`/`PersistenceException` into Spring's `DataAccessException` hierarchy
  (`OptimisticLockingFailureException`, `DuplicateKeyException`, ...). Spring Data repositories get
  this anyway; a hand-written DAO does not, so annotate it. **Here:** `OrderAnalyticsDao`.
* **`@RestController`** = `@Controller` + `@ResponseBody` on every method.
* **`@Service`** and `@Controller` are purely semantic.

**Trap:** `@Component` on a class in a library is never found - the consuming application scans its
own package only. Library beans must come from auto-configuration. `StringToMaskedValueConverter`
originally carried `@Component` and was silently absent until it became an `@Bean`.

### `@Bean`
`org.springframework.context.annotation` · METHOD

The bean name is the **method name** - renaming a `@Bean` method is an API change for anyone using
`@Qualifier("oldName")` or `@ConditionalOnMissingBean(name = ...)`. Use `name =` to decouple them,
and `destroyMethod` / `initMethod` for lifecycle on types you do not own (`@Bean(destroyMethod = "close")`
on the Hikari pools).

**Trap:** a `@Bean` method that returns a `BeanPostProcessor` or `BeanFactoryPostProcessor` must be
`static`. Otherwise the enclosing configuration class has to be instantiated before post-processing
is ready, its own dependencies escape post-processing, and Boot logs the mouthful
*"is not eligible for getting processed by all BeanPostProcessors"*. The same applies to a
`@ConfigurationPropertiesBinding` converter - it is needed in order to bind properties, so it cannot
live next to a bean that consumes bound properties.

**Here:** `PlatformAutoConfiguration#startupTimingBeanPostProcessor` (static),
`PlatformConversionAutoConfiguration` (static, separate class)

### `@Autowired`
`org.springframework.beans.factory.annotation` · CONSTRUCTOR, METHOD, FIELD, PARAMETER

**Use constructor injection.** It makes dependencies final and explicit, makes the class
unit-testable with `new`, and turns a missing dependency into a startup failure instead of an NPE.
Since Spring 4.3, `@Autowired` on a **single** constructor is implicit - so idiomatic modern code has
no `@Autowired` at all.

Field injection additionally hides circular dependencies (constructor injection fails loudly on
them) and prevents `final`.

`required = false`, `Optional<T>`, and `ObjectProvider<T>` express "may be absent";
`ObjectProvider` is the best of the three because it also handles lazy and multiple candidates
(`getIfAvailable()`, `orderedStream()`). **Here:** `AuditAspect` takes
`ObjectProvider<MeterRegistry>` so the starter works without micrometer.

A `List<T>`/`Map<String, T>` parameter injects **all** beans of that type - how
`WebMvcConfigurer`s, `MeterRegistryCustomizer`s and `RestClientCustomizer`s compose.

### `@Qualifier`, `@Primary`, `@Fallback`
Disambiguation when several beans share a type. `@Primary` marks the default;
`@Qualifier("beanName")` picks explicitly; `@Fallback` (Spring 6.2) is the inverse of `@Primary` -
"use me only if nothing else matches".

**Trap:** `@Transactional` with no qualifier resolves the `@Primary` `TransactionManager`, or the one
**named `transactionManager`**. Declaring a second one (as `JpaConfig` does for savepoints) makes
Boot's `@ConditionalOnMissingBean(TransactionManager.class)` back off, so you must declare the JPA
one yourself - and name it `transactionManager`, or every unqualified `@Transactional` breaks.

**Here:** `JpaConfig` (`@Primary` + the `jdbcTransactionManager` sibling),
`TransactionBoundaryDemoService` (`@Qualifier("transactionManager")`)

### `@Lazy`
Defers creation until first use. Its honest use is **breaking a cycle you cannot design away**:
`TransactionBoundaryDemoService` injects `@Lazy` **itself** to get its own proxy, which is the
explicit fix for self-invocation.

**Trap:** `@Lazy` as a startup-time speedup hides failures until the first request. Prefer
`spring.main.lazy-initialization=true` for local development only.

### `@Scope`
`singleton` (default), `prototype`, `request`, `session`, `application`.

**Trap:** injecting a `prototype` into a `singleton` gives you **one** instance forever - the
singleton is wired once. You need `ObjectProvider`/`@Lookup`, or `proxyMode = SCOPED_PROXY`. Same
for request scope.

### `@PostConstruct` / `@PreDestroy`
`jakarta.annotation` (not Spring) · METHOD

Per-bean initialisation. Runs **before the context is refreshed**, so do not touch other beans'
behaviour, start threads, or publish events here - use `ApplicationRunner` or
`ApplicationReadyEvent`. See the startup-hook table in [01-basics](../01-basics.md).

### `@Profile`
`org.springframework.context.annotation` · TYPE, METHOD

Registers the bean only for the named profile(s). Supports `!dev`, `dev | test`, `prod & eu`.

**Trap:** profiles are a blunt instrument - a bean that exists in one environment and not another is
a class of bug that only appears in that environment. Prefer `@ConditionalOnProperty` with a
documented default, and reserve `@Profile` for genuinely environment-shaped things (an in-memory
stub, a seeder).

**Here:** `StartupTour`, `PaymentStubController` - both `@Profile("dev")`, because a data seeder and
a fake payment gateway in a production artifact are liabilities.

### `@Value`
`org.springframework.beans.factory.annotation`

`@Value("${app.name:default}")` for a single property, `@Value("#{...}")` for SpEL.

**Trap:** no validation, no IDE completion, no `/actuator/configprops`, fails at first use rather
than at startup, and unmockable without a context. Use `@ConfigurationProperties` for anything that
is not a one-off. `@Value` is also **not** relaxed-binding-aware in the same way: the exact key is
required.

---

## 3. Conditions

All in `org.springframework.boot.autoconfigure.condition`, all evaluated once at startup, all
reportable via `--debug` / `/actuator/conditions`.

| Annotation | True when | Notes |
| --- | --- | --- |
| `@ConditionalOnClass` | the type is on the classpath | the referenced class must only appear in the **annotation**, never in a field or method signature of the same class - or loading it throws `NoClassDefFoundError` before the condition can save you. Use the `name = "..."` string form, or a nested configuration class. |
| `@ConditionalOnMissingClass` | the type is absent | |
| `@ConditionalOnBean` | a bean of that type/name exists | ordering-sensitive: only safe **inside** auto-configuration, where ordering is defined |
| `@ConditionalOnMissingBean` | no such bean exists | the single most important annotation in a starter - it is what lets the application win |
| `@ConditionalOnProperty` | property matches | `havingValue` + `matchIfMissing`. `matchIfMissing = true` means "on unless told otherwise" |
| `@ConditionalOnWebApplication` | `type = SERVLET` / `REACTIVE` / `ANY` | keeps servlet-only beans out of plain contexts |
| `@ConditionalOnResource` | a classpath/file resource exists | |
| `@ConditionalOnExpression` | SpEL is true | last resort; unreportable and hard to test |
| `@ConditionalOnThreading` | platform vs virtual threads | Boot 3.2+ |
| `@Conditional(MyCondition.class)` | your `Condition#matches` returns true | the general form all the others build on |

### The ordering trap

`@ConditionalOnBean` and `@ConditionalOnMissingBean` are evaluated **when the containing
configuration class is processed**, against the beans registered so far. In a user
`@Configuration` class, processing order is undefined, so the condition is a coin flip. In an
`@AutoConfiguration` class, order is declared (`before`/`after`) and user configuration is always
processed first - which is the whole reason auto-configuration is a separate mechanism.

**Rule:** `@ConditionalOnMissingBean` belongs in auto-configuration. In application code, use
`@Primary` or explicit qualifiers instead.

### Testing conditions

This is what `ApplicationContextRunner` plus `FilteredClassLoader` are for:

```java
runner.withClassLoader(new FilteredClassLoader(
        "org.springframework.boot.actuate.endpoint.annotation.Endpoint"))
      .run(context -> assertThat(context).hasNotFailed()
                                         .doesNotHaveBean(PlatformEndpoint.class));
```

**Here:** `PlatformAutoConfigurationTest` - ten tests, one per branch.

---

## 4. Configuration properties

### `@ConfigurationProperties`
`org.springframework.boot.context.properties` · TYPE, METHOD

Binds a whole prefix onto a type. Relaxed binding means `app.payments.base-url`,
`app.payments.baseUrl` and `APP_PAYMENTS_BASEURL` all reach the same component.

Three ways to register the type:
1. `@ConfigurationPropertiesScan` on the application class - the modern default, nothing else needed;
2. `@EnableConfigurationProperties(Foo.class)` - what a **library** must use, because the application
   does not scan it;
3. `@Component` on the properties class - works, but couples the type to scanning.

On a **`@Bean` method** it binds the returned object instead, which is how you get two
`DataSourceProperties` from two different prefixes.

**Here:** `AppProperties` (scanned), `PlatformProperties` (`@EnableConfigurationProperties`),
`DataSourceConfig#primaryDataSourceProperties` (method form)

### `@ConstructorBinding`
`org.springframework.boot.context.properties.bind` · **CONSTRUCTOR only, since Boot 3.0**

Inferred for a type with a single non-default constructor - including every record - so you almost
never write it. Needed only to pick between several constructors. If it is on the **type**, you are
reading Boot 2.x documentation.

Constructor binding gives immutability and lets records carry defaults. Setter binding is the
alternative and is required for some edge cases (binding into a pre-existing object, as with
`DataSourceProperties`).

**Trap - partial binding on records:** setting ONE sub-property constructs the nested record with
`0`/`null` for every other component. A `@Min(1) long` field then fails validation and the context
does not start, with a message that does not obviously point at the property you set. Defaults
belong in **each nested record's compact constructor**, not only the outer one. This project shipped
that bug; `PlatformAutoConfigurationTest#auditCanBeDisabled` caught it.

**Here:** `PlatformProperties` - every nested record defaults its own components

### `@Validated`
`org.springframework.validation.annotation` · TYPE, METHOD

Two unrelated jobs, which is why it confuses people:

1. **On a `@ConfigurationProperties` type** - validate at bind time and fail startup with a
   `BindValidationException` naming the offending property. Requires a JSR-380 implementation on the
   classpath.
2. **On any bean** - enable method validation, so constraints on method **parameters** and return
   values are enforced by a proxy (`MethodValidationPostProcessor`). This is what makes
   `@PathVariable @Email String email` actually validate; without it those annotations are decoration.

Note the different failure types: binding gives `BindValidationException`, method validation gives
`ConstraintViolationException`, a `@Valid @RequestBody` gives `MethodArgumentNotValidException`. All
three are mapped in `ApiExceptionHandler`.

**Here:** `AppProperties`, `PlatformProperties` (job 1); `OrderController` (job 2)

### `@ConfigurationPropertiesBinding`
`org.springframework.boot.context.properties` · on a `Converter` bean

Registers the converter with the **binder** specifically. Without it the converter is only visible to
MVC/web conversion and binding fails with *"Failed to convert property value of type
java.lang.String"*.

**Trap:** declare it `static`, in a configuration class that does not consume bound properties.
Otherwise: `BeanCurrentlyInCreationException`, surfacing as the baffling *"Could not bind properties
to 'ServerProperties'"*. The converter is needed to bind, and binding is needed to build the class
that declares it.

**Here:** `StringToMaskedValueConverter` + `PlatformConversionAutoConfiguration`

### Also worth knowing
* `@NestedConfigurationProperty` - tells the metadata generator that a field is a nested group rather
  than a single value. Needed when the nested type is **not** an inner class.
* `@DurationUnit` / `@DataSizeUnit` / `@PeriodUnit` - supply the unit for a bare number, so `5` means
  `5s`. `Duration` already parses `250ms`, `5s`, `2m`.
* `@DefaultValue` (`...bind.DefaultValue`) - a default for a constructor-bound parameter.
* `@Name` - bind a differently-named property onto a parameter.
* `@Delimiter` - change the split character for collection binding.
* Bounds like "at least 1ms" need Hibernate Validator's `@DurationMin`/`@DurationMax`; plain Boot has
  no such annotation, which is why `PlatformAutoConfiguration` validates timeout ordering in its
  constructor and pairs it with a `FailureAnalyzer`.

---

## 5. Web / MVC

### Mapping
`@RequestMapping` and its shortcuts `@GetMapping` `@PostMapping` `@PutMapping` `@PatchMapping`
`@DeleteMapping` (`org.springframework.web.bind.annotation`).

Narrow with `params`, `headers`, `consumes`, `produces`. Two mappings that can match the same request
fail **at startup** with `Ambiguous mapping` - a good failure, but one that also catches duplicate
bean registration (see `@Import` above).

**Trap:** a class-level `@RequestMapping("/api/orders")` plus a method-level `@GetMapping("/")` is
`/api/orders/` with a trailing slash. Since Spring 6 trailing-slash matching is **off** by default,
so `/api/orders` and `/api/orders/` are different URLs and the old lenient behaviour is gone.

### Request data

| Annotation | Source | Notes |
| --- | --- | --- |
| `@PathVariable` | URI template | name required if parameter names are not compiled in (`-parameters`) |
| `@RequestParam` | query string or form | `required`, `defaultValue`. Note `defaultValue` implies `required = false` |
| `@RequestBody` | body, via an `HttpMessageConverter` | pair with `@Valid` |
| `@RequestHeader` | header | |
| `@CookieValue` | cookie | |
| `@RequestPart` | multipart | |
| `@ModelAttribute` | bound from many request params | |
| `@SessionAttribute`, `@RequestAttribute` | servlet attributes | |

`Pageable` and `Sort` are resolved by Spring Data's argument resolver, configurable with
`@PageableDefault`/`@SortDefault` and `spring.data.web.pageable.*`.

**Trap:** always cap the page size (`@PageableDefault(size = 20)` **and**
`spring.data.web.pageable.max-page-size`), or a client will ask for `size=1000000`.

**Here:** `OrderController`

### Custom parameters
A `HandlerMethodArgumentResolver` registered via `WebMvcConfigurer#addArgumentResolvers` lets you
invent your own parameter annotation. This is the mechanism behind `@AuthenticationPrincipal` and
`@PageableDefault`.

**Trap:** your annotation needs `RetentionPolicy.RUNTIME` and `ElementType.PARAMETER`, and
`supportsParameter` is called for every parameter of every handler - keep it trivial.

**Here:** `@CurrentActor` + `ActorArgumentResolver`

### Responses
* `ResponseEntity<T>` - full control over status, headers and body. Use it for 201 + `Location`,
  ETags, `Retry-After`.
* `@ResponseStatus` - a fixed status for a handler method or an exception class. Convenient, but it
  scatters status mapping across the codebase; prefer one `@RestControllerAdvice`.
* `@ResponseBody` - implied by `@RestController`.

### Error handling
* `@ExceptionHandler` - on a controller (local) or on an advice (global). Spring picks the **most
  specific** handler by exception type; a handler on the controller wins over one on an advice.
* `@RestControllerAdvice` / `@ControllerAdvice` - global advice. Narrow with
  `basePackages`/`assignableTypes`/`annotations` if you have more than one, and give them
  `@Order` - otherwise which advice handles a given exception is luck.
* `@InitBinder` - per-controller `WebDataBinder` customisation (date formats, disallowed fields).

**Trap:** an `@ExceptionHandler(Exception.class)` catch-all also swallows programming errors. Log at
ERROR with the stack trace and return a correlation id, or you have built a black hole.

**Here:** `ApiExceptionHandler` - extends `ResponseEntityExceptionHandler` so the ~20 built-in MVC
exceptions keep their sensible mappings.

### Declarative HTTP clients
`@HttpExchange` with `@GetExchange` / `@PostExchange` / `@PutExchange` / `@DeleteExchange`
(`org.springframework.web.service.annotation`) on an **interface**, turned into a bean by
`HttpServiceProxyFactory` over a `RestClientAdapter`, `WebClientAdapter` or `RestTemplateAdapter`.
The in-framework answer to OpenFeign.

**Trap:** the method parameter annotations are the **same** `@RequestHeader`/`@PathVariable`/
`@RequestBody` you use on the server side, which reads oddly at first. Error handling is central to
the adapter, so per-call failure classification is awkward - use a plain `RestClient` when
classification matters (as `PaymentGatewayCaller` does).

**Here:** `PaymentHttpApi` + `HttpClientConfig`

---

## 6. Validation (Jakarta Bean Validation)

`jakarta.validation.constraints` - **not** `javax`, and not Spring's own.

| Annotation | Means | Careful |
| --- | --- | --- |
| `@NotNull` | not null | `""` and `"  "` pass |
| `@NotEmpty` | not null, size > 0 | `"  "` passes |
| `@NotBlank` | not null, trimmed length > 0 | the one you usually want for strings |
| `@Size(min, max)` | string/collection/map length | |
| `@Min` / `@Max` | numeric bounds | |
| `@Positive`, `@PositiveOrZero`, `@Negative` | sign | |
| `@DecimalMin` / `@DecimalMax` | `BigDecimal` bounds, with `inclusive` | the right pair for money |
| `@Digits(integer, fraction)` | precision | |
| `@Pattern(regexp)` | regex | anchors are implicit for the whole value |
| `@Email` | an email-ish string | permissive by design; it is not a deliverability check |
| `@Past` / `@Future` / `@PastOrPresent` | temporal | |
| `@Valid` | **cascade** into this object/collection | |

### The three things that actually go wrong

1. **`@Valid` is not recursive.** `List<Line> lines` with constraints on `Line` validates nothing
   until you write `@Valid List<Line> lines`.
2. **`@Valid` on the controller parameter is what triggers everything.** Omit it and the whole DTO's
   annotations are decoration. (Spring also accepts `@Validated` there, with group support.)
3. **Constraints on method parameters need `@Validated` on the class** - a different mechanism, with
   a different exception type.

### Groups and custom constraints
`@Validated(OnCreate.class)` activates a constraint group, so one DTO can have different rules for
create and update. A custom constraint is an annotation meta-annotated `@Constraint(validatedBy =
MyValidator.class)` plus a `ConstraintValidator<A, T>` - and the validator is a Spring bean, so it
can inject repositories (use that sparingly: a validator that queries the database on every request
is a hidden N+1).

**Here:** `CreateOrderRequest`, `AppProperties`, `PlatformProperties`

---

## 7. Persistence

### JPA mapping (`jakarta.persistence`)

| Annotation | Notes and traps |
| --- | --- |
| `@Entity` | needs a no-arg constructor (may be `protected`) and a non-final class. `name` changes the **JPQL** name; `@Table(name)` changes the table |
| `@Table` | also carries `indexes` and `uniqueConstraints` - but if Flyway owns the schema, declare them in SQL and keep the entity quiet |
| `@Id` | a composite key needs `@IdClass` or `@EmbeddedId` |
| `@GeneratedValue` | `IDENTITY` forces an immediate INSERT on `persist()` and so **disables JDBC insert batching**; prefer `SEQUENCE` with a pooled allocation size on PostgreSQL/Oracle. `AUTO` picks for you, which is how projects end up sharing one sequence |
| `@Column` | `nullable`, `length`, `precision`/`scale`, `updatable`, `insertable`. With `ddl-auto=validate` these are checked against the real schema at startup |
| `@Version` | optimistic locking: Hibernate appends `WHERE version = ?` to every UPDATE. Also your ETag |
| `@Enumerated(EnumType.STRING)` | **never** ORDINAL - inserting a constant in the middle silently re-maps existing rows |
| `@Transient` | not persisted (the `jakarta.persistence` one, not Spring's) |
| `@Lob` | maps differently per driver; PostgreSQL turns `@Lob String` into an OID large object unless forced to `text`. A sized `varchar` is more portable |
| `@MappedSuperclass` | shares mappings without being an entity: no table, not queryable. Contrast `@Inheritance` (`SINGLE_TABLE`/`JOINED`/`TABLE_PER_CLASS`), which models a real type hierarchy |
| `@EntityListeners` | hooks `@PrePersist`/`@PostLoad`/... and Spring Data's `AuditingEntityListener` |
| `@OneToMany` | LAZY by default. `mappedBy` marks the non-owning side; `cascade`/`orphanRemoval` only for true composition |
| `@ManyToOne` / `@OneToOne` | **EAGER by default** - always write `fetch = LAZY`. `optional = false` lets Hibernate use an inner join and build a proxy instead of querying to prove existence |
| `@JoinColumn` | the FK column, on the owning side |
| `@ElementCollection` | a collection of values rather than entities; needs `@CollectionTable` |
| `@NamedEntityGraph` | a reusable fetch plan |

Hibernate extras (`org.hibernate.annotations`): `@BatchSize` (turns N+1 into N/size+1), `@Formula`,
`@Filter`, `@NaturalId`, `@DynamicUpdate`, `@SQLRestriction`, and `@CreationTimestamp` /
`@UpdateTimestamp` - the Hibernate equivalent of Spring Data auditing. Pick one, not both.

**Here:** `Order`, `OrderLine`, `Payment`, `StockItem`, `OutboxMessage`, `Auditable`

### Spring Data auditing
`@CreatedDate` `@LastModifiedDate` `@CreatedBy` `@LastModifiedBy`
(`org.springframework.data.annotation`), on a `@MappedSuperclass`, driven by
`AuditingEntityListener` + `@EnableJpaAuditing` + an `AuditorAware` bean.

**Trap:** `@EnableJpaAuditing` must live on a configuration class your **slice tests** can import.
Leave it on a class that also defines DataSources and transaction managers and every `@DataJpaTest`
insert fails with `NULL not allowed for column "CREATED_AT"`, because slices skip regular
`@Configuration` classes.

**Here:** `Auditable`, `JpaAuditingConfig` (deliberately a one-purpose class)

### Spring Data JPA repositories

| Annotation | Notes |
| --- | --- |
| `@Query` | JPQL by default; `nativeQuery = true` for SQL. Add `countQuery` when the derived count would be wrong or expensive. Prefer named `@Param` over positional `?1` |
| `@Param` | binds a method parameter to a named query parameter |
| `@Modifying` | required for UPDATE/DELETE queries. `flushAutomatically = true` pushes pending changes **before**; `clearAutomatically = true` evicts the first-level cache **after**. Without both, the statement may miss pending writes and stale managed entities may write the old value back. Bulk JPQL also bypasses optimistic locking and entity callbacks |
| `@EntityGraph` | declarative fetch plan - the clean fix for N+1 on one aggregate |
| `@Lock(LockModeType...)` | `OPTIMISTIC`, `OPTIMISTIC_FORCE_INCREMENT`, `PESSIMISTIC_READ`, `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) |
| `@QueryHints` / `@QueryHint` | JPA hints - above all `jakarta.persistence.lock.timeout`, so a blocked transaction fails fast instead of exhausting the pool |
| `@Procedure` | stored procedures |
| `@PersistenceContext` | injects a **transaction-scoped EntityManager proxy**; `@Autowired EntityManager` hands you something that is not thread-safe |

**Trap - the `Impl` suffix.** A custom fragment interface `OrderRepositoryCustom` must be implemented
by a class named `OrderRepositoryCustomImpl`, in a package Spring Data scans. Get the name wrong and
the symptom is the misleading *"No property 'statusTotalsSince' found for type Order"* at startup,
because Spring Data fell back to parsing the method name.

**Trap - constructor expressions.** `select new com.example.Dto(...)` needs a fully-qualified,
**top-level** type; a nested `Outer$Inner` is not reliably parsed. Hence `StatusTotal` is top-level.

**Here:** `OrderRepository`, `OrderRepositoryCustom(+Impl)`, `StockItemRepository`,
`OutboxRepository`, `OrderSpecifications`

---

## 8. Transactions

### `@Transactional`
**`org.springframework.transaction.annotation.Transactional`** - never
`jakarta.transaction.Transactional`. Only the Spring one understands `propagation`, `isolation`,
`readOnly`, `timeout`, `rollbackFor` and `transactionManager`.

| Attribute | Notes |
| --- | --- |
| `propagation` | full table in [04-transactions](../04-transactions.md) |
| `isolation` | `DEFAULT` keeps the database's setting. Raising it is a last resort; a row lock is usually cheaper and more portable |
| `readOnly` | four distinct effects: Hibernate `FlushMode.MANUAL`, a read-only JDBC connection, the replica-routing key in this project, and documentation |
| `timeout` / `timeoutString` | bounds the unit of work. Set it on anything that can take a lock |
| `rollbackFor` / `rollbackForClassName` | **a checked exception COMMITS by default** |
| `noRollbackFor` | the inverse |
| `transactionManager` (alias `value`) | required once more than one manager exists |
| `label` | free-form tags, readable from `TransactionSynchronizationManager` |

Precedence: a method-level annotation overrides a class-level one; a class-level one applies to all
public methods. On an **interface** it works only with interface-based proxies, so put it on the
class.

**The four traps, in the order they bite:**

1. **Self-invocation.** `this.method()` never passes the proxy, so the annotation does nothing.
2. **Visibility.** Keep advised methods `public`. A `private` method is never advised, and Spring
   raises no error - it just does not work.
3. **Checked exceptions commit.** Make domain exceptions unchecked, or declare `rollbackFor`.
4. **Rollback-only poisoning.** With `REQUIRED`, an inner failure marks the shared transaction
   rollback-only; catching it does not help, and the commit throws `UnexpectedRollbackException`.

**Here:** everywhere, but `PropagationDemoService`, `TransactionBoundaryDemoService` and
`SavepointDemoService` exist purely to demonstrate each attribute, with `TransactionPropagationTest`
asserting the outcomes.

### `@TransactionalEventListener`
`org.springframework.transaction.event` · METHOD

`phase` = `BEFORE_COMMIT` | `AFTER_COMMIT` (default) | `AFTER_ROLLBACK` | `AFTER_COMPLETION`.

**Trap 1:** if the event is published **outside** a transaction the listener never runs at all,
unless `fallbackExecution = true`. This bites hardest in tests.

**Trap 2:** a database **write** from an `AFTER_COMMIT` listener needs
`@Transactional(propagation = REQUIRES_NEW)`. The committed `EntityManager` is still bound to the
thread, so a `REQUIRED` method joins it, mutates the entity in memory, and the UPDATE is silently
lost. This project shipped that bug: orders stayed `NEW` while the log showed a successful charge.

**Here:** `OrderEventListeners`, `OrderService#applyPaymentResult`

---

## 9. Caching

`org.springframework.cache.annotation` · needs `@EnableCaching`

| Annotation | Does |
| --- | --- |
| `@Cacheable` | look up first; on a miss, call the method and store the result |
| `@CachePut` | always call the method, always store the result |
| `@CacheEvict` | remove one key (`key`), or everything (`allEntries = true`) |
| `@Caching` | combine several of the above on one method |
| `@CacheConfig` | class-level defaults (`cacheNames`, `keyGenerator`) |

Attributes worth knowing: `key` (SpEL over the arguments - `"#orderNumber"`), `condition`
(evaluated **before** the call, so it can only see arguments), `unless` (evaluated **after**, so it
can see `#result`), `sync = true` (one caller computes, the rest wait - the cure for a cache
stampede), `beforeInvocation` on `@CacheEvict`.

**The three traps:**

1. **Self-invocation**, as always.
2. **No TTL.** Boot's fallback `ConcurrentMapCacheManager` never evicts; it is a memory leak with a
   business justification. Caffeine with an explicit `expireAfterWrite` is the minimum bar.
3. **Eviction is not transactional.** `@CacheEvict` fires when the method returns, not when the
   transaction commits, so a concurrent reader can repopulate the cache from state that is about to
   roll back. For strict correctness evict in an `AFTER_COMMIT` listener, or set
   `CacheManager#setTransactionAware(true)`.

Also: do not cache nulls (`setAllowNullValues(false)`) - a cached 404 is a support ticket - and do
not cache mutable objects you then mutate; you are editing the cache.

**Here:** `CacheConfig`, `OrderQueryService` (`@Cacheable`), `OrderService#cancel` (`@CacheEvict`),
`OrderEventListeners#evictCachesAfterCommit` (the transactional version)

---

## 10. Async, scheduling, events

### `@Async`
`org.springframework.scheduling.annotation` · needs `@EnableAsync`

Return `void`, `Future<T>`, `CompletableFuture<T>` or `ListenableFuture<T>`. A `String` return type
compiles and silently gives you `null`.

**Traps:**
* **The default executor is unbounded.** With no `Executor` bean Spring uses
  `SimpleAsyncTaskExecutor`, which creates a thread per task - a fine way to OOM under load.
* **ThreadLocals do not cross the boundary.** MDC, the `SecurityContext` and the transaction are all
  thread-bound. Use a `TaskDecorator` to copy what you need.
* **`@Async` + `@Transactional` on the same method is almost always a bug**: the method runs on
  another thread in a *new* transaction, while the caller's may already have committed - or rolled
  back.
* **Exceptions vanish** from a `void @Async` method unless you install an
  `AsyncUncaughtExceptionHandler`.
* Self-invocation, again.

**Here:** `AsyncConfig` (bounded pool, `MdcTaskDecorator`, exception handler),
`OrderEventListeners#notifyAfterCommit`

### `@Scheduled`
`org.springframework.scheduling.annotation` · needs `@EnableScheduling`

`fixedDelay` measures from the **end** of the previous run - the right choice for a poller.
`fixedRate` measures from the start and queues up runs if one is slow. `cron` takes six fields
(seconds first) plus macros like `@hourly`, and a `zone`. The `*String` variants read properties.

**Traps:**
* **The default scheduler pool size is one.** One slow job delays every other job in the
  application. Set `spring.task.scheduling.pool.size`.
* **Every instance runs every job.** With more than one replica you need a lock (ShedLock, a DB
  advisory lock, `FOR UPDATE SKIP LOCKED`) or an idempotent job.
* Guard it with a property so tests and local runs can switch it off.

**Here:** `OutboxPublisher` - `fixedDelayString`, `@ConditionalOnProperty`, and the multi-instance
problem documented

### Events
* `@EventListener` - any method, any argument type; no `ApplicationEvent` inheritance needed since
  Spring 4.2. Supports `condition` (SpEL) and `@Order`. Returning a value **publishes** it as another
  event, which is either elegant or baffling depending on the reader.
* `@TransactionalEventListener` - see section 8.

**Here:** `OrderEventListeners`, `StartupTour` (`@EventListener(ApplicationReadyEvent.class)`)

---

## 11. AOP and resilience

### AOP (`org.aspectj.lang.annotation`, driven by Spring)
`@Aspect` on the class, plus one of `@Before` `@After` `@AfterReturning` `@AfterThrowing` `@Around`.
Pointcuts: `execution(...)`, `within(...)`, `@annotation(...)` (the **method** carries it),
`@within(...)` (the **class** carries it), `args(...)`, `bean(...)`, and `@Pointcut` to name one.

**Traps:**
* An annotation may sit on the method *or* the class, so a complete pointcut ORs `@annotation` and
  `@within`.
* Resolve the annotation from the **target** method via `AnnotatedElementUtils` - with JDK dynamic
  proxies `MethodSignature#getMethod()` can be the interface method, which carries no annotations.
  This also gets you meta-annotation support for free.
* Order is `Ordered`: **lower value = more outer**. Transaction advice sits at
  `LOWEST_PRECEDENCE`, so an aspect at order 20 wraps the transaction and sees commit failures. Get
  it backwards and your audit record says "success" for a transaction that rolled back.
* `@Around` advice must `return pjp.proceed()` and must rethrow. Advice that catches and returns
  `null` costs somebody a week.
* `@Aspect` classes are not beans automatically - register them.

**Here:** `@Audited` + `AuditAspect` (order 20, both pointcut forms, optional micrometer)

### Retry (`org.springframework.retry.annotation`, needs `@EnableRetry`)
`@Retryable` with `retryFor` / `noRetryFor` (the modern names for `include`/`exclude`),
`maxAttempts` (counting the first call), `backoff = @Backoff(delay, multiplier, maxDelay, random)`,
`listeners`, `recover`, and `*Expression` variants that read properties. `@Recover` marks the
fallback.

**Traps:**
* `random = true` is not optional at scale: without jitter, every instance retries at the same
  millisecond and recreates the outage.
* `@Recover` matching is by exception type **and** the remaining parameter list. Once a class
  declares **any** `@Recover`, non-retryable exceptions also look for one - and if none matches you
  get `ExhaustedRetryException: Cannot locate recovery method` instead of your real exception. This
  project hit exactly that with a 402 decline.
* Retrying a non-idempotent operation without an idempotency key charges the customer twice.
* Self-invocation.

**Here:** `PaymentGatewayCaller`, `RetryConfig`

### Resilience4j (`io.github.resilience4j.*.annotation`)
`@CircuitBreaker`, `@RateLimiter`, `@Bulkhead`, `@TimeLimiter`, `@Retry` - each takes `name`
(the instance key in `application.yml`) and `fallbackMethod`.

**Traps:**
* A fallback must have the **same signature plus a trailing `Throwable`**. Always include a
  `Throwable` overload, or an exception type you forgot propagates and the fallback "does not work".
* `ignore-exceptions` must list your *business* failures, or a decline opens the breaker.
* Combining resilience4j's `@Retry` with Spring's `@Retryable` on the same call is a way to get nine
  attempts when you wanted three.
* Aspect order is configurable (`resilience4j.*.<x>-aspect-order`) but not every aspect exposes a
  setter; an unknown key fails startup with *"No setter found for property"*.

**Here:** `PaymentGatewayClient` (breaker + bulkhead + three fallbacks), `application.yml`

---

## 12. Observability and actuator

### Actuator endpoints
`org.springframework.boot.actuate.endpoint.annotation`

| Annotation | Maps to |
| --- | --- |
| `@Endpoint(id = "x")` | `/actuator/x`, over both HTTP and JMX |
| `@WebEndpoint` / `@JmxEndpoint` | one technology only |
| `@ReadOperation` | GET |
| `@WriteOperation` | POST, parameters taken from the JSON body |
| `@DeleteOperation` | DELETE |
| `@Selector` | a path segment: `/actuator/platform/audit` |
| `@EndpointExtension` | add operations to someone else's endpoint |

**Traps:** the id must be lower-case alphanumeric; the endpoint still has to be exposed via
`management.endpoints.web.exposure.include`; and a `@WriteOperation` on an unsecured management port
is a finding in any serious review.

**Here:** `PlatformEndpoint` - all four operation types

### Health
A `HealthIndicator` bean is exposed at `/actuator/health/<bean name minus "HealthIndicator">`.

**Traps:** never call the dependency from a health check - Kubernetes polls it every few seconds, so
you have built a load generator and a cascading-failure amplifier. Report the circuit breaker's state
instead. And a **custom status** needs a place in `management.endpoint.health.status.order` plus an
HTTP mapping, or the aggregator treats it as unknown.

**Here:** `PaymentGatewayHealthIndicator` (reports `DEGRADED`, mapped to 200)

### Metrics and tracing
`@Timed` and `@Counted` (`io.micrometer.core.annotation`) need a `TimedAspect` / `CountedAspect`
bean. `@Observed` (`io.micrometer.observation.annotation`) needs an `ObservedAspect` and produces a
metric **and** a span from one annotation. **None of these aspect beans is auto-configured.**

**Trap:** high-cardinality tags (order number, user id, raw URI) are the number-one way to blow up a
metrics backend. Cap them centrally with a `MeterFilter`.

**Here:** `ObservabilityConfig`, `PaymentOrchestrator` (`@Observed`)

---

## 13. Testing

| Annotation | Scope | Notes |
| --- | --- | --- |
| `@SpringBootTest` | the whole context | `webEnvironment` = `MOCK` (default) / `RANDOM_PORT` / `DEFINED_PORT` / `NONE`; `properties` and `args` for overrides |
| `@WebMvcTest` | MVC only | mock servlet environment, no DataSource. Narrow it: `@WebMvcTest(OrderController.class)` |
| `@DataJpaTest` | JPA + Flyway | each method runs in a transaction that is **rolled back** |
| `@JdbcTest`, `@JsonTest`, `@RestClientTest`, `@DataRedisTest`, ... | one slice each | |
| `@TestConfiguration` | extra beans for a test | **excluded** from component scanning, unlike `@Configuration` |
| `@MockitoBean` / `@MockitoSpyBean` | replace a bean with a mock/spy | Boot 3.4+; supersede `@MockBean`/`@SpyBean` |
| `@TestBean` | replace a bean with a static factory method's result | no Mockito involved |
| `@ActiveProfiles` | profiles | part of the context cache key |
| `@TestPropertySource` / `@DynamicPropertySource` | properties | `@DynamicPropertySource` is a **static** method taking a `DynamicPropertyRegistry`, evaluated before the context is built - the only way to feed in a runtime value such as a container's mapped port |
| `@Transactional` on a test | wraps and rolls back | convenient, and **useless** for observing commit behaviour |
| `@Sql` / `@SqlGroup` | run scripts before/after | |
| `@DirtiesContext` | evict the cached context | last resort - everything after it pays |
| `@AutoConfigureTestDatabase` | swap in an embedded DB | `replace = NONE` keeps the real one |
| `@ImportAutoConfiguration` | opt a specific auto-configuration into a slice | how you get your own starter back |
| `@LocalServerPort` / `@LocalManagementPort` | the actual port | injected **after** the context is built, so useless for a bean that captures a URL in its constructor |

**Context caching** is keyed on classes + profiles + property sources + customizers + bean
overrides. Change any of them and you pay for another context, so keep them uniform across tests.
Contexts also stay alive for the whole JVM, which is how ports and in-memory databases leak between
test classes.

**Here:** every test class; see [06-testing](../06-testing.md) for the scope-by-scope reasoning.

---

## 14. Annotations that look alike

| These two | Differ by |
| --- | --- |
| `org.springframework.transaction.annotation.Transactional` / `jakarta.transaction.Transactional` | only Spring's has propagation, isolation, readOnly, rollbackFor. **Always Spring's** |
| `jakarta.persistence.Transient` / `org.springframework.data.annotation.Transient` | JPA mapping vs Spring Data mapping |
| `@NotNull` / `@NotEmpty` / `@NotBlank` | null / size > 0 / trimmed length > 0 |
| `@Valid` (Jakarta) / `@Validated` (Spring) | cascade into an object vs enable method validation and groups |
| `@ConditionalOnBean` / `@ConditionalOnMissingBean` | presence vs absence - and both are only trustworthy inside auto-configuration |
| `@Component` / `@Bean` | on your own class vs on a factory method for a type you do not own |
| `proxyBeanMethods = true` / `false` | an inter-bean method call returns the singleton vs a new object |
| `@Import` / `@ImportAutoConfiguration` | a configuration class vs an auto-configuration |
| `@TestConfiguration` / `@Configuration` in test sources | excluded from scanning vs picked up by **every** test context |
| `@MockBean` / `@MockitoBean` | deprecated vs current (Boot 3.4+) |
| `@Scheduled(fixedDelay)` / `@Scheduled(fixedRate)` | measured from the end vs the start of the previous run |
| `@Retryable(retryFor)` / `@Retryable(include)` | current vs deprecated name |
| `@Cacheable(condition)` / `@Cacheable(unless)` | before the call (arguments only) vs after it (can see `#result`) |
| `@Lock` (Spring Data) / `@Version` (JPA) | pessimistic `SELECT FOR UPDATE` vs optimistic version check |
| `@Order` / `@Priority` / `Ordered` | Spring's / Jakarta's / the interface - all order beans, and lower is more important |

---

## 15. Cheat sheet: the proxy-defeated annotations

Every one of these is silently inert on self-invocation, on a private method, or on an object you
created with `new`:

```
@Transactional   @Cacheable/@CachePut/@CacheEvict   @Async
@Retryable       @CircuitBreaker/@Bulkhead/@RateLimiter/@TimeLimiter
@PreAuthorize/@PostAuthorize/@Secured    @Validated (method validation)
@Timed/@Counted/@Observed                any annotation of your own backed by an aspect
```

`@Scheduled` is the near-miss: it is registered at startup rather than intercepted per call, so
self-invocation is irrelevant - but it still does nothing on a non-bean.

**Fixes, best first:**

1. Move the inner method to **another bean**. Nearly always right, and it usually improves the
   design (`PropagationDemoService`, `PaymentGatewayCaller`).
2. Inject `@Lazy` **self** and call `self.method()` (`TransactionBoundaryDemoService`).
3. Use the programmatic API: `TransactionTemplate`, `RetryTemplate`, the `Cache` directly.
4. `AopContext.currentProxy()` with `@EnableAspectJAutoProxy(exposeProxy = true)` - works, but ties
   business code to the AOP framework.
5. Load-time weaving (`mode = ASPECTJ`) - genuinely removes the limitation, and genuinely adds build
   and debugging burden. Rarely worth it.

---

## 16. Where each family is used here

```
Bootstrap       OrderServiceApplication, PlatformAutoConfiguration, PlatformHttpAutoConfiguration,
                PlatformConversionAutoConfiguration
Conditions      PlatformAutoConfiguration, OutboxPublisher    (tested by PlatformAutoConfigurationTest)
Properties      AppProperties, PlatformProperties, DataSourceConfig, StringToMaskedValueConverter
Web             OrderController, TransactionLabController, ApiExceptionHandler, WebConfig,
                ActorArgumentResolver + @CurrentActor, PaymentHttpApi
Validation      CreateOrderRequest, AppProperties, PlatformProperties
Persistence     Order, OrderLine, Payment, StockItem, OutboxMessage, TxNote, Auditable,
                OrderRepository (+Custom/+Impl), StockItemRepository, OutboxRepository,
                OrderSpecifications, OrderAnalyticsDao, JpaConfig, JpaAuditingConfig
Transactions    OrderService, InventoryService, OutboxService, PaymentAttemptRecorder,
                PropagationDemoService, TransactionBoundaryDemoService, SavepointDemoService
Caching         CacheConfig, OrderQueryService, OrderService, OrderEventListeners
Async/events    AsyncConfig, OrderEventListeners, OutboxPublisher, StartupTour
AOP/resilience  @Audited + AuditAspect, PaymentGatewayCaller, PaymentGatewayClient, RetryConfig
Observability   PlatformEndpoint, PaymentGatewayHealthIndicator, ObservabilityConfig,
                PaymentOrchestrator
Testing         all nine test classes, plus testsupport/FlakyGatewayStub and testsupport/TestPorts
```
