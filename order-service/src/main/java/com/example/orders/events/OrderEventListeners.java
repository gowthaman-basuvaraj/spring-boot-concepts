package com.example.orders.events;

import com.example.orders.config.CacheConfig;
import com.example.orders.service.PaymentOrchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event handling, and the difference between the three listener flavours.
 *
 * <table border="1">
 *   <caption>Listener semantics</caption>
 *   <tr><th>Annotation</th><th>Runs</th><th>Thread</th><th>Transaction</th></tr>
 *   <tr><td>{@code @EventListener}</td><td>immediately, at publish time</td><td>publisher's</td>
 *       <td>inside the publisher's transaction; an exception rolls it back</td></tr>
 *   <tr><td>{@code @TransactionalEventListener(AFTER_COMMIT)}</td><td>after commit</td>
 *       <td>publisher's</td><td>no active transaction; writes need a new one</td></tr>
 *   <tr><td>{@code @Async @TransactionalEventListener}</td><td>after commit</td><td>pool thread</td>
 *       <td>none; the publisher is already gone</td></tr>
 * </table>
 *
 * <p>Two traps:
 * <ul>
 *   <li>An {@code AFTER_COMMIT} listener that writes to the database needs
 *       {@code @Transactional(REQUIRES_NEW)}, otherwise the write is silently discarded (Spring
 *       logs "Transaction synchronization is not active"). That is why
 *       {@code PaymentOrchestrator} opens its own transaction.</li>
 *   <li>If the event is published OUTSIDE a transaction, an {@code AFTER_COMMIT} listener never
 *       runs at all unless you set {@code fallbackExecution = true}. This bites hardest in tests.</li>
 * </ul>
 */
@Component
public class OrderEventListeners {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListeners.class);

    private final PaymentOrchestrator payments;
    private final CacheManager cacheManager;

    public OrderEventListeners(PaymentOrchestrator payments, CacheManager cacheManager) {
        this.payments = payments;
        this.cacheManager = cacheManager;
    }

    /**
     * Charge the card only once the order is durably committed. Calling the gateway inside the
     * placing transaction would hold a connection and row locks for the gateway's entire latency.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void chargeAfterCommit(OrderPlacedEvent event) {
        log.debug("order {} committed - charging", event.orderNumber());
        payments.chargeForOrder(event);
    }

    /**
     * Cache invalidation after commit, so a concurrent reader can never repopulate the cache from
     * data that is about to be rolled back.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void evictCachesAfterCommit(OrderPlacedEvent event) {
        var cache = cacheManager.getCache(CacheConfig.CUSTOMER_LTV);
        if (cache != null) {
            cache.evict(event.customerEmail());
        }
    }

    /**
     * Fire-and-forget work. {@code @Async} moves it off the request thread; combined with
     * {@code @TransactionalEventListener} it runs after commit AND off-thread.
     *
     * <p>Exceptions here are invisible to the caller by design - which is exactly why the executor
     * in {@code AsyncConfig} installs an uncaught-exception handler.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyAfterCommit(OrderPlacedEvent event) {
        log.info("[async] notifying customer {} about order {}", event.customerEmail(), event.orderNumber());
    }

    /**
     * Runs on rollback. Good place for metrics and alerting; do NOT attempt to "undo" anything
     * here - nothing was committed.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onRollback(OrderPlacedEvent event) {
        log.warn("order {} rolled back", event.orderNumber());
    }

    /**
     * Plain listener: runs synchronously, inside the publisher's transaction. Only for work that
     * genuinely must be atomic with it - and remember that throwing here fails the whole use case.
     */
    @EventListener
    public void auditInline(OrderPlacedEvent event) {
        log.debug("inline listener saw order {} (still inside the transaction)", event.orderNumber());
    }
}
