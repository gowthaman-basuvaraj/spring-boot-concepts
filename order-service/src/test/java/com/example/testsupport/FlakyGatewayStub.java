package com.example.testsupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.orders.clients.Product;
import com.example.orders.payments.PaymentDtos;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controllable gateway stub served by the application under test.
 *
 * <p>Why this rather than WireMock or {@code MockRestServiceServer}?
 * <ul>
 *   <li>It exercises the REAL {@code RestClient}, the real request factory and the real timeouts -
 *       {@code MockRestServiceServer} replaces the transport, so it can never catch a
 *       misconfigured connect/read timeout or a broken interceptor.</li>
 *   <li>Zero extra dependencies and no extra port management, because it rides on the test's own
 *       {@code RANDOM_PORT} server.</li>
 *   <li>It can record every attempt, which is exactly what retry assertions need.</li>
 * </ul>
 * WireMock is still the better tool when you need fault injection the servlet API cannot express
 * (connection reset mid-body, malformed chunked encoding, TLS failures).
 *
 * <p>NOTE the package: {@code com.example.testsupport}, deliberately OUTSIDE the application's
 * {@code com.example.orders} scan root. Test classes sit on the classpath that
 * {@code @ComponentScan} walks, so a {@code @RestController} in test sources under the scanned
 * package is picked up by EVERY {@code @SpringBootTest} in the project - and if you also register
 * it with an {@code @Bean} method you get the delightful
 * {@code IllegalStateException: Ambiguous mapping}. Keeping test infrastructure in its own package
 * and importing it explicitly removes the whole class of problem.
 *
 * <p>Also note that this is ONE top-level class rather than a {@code @TestConfiguration} wrapping a
 * nested {@code @RestController} registered by an {@code @Bean} method. {@code @Import} of a
 * configuration class ALSO processes its nested component classes, so that arrangement registers
 * the controller twice and fails with {@code Ambiguous mapping}.
 */
@RestController
@RequestMapping("/test-gateway")
public class FlakyGatewayStub {

    public enum Mode { OK, TRANSIENT_THEN_OK, ALWAYS_503, THROTTLED, DECLINED, SLOW }

    private volatile Mode mode = Mode.OK;
    private volatile int failuresBeforeSuccess = 2;
    private volatile int catalogLatencyMillis = 0;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();

    public void reset(Mode mode) {
        this.mode = mode;
        this.calls.set(0);
        this.idempotencyKeys.clear();
    }

    public void failuresBeforeSuccess(int count) {
        this.failuresBeforeSuccess = count;
    }

    public int callCount() {
        return calls.get();
    }

    public List<String> idempotencyKeys() {
        return List.copyOf(idempotencyKeys);
    }

    @PostMapping("/charges")
    public ResponseEntity<?> charge(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody PaymentDtos.ChargeRequest request) throws InterruptedException {
        int call = calls.incrementAndGet();
        idempotencyKeys.add(String.valueOf(idempotencyKey));
        return switch (mode) {
            case OK -> ResponseEntity.ok(authorised(request));
            case TRANSIENT_THEN_OK -> call > failuresBeforeSuccess
                    ? ResponseEntity.ok(authorised(request))
                    : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "hiccup"));
            case ALWAYS_503 -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "down"));
            case THROTTLED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(Map.of("error", "slow down"));
            case DECLINED -> ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "insufficient funds"));
            case SLOW -> {
                // Longer than platform.http.read-timeout in application-test.yml.
                Thread.sleep(2_000);
                yield ResponseEntity.ok(authorised(request));
            }
        };
    }

    /**
     * Catalog side of the stub, for the five-client comparison. Every style hits this one endpoint,
     * so any difference in the results is a difference in the clients, not in the server.
     */
    @GetMapping("/products/{sku}")
    public ResponseEntity<?> product(@PathVariable String sku) throws InterruptedException {
        calls.incrementAndGet();
        if (catalogLatencyMillis > 0) {
            Thread.sleep(catalogLatencyMillis);
        }
        if (!sku.startsWith("SKU-")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown sku"));
        }
        return ResponseEntity.ok(new Product(sku, "Product " + sku, new java.math.BigDecimal("9.99"), 7));
    }

    /** Set to a few hundred millis to make the sequential-vs-concurrent difference obvious. */
    public void catalogLatency(int millis) {
        this.catalogLatencyMillis = millis;
    }

    private Map<String, String> authorised(PaymentDtos.ChargeRequest request) {
        return Map.of("providerReference", "ref-" + request.orderNumber(),
                "status", "AUTHORISED", "message", "ok");
    }
}
