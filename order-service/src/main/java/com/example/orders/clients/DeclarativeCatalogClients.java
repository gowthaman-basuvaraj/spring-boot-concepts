package com.example.orders.clients;

import org.springframework.stereotype.Component;

/**
 * Adapters that put the two declarative clients behind the same {@link CatalogClient} interface as
 * the three imperative ones, so the comparison endpoint can treat all five identically.
 *
 * <p>Wrapping a generated proxy in a thin adapter is also good practice in its own right: your
 * domain code depends on your interface, not on Feign's or Spring's annotations, so swapping client
 * technology stops being a cross-codebase change.
 */
public final class DeclarativeCatalogClients {

    private DeclarativeCatalogClients() {
    }

    @Component
    public static class HttpInterfaceAdapter implements CatalogClient {

        private final CatalogHttpInterface api;

        public HttpInterfaceAdapter(CatalogHttpInterface api) {
            this.api = api;
        }

        @Override
        public Product fetch(String sku) {
            return api.byId(sku);
        }

        @Override
        public String style() {
            return "@HttpExchange interface";
        }
    }

    @Component
    public static class FeignAdapter implements CatalogClient {

        private final CatalogFeignClient api;

        public FeignAdapter(CatalogFeignClient api) {
            this.api = api;
        }

        @Override
        public Product fetch(String sku) {
            return api.byId(sku);
        }

        @Override
        public String style() {
            return "OpenFeign";
        }
    }
}
