package com.example.platform.autoconfigure;

import com.example.platform.actuate.PlatformEndpoint;
import com.example.platform.audit.AuditAspect;
import com.example.platform.audit.AuditCounters;
import com.example.platform.audit.AuditSink;
import com.example.platform.audit.LoggingAuditSink;
import com.example.platform.correlation.CorrelationIdFilter;
import com.example.platform.support.PlatformConfigurationException;
import com.example.platform.support.StartupTimingBeanPostProcessor;

import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The entry point of the starter.
 *
 * <h2>Why {@code @AutoConfiguration} and not {@code @Configuration}?</h2>
 * Auto-configurations are processed <em>after</em> all user configuration, in a dependency order
 * declared by {@code before}/{@code after}, and they are listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * They are deliberately NOT component-scanned: a library in {@code com.example.platform} would
 * never be found by an application scanning {@code com.example.orders}.
 *
 * <h2>The three rules of a well-behaved starter</h2>
 * <ol>
 *   <li>Every bean is {@code @ConditionalOnMissingBean} - the application always wins.</li>
 *   <li>Every feature has an on/off property, and the default is documented.</li>
 *   <li>Optional integrations are guarded by {@code @ConditionalOnClass} so the starter works
 *       whether or not actuator/micrometer/aspectj are present.</li>
 * </ol>
 *
 * <p>Debug what actually happened with {@code --debug} (prints the CONDITIONS EVALUATION REPORT)
 * or {@code /actuator/conditions}.
 */
@AutoConfiguration
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformAutoConfiguration {

    /**
     * Fail fast on nonsense configuration, with a FailureAnalyzer to explain it.
     * Validation that cannot be expressed with annotations belongs here.
     */
    public PlatformAutoConfiguration(PlatformProperties properties, Environment environment) {
        if (properties.http().readTimeout().compareTo(properties.http().connectTimeout()) < 0) {
            throw new PlatformConfigurationException(
                    "platform.http.read-timeout (" + properties.http().readTimeout()
                            + ") is smaller than platform.http.connect-timeout ("
                            + properties.http().connectTimeout() + ")",
                    "Set platform.http.read-timeout to a value greater than the connect timeout.");
        }
    }

    // ---------------------------------------------------------------- correlation id

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(prefix = "platform.correlation", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter(PlatformProperties properties) {
        // Registering through FilterRegistrationBean (instead of exposing the Filter directly)
        // gives explicit control over order and URL patterns, and avoids the filter being added
        // to the chain twice when it is also a @Component.
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter(properties.correlation()));
        registration.setOrder(CorrelationIdFilter.ORDER);
        registration.addUrlPatterns("/*");
        registration.setName("correlationIdFilter");
        return registration;
    }

    // ---------------------------------------------------------------- audit

    @Bean
    @ConditionalOnMissingBean
    AuditCounters auditCounters() {
        return new AuditCounters();
    }

    @Bean
    @ConditionalOnMissingBean(AuditSink.class)
    AuditSink loggingAuditSink(PlatformProperties properties) {
        return new LoggingAuditSink(properties.audit().slowCallThresholdMillis());
    }

    @Bean
    @ConditionalOnClass(Aspect.class)
    @ConditionalOnProperty(prefix = "platform.audit", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean
    AuditAspect auditAspect(AuditSink sink, AuditCounters counters, PlatformProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new AuditAspect(sink, counters, properties.audit(), meterRegistry);
    }

    // ---------------------------------------------------------------- diagnostics

    @Bean
    @ConditionalOnProperty(prefix = "platform", name = "startup-timing.enabled", havingValue = "true")
    static StartupTimingBeanPostProcessor startupTimingBeanPostProcessor(Environment environment) {
        // NOTE the `static`: a BeanPostProcessor factory method must be static, otherwise the
        // enclosing configuration class has to be instantiated too early and its own
        // dependencies escape post-processing (Boot logs a loud warning when you get this wrong).
        long threshold = environment.getProperty("platform.startup-timing.threshold-millis", Long.class, 100L);
        return new StartupTimingBeanPostProcessor(threshold);
    }

    /**
     * Only contributes the endpoint when actuator is on the classpath. Note the nested
     * configuration class: {@code @ConditionalOnClass} on a class means the enclosing class is
     * only parsed when the type is present, so {@code Endpoint} is never loaded without actuator.
     */
    @AutoConfiguration
    @ConditionalOnClass(Endpoint.class)
    public static class PlatformActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        PlatformEndpoint platformEndpoint(PlatformProperties properties, AuditCounters counters) {
            return new PlatformEndpoint(properties, counters);
        }
    }
}
