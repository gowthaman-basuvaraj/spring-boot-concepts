package com.example.orders.clients;

/**
 * One operation, implemented five ways.
 *
 * <p>Every HTTP client style Spring offers is a bean of this interface, so
 * {@code ClientComparisonController} can inject {@code List<CatalogClient>} and call all of them
 * with the same arguments. Injecting a {@code List<T>} to get "every implementation" is itself the
 * idiomatic Spring pattern - the same mechanism behind {@code WebMvcConfigurer} and
 * {@code MeterRegistryCustomizer}.
 *
 * <h2>Which one should you actually use?</h2>
 * <table border="1">
 *   <caption>Choosing an HTTP client in 2026</caption>
 *   <tr><th>Style</th><th>Use when</th><th>Avoid when</th></tr>
 *   <tr><td>{@code RestClient}</td><td><b>Default for new blocking code.</b> Fluent, synchronous,
 *       no reactive types leaking into your service layer</td><td>you need streaming or real
 *       concurrency across many calls</td></tr>
 *   <tr><td>{@code @HttpExchange} interface</td><td>a clean, well-behaved API you want to declare
 *       rather than call; trivially mockable</td><td>per-call failure classification matters</td></tr>
 *   <tr><td>{@code WebClient}</td><td>genuine concurrency (fan-out), streaming, or you are already
 *       reactive</td><td>you would only ever call {@code .block()} - then it is a slower
 *       RestClient</td></tr>
 *   <tr><td>{@code RestTemplate}</td><td>maintaining existing code</td><td>new code. It is in
 *       maintenance mode: no removal planned, no new features either</td></tr>
 *   <tr><td>OpenFeign ({@code @FeignClient})</td><td>you are already on Spring Cloud and want
 *       load-balanced, discovery-aware clients for free</td><td>you are not - it is a whole
 *       dependency tree to replace an interface Spring now ships itself</td></tr>
 * </table>
 */
public interface CatalogClient {

    Product fetch(String sku);

    /** Short label used in the comparison response. */
    String style();
}
