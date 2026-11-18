package com.example.orders.clients;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Spring's own declarative client (Spring 6+): an interface, no implementation, no extra
 * dependency. {@code HttpServiceProxyFactory} generates the implementation over a
 * {@code RestClient}, {@code WebClient} or {@code RestTemplate} adapter - see {@code ClientsConfig}.
 *
 * <p>This is the in-framework replacement for OpenFeign. Compare {@link CatalogFeignClient}: the
 * declarations are near-identical, but this needs no Spring Cloud, no {@code @EnableFeignClients},
 * and uses the same {@code RestClient} (and therefore the same timeouts, interceptors and metrics)
 * as the rest of the application.
 */
@HttpExchange(accept = "application/json")
public interface CatalogHttpInterface {

    @GetExchange("/products/{sku}")
    Product byId(@PathVariable String sku);
}
