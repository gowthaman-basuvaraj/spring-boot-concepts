package com.example.orders.service;

import java.math.BigDecimal;

import com.example.orders.domain.Payment;
import com.example.orders.payments.PaymentGatewayClient.ChargeOutcome;
import com.example.orders.repo.PaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the payment-attempt row in its OWN transaction.
 *
 * <p>{@code REQUIRES_NEW} is right here: if the caller's transaction later rolls back, we still
 * want the forensic record of what the gateway said. This is the legitimate use of REQUIRES_NEW -
 * not a workaround for a badly drawn transaction boundary.
 *
 * <p>A separate top-level bean rather than a nested class, because the method must be invoked
 * through a proxy from {@code PaymentOrchestrator}.
 */
@Service
public class PaymentAttemptRecorder {

    private final PaymentRepository payments;

    public PaymentAttemptRecorder(PaymentRepository payments) {
        this.payments = payments;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String orderNumber, BigDecimal amount, ChargeOutcome outcome) {
        int attemptNo = (int) payments.countByOrderNumber(orderNumber) + 1;
        Payment.Result result = switch (outcome.status()) {
            case SUCCEEDED -> Payment.Result.SUCCEEDED;
            case DECLINED -> Payment.Result.DECLINED;
            case UNAVAILABLE, FAILED -> Payment.Result.ERROR;
        };
        payments.save(new Payment(orderNumber, amount, attemptNo, result,
                outcome.providerReference(), outcome.detail()));
    }
}
