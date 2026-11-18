package com.example.orders.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * The repository: derived queries, JPQL, projections, entity graphs, locking and a custom fragment.
 *
 * <p>Interfaces composed here:
 * <ul>
 *   <li>{@code JpaRepository} - CRUD + paging + flush/batch methods.</li>
 *   <li>{@code JpaSpecificationExecutor} - dynamic {@code WHERE} built from Criteria API, the right
 *       tool for optional filters (see {@link OrderSpecifications}).</li>
 *   <li>{@link OrderRepositoryCustom} - a <em>fragment</em>: when you need the EntityManager, add a
 *       fragment interface plus an {@code Impl} class. Spring Data wires it into the same proxy, so
 *       callers still see one repository.</li>
 * </ul>
 */
public interface OrderRepository extends JpaRepository<Order, Long>,
        JpaSpecificationExecutor<Order>, OrderRepositoryCustom {

    /**
     * Derived query. The method name is parsed into a query at startup, so a typo fails the context
     * rather than production. Returning {@code Optional} documents "zero or one".
     */
    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    /**
     * Fixes N+1 for a single aggregate: one query with a LEFT JOIN FETCH on lines.
     * {@code EntityGraph} is declarative and composable; {@code JOIN FETCH} in JPQL is the
     * alternative. Never "fix" N+1 by making the association EAGER.
     */
    @EntityGraph(attributePaths = "lines")
    Optional<Order> findWithLinesByOrderNumber(String orderNumber);

    /**
     * Interface projection: Spring Data generates a proxy and - crucially - selects only these
     * columns. Avoids dragging whole aggregates into a list endpoint.
     */
    interface OrderSummary {
        String getOrderNumber();

        String getCustomerEmail();

        OrderStatus getStatus();

        java.math.BigDecimal getTotalAmount();
    }

    Page<OrderSummary> findAllByStatus(OrderStatus status, Pageable pageable);

    /**
     * Explicit JPQL with a named parameter. Prefer {@code @Param} names over positional {@code ?1}.
     * Add {@code countQuery} when the derived count query would be expensive or wrong.
     */
    @Query("""
            select o from Order o
            where o.customerEmail = :email
              and o.createdAt >= :since
            order by o.createdAt desc
            """)
    List<Order> findRecentForCustomer(@Param("email") String email, @Param("since") Instant since);

    /**
     * Bulk update: ONE statement instead of N loaded entities.
     *
     * <p>{@code @Modifying} is mandatory. The two flags are the part people get wrong:
     * <ul>
     *   <li>{@code flushAutomatically} - push pending changes BEFORE the bulk statement, otherwise
     *       the UPDATE may not see them.</li>
     *   <li>{@code clearAutomatically} - evict the first-level cache AFTER, otherwise managed
     *       entities in the session still hold the stale status and may write it back.</li>
     * </ul>
     * Bulk JPQL also bypasses optimistic locking and entity callbacks - that is the trade.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Order o set o.status = :to, o.updatedAt = :now where o.status = :from")
    int bulkUpdateStatus(@Param("from") OrderStatus from, @Param("to") OrderStatus to,
            @Param("now") Instant now);

    /**
     * Pessimistic write lock: {@code SELECT ... FOR UPDATE}. Use when you must serialise access to
     * a hot row (inventory, balances) and optimistic retries would thrash.
     *
     * <p>The query hint sets a lock timeout so a stuck transaction fails fast instead of piling up
     * connections. H2 and PostgreSQL honour this differently - always test it on the real engine.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select o from Order o where o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);
}
