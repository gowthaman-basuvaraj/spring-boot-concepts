package com.example.orders.repo;

import java.math.BigDecimal;

import com.example.orders.domain.OrderStatus;

/**
 * Query DTO used by a JPQL constructor expression.
 *
 * <p>Top-level on purpose: a nested type would have to be written as {@code Outer$Inner} inside
 * JPQL, which Hibernate's parser does not reliably accept. Keep DTOs referenced from queries
 * top-level.
 */
public record StatusTotal(OrderStatus status, long count, BigDecimal total) {
}
