package com.example.orders.clients;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * {@code WebClient} - reactive, non-blocking, and the right tool for exactly two jobs.
 *
 * <h2>When it earns its keep</h2>
 * <ol>
 *   <li><b>Real concurrency.</b> {@link #fetchAll} issues N requests on a handful of event-loop
 *       threads and finishes in the time of the slowest, not the sum. Doing that with a blocking
 *       client means N threads or an executor.</li>
 *   <li><b>Streaming.</b> {@code bodyToFlux} can process a large or server-sent-event response
 *       without buffering all of it.</li>
 * </ol>
 *
 * <h2>The thing to be honest about</h2>
 * If every call site ends in {@code .block()}, WebClient is strictly worse than {@code RestClient}:
 * you pay the reactive machinery, get harder stack traces, and still block a thread. Worse,
 * {@code .block()} on an event-loop thread throws
 * {@code IllegalStateException: block()/blockFirst()/blockLast() are blocking, which is not
 * supported in thread reactor-http-nio-N} - so it works in an MVC controller and explodes inside a
 * WebFlux handler or an exchange filter. {@link #fetch} blocks on purpose, to satisfy the blocking
 * {@link CatalogClient} contract, and that is the only reason it is acceptable here.
 *
 * <h2>Mechanics worth knowing</h2>
 * <ul>
 *   <li>Timeouts are NOT on the builder - they are on the underlying connector
 *       (see {@code ClientsConfig}), plus {@code .timeout(Duration)} per call as a backstop.</li>
 *   <li>Cross-cutting concerns go in an {@code ExchangeFilterFunction}, the WebClient equivalent of
 *       a {@code ClientHttpRequestInterceptor}.</li>
 *   <li>Retry is {@code Retry.backoff(...)} from Reactor, not spring-retry: {@code @Retryable} is
 *       proxy-based and would retry the assembly of the pipeline, not the subscription.</li>
 *   <li>MDC does not propagate across operators. Use context propagation, or accept losing the
 *       correlation id in the reactive section.</li>
 * </ul>
 */
@Component
public class WebClientCatalogClient implements CatalogClient {

    private final WebClient webClient;

    public WebClientCatalogClient(WebClient.Builder builder, @Value("${app.catalog.base-url}") String baseUrl) {
        this.webClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Product fetch(String sku) {
        return fetchReactive(sku).block();
    }

    /** The shape you would actually expose in a reactive application. */
    public Mono<Product> fetchReactive(String sku) {
        return webClient.get()
                .uri("/products/{sku}", sku)
                .retrieve()
                .bodyToMono(Product.class)
                // Per-call backstop on top of the connector's response timeout.
                .timeout(Duration.ofSeconds(2))
                // Reactor's own retry: exponential with jitter, and only for 5xx/IO failures.
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100)).jitter(0.5)
                        .filter(WebClientCatalogClient::isTransient));
    }

    /**
     * The payoff: N requests concurrently, wall-clock = the slowest one.
     *
     * <p>{@code concurrency} is not optional - an unbounded {@code flatMap} over a large list is
     * how you DDoS your own dependency.
     */
    public List<Product> fetchAll(List<String> skus) {
        return Flux.fromIterable(skus)
                .flatMap(this::fetchReactive, 8)
                .collectList()
                .block();
    }

    private static boolean isTransient(Throwable ex) {
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException http) {
            return http.getStatusCode().is5xxServerError() || http.getStatusCode().value() == 429;
        }
        return ex instanceof java.io.IOException
                || ex instanceof java.util.concurrent.TimeoutException;
    }

    @Override
    public String style() {
        return "WebClient";
    }
}
