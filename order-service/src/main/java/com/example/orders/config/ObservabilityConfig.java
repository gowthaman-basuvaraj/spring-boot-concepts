package com.example.orders.config;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics and observations.
 *
 * <p>Boot already exports HTTP server/client, JVM, Hikari, Hibernate and cache metrics. What you
 * add yourself:
 * <ul>
 *   <li>{@link MeterRegistryCustomizer} for common tags (service, env, instance) - do this ONCE,
 *       centrally, or your dashboards will be unjoinable.</li>
 *   <li>{@link MeterFilter} to cap tag cardinality. High-cardinality tags (order number, user id,
 *       raw URI) are the number-one way to blow up a metrics backend; {@code maximumAllowableTags}
 *       and URI templating protect you.</li>
 *   <li>{@link TimedAspect}/{@link CountedAspect}/{@link ObservedAspect} so {@code @Timed},
 *       {@code @Counted} and {@code @Observed} work. These aspect beans are NOT auto-configured.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags(
            org.springframework.core.env.Environment environment) {
        String app = environment.getProperty("spring.application.name", "order-service");
        String instance = environment.getProperty("platform.instance-id", "local");
        return registry -> registry.config()
                .commonTags("application", app, "instance", instance)
                // Percentile histograms for the two things anyone ever asks about.
                .meterFilter(MeterFilter.maxExpected("http.server.requests", java.time.Duration.ofSeconds(10)))
                // Drop any high-cardinality tag that sneaks in from call sites.
                .meterFilter(MeterFilter.ignoreTags("orderNumber", "correlationId"));
    }

    @Bean
    TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    CountedAspect countedAspect(MeterRegistry registry) {
        return new CountedAspect(registry);
    }

    /** Enables {@code @Observed}: one annotation produces a metric AND a span. */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
