# 1. Basics worth re-reading

## `@SpringBootApplication` is three annotations

| Part | What it does | Why you care |
| --- | --- | --- |
| `@SpringBootConfiguration` | marks THE configuration root | tests find it by walking **up** the package tree, which is why test packages must mirror main packages |
| `@EnableAutoConfiguration` | imports every class listed in every `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` on the classpath, then filters by `@Conditional` | this is how a jar on the classpath changes your application's behaviour |
| `@ComponentScan` | scans this package and below | it does **not** see `com.example.platform` - library code is contributed by auto-configuration, never by scanning |

Two diagnostics to memorise:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--debug   # CONDITIONS EVALUATION REPORT
curl localhost:8080/actuator/conditions                   # the same thing, as JSON
curl localhost:8080/actuator/configprops                  # every bound @ConfigurationProperties
curl localhost:8080/actuator/env                          # every property source, in precedence order
```

When somebody says "the property is not being picked up", one of those four answers it.

## Configuration precedence

Highest wins (abridged, see the reference docs for all 17 levels):

1. command-line arguments (`--server.port=9000`)
2. `SPRING_APPLICATION_JSON`
3. OS environment variables / system properties
4. profile-specific files (`application-prod.yml`) - **outside** the jar beats inside
5. `application.yml`
6. `@PropertySource`
7. defaults set in code (`SpringApplication.setDefaultProperties`, an `EnvironmentPostProcessor`
   that uses `addLast`)

Relaxed binding means `app.payments.base-url`, `app.payments.baseUrl` and `APP_PAYMENTS_BASEURL`
are the same key. In Kubernetes always use the `SCREAMING_SNAKE_CASE` form in env vars.

## `@ConfigurationProperties` over `@Value`

`@Value("${app.payments.base-url}")` fails at first use, is invisible to `/actuator/configprops`,
has no validation, and cannot be unit-tested without a context. A record with
`@ConfigurationProperties` + `@Validated` fails **at startup** with a message naming the property.
See `AppProperties` and `PlatformProperties`.

Three things that bite with constructor binding:

* With a single constructor, Boot infers it - `@ConstructorBinding` is only needed when there are
  several.
* Setting ONE sub-property of a nested record constructs that record with `0`/`null` for everything
  else. Put defaults in each nested record's compact constructor, not only in the outer one.
* A custom property type needs a `Converter` annotated `@ConfigurationPropertiesBinding`, declared
  as a **static** `@Bean` in a configuration class that does not itself consume bound properties.

## DTOs, not entities, at the boundary

`OrderResponse` and `CreateOrderRequest` are records. Exposing an entity means clients can set your
`id` and `version`, every mapping change becomes a breaking API change, Jackson walks your lazy
associations, and validation annotations end up in your persistence model.

The mapper (`OrderResponse.from`) is hand-written on purpose: for this size it beats
MapStruct/ModelMapper because a field you forget is a compile error rather than a silently-null
JSON property.

## Validation

* `@Valid` on the controller parameter is what actually triggers it. Without it the annotations on
  the DTO are decoration.
* Validation is **not** recursive: `@Valid List<Line> lines` is required to validate the elements.
* `@NotNull` / `@NotEmpty` / `@NotBlank` = not null / size > 0 / trimmed length > 0. The wrong one
  accepts `"   "` as an email address.
* Constraints on method **parameters** (`@PathVariable @Email String email`) need `@Validated` on
  the class, and they fail with `ConstraintViolationException`, not
  `MethodArgumentNotValidException`. Both are mapped in `ApiExceptionHandler`.

## Errors: RFC 7807 `application/problem+json`

Extend `ResponseEntityExceptionHandler` rather than writing an advice from scratch - it already
maps around twenty Spring MVC exceptions sensibly. Then:

* map exception **type** to status in exactly one place;
* never leak stack traces or SQL - log the detail, return a correlation id;
* 400 = malformed, 422 = well-formed but invalid, 409 = conflicts with current state;
* always send `Retry-After` with 429 and 503;
* use problem-detail **extensions** (`sku`, `available`) so clients never parse your message string.

## Jackson

Configure it with `spring.jackson.*` properties or a `Jackson2ObjectMapperBuilderCustomizer`.
Do **not** declare your own `ObjectMapper` bean: that replaces Boot's and drops the auto-registered
modules (`JavaTimeModule`, `Jdk8Module`, `ParameterNamesModule`) that make `Instant` and records
serialise correctly, plus every `spring.jackson.*` property you set.

## Startup hooks, in order

| Hook | Runs | Use for |
| --- | --- | --- |
| `EnvironmentPostProcessor` | before the context exists | inject a pod id, derive a property, pull a secret |
| `ApplicationContextInitializer` | after the context is created, before refresh | register bean definitions programmatically |
| `@PostConstruct` | per bean | that bean's own initialisation only |
| `ApplicationRunner` / `CommandLineRunner` | after refresh, before "ready" | one-off data setup; an exception here fails startup |
| `ApplicationReadyEvent` | after the port is bound | "we are live" logging, warm-up, registry registration |
