package com.example.orders.support;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * A custom health indicator, exposed at {@code /actuator/health/paymentGateway} (the bean name
 * minus the "HealthIndicator" suffix).
 *
 * <p>Design rules that matter in production:
 * <ul>
 *   <li><b>Never call the dependency from here.</b> Kubernetes polls liveness/readiness every few
 *       seconds; a health check that makes an HTTP call turns your probe into a load generator and
 *       a cascading-failure amplifier. Report the circuit breaker's state instead - it already
 *       knows.</li>
 *   <li><b>Decide what "unhealthy" means.</b> If a dependency is optional, report
 *       {@code Status.UNKNOWN} or a custom "DEGRADED" status; returning DOWN for an optional
 *       dependency makes the orchestrator kill a service that was serving traffic fine.</li>
 *   <li>Use {@code management.endpoint.health.group.readiness.include} to separate "can accept
 *       traffic" from "is fully functional".</li>
 * </ul>
 */
@Component
public class PaymentGatewayHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry registry;

    public PaymentGatewayHealthIndicator(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        CircuitBreaker breaker = registry.circuitBreaker("payments");
        CircuitBreaker.State state = breaker.getState();
        CircuitBreaker.Metrics metrics = breaker.getMetrics();
        Health.Builder builder = switch (state) {
            case CLOSED, HALF_OPEN -> Health.up();
            // Degraded, not DOWN: orders are still accepted, payment is deferred.
            case OPEN, FORCED_OPEN -> Health.status("DEGRADED");
            case DISABLED, METRICS_ONLY -> Health.unknown();
        };
        return builder
                .withDetail("circuitBreakerState", state.name())
                .withDetail("failureRate", metrics.getFailureRate())
                .withDetail("bufferedCalls", metrics.getNumberOfBufferedCalls())
                .withDetail("failedCalls", metrics.getNumberOfFailedCalls())
                .build();
    }
}
