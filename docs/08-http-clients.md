# 8. The five HTTP clients

Spring now ships five ways to make an HTTP call. All five are wired up in this project against the
same endpoint (`com.example.orders.clients`), behind one `CatalogClient` interface, so
`GET /api/clients/{sku}` fetches one product five ways and reports what each returned.

`CatalogClientComparisonTest` asserts they agree on the happy path and differ on failure.

## Choosing

| Style | Use when | Avoid when |
| --- | --- | --- |
| **`RestClient`** | **the default for new blocking code**: fluent, synchronous, no reactive types in your service layer | you need streaming or real concurrency |
| **`@HttpExchange` interface** | a clean API you would rather declare than call; trivially mockable | per-call failure classification matters |
| **`WebClient`** | genuine fan-out, streaming, or you are already reactive | you would only ever call `.block()` - then it is a slower `RestClient` |
| **`RestTemplate`** | maintaining existing code | new code: maintenance mode since Spring 5.0 |
| **OpenFeign** | already on Spring Cloud and you want discovery-aware, load-balanced clients | you are not - it is a dependency tree to replace an interface Spring now ships |

## Timeouts live in a different place for every one of them

This is the single most common production gap, so here they are together (`ClientsConfig`):

| Style | Where the timeout goes | Default if you forget |
| --- | --- | --- |
| `RestTemplate` | `RestTemplateBuilder.connectTimeout/readTimeout` | **none** - `new RestTemplate()` waits forever |
| `RestClient` | the `ClientHttpRequestFactory`, via a `RestClientCustomizer` | **none** - the JDK client's read timeout is infinite |
| `WebClient` | the **connector** (`HttpClient.responseTimeout`, `ReadTimeoutHandler`), not the builder | **none** |
| `@HttpExchange` | inherited from whichever client the adapter wraps | inherits |
| Feign | `spring.cloud.openfeign.client.config.*`, entirely separate from the above | 10s connect / 60s read |

That last row is how a service ends up with no timeouts on half its calls: the team set them on
`RestClient` and never noticed Feign has its own.

## Each one fails with its own exception type

| Style | A 404 arrives as |
| --- | --- |
| `RestTemplate`, `RestClient`, `@HttpExchange` | `HttpClientErrorException.NotFound` |
| `WebClient` | `WebClientResponseException.NotFound` |
| Feign | `FeignException.NotFound` |

A service layer that catches one will silently not catch the others. This is the practical argument
for wrapping every client behind an interface you own (`CatalogClient`) and translating failures at
that boundary - which is also what makes the client technology swappable.

## WebClient: the honest version

`fetchAll` in `WebClientCatalogClient` is the case that justifies the dependency: N requests on a
handful of event-loop threads, finishing in the time of the slowest rather than the sum. With
200ms of injected latency and six SKUs the test measures concurrent-vs-sequential and asserts the
concurrent path is at least twice as fast; in practice it is ~200ms against ~1.2s.

Everything else about it is a cost:

* **`.block()` is a smell, and sometimes an exception.** On an event-loop thread it throws
  `IllegalStateException: block()/blockFirst()/blockLast() are blocking, which is not supported in
  thread reactor-http-nio-N` - so it works in an MVC controller and explodes inside a WebFlux
  handler or an exchange filter.
* **Unbounded `flatMap` is a self-inflicted DDoS.** Always pass a concurrency limit.
* **Retry is Reactor's**, not spring-retry's: `Retry.backoff(...).jitter(...)`. `@Retryable` would
  retry the *assembly* of the pipeline, not the subscription.
* **MDC does not propagate** across operators, so the correlation id vanishes mid-pipeline unless
  you wire up context propagation.
* Cross-cutting concerns go in an `ExchangeFilterFunction`, and it must return the `Mono` - work
  done eagerly in a filter runs at assembly time, once, not per request.

## OpenFeign: what it actually buys you

Service **discovery and load balancing**. `@FeignClient(name = "catalog")` with a registry resolves
instances and spreads load; Spring's own HTTP interfaces do not. Without a registry you supply
`url=`, and at that point `@HttpExchange` does the same job with no extra dependency.

Costs to go in with your eyes open about:

* Its annotations are Spring MVC's (`@GetMapping`, `@PathVariable`), not Feign's own, because Spring
  Cloud contributes a contract that reads them.
* Error handling is central (`ErrorDecoder`); there is no per-call hook.
* It has its own `Retryer` (default: never retry) and its own timeouts, independent of everything
  else you configured.
* It does not use your `RestClient.Builder`, so platform concerns - correlation-id propagation,
  outbound logging - must be re-implemented as a Feign `RequestInterceptor`. `ClientsConfig` does
  exactly that, and the duplication is the point.

## Having both starters on the classpath

`spring-boot-starter-web` and `spring-boot-starter-webflux` together is legal and common when you
want `WebClient` in an MVC application. Boot deduces `SERVLET` because `spring-webmvc` is present;
WebFlux contributes only the client. If you ever see "reactive web application" in the banner of an
MVC app, you removed `spring-boot-starter-web` by accident.

## The dependency lesson this cost us

Adding `spring-cloud-starter-openfeign` broke startup with:

```
NoClassDefFoundError: io/github/resilience4j/spring6/fallback/RxJava3FallbackDecorator
```

The Spring Cloud BOM **manages resilience4j** (for spring-cloud-circuitbreaker), so importing it
silently overrode the version this project had pinned: `resilience4j-spring-boot3` stayed at 2.3.0
while every other resilience4j module dropped to the BOM's 2.2.0, and the 2.3.0 autoconfiguration
references a class the 2.2.0 core does not have.

The fix is not more pins - it is letting **one** source manage a library's whole module set.
resilience4j now has no version declared anywhere in this build. The general rule: after importing
any BOM, run `mvn dependency:list` and look for a library whose modules disagree.
