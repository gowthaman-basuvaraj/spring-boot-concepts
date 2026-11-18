package com.example.orders.clients;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * {@code RestTemplate} - the one you already have in a legacy codebase.
 *
 * <p>Status: <b>maintenance mode since Spring 5.0.</b> Not deprecated, not going away, but it will
 * get no new features. For new code use {@code RestClient}, which wraps the same
 * {@code ClientHttpRequestFactory} infrastructure behind a fluent API - so migration is mechanical
 * and you keep your interceptors, message converters and timeouts.
 *
 * <p>Things people get wrong with it:
 * <ul>
 *   <li><b>{@code new RestTemplate()}</b> has NO timeouts and none of your interceptors. Always
 *       inject a {@code RestTemplateBuilder} (see {@code ClientsConfig}).</li>
 *   <li><b>{@code getForObject}</b> throws {@code HttpClientErrorException}/{@code
 *       HttpServerErrorException} on 4xx/5xx and gives you no access to the response otherwise;
 *       {@code exchange()} returns a {@code ResponseEntity} so you can inspect status and headers.</li>
 *   <li><b>Generics</b> need {@code ParameterizedTypeReference} - {@code getForObject(url,
 *       List.class)} silently gives you a {@code List<LinkedHashMap>}.</li>
 *   <li><b>It is thread-safe once built</b> - build one, share it. Building one per request throws
 *       away connection pooling.</li>
 * </ul>
 */
@Component
public class RestTemplateCatalogClient implements CatalogClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RestTemplateCatalogClient(RestTemplate restTemplate,
            @org.springframework.beans.factory.annotation.Value("${app.catalog.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Override
    public Product fetch(String sku) {
        // URI variables are expanded and encoded for you - never concatenate user input into a URL.
        return restTemplate.getForObject(baseUrl + "/products/{sku}", Product.class, sku);
    }

    @Override
    public String style() {
        return "RestTemplate";
    }
}
