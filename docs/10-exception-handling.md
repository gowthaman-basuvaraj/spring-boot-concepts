# 10. Exception handling

The error contract is part of your public API, and it breaks silently: nothing fails to compile when
a 409 quietly becomes a 500. `ApiExceptionHandler` and `ErrorContractTest` (11 assertions) are the
worked example.

## Where an exception can be caught, and in what order

```
                    ┌─────────────────────────────────────────────┐
  request ─────────▶│ FILTERS            ← advice NEVER sees these │
                    └──────────────────┬──────────────────────────┘
                                       ▼
                    ┌─────────────────────────────────────────────┐
                    │ DispatcherServlet                           │
                    │   HandlerInterceptor.preHandle              │
                    │   ┌───────────────────────────────────────┐ │
                    │   │ controller method                     │ │
                    │   │   try/catch in the method   (1st)     │ │
                    │   └───────────────────────────────────────┘ │
                    │   @ExceptionHandler on the controller (2nd) │
                    │   @ControllerAdvice                   (3rd) │
                    │   HandlerExceptionResolver chain      (4th) │
                    └──────────────────┬──────────────────────────┘
                                       ▼
                    /error  →  BasicErrorController        (last resort)
```

Within each level, **the most specific exception type wins**. A handler on the controller beats the
same handler on an advice. With several advices, `@Order` decides — and if you do not set it, which
one handles a given exception is luck.

Two consequences people meet the hard way:

* **A filter's response is yours to write.** `@RestControllerAdvice` only sees exceptions thrown by
  a *handler*. `PayloadSizeLimitFilter` in this project rejects oversized bodies before MVC is
  involved, so it writes its own RFC 7807 body — otherwise that one path would return a different
  error shape from every other.
* **`@Async`, `@Scheduled` and reactive pipelines are outside all of this.** An exception from a
  `void @Async` method goes nowhere without an `AsyncUncaughtExceptionHandler`; a `@Scheduled` job
  needs an `ErrorHandler`; a `Mono` needs `onErrorResume`.

## Start from `ResponseEntityExceptionHandler`

```java
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler { }
```

It already maps around twenty Spring MVC exceptions — unreadable body, missing parameter,
unsupported media type, method not allowed, no handler found — to sensible statuses and
`ProblemDetail` bodies. Writing an advice from scratch means rediscovering each of those in
production. Override only what you want to change.

Pair it with `spring.mvc.problemdetails.enabled=true` so the framework's own exceptions produce
problem+json too, and your API has *one* error shape rather than two.

## The response: RFC 7807 / 9457

```json
{
  "type": "https://errors.example.com/orders/insufficient-stock",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "Insufficient stock for SKU-DOCK: requested 999, available 10",
  "instance": "/api/orders",
  "timestamp": "2026-11-18T08:18:33Z",
  "correlationId": "cd146eb133af4e0d",
  "sku": "SKU-DOCK",
  "requested": 999,
  "available": 10
}
```

The five standard members, then **extensions**. Those last three are what make this useful: a client
can branch on `available` without parsing an English sentence. Any error a client is expected to
*react* to should carry the data it needs to react.

`type` should be a stable URI you never change — it is the machine-readable identity of the error.
`detail` is prose and may change. Clients key on `type`, humans read `detail`.

## Choosing the status

| Status | Means | Example here |
| --- | --- | --- |
| 400 | malformed — could not be processed as a request | bad JSON, failed validation |
| 401 / 403 | not authenticated / not allowed | — |
| 404 | the resource does not exist | `OrderNotFoundException` |
| 409 | conflicts with current state | optimistic lock, duplicate key, illegal transition, insufficient stock |
| 422 | well-formed and understood, but semantically invalid | business rule rejection, when you want to distinguish it from 400 |
| 429 | rate limited — **always with `Retry-After`** | — |
| 502 | a downstream dependency gave a bad answer | `PermanentPaymentException` |
| 503 | temporarily unable — **always with `Retry-After`** | circuit breaker open, lock timeout |
| 504 | a downstream dependency timed out | — |

The distinction worth being deliberate about is **409 vs 422**: 409 is "the world changed under
you, re-read and retry", 422 is "your request will never be accepted as written". They imply
different client behaviour, which is the only reason the distinction exists.

## The three validation exceptions

Validation failures arrive as three different types, and which one you get depends on something as
invisible as whether the class carries `@Validated`. Both of the method-validation rows below were
confirmed empirically against Spring 6.2:

| Where the constraint is | Exception | Needs |
| --- | --- | --- |
| `@Valid @RequestBody` | `MethodArgumentNotValidException` | nothing — it is a `BindException` |
| method parameter, class has `@Validated` | `ConstraintViolationException` | `@Validated` on the class |
| method parameter, no `@Validated` | `HandlerMethodValidationException` | Spring 6.1+ built-in |

Handle all three or your error shape changes when someone adds or removes one annotation.
Note the property paths differ too — `lines[0].sku` from body validation,
`lifetimeValue.email` from `ConstraintViolationException` — so normalise them if clients depend on
the key.

## Designing the exceptions themselves

### Unchecked, almost always

```java
public class InsufficientStockException extends RuntimeException { ... }
```

Not a style preference — a **correctness** one. `@Transactional` rolls back on `RuntimeException`
and `Error`, and **commits** on checked exceptions. A checked domain exception silently commits a
half-finished unit of work unless every caller remembers `rollbackFor`. Make them unchecked and the
default is right. See [04-transactions](04-transactions.md).

### Carry data, not just a message

```java
public class InsufficientStockException extends RuntimeException {
    private final String sku;
    private final int requested;
    private final int available;
    ...
}
```

The handler turns those fields into problem-detail extensions. If the only way for a caller to learn
*which* SKU failed is to regex your message string, the exception is underdesigned.

### Not every failure deserves an exception

An expected business outcome is not exceptional. This project's payment flow returns an explicit
outcome instead:

```java
public record ChargeOutcome(Status status, String providerReference, String detail) {
    public enum Status { SUCCEEDED, DECLINED, UNAVAILABLE, FAILED }
}
```

A declined card is a *normal Tuesday*, not an error — and modelling it as a return value forces the
caller to handle all four cases, where an exception lets them forget one. Reserve exceptions for the
unexpected. (Also: exceptions are expensive to construct, since filling in the stack trace walks the
stack. Irrelevant at 10/sec, measurable at 10 000/sec.)

### Classify by how the caller should react

`PaymentExceptions` splits into `TransientPaymentException` and `PermanentPaymentException` — not
because they come from different places, but because one is worth retrying and the other never is.
Exception hierarchies are most useful when they encode *what to do*, not *what went wrong*.

### The alternative: self-describing exceptions

Spring 6 lets an exception carry its own response, needing no handler at all:

```java
public class OrderNotFoundException extends RuntimeException implements ErrorResponse {

    private final ProblemDetail body;

    public OrderNotFoundException(String orderNumber) {
        super("Order not found: " + orderNumber);
        this.body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, getMessage());
        this.body.setType(URI.create("https://errors.example.com/orders/order-not-found"));
        this.body.setProperty("orderNumber", orderNumber);
    }

    @Override public HttpStatusCode getStatusCode() { return HttpStatus.NOT_FOUND; }
    @Override public ProblemDetail getBody() { return body; }
}
```

`ResponseEntityExceptionHandler` renders any `ErrorResponse` automatically. The trade-off, and why
this project does **not** do it: it couples the exception to `spring-web`. That is fine for an
API-layer exception and a layering violation for a domain one — a domain exception should not know
HTTP exists. Use it for exceptions defined in the web layer; keep the mapping in the advice for
domain exceptions.

`ResponseStatusException` is the ready-made version (`throw new ResponseStatusException(NOT_FOUND,
"...")`). Convenient for one-offs, but it scatters status decisions across the codebase, which is
exactly what a central advice exists to prevent.

The same objection applies to `@ResponseStatus` on an exception class: it works, and it means the
status for that exception now lives somewhere your advice does not.

## What must never reach the client

```java
@ExceptionHandler(DataIntegrityViolationException.class)
ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
    return problem(HttpStatus.CONFLICT, "Conflict",
            "The request conflicts with existing data.", "data-conflict", ex);
}
```

Note what is *not* there: `ex.getMessage()`. That message contains the constraint name, the table,
and often the offending values — a schema leak, and an invitation to probe. `ErrorContractTest`
asserts the response contains neither `uq_orders_order_number` nor `insert into`.

Belt and braces in configuration:

```yaml
server:
  error:
    include-stacktrace: never
    include-message: never      # `always` is convenient locally, a leak in production
    include-binding-errors: never
```

The same test asserts that an internal `RuntimeException` carrying
`NPE at com.example.internal.SecretVault:42` produces a response containing no package names — only
a correlation id, which is what lets support join the opaque response to the full stack trace in
your logs.

## Logging: level by status, exactly once

```java
private void logByStatus(HttpStatus status, Exception ex) {
    if (status.is5xxServerError()) {
        log.error("{} -> {}", ex.getClass().getSimpleName(), status, ex);  // with stack trace
    } else if (log.isDebugEnabled()) {
        log.debug("{} -> {}: {}", ex.getClass().getSimpleName(), status, ex.getMessage());
    }
}
```

**4xx is the client's problem; 5xx is yours.** A 404 logged at ERROR with a stack trace trains
everyone to ignore ERROR. A 500 logged at DEBUG means you hear about it from a customer.

In this project `logByStatus` is called from inside `problem(...)`, so building an error response
*is* logging it — a handler cannot forget. And nothing here logs-and-rethrows: every exception that
reaches the advice is terminal, so it is logged once. "Log and rethrow" is how one failure becomes
five stack traces that look like five failures.

## Anti-patterns

| Pattern | Why it hurts |
| --- | --- |
| `catch (Exception e) { return null; }` | the failure resurfaces as an NPE somewhere unrelated |
| log and rethrow | one failure, five stack traces, nobody sure how many incidents |
| `catch (Exception e) { log.error(...) }` and continue | a corrupted operation reports success |
| `@ResponseStatus` scattered on exception classes | status mapping in n places; no single answer to "what do we return for X?" |
| exceptions for expected outcomes | callers forget cases the compiler never made them handle |
| echoing `ex.getMessage()` for infrastructure exceptions | schema, SQL and internals leak |
| a catch-all with no correlation id | an opaque 500 nobody can trace |

## Testing it

Error responses change silently, so assert them. `ErrorContractTest` is a `@WebMvcTest` — no
database, no HTTP server, ~5 seconds — covering: the standard members are present; extensions are
machine-readable; all three validation flavours produce the same shape; optimistic lock and
duplicate key both map to 409; 503s carry `Retry-After`; and the two leak assertions above.

One test per *status mapping you would be embarrassed to break*.
