package com.example.orders.web;

import com.example.orders.repo.OrderRepository;
import com.example.testsupport.FlakyGatewayStub;
import com.example.testsupport.TestPorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Servlet filter behaviour, end to end through a real container.
 *
 * <p>Body-logging is switched ON here deliberately: the single most common bug in a home-made
 * logging filter is forgetting {@code ContentCachingResponseWrapper#copyBodyToResponse()}, which
 * returns a correct status with an <b>empty body</b>. A test that only asserts the status code
 * passes happily while every client gets nothing - so these tests assert the body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = { "app.web.request-logging=true", "app.web.log-bodies=true",
                "app.web.max-request-bytes=512" })
@ActiveProfiles("test")
@Import(FlakyGatewayStub.class)
@DisplayName("Servlet filters: ordering, content caching, early rejection")
class FilterChainTest {

    private static final int PORT = TestPorts.reserve();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OrderRepository orders;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("app.payments.base-url", () -> "http://localhost:" + PORT + "/test-gateway");
        registry.add("app.catalog.base-url", () -> "http://localhost:" + PORT + "/test-gateway");
    }

    @Test
    @DisplayName("the content-caching logging filter does not swallow the response body")
    void loggingFilterPreservesTheResponseBody() {
        ResponseEntity<String> response = rest.exchange("/api/orders", HttpMethod.POST,
                json("{\"customerEmail\":\"filters@example.com\",\"currency\":\"EUR\","
                        + "\"lines\":[{\"sku\":\"SKU-MOUSE\",\"quantity\":1,\"unitPrice\":9.99}]}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The assertion that catches the missing copyBodyToResponse():
        assertThat(response.getBody()).isNotNull().contains("orderNumber").contains("filters@example.com");
        assertThat(response.getHeaders().getContentLength()).isNotZero();
    }

    @Test
    @DisplayName("an oversized body is rejected with 413 before the handler runs")
    void oversizedBodyIsRejectedEarly() {
        long ordersBefore = orders.count();
        String padding = "x".repeat(1000);

        ResponseEntity<String> response = rest.exchange("/api/orders", HttpMethod.POST,
                json("{\"customerEmail\":\"big@example.com\",\"currency\":\"EUR\",\"pad\":\"" + padding
                        + "\",\"lines\":[{\"sku\":\"SKU-MOUSE\",\"quantity\":1,\"unitPrice\":9.99}]}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).contains("payload-too-large");
        // Nothing downstream ran: no order, and the filter wrote the response itself because a
        // @RestControllerAdvice never sees what a filter rejects.
        assertThat(orders.count()).isEqualTo(ordersBefore);
    }

    @Test
    @DisplayName("the correlation filter runs before ours and is echoed on every response")
    void correlationIdIsPresentOnFilteredResponses() {
        ResponseEntity<String> ok = rest.exchange("/api/orders?size=1", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(ok.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();

        // ...including on a response written by a filter rather than by MVC.
        ResponseEntity<String> rejected = rest.exchange("/api/orders", HttpMethod.POST,
                json("{\"pad\":\"" + "y".repeat(1000) + "\"}"), String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(rejected.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    @DisplayName("filters skip the paths they declare they skip")
    void healthIsNotFiltered() {
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor", "filter-test");
        return new HttpEntity<>(body, headers);
    }
}
