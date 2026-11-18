package com.example.orders.config;

import java.util.Map;

import com.example.orders.payments.PaymentExceptions.TransientPaymentException;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Retry infrastructure: a listener for observability, and a programmatic {@link RetryTemplate} for
 * the cases annotations cannot express.
 *
 * <h2>Annotation vs template</h2>
 * {@code @Retryable} is right for 90% of cases. Reach for {@code RetryTemplate} when you need to
 * decide the delay <em>from the exception</em> (honouring {@code Retry-After}), retry a lambda
 * rather than a method, or share one policy across several call sites.
 *
 * <h2>Rules for retrying anything</h2>
 * <ol>
 *   <li><b>Only idempotent operations.</b> GET/PUT/DELETE are idempotent by definition; POST needs
 *       an idempotency key.</li>
 *   <li><b>Only transient failures.</b> See {@code PaymentExceptions}.</li>
 *   <li><b>Always cap attempts AND total time.</b> A caller with a 2s SLA does not benefit from
 *       3 attempts with 2s backoff - you have turned a fast failure into a timeout.</li>
 *   <li><b>Never retry at more than one layer.</b> Retries multiply: 3 at the client times 3 at
 *       the gateway times 3 at the service is 27 requests for one user action. This is how a small
 *       blip becomes a self-inflicted DDoS. Pick ONE layer - usually the one closest to the
 *       dependency - and make the others fail fast.</li>
 *   <li><b>Pair retries with a circuit breaker and a bulkhead.</b> Retry handles blips; the
 *       breaker handles outages; the bulkhead stops one slow dependency from consuming every
 *       thread.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class RetryConfig {

    private static final Logger log = LoggerFactory.getLogger(RetryConfig.class);

    /**
     * Referenced by name from {@code @Retryable(listeners = "retryMetricsListener")}.
     *
     * <p>Without this you are blind: retries hide latency. The metric answers "are we retrying
     * more than usual?" and the log line answers "why?".
     */
    @Bean
    RetryListener retryMetricsListener(MeterRegistry registry) {
        return new RetryListener() {

            @Override
            public <T, E extends Throwable> void onError(RetryContext context,
                    RetryCallback<T, E> callback, Throwable throwable) {
                String operation = String.valueOf(context.getAttribute(RetryContext.NAME));
                // NOTE: onError fires for EVERY failed attempt, including exceptions the policy
                // classifies as non-retryable. Do not read this log line as "we retried".
                log.warn("attempt {} failed on {}: {}",
                        context.getRetryCount(), operation, throwable.toString());
                registry.counter("retry.attempts",
                        "operation", operation,
                        "exception", throwable.getClass().getSimpleName()).increment();
            }

            @Override
            public <T, E extends Throwable> void close(RetryContext context,
                    RetryCallback<T, E> callback, Throwable throwable) {
                if (throwable != null) {
                    registry.counter("retry.exhausted",
                            "operation", String.valueOf(context.getAttribute(RetryContext.NAME))).increment();
                }
            }
        };
    }

    /**
     * A reusable template with exponential backoff and jitter.
     *
     * <p>{@link ExponentialRandomBackOffPolicy} is spring-retry's jittered exponential policy: the
     * actual sleep is uniformly random in {@code [delay, delay * multiplier)}. "Full jitter" in the
     * AWS sense. Prefer it over {@code ExponentialBackOffPolicy} for anything that fans out.
     */
    @Bean
    RetryTemplate paymentRetryTemplate(AppProperties properties, RetryListener retryMetricsListener) {
        AppProperties.Payments config = properties.payments();

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(config.maxAttempts(),
                Map.of(TransientPaymentException.class, true), true, false);

        ExponentialRandomBackOffPolicy backOff = new ExponentialRandomBackOffPolicy();
        backOff.setInitialInterval(config.initialBackoffMillis());
        backOff.setMultiplier(config.backoffMultiplier());
        backOff.setMaxInterval(config.maxBackoffMillis());

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOff);
        template.registerListener(retryMetricsListener);
        // Do not wrap the final failure in ExhaustedRetryException when no recoverer exists.
        template.setThrowLastExceptionOnExhausted(true);
        return template;
    }
}
