package com.example.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per payment attempt. Written in its own {@code REQUIRES_NEW} transaction so the attempt
 * log survives even when the surrounding business transaction rolls back - see
 * {@code PaymentAttemptRecorder}.
 */
@Entity
@Table(name = "payment_attempt")
public class Payment {

    public enum Result { SUCCEEDED, DECLINED, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, length = 36)
    private String orderNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Result result;

    @Column(name = "provider_reference", length = 64)
    private String providerReference;

    @Column(length = 512)
    private String detail;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt = Instant.now();

    protected Payment() {
    }

    public Payment(String orderNumber, BigDecimal amount, int attemptNo, Result result,
            String providerReference, String detail) {
        this.orderNumber = orderNumber;
        this.amount = amount;
        this.attemptNo = attemptNo;
        this.result = result;
        this.providerReference = providerReference;
        this.detail = detail == null ? null : detail.substring(0, Math.min(512, detail.length()));
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public Result getResult() {
        return result;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }
}
