package com.example.orders.clients;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@code RestClient} (Spring 6.1+) - <b>the default choice for new blocking code.</b>
 *
 * <p>Same synchronous execution model and same request factory as {@code RestTemplate}, with
 * {@code WebClient}'s fluent API and none of its reactive types. Nothing reactive leaks into your
 * service layer, so stack traces stay readable and {@code ThreadLocal}-based things (MDC, security
 * context, transactions) keep working.
 *
 * <p>Note {@code retrieve()} vs {@code exchange()}:
 * <ul>
 *   <li>{@code retrieve().body(X.class)} - throws on 4xx/5xx. Concise; right when any error is
 *       simply an error.</li>
 *   <li>{@code exchange((req, res) -> ...)} - gives you status, headers and body in one place and
 *       does NOT throw, which is what you need to classify failures (see
 *       {@code PaymentGatewayCaller}).</li>
 * </ul>
 *
 * <p>{@code onStatus(predicate, handler)} sits between the two: keep the fluent style but map
 * specific statuses to your own exceptions.
 */
@Component
public class RestClientCatalogClient implements CatalogClient {

    private final RestClient restClient;

    public RestClientCatalogClient(RestClient.Builder builder,
            @org.springframework.beans.factory.annotation.Value("${app.catalog.base-url}") String baseUrl) {
        // The injected builder already carries the platform defaults: timeouts, correlation-id
        // propagation and outbound logging. RestClient.create() would bypass all of them.
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Product fetch(String sku) {
        return restClient.get()
                .uri("/products/{sku}", sku)
                .retrieve()
                .body(Product.class);
    }

    @Override
    public String style() {
        return "RestClient";
    }
}
