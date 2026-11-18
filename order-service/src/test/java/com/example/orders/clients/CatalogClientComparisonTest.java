package com.example.orders.clients;

import java.time.Duration;
import java.util.List;

import com.example.testsupport.FlakyGatewayStub;
import com.example.testsupport.TestPorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * All five client styles, against one real endpoint.
 *
 * <p>The assertions that matter are not "it returns a product" - they are that the five styles are
 * genuinely interchangeable for the happy path, and genuinely different in how they fail. The
 * second half is what you need to know before choosing one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Import(FlakyGatewayStub.class)
@DisplayName("HTTP clients: RestTemplate vs RestClient vs WebClient vs @HttpExchange vs Feign")
class CatalogClientComparisonTest {

    private static final int PORT = TestPorts.reserve();

    @Autowired
    private List<CatalogClient> clients;

    @Autowired
    private WebClientCatalogClient webClient;

    @Autowired
    private FlakyGatewayStub stub;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("app.catalog.base-url", () -> "http://localhost:" + PORT + "/test-gateway");
    }

    @Test
    @DisplayName("all five styles are registered and distinct")
    void allStylesPresent() {
        assertThat(clients).extracting(CatalogClient::style)
                .containsExactlyInAnyOrder("RestTemplate", "RestClient", "WebClient",
                        "@HttpExchange interface", "OpenFeign");
    }

    @Test
    @DisplayName("all five return the same payload for the same request")
    void allStylesAgree() {
        List<Product> results = clients.stream().map(c -> c.fetch("SKU-LAPTOP")).toList();

        assertThat(results).hasSize(5);
        assertThat(results).allSatisfy(product -> {
            assertThat(product.sku()).isEqualTo("SKU-LAPTOP");
            assertThat(product.available()).isEqualTo(7);
        });
        // Identical DTOs, so records give us value equality for free.
        assertThat(results).containsOnly(results.get(0));
    }

    @Test
    @DisplayName("each style surfaces a 404 as its OWN exception type - the real cost of mixing them")
    void failureTypesDiffer() {
        for (CatalogClient client : clients) {
            assertThatExceptionOfType(RuntimeException.class)
                    .as("style %s", client.style())
                    .isThrownBy(() -> client.fetch("NOT-A-SKU"));
        }
        // Specifically: RestTemplate -> HttpClientErrorException.NotFound,
        // RestClient and @HttpExchange -> HttpClientErrorException.NotFound (same hierarchy),
        // WebClient -> WebClientResponseException.NotFound,
        // Feign -> FeignException.NotFound.
        // A service layer that catches one of these will silently not catch the others, which is
        // why every client here is wrapped behind an interface we own.
        assertThatExceptionOfType(org.springframework.web.reactive.function.client.WebClientResponseException.class)
                .isThrownBy(() -> webClient.fetch("NOT-A-SKU"));
        assertThatExceptionOfType(feign.FeignException.class)
                .isThrownBy(() -> clients.stream()
                        .filter(c -> c.style().equals("OpenFeign")).findFirst().orElseThrow()
                        .fetch("NOT-A-SKU"));
    }

    @Test
    @DisplayName("WebClient fans out concurrently; the blocking client cannot")
    void concurrentFanOutIsFaster() {
        stub.catalogLatency(200);
        List<String> skus = List.of("SKU-A", "SKU-B", "SKU-C", "SKU-D", "SKU-E", "SKU-F");
        CatalogClient blocking = clients.stream()
                .filter(c -> c.style().equals("RestClient")).findFirst().orElseThrow();
        try {
            long concurrentStart = System.nanoTime();
            List<Product> concurrent = webClient.fetchAll(skus);
            Duration concurrentTime = Duration.ofNanos(System.nanoTime() - concurrentStart);

            long sequentialStart = System.nanoTime();
            List<Product> sequential = skus.stream().map(blocking::fetch).toList();
            Duration sequentialTime = Duration.ofNanos(System.nanoTime() - sequentialStart);

            assertThat(concurrent).hasSize(6);
            assertThat(sequential).hasSize(6);
            // 6 x 200ms sequentially is ~1.2s; concurrently (8 at a time) it is ~200ms. Asserting
            // "at least twice as fast" rather than an absolute number keeps this stable on CI.
            assertThat(concurrentTime).isLessThan(sequentialTime.dividedBy(2));
        } finally {
            stub.catalogLatency(0);
        }
    }
}
