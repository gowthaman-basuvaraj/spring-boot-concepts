package com.example.orders.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.orders.api.OrderResponse;
import com.example.orders.config.CacheConfig;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderNotFoundException;
import com.example.orders.domain.OrderStatus;
import com.example.orders.repo.OrderAnalyticsDao;
import com.example.orders.repo.OrderRepository;
import com.example.orders.repo.OrderSpecifications;
import com.example.orders.repo.StatusTotal;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side.
 *
 * <p>{@code @Transactional(readOnly = true)} at class level is the right default for a query
 * service, and it does four distinct things:
 * <ol>
 *   <li>Hibernate flips the session to {@code FlushMode.MANUAL} - no dirty checking, no accidental
 *       UPDATE from a mutated entity.</li>
 *   <li>The JDBC connection is marked read-only, which some drivers and proxies optimise.</li>
 *   <li>It is the routing key for the read replica (see {@code DataSourceConfig}).</li>
 *   <li>It documents intent: a reviewer can see at a glance that this class does not write.</li>
 * </ol>
 *
 * <p>It also keeps the transaction open for the whole method, which is what lets lazy associations
 * be navigated while mapping to DTOs. Mapping INSIDE the transaction - and returning DTOs, never
 * entities - is how you avoid both {@code LazyInitializationException} and the
 * {@code spring.jpa.open-in-view} anti-pattern (which is enabled by default in Boot and should be
 * switched off - see application.yml).
 */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orders;
    private final OrderAnalyticsDao analytics;

    public OrderQueryService(OrderRepository orders, OrderAnalyticsDao analytics) {
        this.orders = orders;
        this.analytics = analytics;
    }

    /**
     * {@code @Cacheable} with an explicit key. The default key generator uses ALL parameters, which
     * is usually right but silently changes when you add a parameter - so be explicit for anything
     * long-lived. {@code unless = "#result == null"} avoids caching misses.
     */
    @Cacheable(cacheNames = CacheConfig.ORDER_BY_NUMBER, key = "#orderNumber", unless = "#result == null")
    public OrderResponse byNumber(String orderNumber) {
        Order order = orders.findWithLinesByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
        return OrderResponse.from(order);
    }

    /**
     * Paged search with optional filters.
     *
     * <p>Returning {@code Page} issues a second COUNT query. For infinite-scroll UIs prefer
     * {@code Slice} (no count) or keyset pagination; {@code OFFSET 50000} is a table scan on every
     * engine, and page 2 of a list that changed under you silently skips rows.
     */
    public Page<OrderResponse> search(OrderStatus status, String email, BigDecimal minTotal,
            String sku, Pageable pageable) {
        return orders.findAll(
                        OrderSpecifications.allOf(
                                OrderSpecifications.hasStatus(status),
                                OrderSpecifications.customerEmailContains(email),
                                OrderSpecifications.totalAtLeast(minTotal),
                                OrderSpecifications.containsSku(sku)),
                        pageable)
                // Mapped inside the transaction: Page.map keeps paging metadata intact.
                .map(OrderResponse::from);
    }

    public List<StatusTotal> statusTotalsSince(Instant since) {
        return orders.statusTotalsSince(since);
    }

    public List<OrderAnalyticsDao.DailyRevenue> dailyRevenue(Instant since) {
        return analytics.dailyRevenueSince(since);
    }

    @Cacheable(cacheNames = CacheConfig.CUSTOMER_LTV, key = "#email")
    public BigDecimal lifetimeValue(String email) {
        return analytics.lifetimeValue(email).orElse(BigDecimal.ZERO);
    }
}
