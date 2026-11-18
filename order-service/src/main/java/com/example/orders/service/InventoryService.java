package com.example.orders.service;

import com.example.orders.domain.InsufficientStockException;
import com.example.orders.domain.StockItem;
import com.example.orders.repo.StockItemRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory reservation - the canonical "must participate in the caller's transaction" service.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StockItemRepository stock;

    public InventoryService(StockItemRepository stock) {
        this.stock = stock;
    }

    /**
     * {@code MANDATORY}: refuses to run without an existing transaction.
     *
     * <p>This is an underused and excellent tool. Reserving stock outside a transaction is a bug,
     * and MANDATORY turns that bug into an immediate
     * {@code IllegalTransactionStateException: No existing transaction found} at the call site -
     * instead of a silently auto-committed decrement that cannot be rolled back when the order
     * fails two lines later.
     *
     * <p>Isolation is left at the default: the pessimistic row lock taken by
     * {@code findBySku} already serialises concurrent reservations, which is cheaper and more
     * portable than raising the isolation level. {@code SERIALIZABLE} would also work and would
     * cost you throughput plus engine-specific retry handling.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(String sku, int quantity) {
        StockItem item = stock.findBySku(sku)
                .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));
        item.reserve(quantity);
        log.debug("reserved {} x {} (available now {})", quantity, sku, item.getAvailable());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String sku, int quantity) {
        stock.findBySku(sku).ifPresent(item -> item.release(quantity));
    }

    /**
     * A read that deliberately does NOT need a transaction.
     *
     * <p>{@code SUPPORTS} joins one if present, otherwise runs non-transactionally. Combined with
     * {@code readOnly} it is the cheapest way to expose a lookup: no transaction is started for a
     * single SELECT, and when called from inside a bigger transaction it still sees that
     * transaction's uncommitted changes.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true, isolation = Isolation.DEFAULT)
    public int available(String sku) {
        return stock.findById(sku).map(StockItem::getAvailable).orElse(0);
    }
}
