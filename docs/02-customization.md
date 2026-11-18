# 2. Customizing the framework

Everything in this chapter lives in `platform-starter`, which is what an internal platform team
ships so that product teams get correlation ids, audit logging, HTTP defaults and an actuator view
for free.

## Auto-configuration

```
platform-starter/src/main/resources/META-INF/
├── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports   <- @AutoConfiguration classes
├── spring.factories                                                          <- every OTHER Boot SPI
└── additional-spring-configuration-metadata.json                             <- IDE hints
```

Since Boot 2.7/3.0, auto-configuration classes are listed in the `.imports` file. **Everything
else still uses `spring.factories`**: `EnvironmentPostProcessor`, `ApplicationContextInitializer`,
`ApplicationListener`, `FailureAnalyzer`, `SpringApplicationRunListener`. Mixing them up is a
common cause of "my class is never called".

`@AutoConfiguration` differs from `@Configuration` in that it is processed **after** all user
configuration and can declare ordering with `before`/`after`/`beforeName`/`afterName`. That
ordering is why `PlatformHttpAutoConfiguration` declares
`@AutoConfiguration(before = RestClientAutoConfiguration.class)`: a `RestClientCustomizer`
registered too late is never applied.

### The three rules of a well-behaved starter

1. **`@ConditionalOnMissingBean` on everything.** The application always wins. `AuditSink` is an
   interface with a logging default; publish your own bean and the starter steps aside.
2. **Every feature has an on/off property with a documented default.**
   `@ConditionalOnProperty(..., matchIfMissing = true)` means "on unless told otherwise".
3. **Guard optional integrations with `@ConditionalOnClass`.** The starter works with or without
   actuator, micrometer and aspectj on the classpath. Prove it with `FilteredClassLoader` in a test
   - see `PlatformAutoConfigurationTest`.

Dependencies in a library POM follow from rule 3: depend on the narrowest modules, never on a
`spring-boot-starter-*`, and mark anything that only powers a conditional bean `<optional>true</optional>`.

### Two traps

* `spring-boot-starter-parent` declares `spring-boot-maven-plugin` in `build/plugins`, so a library
  module inherits the `repackage` goal and fails with *"Unable to find a single main class"*. Set
  `<skip>true</skip>`.
* A `@ConfigurationPropertiesBinding` converter must be a **static** `@Bean` in a configuration
  class that does not consume bound properties, or you get `BeanCurrentlyInCreationException` -
  which surfaces as "Could not bind properties to 'ServerProperties'" and sends you hunting in
  entirely the wrong place.

## The extension points, and when to use which

| Want to... | Use | In this repo |
| --- | --- | --- |
| Add declarative behaviour via your own annotation | annotation + `@Aspect` | `@Audited` / `AuditAspect` |
| Add a cross-cutting HTTP concern (ids, auth, logging) | `Filter` via `FilterRegistrationBean` | `CorrelationIdFilter` |
| Do something that needs the handler method | `HandlerInterceptor` | `WebConfig#addInterceptors` |
| Inject a custom controller parameter | `HandlerMethodArgumentResolver` | `@CurrentActor` / `ActorArgumentResolver` |
| Convert a query parameter or path variable | `Converter` + `addFormatters` | `String` → `OrderStatus` |
| Change MVC defaults | implement `WebMvcConfigurer` | `WebConfig` |
| Change JSON | `Jackson2ObjectMapperBuilderCustomizer` | `JacksonConfig` |
| Apply HTTP client defaults everywhere | `RestClientCustomizer` | `PlatformRestClientCustomizer` |
| Tweak the auto-configured transaction manager | `TransactionManagerCustomizer` | discussed in `JpaConfig` |
| Set a Hibernate property with no Boot equivalent | `HibernatePropertiesCustomizer` | `JpaConfig#hibernateTuning` |
| Customise the cache manager | `CacheManagerCustomizer` | `CacheConfig` |
| Add common metric tags / cap cardinality | `MeterRegistryCustomizer`, `MeterFilter` | `ObservabilityConfig` |
| Expose internal state for operators | `@Endpoint` | `/actuator/platform` |
| Report dependency state to the orchestrator | `HealthIndicator` | `PaymentGatewayHealthIndicator` |
| Inspect/modify every bean as it is created | `BeanPostProcessor` | `StartupTimingBeanPostProcessor` |
| Act before any bean exists | `EnvironmentPostProcessor` | `PlatformEnvironmentPostProcessor` |
| Turn an ugly startup stack trace into advice | `FailureAnalyzer` | `PlatformFailureAnalyzer` |

### `WebMvcConfigurer`, never `@EnableWebMvc`

In a Boot application `@EnableWebMvc` switches `WebMvcAutoConfiguration` off wholesale. You
silently lose content-negotiation defaults, static resource handling, the configured
`ObjectMapper`, error handling and more. Boot collects every `WebMvcConfigurer` bean and applies
them **on top of** its defaults.

### Filter or interceptor?

A filter sits outside `DispatcherServlet`: it sees every request including static resources, error
dispatches and other servlets, and it can wrap the request/response. Use it for correlation ids,
security and compression. An interceptor runs inside MVC and knows the handler method - use it for
handler-aware concerns. Extend `OncePerRequestFilter`, not `Filter`, so forwards and async
dispatches do not run your cleanup at the wrong time.

### `BeanPostProcessor` rules

* It is created before application beans, so it must not depend on them. Injecting a
  `@ConfigurationProperties` bean into one produces the "is not eligible for getting processed by
  all BeanPostProcessors" warning and silently unproxied beans. Read the `Environment` instead.
* Declare the factory method `static`, so the enclosing configuration class is not instantiated too
  early.
* Implement `PriorityOrdered`.

## AOP: what every senior engineer must know

Spring AOP is **proxy-based**, and that single fact explains a family of bugs:

* `this.method()` never goes through the proxy, so `@Transactional`, `@Cacheable`, `@Async`,
  `@Retryable` and `@Audited` are all silently ignored on self-invocation. Fix by moving the method
  to another bean (preferred), injecting `@Lazy` self, or `AopContext.currentProxy()`.
* Private, static and final methods cannot be advised. CGLIB cannot subclass a final class.
* Advice order is `Ordered`: **lower value = more outer**. `AuditAspect` is at 20, transaction
  advice defaults to `Ordered.LOWEST_PRECEDENCE`, so audit wraps the transaction and sees commit
  failures. Get this backwards and your audit record says "success" for a transaction that rolled
  back.
* Resolve annotations from the **target** method, not the interface method - with JDK dynamic
  proxies `signature.getMethod()` may carry no annotations. Use `AnnotatedElementUtils` against the
  target class, which also handles meta-annotations.
* Advice must be transparent: never swallow the exception.
