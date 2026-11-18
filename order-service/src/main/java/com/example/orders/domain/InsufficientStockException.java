package com.example.orders.domain;

/**
 * A domain exception. Unchecked on purpose: {@code @Transactional} rolls back on
 * {@code RuntimeException} by default, and a checked exception here would silently COMMIT the
 * half-finished transaction unless every caller remembered {@code rollbackFor}.
 */
public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super("Insufficient stock for " + sku + ": requested " + requested + ", available " + available);
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
