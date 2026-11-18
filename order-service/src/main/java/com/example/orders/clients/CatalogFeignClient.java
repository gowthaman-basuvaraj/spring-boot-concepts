package com.example.orders.clients;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * OpenFeign, via Spring Cloud.
 *
 * <p>{@code name} is the logical service id. With a service registry (Eureka, Consul, Kubernetes)
 * Feign resolves it and load-balances across instances - that is the real reason to use Feign, and
 * Spring's own HTTP interfaces do not do it. Without a registry you must supply {@code url}, as
 * here, at which point {@link CatalogHttpInterface} does the same job with no extra dependency.
 *
 * <p>Note that the annotations are Spring MVC's ({@code @GetMapping}, {@code @PathVariable}), not
 * Feign's own - Spring Cloud contributes a contract that reads them. Mixing in a real
 * {@code @HttpExchange} annotation here would not work.
 *
 * <p>Things to know before adopting it:
 * <ul>
 *   <li>Needs {@code @EnableFeignClients} on a configuration class, with {@code basePackages} if
 *       your clients live outside the application package.</li>
 *   <li>Error handling is central: implement an {@code ErrorDecoder} to map status codes onto your
 *       exceptions. There is no per-call hook.</li>
 *   <li>Feign has its OWN {@code Retryer} (default: never retry) and its own timeouts under
 *       {@code spring.cloud.openfeign.client.config.*}. They are independent of your
 *       {@code RestClient} settings - configuring one does nothing for the other, which is exactly
 *       how a service ends up with no timeouts on half its calls.</li>
 *   <li>It does not use your {@code RestClient.Builder}, so platform customizations (correlation-id
 *       propagation, outbound logging) must be re-implemented as a Feign {@code RequestInterceptor}.</li>
 * </ul>
 */
@FeignClient(name = "catalog", url = "${app.catalog.base-url}")
public interface CatalogFeignClient {

    @GetMapping("/products/{sku}")
    Product byId(@PathVariable("sku") String sku);
}
