package com.example.orders.repo;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderStatus;

import org.springframework.data.jpa.domain.Specification;

/**
 * Composable Criteria predicates for the list endpoint.
 *
 * <p>Why not 2^n derived query methods? Because
 * {@code findByStatusAndCustomerEmailAndTotalAmountGreaterThanAndCreatedAtAfter} is where
 * repositories go to die. Specifications combine with {@code and}/{@code or} and a null-safe
 * {@code allOf}, giving one query method and n small, testable predicates.
 *
 * <p>Alternatives for the same job: Querydsl (type-safe, needs codegen), JPA Criteria by hand,
 * or - very often the best answer for reporting - plain SQL via {@code JdbcClient}.
 */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> hasStatus(OrderStatus status) {
        // Returning null for "no filter" is the idiom Specification.and() understands.
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Order> customerEmailContains(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return null;
        }
        String pattern = "%" + fragment.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("customerEmail")), pattern);
    }

    public static Specification<Order> totalAtLeast(BigDecimal minimum) {
        return minimum == null ? null
                : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("totalAmount"), minimum);
    }

    public static Specification<Order> createdAfter(Instant since) {
        return since == null ? null : (root, query, cb) -> cb.greaterThan(root.get("createdAt"), since);
    }

    /**
     * Join + distinct, the classic "find orders containing a SKU".
     *
     * <p>{@code query.distinct(true)} is needed because the join multiplies rows. Note that
     * distinct + pagination + a fetch join cannot be combined safely: Hibernate would paginate in
     * memory (HHH000104) - fetch the ids first, then the aggregates.
     */
    public static Specification<Order> containsSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return null;
        }
        return (root, query, cb) -> {
            if (query != null) {
                query.distinct(true);
            }
            return cb.equal(root.join("lines").get("sku"), sku);
        };
    }

    /** Null-safe AND over all given specs. */
    @SafeVarargs
    public static Specification<Order> allOf(Specification<Order>... specs) {
        Specification<Order> result = null;
        for (Specification<Order> spec : specs) {
            if (spec == null) {
                continue;
            }
            result = (result == null) ? spec : result.and(spec);
        }
        return result;
    }
}
