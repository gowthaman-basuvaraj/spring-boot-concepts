package com.example.orders.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.orders.clients.CatalogClient;
import com.example.orders.clients.Product;
import com.example.orders.clients.WebClientCatalogClient;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calls the same downstream operation through all five client styles and reports what each
 * returned, so the comparison in {@link CatalogClient} is observable rather than asserted.
 *
 * <p>{@code List<CatalogClient>} injection collects every implementation in the context.
 */
@RestController
@RequestMapping("/api/clients")
public class ClientComparisonController {

    private final List<CatalogClient> clients;
    private final WebClientCatalogClient webClient;

    public ClientComparisonController(List<CatalogClient> clients, WebClientCatalogClient webClient) {
        this.clients = clients;
        this.webClient = webClient;
    }

    /** GET /api/clients/SKU-LAPTOP - one product, fetched five ways. */
    @GetMapping("/{sku}")
    public Map<String, Object> compare(@PathVariable String sku) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (CatalogClient client : clients) {
            long start = System.nanoTime();
            Map<String, Object> entry = new LinkedHashMap<>();
            try {
                Product product = client.fetch(sku);
                entry.put("outcome", "ok");
                entry.put("product", product);
            } catch (RuntimeException ex) {
                // Each style fails with its own exception type - which is half the point.
                entry.put("outcome", "failed");
                entry.put("exception", ex.getClass().getName());
                entry.put("message", ex.getMessage());
            }
            entry.put("millis", (System.nanoTime() - start) / 1_000_000);
            result.put(client.style(), entry);
        }
        return result;
    }

    /**
     * The one thing a blocking client cannot do well: N requests concurrently, finishing in the
     * time of the slowest rather than the sum. Compare the reported totals.
     */
    @GetMapping("/fan-out")
    public Map<String, Object> fanOut(@RequestParam List<String> skus) {
        long reactiveStart = System.nanoTime();
        List<Product> concurrent = webClient.fetchAll(skus);
        long reactiveMillis = (System.nanoTime() - reactiveStart) / 1_000_000;

        CatalogClient blocking = clients.stream()
                .filter(c -> c.style().equals("RestClient"))
                .findFirst()
                .orElseThrow();
        long blockingStart = System.nanoTime();
        List<Product> sequential = skus.stream().map(blocking::fetch).toList();
        long blockingMillis = (System.nanoTime() - blockingStart) / 1_000_000;

        return Map.of(
                "requested", skus.size(),
                "webClientConcurrentMillis", reactiveMillis,
                "restClientSequentialMillis", blockingMillis,
                "sameResults", concurrent.size() == sequential.size(),
                "products", concurrent);
    }
}
