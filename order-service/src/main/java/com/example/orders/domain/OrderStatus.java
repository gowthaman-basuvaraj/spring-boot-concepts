package com.example.orders.domain;

/**
 * Persisted with {@code @Enumerated(EnumType.STRING)} - never ORDINAL. With ORDINAL, inserting a
 * new constant in the middle silently re-maps every existing row. The CHECK constraint in
 * V1__init.sql keeps the database honest about the allowed values.
 */
public enum OrderStatus {

    NEW,
    PAID,
    PAYMENT_FAILED,
    CANCELLED,
    SHIPPED;

    public boolean isCancellable() {
        return this == NEW || this == PAID;
    }
}
