package com.example.orders.config;

import java.util.Optional;

import com.example.platform.correlation.CorrelationContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA auditing, deliberately in its OWN tiny configuration class.
 *
 * <p>Why not next to the rest of {@code JpaConfig}? Because slice tests exclude regular
 * {@code @Configuration} classes. Put {@code @EnableJpaAuditing} in a class that also defines
 * DataSources and transaction managers and your {@code @DataJpaTest} will fail with
 *
 * <pre>NULL not allowed for column "CREATED_AT"</pre>
 *
 * - auditing was simply never enabled. A one-purpose configuration class can be pulled into a
 * slice test with {@code @Import(JpaAuditingConfig.class)} without dragging in the whole
 * infrastructure. This is the general rule for configuration design: one concern per class, so it
 * can be composed.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    /**
     * Supplies {@code @CreatedBy}/{@code @LastModifiedBy}. In a real service this reads
     * {@code SecurityContextHolder.getContext().getAuthentication()}; here it falls back to the
     * correlation id so the wiring is visible.
     *
     * <p>Returning {@code Optional.empty()} leaves the column null - never throw from here, it
     * runs inside the flush.
     */
    @Bean
    AuditorAware<String> auditorAware() {
        return () -> Optional.ofNullable(CorrelationContext.get()).or(() -> Optional.of("system"));
    }
}
