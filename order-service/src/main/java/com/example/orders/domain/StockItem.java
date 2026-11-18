package com.example.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Inventory row, used to demonstrate locking.
 *
 * <p>The primary key is a natural key (the SKU), so no {@code @GeneratedValue}. Note that
 * {@code save()} on an entity with an assigned id cannot tell insert from update, so Spring Data
 * issues a SELECT first (or you implement {@code Persistable#isNew()}). That is the hidden cost of
 * natural keys.
 */
@Entity
@Table(name = "stock_item")
public class StockItem {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @Version
    @Column(nullable = false)
    private long version;

    protected StockItem() {
    }

    public StockItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
    }

    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (available < quantity) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        this.available -= quantity;
        this.reserved += quantity;
    }

    public void release(int quantity) {
        this.reserved = Math.max(0, this.reserved - quantity);
        this.available += quantity;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public int getReserved() {
        return reserved;
    }

    public long getVersion() {
        return version;
    }
}
