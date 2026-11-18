# 5. Outbound HTTP: retry, backoff and resilience

Asserted by `PaymentRetryIntegrationTest` (9 tests) against a real HTTP endpoint - not a mocked
transport, so the timeouts and interceptors are genuinely exercised.

## The layer cake

```
PaymentOrchestrator          no transaction around any of this
  PaymentGatewayClient       @CircuitBreaker + @Bulkhead + fallback      (outer)
    PaymentGatewayCaller     @Retryable(backoff + jitter)                (inner)
      RestClient             connect + read timeouts, interceptors
```

Two beans, not one, because `@Retryable` and `@CircuitBreaker` are both proxy-based: put them on
methods of the same class and let one call the other internally, and the inner annotation is
silently ignored.

## Timeouts first

Nothing else matters until timeouts are set. The JDK `HttpClient` default read timeout is
**infinite**: one hung downstream pins every servlet thread and takes the service down. Set them
once, centrally, in a `RestClientCustomizer` (`PlatformRestClientCustomizer`) rather than at each
call site.

Also build clients from the **injected** `RestClient.Builder`. `RestClient.create()` bypasses every
customizer - timeouts, correlation propagation, logging - and it is a very common and very expensive
mistake.

Sanity rule: `connect + (attempts x read) + backoff` must fit inside the SLA of whoever is calling
*you*, otherwise retries have turned a fast failure into a timeout.

## Classify before you retry

`PaymentExceptions` is the most important class in the package. Retrying is only safe and useful for
**transient** failures.

| Signal | Class | Why |
| --- | --- | --- |
| connect/read timeout, connection reset, DNS blip | transient | the request may never have been processed |
| 502, 503, 504 | transient | infrastructure, not your payload |
| 429 | transient, honour `Retry-After` | the server told you to slow down |
| 500 | transient **only if the operation is idempotent** | it may have half-succeeded |
| 408 | transient | server-side timeout |
| 400, 401, 403, 404, 409, 422 | permanent | nothing about waiting changes the answer |
| 200 with a business decline | permanent | an answer, not an outage |

`RestClient.exchange()` is the right tool: unlike `retrieve()` it does not throw on error statuses,
so status, headers and body are available in one place and classification stays in one method.

## Backoff and jitter

* `maxAttempts` counts the **first** call: 3 means try, retry, retry.
* `random = true` is not optional at scale. Without jitter every instance that failed during the
  same blip retries at the same millisecond and recreates the outage (thundering herd). The
  programmatic equivalent is `ExponentialRandomBackOffPolicy`.
* The `*Expression` attributes read properties, which is how the policy becomes configurable per
  environment without a code change.
* `Retry-After` is either delta-seconds or an HTTP date. Honour it, but **cap** it
  (`app.payments.max-retry-after`) - a buggy or hostile upstream must not be able to park your
  threads for an hour.

### The `@Recover` trap

Once a class declares **any** `@Recover` method, spring-retry wires a recovery callback for the
whole class - so an exception listed in `noRetryFor` also goes looking for a recoverer. If none
matches its type, you do not get your original exception, you get

```
ExhaustedRetryException: Cannot locate recovery method
```

which then defeats downstream exception handling. This project hit exactly that: a 402 decline
arrived at the circuit breaker as an `ExhaustedRetryException` and the order was never marked
failed. The fix is a `@Recover` parameter typed to the shared base exception. If you have nothing
useful to do in recovery, declare **no** `@Recover` at all - the last exception is then rethrown
as-is.

Matching rules: the return type must match the retryable method's; the first parameter is the
exception; the rest must match the original argument list or be omitted entirely.

## Idempotency

An idempotency key is what makes retrying a POST safe: the gateway must return the original result
for a repeated key instead of charging again. Derive it from the **business operation**
(`"order-" + orderNumber`), never per attempt - the test asserts all three attempts send the same
key. If a downstream API has no idempotency support you cannot safely retry its writes; you can only
retry reads and record the uncertainty, which is why an inconclusive charge leaves the order `NEW`
rather than marking it failed.

## Retry at exactly one layer

Retries **multiply**. Three at the client times three at the gateway times three at the service is
27 requests for one user action - this is how a small blip becomes a self-inflicted DDoS. Pick one
layer, usually the one closest to the dependency, and make the others fail fast.

## Circuit breaker

A breaker converts slow failure into fast failure: while open, calls are rejected immediately with
`CallNotPermittedException` - no socket, no thread, no 5-second timeout.

Settings people get wrong:

* `minimum-number-of-calls` - without it, the first failed call on a quiet endpoint opens the
  breaker at a 100% failure rate.
* `ignore-exceptions` - business failures (a decline, a validation error) must **not** count towards
  opening it. Only infrastructure failures should.
* `slow-call-duration-threshold` - calls that succeed but are too slow should count as failures; a
  slow dependency is an outage with extra steps.
* `sliding-window-type` - `TIME_BASED` is usually better for low-traffic services, where a
  count-based window can be hours old.

### Retry inside or outside the breaker?

This project puts **retry inside** (the breaker is the outer bean), so the breaker sees one logical
failure per business call and a `maxAttempts=3` retry does not fill a 10-call window with three
failures from one request. The opposite nesting is also legitimate - it fails fast while the breaker
is open, and is preferable when the retry should outlive a short outage - but then you must size the
window with the retry count in mind. Choose deliberately and write the choice down.

Note that not every resilience4j aspect order is settable (the bulkhead order has no setter in
2.3), and an unknown key fails startup with "No setter found for property".

## Bulkhead

Caps concurrent calls to one dependency. Without it, a dependency that slows from 50 ms to 5 s will
consume every request thread and take down endpoints that have nothing to do with it. Bulkheads are
how you keep a partial outage partial.

## Fallbacks

A resilience4j fallback method takes the same signature plus a trailing `Throwable`, and the most
specific match wins - so you can have one per exception type. Always include a `Throwable` overload:
without it, an exception type you forgot propagates and the fallback "does not work".

A fallback that hides a real failure is worse than no fallback. Here the outcomes are explicit
(`SUCCEEDED`, `DECLINED`, `UNAVAILABLE`, `FAILED`) so the caller can distinguish "the customer was
declined" from "we do not know yet".

## Surface it to clients and operators

* `CallNotPermittedException` maps to **503 + `Retry-After`**, never a 500 (`ApiExceptionHandler`).
* The health indicator reports the **breaker's** state rather than calling the dependency: a health
  check that makes an HTTP call turns your readiness probe into a load generator and a
  cascading-failure amplifier.
* An optional dependency being down is `DEGRADED`, not `DOWN` - returning DOWN makes the
  orchestrator kill a pod that was serving traffic fine. A custom status needs a place in
  `management.endpoint.health.status.order` and an HTTP mapping, or the aggregator treats it as
  unknown.
* Keep `readiness` narrow: include what makes the service able to *serve*. Putting an optional
  downstream dependency in the readiness group means a partner outage takes your pods out of
  rotation.
* A `RetryListener` counts attempts and exhaustions. Without it you are blind: retries hide latency,
  and the only question that matters during an incident is "are we retrying more than usual?".
  Beware that `onError` fires for every failed attempt, including non-retryable ones.

## Declarative clients

`PaymentHttpApi` shows Spring 6's HTTP interface (`@HttpExchange` + `HttpServiceProxyFactory` over
`RestClientAdapter`) - the modern replacement for OpenFeign in a plain-Spring stack. Trade-off: the
contract is readable in one screen and trivially mockable, but per-call failure classification is
harder to express, because errors surface as `RestClientResponseException` from a central handler.

Rule of thumb: declarative interfaces for simple, well-behaved APIs; an explicit `RestClient` for
anything where failure classification matters.

## Driving it by hand

```bash
curl -X POST 'localhost:8080/stub/payments/mode?value=flaky'      # fail twice, then succeed
curl -X POST 'localhost:8080/stub/payments/mode?value=down'       # always 503 -> opens the breaker
curl -X POST 'localhost:8080/stub/payments/mode?value=throttled'  # 429 + Retry-After: 1
curl -X POST 'localhost:8080/stub/payments/mode?value=declined'   # 402, must NOT be retried
curl -X POST 'localhost:8080/stub/payments/mode?value=slow'       # sleeps past the read timeout
```

Place an order after each, then watch the log, `payment_attempt` rows,
`/actuator/health/paymentGateway` and `/actuator/metrics/retry.attempts`.
