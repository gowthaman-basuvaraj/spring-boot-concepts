package com.example.orders.support;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.orders.clients.Product;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stub catalog service, so the five-client comparison runs with no external dependency.
 *
 * <p>{@code POST /stub/catalog/latency?millis=300} makes every lookup slow, which is what makes the
 * {@code /api/clients/fan-out} comparison meaningful: with 300ms of latency and six SKUs, WebClient
 * finishes in roughly 300ms and the blocking client in roughly 1800ms.
 *
 * <p>{@code dev} profile only.
 */
@RestController
@RequestMapping("/stub/catalog")
@Profile("dev")
public class CatalogStubController {

    private static final Map<String, Product> CATALOG = Map.of(
            "SKU-LAPTOP", new Product("SKU-LAPTOP", "Developer laptop", new BigDecimal("999.99"), 50),
            "SKU-MOUSE", new Product("SKU-MOUSE", "Ergonomic mouse", new BigDecimal("25.50"), 500),
            "SKU-DOCK", new Product("SKU-DOCK", "Thunderbolt dock", new BigDecimal("199.00"), 10));

    private final AtomicInteger latencyMillis = new AtomicInteger();

    @PostMapping("/latency")
    public Map<String, Object> latency(@RequestParam int millis) {
        latencyMillis.set(Math.max(0, millis));
        return Map.of("latencyMillis", latencyMillis.get());
    }

    @GetMapping("/products/{sku}")
    public ResponseEntity<?> product(@PathVariable String sku) throws InterruptedException {
        int delay = latencyMillis.get();
        if (delay > 0) {
            Thread.sleep(delay);
        }
        Product product = CATALOG.get(sku);
        return product != null
                ? ResponseEntity.ok(product)
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown sku " + sku));
    }
}
