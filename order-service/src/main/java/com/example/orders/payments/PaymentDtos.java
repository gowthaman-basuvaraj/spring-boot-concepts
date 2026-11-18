package com.example.orders.payments;

import java.math.BigDecimal;

/** Wire contracts for the payment gateway. Records keep them immutable and obviously DTOs. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * {@code idempotencyKey} is what makes retrying a POST safe: the gateway must return the
     * original result for a repeated key instead of charging again. If a downstream API does not
     * support idempotency keys, you cannot safely retry its writes - you can only retry reads and
     * record the uncertainty.
     */
    public record ChargeRequest(String orderNumber, BigDecimal amount, String currency, String idempotencyKey) {
    }

    public record ChargeResponse(String providerReference, String status, String message) {

        public boolean succeeded() {
            return "AUTHORISED".equalsIgnoreCase(status) || "CAPTURED".equalsIgnoreCase(status);
        }
    }
}
