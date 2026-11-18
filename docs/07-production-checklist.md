# 7. Production checklist

The list to run through before a Spring Boot service ships. Every line is implemented or explicitly
discussed somewhere in this repo.

## Configuration

- [ ] Secrets come from the environment or a vault, never from a file in the image.
- [ ] `@ConfigurationProperties` + `@Validated` for everything, so bad config fails at **startup**.
- [ ] Profile-specific files for what differs; no `if (env.equals("prod"))` in code.
- [ ] `/actuator/configprops` and `/actuator/env` reviewed once, so you know what is really set.

## Database

- [ ] Pool size small (around 10) and justified; `connection-timeout` set.
- [ ] `max-lifetime` shorter than any DB/proxy idle timeout.
- [ ] `leak-detection-threshold` on.
- [ ] `auto-commit: false` if `hibernate.connection.provider_disables_autocommit=true`.
- [ ] Schema owned by migrations; `ddl-auto: validate`.
- [ ] Migrations append-only; index creation separated out.
- [ ] `spring.jpa.open-in-view: false`.
- [ ] Every foreign key indexed (PostgreSQL does not do it for you).
- [ ] Page size capped.
- [ ] Lock timeout set on every pessimistic lock.

## Transactions

- [ ] Boundary at the service, one transaction per use case.
- [ ] No remote calls inside a transaction.
- [ ] Domain exceptions unchecked, or `rollbackFor` declared.
- [ ] Writes from `AFTER_COMMIT` hooks use `REQUIRES_NEW`.
- [ ] `timeout` set on anything that can take a lock.
- [ ] You know how to switch on `org.springframework.transaction.interceptor` logging.

## Outbound calls

- [ ] Connect **and** read timeouts on every client.
- [ ] Failures classified transient vs permanent before any retry.
- [ ] Retry at exactly one layer, with jitter and a cap.
- [ ] Idempotency keys on retried writes.
- [ ] Circuit breaker with `minimum-number-of-calls` and `ignore-exceptions` set.
- [ ] Bulkhead per dependency.
- [ ] `Retry-After` honoured on the way in and emitted on the way out.
- [ ] Retry attempts and exhaustions exported as metrics.

## Threads and shutdown

- [ ] No `SimpleAsyncTaskExecutor` (unbounded) - a bounded pool with a bounded queue.
- [ ] A `TaskDecorator` propagating MDC / SecurityContext.
- [ ] `AsyncUncaughtExceptionHandler` installed, or return `CompletableFuture`.
- [ ] `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`, matched to the
      orchestrator's grace period.
- [ ] `@Scheduled` jobs guarded against multi-instance duplication; scheduler pool size > 1.

## Observability

- [ ] Correlation id generated or propagated, in the MDC, in the log pattern, echoed on responses,
      forwarded to downstream calls, and returned in error bodies.
- [ ] Common metric tags set once, centrally.
- [ ] High-cardinality tags filtered out.
- [ ] Health groups split: `liveness` vs `readiness`; readiness contains only what blocks serving.
- [ ] Health indicators do **not** call dependencies.
- [ ] Structured (JSON) logging in production; no SQL or bind parameters logged.
- [ ] Actuator on its own port, secured, with a minimal `exposure.include`.

## API

- [ ] RFC 7807 problem details, with one exception-to-status mapping.
- [ ] No stack traces or internal messages leaking (`server.error.include-*: never`).
- [ ] 201 + `Location` on creation; ETag / `If-Match` where concurrency matters.
- [ ] Unknown JSON properties rejected, or accepted deliberately as a versioning decision.
- [ ] Request size and page size limits.

## Build and test

- [ ] A `@SpringBootTest` that only loads the context - it catches most misconfigurations.
- [ ] Slice tests per layer; full integration tests for the critical flows.
- [ ] Auto-configuration tested with `ApplicationContextRunner` + `FilteredClassLoader`.
- [ ] `build-info` generated, so `/actuator/info` reports the real version.
- [ ] No test depends on execution order or on a port another test might take.
