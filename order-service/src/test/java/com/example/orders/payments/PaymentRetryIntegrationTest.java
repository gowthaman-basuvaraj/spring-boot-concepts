package com.example.orders.payments;

import java.math.BigDecimal;
import java.time.Duration;

import com.example.testsupport.FlakyGatewayStub;
import com.example.testsupport.TestPorts;
import com.example.testsupport.FlakyGatewayStub.Mode;
import com.example.orders.payments.PaymentDtos.ChargeRequest;
import com.example.orders.payments.PaymentExceptions.PaymentDeclinedException;
import com.example.orders.payments.PaymentExceptions.TransientPaymentException;
import com.example.orders.payments.PaymentGatewayClient.ChargeOutcome;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
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
 * Retry, backoff and circuit-breaker behaviour against a real HTTP endpoint.
 *
 * <p>{@link DynamicPropertySource} is how you point configuration at a value only known at
 * runtime - the same mechanism Testcontainers uses.
 *
 * <p>Why a pre-chosen port and {@code DEFINED_PORT} instead of {@code RANDOM_PORT} plus
 * {@code @LocalServerPort}? Because {@code @LocalServerPort} is injected AFTER the context is
 * built, and {@code PaymentGatewayCaller} captures its base URL when its {@code RestClient} is
 * constructed. By the time the port is known, the client already points at
 * {@code http://localhost:0}. Anything that must be known before bean creation has to be supplied
 * through the Environment, not injected into a field.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Import(FlakyGatewayStub.class)
@DisplayName("Outbound HTTP: retry, backoff, classification, circuit breaker")
class PaymentRetryIntegrationTest {

    /** Chosen before the context starts - see {@link TestPorts} for why this is not trivial. */
    private static final int PORT = TestPorts.reserve();

    @Autowired
    private PaymentGatewayCaller caller;

    @Autowired
    private PaymentGatewayClient client;

    @Autowired
    private FlakyGatewayStub stub;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @DynamicPropertySource
    static void gatewayUrl(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("app.payments.base-url", () -> "http://localhost:" + PORT + "/test-gateway");
    }


    @BeforeEach
    void reset() {
        stub.reset(Mode.OK);
        circuitBreakers.circuitBreaker(PaymentGatewayClient.INSTANCE).reset();
    }

    @Test
    @DisplayName("a healthy gateway is called exactly once")
    void happyPath() {
        ChargeOutcome outcome = client.charge(request("order-1"));

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome.providerReference()).isEqualTo("ref-order-1");
        assertThat(stub.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("transient 502s are retried until success, with the SAME idempotency key")
    void retriesTransientFailures() {
        stub.reset(Mode.TRANSIENT_THEN_OK);
        stub.failuresBeforeSuccess(2);

        ChargeOutcome outcome = client.charge(request("order-2"));

        assertThat(outcome.isSuccess()).isTrue();
        // 2 failures + 1 success = exactly maxAttempts (3).
        assertThat(stub.callCount()).isEqualTo(3);
        // The key MUST be stable across attempts, or a retry charges the customer twice.
        assertThat(stub.idempotencyKeys()).containsExactly(
                "order-order-2", "order-order-2", "order-order-2");
    }

    @Test
    @DisplayName("retries stop at maxAttempts and the last exception propagates")
    void stopsAtMaxAttempts() {
        stub.reset(Mode.ALWAYS_503);

        assertThatExceptionOfType(TransientPaymentException.class)
                .isThrownBy(() -> caller.charge(request("order-3")));

        assertThat(stub.callCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("backoff actually sleeps between attempts")
    void appliesExponentialBackoff() {
        stub.reset(Mode.ALWAYS_503);

        long start = System.nanoTime();
        assertThatExceptionOfType(TransientPaymentException.class)
                .isThrownBy(() -> caller.charge(request("order-4")));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // application-test.yml: initial 20ms, multiplier 2 -> at least 20 + 40 = 60ms of sleeping.
        // Jitter (random = true) only ever lengthens a delay, so a lower bound is stable; an upper
        // bound would be flaky on a loaded CI machine.
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(55));
    }

    @Test
    @DisplayName("a permanent failure (402) is NOT retried")
    void doesNotRetryPermanentFailures() {
        stub.reset(Mode.DECLINED);

        assertThatExceptionOfType(PaymentDeclinedException.class)
                .isThrownBy(() -> caller.charge(request("order-5")));

        assertThat(stub.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a decline reaches the caller as DECLINED, not as an outage")
    void declineIsMappedThroughTheFallback() {
        stub.reset(Mode.DECLINED);

        ChargeOutcome outcome = client.charge(request("order-6"));

        assertThat(outcome.status()).isEqualTo(ChargeOutcome.Status.DECLINED);
        assertThat(outcome.isRetryableLater()).isFalse();
    }

    @Test
    @DisplayName("a read timeout is transient and is retried")
    void readTimeoutIsTransient() {
        stub.reset(Mode.SLOW);

        assertThatExceptionOfType(TransientPaymentException.class)
                .isThrownBy(() -> caller.charge(request("order-7")))
                .withMessageContaining("transport failure");

        // The read timeout (500ms in application-test.yml) fired on every attempt. Without one,
        // this test would block for 2s per attempt - which is what production does when you
        // forget to configure it.
        assertThat(stub.callCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("429 is transient, and Retry-After is parsed and capped")
    void throttlingIsTransient() {
        stub.reset(Mode.THROTTLED);

        assertThatExceptionOfType(TransientPaymentException.class)
                .isThrownBy(() -> caller.charge(request("order-8")));

        assertThat(stub.callCount()).isEqualTo(3);
        assertThat(caller.parseRetryAfter("1")).isEqualTo(Duration.ofSeconds(1));
        // Capped by app.payments.max-retry-after, so a hostile upstream cannot park a thread.
        assertThat(caller.parseRetryAfter("3600")).isEqualTo(Duration.ofSeconds(5));
        assertThat(caller.parseRetryAfter("nonsense")).isNull();
    }

    @Test
    @DisplayName("the breaker opens after repeated outages and then fails fast")
    void circuitBreakerOpensAndShortCircuits() {
        stub.reset(Mode.ALWAYS_503);
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(PaymentGatewayClient.INSTANCE);

        // minimum-number-of-calls = 5, failure-rate-threshold = 50%.
        for (int i = 0; i < 5; i++) {
            assertThat(client.charge(request("order-cb-" + i)).isRetryableLater()).isTrue();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = stub.callCount();
        ChargeOutcome outcome = client.charge(request("order-cb-last"));

        assertThat(outcome.status()).isEqualTo(ChargeOutcome.Status.UNAVAILABLE);
        // Fail fast: an open breaker makes no network call at all.
        assertThat(stub.callCount()).isEqualTo(callsBefore);
    }

    private ChargeRequest request(String orderNumber) {
        return new ChargeRequest(orderNumber, new BigDecimal("10.00"), "EUR", "order-" + orderNumber);
    }
}
