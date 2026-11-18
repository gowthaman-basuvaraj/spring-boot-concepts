package com.example.orders.payments;

import java.time.Duration;

/**
 * Exception taxonomy for the payment gateway.
 *
 * <p><b>This class is the single most important design decision in retry handling.</b> Retrying is
 * only safe and useful for <em>transient</em> failures. Retrying a 400 just burns your error budget
 * three times as fast; retrying a non-idempotent POST can charge the customer twice. So the client
 * must classify every failure before any retry policy is applied:
 *
 * <table border="1">
 *   <caption>Classification</caption>
 *   <tr><th>Signal</th><th>Class</th><th>Why</th></tr>
 *   <tr><td>Connect timeout, read timeout, connection reset, DNS blip</td>
 *       <td>Transient</td><td>The request may never have been processed.</td></tr>
 *   <tr><td>502, 503, 504</td><td>Transient</td><td>Infrastructure, not your payload.</td></tr>
 *   <tr><td>429</td><td>Transient, honour {@code Retry-After}</td><td>Server told you to slow down.</td></tr>
 *   <tr><td>500</td><td>Transient only if the operation is idempotent</td>
 *       <td>It may have half-succeeded. This is what the idempotency key is for.</td></tr>
 *   <tr><td>400, 401, 403, 404, 409, 422</td><td>Permanent</td>
 *       <td>Nothing about waiting changes the answer.</td></tr>
 * </table>
 */
public final class PaymentExceptions {

    private PaymentExceptions() {
    }

    /** Base type so callers - and {@code @Recover} methods - can match both kinds. */
    public abstract static class PaymentException extends RuntimeException {

        protected PaymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Worth retrying. */
    public static class TransientPaymentException extends PaymentException {

        private final Duration retryAfter;

        public TransientPaymentException(String message) {
            this(message, null, null);
        }

        public TransientPaymentException(String message, Throwable cause, Duration retryAfter) {
            super(message, cause);
            this.retryAfter = retryAfter;
        }

        /** Server-provided backoff hint from the {@code Retry-After} header, if any. */
        public Duration getRetryAfter() {
            return retryAfter;
        }
    }

    /** Never retry. */
    public static class PermanentPaymentException extends PaymentException {

        private final int status;

        public PermanentPaymentException(String message, int status) {
            super(message, null);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** The gateway said "no" for business reasons (insufficient funds, fraud block). */
    public static class PaymentDeclinedException extends PermanentPaymentException {

        public PaymentDeclinedException(String message) {
            super(message, 402);
        }
    }
}
