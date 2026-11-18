package com.example.orders.api;

import java.math.BigDecimal;

import com.example.orders.domain.OrderStatus;
import com.example.orders.domain.Payment;
import com.example.orders.repo.OrderRepository;
import com.example.orders.repo.OutboxRepository;
import com.example.orders.repo.PaymentRepository;
import com.example.orders.repo.StockItemRepository;
import com.example.testsupport.FlakyGatewayStub;
import com.example.testsupport.TestPorts;
import com.example.testsupport.FlakyGatewayStub.Mode;

import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end through HTTP: controller, validation, transaction, JPA against the Flyway-created
 * schema, the after-commit event, the outbound call to the stub, and the payment write-back.
 *
 * <p>One test like this per critical flow is worth a hundred mock-heavy unit tests, because it is
 * the only place that proves the WIRING: that the AFTER_COMMIT listener really fires, that the
 * REQUIRES_NEW write really lands, that the JSON really serialises.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Import(FlakyGatewayStub.class)
@DisplayName("End-to-end order placement")
class OrderApiIntegrationTest {

    /** Chosen before the context starts - see {@link TestPorts} for why this is not trivial. */
    private static final int PORT = TestPorts.reserve();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private FlakyGatewayStub gateway;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private StockItemRepository stock;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("app.payments.base-url", () -> "http://localhost:" + PORT + "/test-gateway");
    }

    @BeforeEach
    void reset() {
        gateway.reset(Mode.OK);
    }

    @Test
    @DisplayName("a placed order is charged after commit and recorded everywhere")
    void placeOrderEndToEnd() {
        int availableBefore = stock.findById("SKU-LAPTOP").orElseThrow().getAvailable();

        ResponseEntity<OrderResponse> created = rest.exchange("/api/orders", HttpMethod.POST,
                json("{\"customerEmail\":\"e2e@example.com\",\"currency\":\"EUR\","
                        + "\"lines\":[{\"sku\":\"SKU-LAPTOP\",\"quantity\":2,\"unitPrice\":999.99}]}"),
                OrderResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        OrderResponse body = created.getBody();
        assertThat(body).isNotNull();
        assertThat(body.totalAmount()).isEqualByComparingTo(new BigDecimal("1999.98"));

        String orderNumber = body.orderNumber();

        // The gateway was called exactly once, with the stable idempotency key.
        assertThat(gateway.callCount()).isEqualTo(1);
        assertThat(gateway.idempotencyKeys()).containsExactly("order-" + orderNumber);

        // The AFTER_COMMIT listener charged the card and the REQUIRES_NEW write-back landed.
        assertThat(orders.findByOrderNumber(orderNumber).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);

        // The attempt was recorded in its own transaction.
        assertThat(payments.findByOrderNumberOrderByAttemptNoAsc(orderNumber))
                .singleElement()
                .satisfies(payment -> assertThat(payment.getResult()).isEqualTo(Payment.Result.SUCCEEDED));

        // The outbox row committed together with the business change.
        assertThat(outbox.count()).isPositive();

        // Stock was reserved inside the same transaction as the order.
        assertThat(stock.findById("SKU-LAPTOP").orElseThrow().getAvailable())
                .isEqualTo(availableBefore - 2);
    }

    @Test
    @DisplayName("insufficient stock rolls the whole order back and returns 409")
    void insufficientStockRollsBack() {
        long ordersBefore = orders.count();

        ResponseEntity<String> response = rest.exchange("/api/orders", HttpMethod.POST,
                json("{\"customerEmail\":\"greedy@example.com\",\"currency\":\"EUR\","
                        + "\"lines\":[{\"sku\":\"SKU-DOCK\",\"quantity\":999,\"unitPrice\":5.00}]}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("insufficient-stock").contains("available");
        // Nothing was written, and the gateway was never called.
        assertThat(orders.count()).isEqualTo(ordersBefore);
        assertThat(gateway.callCount()).isZero();
    }

    @Test
    @DisplayName("a declined payment leaves the order PAYMENT_FAILED, not lost")
    void declinedPaymentIsRecorded() {
        gateway.reset(Mode.DECLINED);

        ResponseEntity<OrderResponse> created = rest.exchange("/api/orders", HttpMethod.POST,
                json("{\"customerEmail\":\"declined@example.com\",\"currency\":\"EUR\","
                        + "\"lines\":[{\"sku\":\"SKU-MOUSE\",\"quantity\":1,\"unitPrice\":25.50}]}"),
                OrderResponse.class);

        String orderNumber = created.getBody().orderNumber();

        // A permanent failure must not be retried.
        assertThat(gateway.callCount()).isEqualTo(1);
        assertThat(orders.findByOrderNumber(orderNumber).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("search supports filtering, paging and the case-insensitive enum converter")
    void searchAndPaging() {
        rest.exchange("/api/orders", HttpMethod.POST,
                json("{\"customerEmail\":\"paging@example.com\",\"currency\":\"EUR\","
                        + "\"lines\":[{\"sku\":\"SKU-MOUSE\",\"quantity\":1,\"unitPrice\":10.00}]}"),
                OrderResponse.class);

        // ?status=paid exercises the custom String -> OrderStatus converter from WebConfig.
        ResponseEntity<String> page = rest.getForEntity(
                "/api/orders?status=paid&email=paging&page=0&size=5&sort=createdAt,desc", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("paging@example.com").contains("totalElements");
    }

    @Test
    @DisplayName("the custom actuator endpoint and health indicator are wired")
    void actuatorIsWired() {
        assertThat(rest.getForObject("/actuator/platform", String.class))
                .contains("applicationName").contains("maskedFields");
        assertThat(rest.getForObject("/actuator/health", String.class)).contains("status");
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor", "integration-test");
        return new HttpEntity<>(body, headers);
    }

}
