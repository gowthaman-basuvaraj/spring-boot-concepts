package com.example.orders.service;

import java.util.UUID;

import com.example.orders.api.CreateOrderRequest;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderNotFoundException;
import com.example.orders.events.OrderPlacedEvent;
import com.example.orders.repo.OrderRepository;
import com.example.platform.audit.Audited;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write-side application service.
 *
 * <h2>Where the transaction boundary belongs</h2>
 * Here - at the service, one transaction per use case. Not in the controller (you would be holding
 * a database connection while serialising JSON) and not in the repository (every repository call
 * its own transaction means no atomicity at all).
 *
 * <h2>The rule that matters most</h2>
 * <b>No remote calls inside a transaction.</b> The charge happens AFTER commit, via an event - see
 * {@code OrderEventListeners}. If you call a payment gateway while holding a transaction open, a
 * 5-second gateway timeout becomes a 5-second row lock plus a 5-second connection checkout, and
 * your pool is gone at 20 requests per second. The database is not a mutex for the internet.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, InventoryService inventory, OutboxService outbox,
            ApplicationEventPublisher events) {
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
        this.events = events;
    }

    /**
     * Places an order atomically: reserve stock, persist the aggregate, write the outbox row.
     *
     * <p>{@code @Transactional} notes:
     * <ul>
     *   <li>Default propagation REQUIRED: joins the caller's transaction or starts one.</li>
     *   <li>Default rollback: unchecked exceptions and {@code Error} only. A checked exception
     *       COMMITS unless you add {@code rollbackFor}. Our domain exceptions are unchecked
     *       precisely so this default is correct.</li>
     *   <li>{@code timeout} bounds the whole unit of work; without it a lock wait can outlive the
     *       HTTP client that is waiting for the answer.</li>
     *   <li>Always {@code org.springframework.transaction.annotation.Transactional}, not
     *       {@code jakarta.transaction.Transactional} - the Spring one understands propagation,
     *       isolation, readOnly and rollback rules.</li>
     * </ul>
     */
    @Transactional(timeout = 10)
    @Audited(action = "order.place")
    public Order placeOrder(CreateOrderRequest request, String actor) {
        String orderNumber = UUID.randomUUID().toString();
        Order order = new Order(orderNumber, request.customerEmail(), request.currency());
        request.lines().forEach(line -> order.addLine(line.sku(), line.quantity(), line.unitPrice()));

        // Reserve first: fail before we have written anything expensive.
        request.lines().forEach(line -> inventory.reserve(line.sku(), line.quantity()));

        Order saved = orders.save(order);

        OrderPlacedEvent event = new OrderPlacedEvent(orderNumber, saved.getCustomerEmail(),
                saved.getTotalAmount(), saved.getCurrency(), actor);

        // Same transaction as the business change - that is the whole point of the outbox.
        // Serialise a purpose-built event record, never the entity: an entity payload drags in
        // lazy associations and, with a bidirectional mapping, recurses forever.
        outbox.enqueue("Order", orderNumber, "OrderPlaced", event);

        // Published now, DELIVERED after commit (@TransactionalEventListener). Side effects that
        // must not happen when the transaction rolls back belong here, not inline.
        events.publishEvent(event);

        log.info("placed order {} for {} ({} {})", orderNumber, saved.getCustomerEmail(),
                saved.getTotalAmount(), saved.getCurrency());
        return saved;
    }

    /**
     * Cancels an order.
     *
     * <p>Reads the row with a pessimistic lock because two concurrent cancels would otherwise both
     * pass the state check. The optimistic {@code @Version} column would also catch it - and return
     * a 409 - but for a short, hot critical section the explicit lock is kinder to the client.
     */
    @Transactional
    @Audited(action = "order.cancel")
    @CacheEvict(cacheNames = com.example.orders.config.CacheConfig.ORDER_BY_NUMBER, key = "#orderNumber")
    public Order cancel(String orderNumber) {
        Order order = orders.findByOrderNumberForUpdate(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
        order.cancel();
        order.getLines().forEach(line -> inventory.release(line.getSku(), line.getQuantity()));
        // No explicit save(): the entity is managed, so the flush at commit writes the UPDATE.
        // Calling save() here would be harmless but is cargo-cult code.
        return order;
    }

    /**
     * Marks the order paid (or failed) once the charge completes.
     *
     * <p><b>REQUIRES_NEW is load-bearing here, not decoration.</b> This method is called from an
     * {@code @TransactionalEventListener(AFTER_COMMIT)} handler. At that point the original
     * transaction has committed, but its {@code EntityManager} is still bound to the thread (it is
     * only released in {@code afterCompletion}). A plain {@code REQUIRED} would therefore join
     * that already-committed, non-transactional EntityManager: the entity is mutated in memory,
     * no transaction is ever committed, and the UPDATE is <em>silently lost</em>. You observe an
     * order that stays NEW even though the logs clearly show a successful charge.
     *
     * <p>REQUIRES_NEW forces a genuinely new transaction, and the write lands. The same rule
     * applies to every database write performed from an AFTER_COMMIT hook.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void applyPaymentResult(String orderNumber, boolean success, String detail) {
        Order order = orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
        if (success) {
            order.markPaid();
        } else {
            order.markPaymentFailed(detail);
        }
    }
}
