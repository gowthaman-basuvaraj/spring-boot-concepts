# 9. Container extension points and filters

How Spring itself is built. Once you have written a `BeanPostProcessor` that returns a proxy, every
"why doesn't `@Transactional` work here?" question answers itself.

All of this lives in `com.example.orders.support.extension` and `com.example.orders.web.filter`,
and is asserted by `ExtensionPointsTest` and `FilterChainTest`.

## The post-processor family, in the order they run

| Type | Sees | Can | Spring uses it for |
| --- | --- | --- | --- |
| `BeanDefinitionRegistryPostProcessor` | the registry | **add/remove** definitions | `@ComponentScan`, Spring Data repositories, `@FeignClient`, `@MapperScan` |
| `BeanFactoryPostProcessor` | all definitions, no instances | **edit** definitions: scope, lazy-init, property values, primary | `PropertySourcesPlaceholderConfigurer` |
| `BeanPostProcessor` | each instance | **wrap or replace** instances | `@Autowired`, `@PostConstruct`, **all AOP** |
| `SmartInitializingSingleton` | a fully-built context | react once every singleton exists | `@EventListener` registration |
| `ApplicationRunner` / `ApplicationReadyEvent` | a started application | side effects | - |

### The rule that bites: a BFPP must not instantiate beans

Calling `beanFactory.getBean(...)` in a `BeanFactoryPostProcessor` forces creation **before** the
post-processors are registered. The victim silently misses out on proxying - no transactions, no
caching, no metrics - and nothing warns you. Inspect and edit `BeanDefinition`s; never ask for
instances. `BeanDefinitionAuditor` sticks to definitions, and marks the dev stub controllers
lazy-init as a demonstration of editing one.

### The rule that bites twice: `static` factory methods

A `@Bean` method returning a `BeanPostProcessor` or `BeanFactoryPostProcessor` must be `static`.
Otherwise the enclosing `@Configuration` class has to be built too early, its own dependencies
escape post-processing, and Boot logs:

```
... is not eligible for getting processed by all BeanPostProcessors
```

Most people do not read that warning. It means some of your beans are not proxied.

## Writing a BeanPostProcessor that returns a proxy

`TimingBeanPostProcessor` wraps any `@Monitored` bean in a JDK dynamic proxy that logs slow calls.
Forty lines, and it demystifies the framework:

* **A BPP may return a different object**, and whatever it returns is what gets injected everywhere.
  `AbstractAutoProxyCreator` - the thing behind `@Transactional`, `@Cacheable`, `@Async`,
  `@Retryable` - is "just" a BPP that returns proxies.
* **Why self-invocation fails**: the proxy wraps the target, and the target's own `this` is not the
  proxy, so an internal call never reaches `invoke`.
* **Why you inject interfaces**: a JDK dynamic proxy can only implement interfaces. Spring falls
  back to CGLIB subclassing for classes, which cannot proxy a `final` class or a `final`/`private`
  method.
* **Always unwrap `InvocationTargetException`**, or callers see that instead of their own exception
  and every `catch` downstream stops matching.

`MonitoredInventoryGateway` ends up wrapped in a timing proxy, wrapping Spring's transaction proxy,
wrapping the target. Two layers of proxy around one object is ordinary - and it is why a stack trace
has eight frames you did not write.

## Writing your own `@Enable*`

There is no magic: **every `@Enable*` is an annotation that `@Import`s something.**

```java
@Target(TYPE) @Retention(RUNTIME)
@Import(GreeterRegistrar.class)
public @interface EnableGreeters { String[] names(); String prefix() default "Hello"; }
```

`@Import` accepts three things:

| Import | Returns | Used by |
| --- | --- | --- |
| a `@Configuration` class | fixed beans | the simple `@Enable*`s |
| an `ImportSelector` | class **names** to import, decided at runtime | `@EnableAutoConfiguration` reading the `.imports` files |
| an `ImportBeanDefinitionRegistrar` | registers definitions directly | `@EnableFeignClients`, `@EnableJpaRepositories`, `@MapperScan` |

`GreeterRegistrar` registers one bean per name in the annotation - which is precisely how
`@EnableFeignClients` turns N interfaces into N beans. Two details: you work with
`AnnotationMetadata` rather than the annotation instance (the class is being read from bytecode and
may not be loadable yet), and you register **definitions**, so your beans still get the full
lifecycle - injection, post-processing, AOP, scoping.

## Filters

### Order, and why the numbers matter

```
-2147483628  CorrelationIdFilter            so EVERYTHING below is logged with an id
-2147483618  PayloadSizeLimitFilter         reject huge bodies before anyone allocates
       ...   Boot's own filters             metrics, request context, encoding
      -100   [Spring Security would sit here]
         0   RequestResponseLoggingFilter   log the request as the application really saw it
       ...   DispatcherServlet -> interceptors -> controller
```

Lower = earlier = more outer.

### Register with `FilterRegistrationBean`, not `@Component`

A `Filter` that is also a `@Component` is auto-registered for `/*` at `LOWEST_PRECEDENCE` - no
control over order, and if you *also* register it manually it runs **twice**.
`FilterRegistrationBean` gives you order, URL patterns, dispatcher types and a name that shows up in
`/actuator/mappings`. Note `setDispatcherTypes`: by default a filter does **not** run on
`DispatcherType.ERROR`, so your correlation id is missing from exactly the responses you most want
to trace.

### Body logging is harder than it looks

A servlet request body is a one-shot stream. Read it in a filter and the controller gets nothing.
`ContentCachingRequestWrapper` buffers what the application reads so you can retrieve it *after* the
chain; `ContentCachingResponseWrapper` does the same outbound - but **you must call
`copyBodyToResponse()`** or the client receives a correct status with an empty body. That is the
most common bug in home-made logging filters, and a test that only asserts the status code passes
while every client gets nothing. `FilterChainTest` asserts the body for exactly that reason.

The cost is real (every body held in memory), so bodies are size-capped and off by default. A log
aggregator is not an approved store for card numbers.

### A filter's response is yours to write

`@RestControllerAdvice` only sees exceptions from a *handler*. Anything a filter rejects bypasses
MVC entirely, so `PayloadSizeLimitFilter` writes its own RFC 7807 body to keep error responses in
one shape.

### Filter vs interceptor vs advice

| | Sees | Use for |
| --- | --- | --- |
| **Filter** | every request, including static resources and error dispatches; can wrap request/response; no handler info | correlation ids, security, compression, early rejection |
| **Interceptor** | requests routed to MVC, **with** the handler method | handler-aware concerns, timing per endpoint |
| **`@RestControllerAdvice`** | exceptions from handlers | turning exceptions into responses |
