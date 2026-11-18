package com.example.orders.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A domain event.
 *
 * <p>Since Spring 4.2 an event does not have to extend {@code ApplicationEvent} - any object will
 * do, which is why a record is the natural choice. Keep events immutable and self-contained: they
 * may be handled after the transaction has committed, on another thread, where a lazily-loaded
 * entity reference would blow up with {@code LazyInitializationException}.
 */
public record OrderPlacedEvent(
        String orderNumber,
        String customerEmail,
        BigDecimal amount,
        String currency,
        String actor,
        Instant occurredAt) {

    public OrderPlacedEvent(String orderNumber, String customerEmail, BigDecimal amount,
            String currency, String actor) {
        this(orderNumber, customerEmail, amount, currency, actor, Instant.now());
    }
}
