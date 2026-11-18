package com.example.orders.payments;

import com.example.orders.payments.PaymentDtos.ChargeRequest;
import com.example.orders.payments.PaymentDtos.ChargeResponse;
import com.example.orders.payments.PaymentExceptions.PermanentPaymentException;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The outer resilience layer: circuit breaker, bulkhead, and a fallback.
 *
 * <h2>Circuit breaker</h2>
 * A breaker converts "slow failure" into "fast failure". While open, calls are rejected immediately
 * with {@link CallNotPermittedException} - no socket, no thread, no 5-second timeout. After
 * {@code wait-duration-in-open-state} it moves to HALF_OPEN and lets a few probe calls through.
 * Configuration lives in {@code application.yml} under {@code resilience4j.circuitbreaker}.
 *
 * <p>The two settings people get wrong:
 * <ul>
 *   <li>{@code minimum-number-of-calls} - without it, the first failed call on a quiet service
 *       opens the breaker at 100% failure rate.</li>
 *   <li>{@code ignore-exceptions} - business failures (a decline, a validation error) must NOT
 *       count towards opening the breaker. Only infrastructure failures should.</li>
 * </ul>
 *
 * <h2>Bulkhead</h2>
 * Caps concurrent calls to this dependency. Without it, one dependency that slows from 50ms to 5s
 * will consume every request thread in the service and take down endpoints that have nothing to do
 * with payments. Bulkheads are how you keep a partial outage partial.
 *
 * <h2>Fallback</h2>
 * The fallback method must have the SAME signature plus a trailing {@code Throwable} parameter.
 * Resilience4j picks the most specific match, so you can have one fallback per exception type.
 * A fallback that hides a real failure is worse than no fallback: here we degrade to "payment
 * pending" and let the business process retry, rather than pretending the charge succeeded.
 */
@Component
public class PaymentGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayClient.class);

    public static final String INSTANCE = "payments";

    private final PaymentGatewayCaller caller;

    public PaymentGatewayClient(PaymentGatewayCaller caller) {
        this.caller = caller;
    }

    /**
     * Annotation order matters. Resilience4j applies its aspects in a configured order
     * (see {@code resilience4j.*.aspect-order} in application.yml); by default the chain is
     * Retry -&gt; CircuitBreaker -&gt; RateLimiter -&gt; TimeLimiter -&gt; Bulkhead, outermost first.
     * Here spring-retry sits one bean deeper, inside the breaker.
     */
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "chargeFallback")
    @Bulkhead(name = INSTANCE)
    public ChargeOutcome charge(ChargeRequest request) {
        ChargeResponse response = caller.charge(request);
        return ChargeOutcome.succeeded(response.providerReference());
    }

    /** Breaker is open: do not even try. Degrade explicitly. */
    ChargeOutcome chargeFallback(ChargeRequest request, CallNotPermittedException ex) {
        log.warn("payment circuit breaker OPEN - order {} deferred", request.orderNumber());
        return ChargeOutcome.unavailable("payment provider circuit open");
    }

    /** A permanent failure is a real answer, not an outage: surface it as a decline. */
    ChargeOutcome chargeFallback(ChargeRequest request, PermanentPaymentException ex) {
        log.info("payment permanently rejected for order {}: {}", request.orderNumber(), ex.getMessage());
        return ChargeOutcome.declined(ex.getMessage());
    }

    /**
     * Catch-all. Required: without a {@code Throwable} overload, any exception type you forgot
     * propagates and the fallback looks like it "did not work".
     */
    ChargeOutcome chargeFallback(ChargeRequest request, Throwable ex) {
        log.error("payment failed for order {}: {}", request.orderNumber(), ex.toString());
        return ChargeOutcome.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    /**
     * An explicit outcome type instead of exceptions-as-control-flow at the service boundary.
     * The caller can then decide the business reaction (retry later, cancel, notify) in one place.
     */
    public record ChargeOutcome(Status status, String providerReference, String detail) {

        public enum Status { SUCCEEDED, DECLINED, UNAVAILABLE, FAILED }

        static ChargeOutcome succeeded(String reference) {
            return new ChargeOutcome(Status.SUCCEEDED, reference, null);
        }

        static ChargeOutcome declined(String detail) {
            return new ChargeOutcome(Status.DECLINED, null, detail);
        }

        static ChargeOutcome unavailable(String detail) {
            return new ChargeOutcome(Status.UNAVAILABLE, null, detail);
        }

        static ChargeOutcome failed(String detail) {
            return new ChargeOutcome(Status.FAILED, null, detail);
        }

        public boolean isSuccess() {
            return status == Status.SUCCEEDED;
        }

        /** Worth trying again later (outage/unknown), as opposed to a definitive decline. */
        public boolean isRetryableLater() {
            return status == Status.UNAVAILABLE || status == Status.FAILED;
        }
    }
}
