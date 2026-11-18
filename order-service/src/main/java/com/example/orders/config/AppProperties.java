package com.example.orders.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application configuration, bound from {@code app.*} and validated at startup.
 *
 * <p>Picked up by {@code @ConfigurationPropertiesScan} on the application class - no
 * {@code @Component} and no {@code @EnableConfigurationProperties} needed.
 *
 * <p>Why bother instead of {@code @Value("${app.payments.base-url}")}?
 * <ul>
 *   <li>Fails at startup, not at first use, when a value is missing or malformed.</li>
 *   <li>Relaxed binding: {@code APP_PAYMENTS_BASEURL}, {@code app.payments.base-url} and
 *       {@code app.payments.baseUrl} all reach the same field.</li>
 *   <li>Shows up in {@code /actuator/configprops} and in IDE completion.</li>
 *   <li>Testable as a plain object, with no Spring context.</li>
 * </ul>
 *
 * <p>The backoff values are {@code long} milliseconds rather than {@code Duration} on purpose:
 * spring-retry's {@code @Backoff(delayExpression = ...)} evaluates to a number of milliseconds, and
 * having ONE key per knob - shared by the annotation and the programmatic {@code RetryTemplate} -
 * beats having two keys that can drift apart.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid Payments payments, @Valid Outbox outbox) {

    public record Payments(
            @NotBlank String baseUrl,
            /** Total attempts including the first. 3 is sane; 10 is an outage amplifier. */
            @Min(1) @Max(10) int maxAttempts,
            /** Delay before the first retry. */
            @Min(1) long initialBackoffMillis,
            /** Each retry multiplies the previous delay by this factor. */
            @Min(1) double backoffMultiplier,
            /** Upper bound, so exponential growth cannot turn into a minute-long sleep. */
            @Min(1) long maxBackoffMillis,
            /** Randomise delays to break up thundering herds. */
            boolean jitter,
            /** Cap on how long a server-sent {@code Retry-After} we are willing to honour. */
            Duration maxRetryAfter) {

        public Payments {
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            initialBackoffMillis = initialBackoffMillis <= 0 ? 200 : initialBackoffMillis;
            backoffMultiplier = backoffMultiplier < 1 ? 2.0 : backoffMultiplier;
            maxBackoffMillis = maxBackoffMillis <= 0 ? 2_000 : maxBackoffMillis;
            maxRetryAfter = maxRetryAfter != null ? maxRetryAfter : Duration.ofSeconds(5);
        }

        public Duration initialBackoff() {
            return Duration.ofMillis(initialBackoffMillis);
        }

        public Duration maxBackoff() {
            return Duration.ofMillis(maxBackoffMillis);
        }
    }

    public record Outbox(boolean publisherEnabled, @Min(1) @Max(1000) int batchSize) {

        public Outbox {
            batchSize = batchSize <= 0 ? 50 : batchSize;
        }
    }
}
