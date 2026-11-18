package com.example.orders.clients;

import java.math.BigDecimal;

/** What the (fictional) catalog service returns. One DTO, five clients, so the comparison is fair. */
public record Product(String sku, String name, BigDecimal price, int available) {
}
