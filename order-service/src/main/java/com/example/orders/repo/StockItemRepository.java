package com.example.orders.repo;

import java.util.Optional;

import com.example.orders.domain.StockItem;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

public interface StockItemRepository extends JpaRepository<StockItem, String> {

    /**
     * Pessimistic lock on the inventory row.
     *
     * <p>Optimistic locking (the {@code @Version} column) is the better default, but for a row that
     * every order touches, optimistic retries degenerate into a livelock. {@code SELECT FOR UPDATE}
     * serialises them. Keep such transactions short and always take locks in a consistent order to
     * avoid deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    Optional<StockItem> findBySku(String sku);
}
