package com.example.orders.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

/**
 * Aggregate root.
 *
 * <h2>Mapping decisions worth defending in a design review</h2>
 * <ul>
 *   <li><b>{@code GenerationType.IDENTITY} vs {@code SEQUENCE}</b>: IDENTITY forces Hibernate to
 *       execute the INSERT immediately on {@code persist()}, which disables JDBC batching. On
 *       PostgreSQL/Oracle prefer SEQUENCE with a pooled allocation size. IDENTITY is used here
 *       because H2/MySQL auto-increment is what most people start with - the comment is the
 *       lesson.</li>
 *   <li><b>{@code @Version}</b>: optimistic locking. Hibernate adds {@code WHERE version = ?} to
 *       every UPDATE and throws {@code OptimisticLockingFailureException} when the row moved under
 *       you. This is the correct default for HTTP APIs - map it to HTTP 409 and let the client
 *       retry. It also lets you implement ETag/If-Match.</li>
 *   <li><b>Owning side</b>: {@code OrderLine.order} owns the FK; {@code mappedBy} tells Hibernate
 *       not to maintain a second, redundant update. {@code orphanRemoval} + {@code CascadeType.ALL}
 *       is right for a true composition (lines cannot exist without their order) and wrong for
 *       associations to shared entities.</li>
 *   <li><b>{@code FetchType.LAZY} everywhere</b>: the one-to-many default is already LAZY, but
 *       {@code @ManyToOne}/{@code @OneToOne} default to EAGER, which quietly turns one query into
 *       twelve joins. Always declare it.</li>
 *   <li><b>{@code @BatchSize}</b>: when you do need the lines of N orders, Hibernate fetches them
 *       in batches of 20 instead of N+1 round trips.</li>
 *   <li><b>No Lombok {@code @Data}</b>: {@code equals}/{@code hashCode} over a mutable collection
 *       plus a generated id is how entities "disappear" from HashSets. Identity is the business
 *       key ({@code orderNumber}) here.</li>
 * </ul>
 */
@Entity
@Table(name = "orders")
public class Order extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, updatable = false, length = 36)
    private String orderNumber;

    @Column(name = "customer_email", nullable = false, length = 255)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status = OrderStatus.NEW;

    /**
     * Money: {@code BigDecimal} with explicit precision/scale, never {@code double}.
     * {@code numeric(19,2)} in the DDL; a mismatch here is caught at startup by ddl-auto=validate.
     */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {
        // JPA requires a no-arg constructor; keep it non-public so application code uses the real one.
    }

    public Order(String orderNumber, String customerEmail, String currency) {
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber");
        this.customerEmail = Objects.requireNonNull(customerEmail, "customerEmail");
        this.currency = currency == null ? "EUR" : currency;
    }

    /**
     * Both sides of a bidirectional association must be kept in sync by the application; JPA will
     * not do it for you. Forgetting this is why "the child saved but the parent's collection is
     * empty until I restart".
     */
    public Order addLine(String sku, int quantity, BigDecimal unitPrice) {
        OrderLine line = new OrderLine(this, sku, quantity, unitPrice);
        this.lines.add(line);
        recalculateTotal();
        return this;
    }

    public void recalculateTotal() {
        this.totalAmount = lines.stream()
                .map(OrderLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
        this.failureReason = null;
    }

    public void markPaymentFailed(String reason) {
        this.status = OrderStatus.PAYMENT_FAILED;
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(512, reason.length()));
    }

    public void cancel() {
        if (!status.isCancellable()) {
            throw new IllegalStateException("Order " + orderNumber + " cannot be cancelled in status " + status);
        }
        this.status = OrderStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public long getVersion() {
        return version;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        // Hibernate proxies: compare the business key, and never use getClass() == other.getClass().
        if (!(other instanceof Order that)) {
            return false;
        }
        return orderNumber != null && orderNumber.equals(that.orderNumber);
    }

    @Override
    public int hashCode() {
        return orderNumber == null ? 31 : orderNumber.hashCode();
    }

    @Override
    public String toString() {
        return "Order[" + orderNumber + ", " + status + ", " + totalAmount + " " + currency + "]";
    }
}
