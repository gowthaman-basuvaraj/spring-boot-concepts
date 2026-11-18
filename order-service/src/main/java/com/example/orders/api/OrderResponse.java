package com.example.orders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderStatus;

/** Outbound DTO. Mapped inside the transaction so lazy associations are still loadable. */
public record OrderResponse(
        String orderNumber,
        String customerEmail,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        String failureReason,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<LineResponse> lines) {

    public record LineResponse(String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /**
     * A hand-written mapper. For anything this size it beats MapStruct/ModelMapper: it is explicit,
     * debuggable, has no build-time magic, and a field you forget is a compile error rather than a
     * silently-null JSON property.
     */
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderNumber(),
                order.getCustomerEmail(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getFailureReason(),
                order.getVersion(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getLines().stream()
                        .map(line -> new LineResponse(line.getSku(), line.getQuantity(),
                                line.getUnitPrice(), line.lineTotal()))
                        .toList());
    }
}
