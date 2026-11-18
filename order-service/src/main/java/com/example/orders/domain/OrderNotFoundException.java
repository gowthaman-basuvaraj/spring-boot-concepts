package com.example.orders.domain;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Object identifier) {
        super("Order not found: " + identifier);
    }
}
