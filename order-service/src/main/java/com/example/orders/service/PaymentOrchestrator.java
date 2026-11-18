package com.example.orders.service;

import com.example.orders.events.OrderPlacedEvent;
import com.example.orders.payments.PaymentDtos.ChargeRequest;
import com.example.orders.payments.PaymentGatewayClient;
import com.example.orders.payments.PaymentGatewayClient.ChargeOutcome;
import com.example.platform.audit.Audited;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Drives the charge and records the result.
 *
 * <p>Note the shape: <b>no transaction around the HTTP call</b>. The method itself is
 * non-transactional; only the two short database writes are, each in its own transaction. This is
 * the pattern to copy whenever a use case mixes local state with a remote call.
 */
@Service
public class PaymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrator.class);

    private final PaymentGatewayClient gateway;
    private final PaymentAttemptRecorder attempts;
    private final OrderService orders;

    public PaymentOrchestrator(PaymentGatewayClient gateway, PaymentAttemptRecorder attempts,
            OrderService orders) {
        this.gateway = gateway;
        this.attempts = attempts;
        this.orders = orders;
    }

    /**
     * {@code @Observed} produces a timer plus a span (when tracing is configured) named
     * {@code payments.charge} - one annotation, both signals.
     */
    @Observed(name = "payments.charge", contextualName = "charge-order")
    @Audited(action = "payment.charge")
    public void chargeForOrder(OrderPlacedEvent event) {
        // Stable idempotency key: same order, same key, no matter how many times we retry or
        // how many instances process the event. A UUID generated per attempt would be a bug.
        ChargeRequest request = new ChargeRequest(event.orderNumber(), event.amount(),
                event.currency(), "order-" + event.orderNumber());

        ChargeOutcome outcome = gateway.charge(request);

        attempts.record(event.orderNumber(), event.amount(), outcome);

        switch (outcome.status()) {
            case SUCCEEDED -> orders.applyPaymentResult(event.orderNumber(), true, outcome.providerReference());
            case DECLINED -> orders.applyPaymentResult(event.orderNumber(), false, outcome.detail());
            // Unknown/unavailable: do NOT mark the order failed. The outbox poller or a scheduled
            // reconciliation job will try again; marking it failed here would lose a payment that
            // may well have succeeded on the provider's side.
            case UNAVAILABLE, FAILED -> log.warn("charge for {} inconclusive ({}), leaving order NEW",
                    event.orderNumber(), outcome.detail());
            default -> throw new IllegalStateException("unhandled outcome " + outcome.status());
        }
    }

}
