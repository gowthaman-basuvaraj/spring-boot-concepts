package com.example.orders.support;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.orders.payments.PaymentDtos.ChargeRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A deliberately unreliable payment gateway, so the retry/backoff/circuit-breaker machinery can be
 * driven locally without any external service.
 *
 * <p>Control it at runtime:
 * <pre>
 *   POST /stub/payments/mode?value=ok          always succeed
 *   POST /stub/payments/mode?value=flaky       fail twice, then succeed (default)
 *   POST /stub/payments/mode?value=down        always 503
 *   POST /stub/payments/mode?value=throttled   always 429 with Retry-After: 1
 *   POST /stub/payments/mode?value=declined    always 402 (permanent - must NOT be retried)
 *   POST /stub/payments/mode?value=slow        sleep past the read timeout
 * </pre>
 * Then place an order and watch the log: you should see the retry attempts with growing delays,
 * the attempt rows in {@code payment_attempt}, and - in {@code down} mode, after a few orders -
 * the circuit breaker opening and {@code /actuator/health} reporting DEGRADED.
 *
 * <p>{@code dev} profile only. A stub in the production artifact is a liability.
 */
@RestController
@RequestMapping("/stub/payments")
@Profile("dev")
public class PaymentStubController {

    private static final Logger log = LoggerFactory.getLogger(PaymentStubController.class);

    private final AtomicInteger calls = new AtomicInteger();
    private volatile String mode = "flaky";

    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestParam String value) {
        this.mode = value;
        this.calls.set(0);
        return Map.of("mode", mode, "callsReset", true);
    }

    @GetMapping("/mode")
    public Map<String, Object> mode() {
        return Map.of("mode", mode, "calls", calls.get());
    }

    @PostMapping("/charges")
    public ResponseEntity<?> charge(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody ChargeRequest request) throws InterruptedException {
        int call = calls.incrementAndGet();
        log.info("[stub] charge call #{} mode={} idempotencyKey={} order={}",
                call, mode, key, request.orderNumber());
        return switch (mode) {
            case "ok" -> ResponseEntity.ok(authorised(request));
            case "down" -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "gateway down"));
            case "throttled" -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(Map.of("error", "slow down"));
            case "declined" -> ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "insufficient funds"));
            case "slow" -> {
                Thread.sleep(10_000);
                yield ResponseEntity.ok(authorised(request));
            }
            // "flaky": the first two attempts of every charge fail, the third succeeds - which is
            // exactly the shape that retry with backoff is designed for.
            default -> (call % 3 == 0)
                    ? ResponseEntity.ok(authorised(request))
                    : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "upstream hiccup"));
        };
    }

    private Map<String, String> authorised(ChargeRequest request) {
        return Map.of(
                "providerReference", "stub-" + Integer.toHexString(request.orderNumber().hashCode()),
                "status", "AUTHORISED",
                "message", "ok");
    }
}
