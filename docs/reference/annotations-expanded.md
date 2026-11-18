# Reference: what each annotation expands to

Every Spring Boot annotation is shorthand for code you could have written yourself. This page shows
the longhand, because a mechanism you can write by hand is a mechanism you can debug.

Compositions below were read out of the bytecode with `javap -v`, not from memory.

---

## `@SpringBootApplication`

One annotation, three jobs:

```java
@SpringBootApplication
public class OrderServiceApplication { }
```

expands to exactly this:

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class)
})
public class OrderServiceApplication { }
```

Those two **exclude filters** are the part people miss, and they explain two behaviours:

* `TypeExcludeFilter` is why `@TestConfiguration` classes are not picked up by a normal run, and how
  slice tests (`@WebMvcTest`, `@DataJpaTest`) narrow what gets scanned.
* `AutoConfigurationExcludeFilter` stops a class that is *both* `@Configuration` and listed as an
  auto-configuration from being registered twice.

Keep going down:

```java
// @SpringBootConfiguration =
@Configuration
@Indexed                       // lets the annotation processor pre-build a component index

// @EnableAutoConfiguration =
@AutoConfigurationPackage      // = @Import(AutoConfigurationPackages.Registrar.class)
@Import(AutoConfigurationImportSelector.class)
```

So the whole thing bottoms out at **`@Import`**. `AutoConfigurationImportSelector` reads every
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` on the classpath
and returns the class names to register; the `@Conditional`s then decide which survive.

`@AutoConfigurationPackage` is the quiet one: it records *this* package as the default root for
entity scanning and repository scanning. That is why moving your application class breaks JPA
without you touching a JPA setting.

### Writing it out by hand

If you wanted no magic at all:

```java
@Configuration
@Import({ DataSourceConfiguration.class, JpaConfiguration.class, WebMvcConfiguration.class,
          JacksonConfiguration.class, TransactionConfiguration.class /* ...and ~30 more */ })
@ComponentScan("com.example.orders")
@EntityScan("com.example.orders.domain")
@EnableJpaRepositories("com.example.orders.repo")
public class OrderServiceApplication { }
```

That is the trade: Boot writes those thirty imports for you, conditionally, based on what is on the
classpath. Everything it did is visible with `--debug` or `/actuator/conditions`.

---

## `SpringApplication.run(...)`

```java
SpringApplication.run(OrderServiceApplication.class, args);
```

is roughly:

```java
SpringApplication app = new SpringApplication(OrderServiceApplication.class);
app.setWebApplicationType(WebApplicationType.SERVLET);   // deduced from the classpath
app.setBannerMode(Banner.Mode.CONSOLE);
ConfigurableApplicationContext context = app.run(args);
```

and `app.run(args)` is, in order: create a `StopWatch`; run `SpringApplicationRunListener`s; build
the `Environment` (and call every `EnvironmentPostProcessor`, including the one in this project's
starter); print the banner; create the `ApplicationContext`; apply every
`ApplicationContextInitializer`; load the sources; **`refresh()`**; start the web server; call every
`ApplicationRunner` and `CommandLineRunner`; publish `ApplicationReadyEvent`.

Without Boot, in plain Spring:

```java
var context = new AnnotationConfigServletWebServerApplicationContext();
context.register(AppConfig.class);
context.refresh();          // you own the lifecycle, the server factory, and the shutdown hook
```

And before annotation config existed, a `web.xml` registering `DispatcherServlet` plus a
`dispatcher-servlet.xml`. That is the "quickly" the question is really about.

---

## Stereotypes and web annotations

All of these are pure composition - no behaviour of their own.

```java
@Service          =  @Component
@Repository       =  @Component   (+ exception translation, via a BeanPostProcessor)
@Controller       =  @Component
@RestController   =  @Controller + @ResponseBody

@GetMapping("/x") =  @RequestMapping(path = "/x", method = RequestMethod.GET)
```

So this:

```java
@RestController
@RequestMapping("/api/orders")
class OrderController {
    @GetMapping("/{id}")
    OrderResponse get(@PathVariable String id) { ... }
}
```

is the same as:

```java
@Component
class OrderController {
    @RequestMapping(path = "/api/orders/{id}", method = RequestMethod.GET)
    @ResponseBody
    OrderResponse get(@PathVariable String id) { ... }
}
```

And without any of it, you are writing a `HandlerMapping` and a `HandlerAdapter` by hand, parsing
the path yourself, and calling an `HttpMessageConverter` to serialise the result.

---

## `@Bean` / `@Component`

```java
@Configuration(proxyBeanMethods = false)
class ClientsConfig {
    @Bean
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.connectTimeout(Duration.ofSeconds(2)).build();
    }
}
```

Longhand against the raw container:

```java
var context = new AnnotationConfigApplicationContext();
context.registerBean("restTemplate", RestTemplate.class,
        () -> context.getBean(RestTemplateBuilder.class)
                     .connectTimeout(Duration.ofSeconds(2))
                     .build());
context.refresh();
```

The bean *name* is the method name - which is why renaming a `@Bean` method is an API change for
anyone using `@Qualifier("oldName")`.

---

## `@Autowired`

```java
@Service
class OrderService {
    private final OrderRepository orders;
    OrderService(OrderRepository orders) { this.orders = orders; }   // @Autowired implicit since 4.3
}
```

What the container does for you:

```java
var constructor = OrderService.class.getDeclaredConstructors()[0];
var args = Arrays.stream(constructor.getParameterTypes())
        .map(beanFactory::getBean)     // resolve each by type, then by @Qualifier, then @Primary
        .toArray();
OrderService service = (OrderService) constructor.newInstance(args);
```

Done by `AutowiredAnnotationBeanPostProcessor` - itself just a `BeanPostProcessor`, the same
extension point `TimingBeanPostProcessor` in this project uses.

---

## `@Value` vs `@ConfigurationProperties`

```java
@Value("${app.payments.base-url}") String baseUrl;
```

longhand:

```java
String baseUrl = environment.getRequiredProperty("app.payments.base-url");
```

`@ConfigurationProperties` is doing considerably more:

```java
@ConfigurationProperties("app")
record AppProperties(@Valid Payments payments) { }
```

by hand:

```java
AppProperties props = Binder.get(environment)
        .bind("app", AppProperties.class)
        .orElseThrow(() -> new IllegalStateException("app.* is not configured"));

Set<ConstraintViolation<AppProperties>> violations = validator.validate(props);
if (!violations.isEmpty()) {
    throw new IllegalStateException("invalid configuration: " + violations);
}
```

plus relaxed binding (`APP_PAYMENTS_BASEURL` ↔ `app.payments.base-url`), type conversion, and the
entry in `/actuator/configprops` - none of which `@Value` gives you.

---

## `@Transactional`

```java
@Transactional(timeout = 10)
public Order placeOrder(CreateOrderRequest request) {
    return orders.save(toOrder(request));
}
```

What the proxy does around your method:

```java
public Order placeOrder(CreateOrderRequest request) {
    var definition = new DefaultTransactionDefinition();
    definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    definition.setTimeout(10);
    definition.setName("OrderService.placeOrder");

    TransactionStatus status = transactionManager.getTransaction(definition);   // begin or join
    try {
        Order result = placeOrderTarget(request);     // your actual method body
        transactionManager.commit(status);
        return result;
    } catch (RuntimeException | Error ex) {           // <-- THE default rollback rule, in one line
        transactionManager.rollback(status);
        throw ex;
    } catch (Throwable checked) {
        transactionManager.commit(status);            // checked exceptions COMMIT
        throw checked;
    }
}
```

Two things fall straight out of that longhand:

* the checked-exception-commits rule is not a mystery, it is that second `catch`;
* the wrapper lives on the **proxy**, so `this.placeOrder()` from inside the same class never runs
  any of it.

The honest middle ground, when you want the boundary without the proxy:

```java
transactionTemplate.execute(status -> orders.save(toOrder(request)));
```

---

## `@Cacheable`

```java
@Cacheable(cacheNames = "orderByNumber", key = "#orderNumber", unless = "#result == null")
public OrderResponse byNumber(String orderNumber) { ... }
```

longhand:

```java
public OrderResponse byNumber(String orderNumber) {
    Cache cache = cacheManager.getCache("orderByNumber");
    Cache.ValueWrapper hit = cache.get(orderNumber);      // key from the SpEL
    if (hit != null) {
        return (OrderResponse) hit.get();
    }
    OrderResponse value = byNumberTarget(orderNumber);
    if (value != null) {                                   // the `unless`
        cache.put(orderNumber, value);
    }
    return value;
}
```

`condition` is evaluated before the call (so it only sees arguments); `unless` after (so it can see
`#result`). That ordering is obvious in the longhand and easy to get backwards in the annotation.

---

## `@Retryable`

```java
@Retryable(retryFor = TransientPaymentException.class,
           maxAttempts = 3,
           backoff = @Backoff(delay = 200, multiplier = 2, random = true))
public ChargeResponse charge(ChargeRequest request) { ... }
```

longhand - and this is a real API you can use directly:

```java
SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
        Map.of(TransientPaymentException.class, true), true, false);

ExponentialRandomBackOffPolicy backOff = new ExponentialRandomBackOffPolicy();
backOff.setInitialInterval(200);
backOff.setMultiplier(2);

RetryTemplate template = new RetryTemplate();
template.setRetryPolicy(policy);
template.setBackOffPolicy(backOff);

ChargeResponse response = template.execute(context -> chargeTarget(request));
```

Reach for the template rather than the annotation when the delay has to come **from the exception**
- honouring a `Retry-After` header, for instance - because an annotation attribute cannot see the
failure it is reacting to.

---

## `@Async` and `@Scheduled`

```java
@Async
public void notifyCustomer(String orderNumber) { ... }
```

```java
executor.submit(() -> notifyCustomerTarget(orderNumber));
```

The proxy submits to the `Executor` and returns immediately - which is exactly why a `void @Async`
method's exception goes nowhere unless an `AsyncUncaughtExceptionHandler` is installed, and why the
caller's transaction and MDC do not come along.

```java
@Scheduled(fixedDelayString = "${app.outbox.poll-interval:2000}")
public void publishPending() { ... }
```

```java
taskScheduler.scheduleWithFixedDelay(
        this::publishPending,
        Duration.ofMillis(environment.getProperty("app.outbox.poll-interval", Long.class, 2000L)));
```

`scheduleWithFixedDelay` (from the *end* of the last run) vs `scheduleAtFixedRate` (from its
*start*) is the `fixedDelay`/`fixedRate` distinction, with no annotation involved.

---

## Every `@Enable*`

```java
@EnableCaching
@EnableRetry
@EnableAsync
@EnableFeignClients
@EnableGreeters(names = { "world", "spring" })     // this project's own, see support/extension
```

All five are the same shape:

```java
@Import(CachingConfigurationSelector.class)        // @EnableCaching
@Import(RetryConfiguration.class)                  // @EnableRetry
@Import(AsyncConfigurationSelector.class)          // @EnableAsync
@Import(FeignClientsRegistrar.class)               // @EnableFeignClients
@Import(GreeterRegistrar.class)                    // @EnableGreeters
```

`@Import` takes one of three things, and which one tells you how much the annotation can do:

| Imported type | Can | Used by |
| --- | --- | --- |
| a `@Configuration` class | register a fixed set of beans | the simple `@Enable*`s |
| an `ImportSelector` | choose class names at runtime | `@EnableAutoConfiguration`, `@EnableCaching` |
| an `ImportBeanDefinitionRegistrar` | register a **variable number** of beans from the annotation's attributes | `@EnableFeignClients`, `@EnableJpaRepositories` |

The registrar longhand, for one bean per configured name:

```java
public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
    var attributes = metadata.getAnnotationAttributes(EnableGreeters.class.getName());
    for (String name : (String[]) attributes.get("names")) {
        registry.registerBeanDefinition("greeter_" + name,
                BeanDefinitionBuilder.genericBeanDefinition(Greeter.class)
                        .addConstructorArgValue(attributes.get("prefix"))
                        .addConstructorArgValue(name)
                        .getBeanDefinition());
    }
}
```

That is, to within naming, how `@EnableFeignClients` turns N annotated interfaces into N beans.

---

## Declarative HTTP clients

```java
@HttpExchange
interface CatalogHttpInterface {
    @GetExchange("/products/{sku}")
    Product byId(@PathVariable String sku);
}
```

There is no generated source file. The bean is a runtime proxy:

```java
RestClient restClient = builder.baseUrl(baseUrl).build();
CatalogHttpInterface client = HttpServiceProxyFactory
        .builderFor(RestClientAdapter.create(restClient))
        .build()
        .createClient(CatalogHttpInterface.class);
```

and what the proxy does per call is what you would otherwise write by hand:

```java
Product product = restClient.get()
        .uri("/products/{sku}", sku)
        .retrieve()
        .body(Product.class);
```

`@FeignClient` is the same idea with its own proxy factory, its own contract reader, and - the part
that catches people - its own timeouts and `Retryer`, independent of anything you configured on
`RestClient`.

---

## `@Valid`

```java
@PostMapping
ResponseEntity<OrderResponse> place(@Valid @RequestBody CreateOrderRequest request) { ... }
```

```java
ResponseEntity<OrderResponse> place(@RequestBody CreateOrderRequest request) {
    Set<ConstraintViolation<CreateOrderRequest>> violations = validator.validate(request);
    if (!violations.isEmpty()) {
        throw new MethodArgumentNotValidException(/* ... */);   // -> 400 via the advice
    }
    ...
}
```

Which makes the common bug obvious: drop `@Valid` and no one calls `validator.validate`, so every
constraint on the DTO is decoration.

---

## `@SpringBootTest`

```java
@SpringBootTest
@ActiveProfiles("test")
class OrderServiceApplicationTests { }
```

roughly:

```java
var app = new SpringApplication(OrderServiceApplication.class);   // found by walking UP the package
app.setAdditionalProfiles("test");
ConfigurableApplicationContext context = app.run();
// ...and the context is CACHED, keyed on classes + profiles + properties + bean overrides,
// which is why changing any of those costs you another full startup.
```

The "found by walking up the package tree" is `@SpringBootConfiguration` doing its one job - and the
reason a test package must mirror the main package.

---

## The through-line

Nothing here is generated at build time and nothing is bytecode-woven. It is all:

1. **composition** - annotations meta-annotated with other annotations (`@RestController`);
2. **`@Import`** - pulling in configuration, a selector, or a registrar (every `@Enable*`);
3. **post-processors** - reading annotations and editing definitions or wrapping instances
   (`@Autowired`, `@Transactional`, `@Cacheable`, `@Async`, `@Retryable`).

Which is why the three failure modes in
[annotations.md](annotations.md#0-the-mental-model) cover essentially every "my annotation does
nothing" bug: nobody found it, the proxy was bypassed, or the feature was never enabled.
