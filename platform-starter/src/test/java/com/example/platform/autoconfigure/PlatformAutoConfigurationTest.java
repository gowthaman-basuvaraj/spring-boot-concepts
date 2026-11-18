package com.example.platform.autoconfigure;

import com.example.platform.actuate.PlatformEndpoint;
import com.example.platform.audit.AuditAspect;
import com.example.platform.audit.AuditRecord;
import com.example.platform.audit.AuditSink;
import com.example.platform.audit.LoggingAuditSink;
import com.example.platform.convert.MaskedValue;
import com.example.platform.correlation.CorrelationIdFilter;
import com.example.platform.support.PlatformConfigurationException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How to test an auto-configuration properly.
 *
 * <p>{@link ApplicationContextRunner} starts a throwaway context per assertion - no
 * {@code @SpringBootTest}, no web server, milliseconds per case. It is the only sane way to cover
 * the combinatorics of {@code @Conditional}: property on/off, bean present/absent, class on/off
 * the classpath. {@link FilteredClassLoader} simulates a missing dependency, which is how you
 * prove {@code @ConditionalOnClass} actually works instead of hoping it does.
 *
 * <p>If you ship a starter without tests like these, you will ship a starter that breaks the first
 * application whose classpath differs from yours.
 */
class PlatformAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformConversionAutoConfiguration.class,
                    PlatformAutoConfiguration.class,
                    PlatformHttpAutoConfiguration.class))
            .withPropertyValues("platform.application-name=test-app", "platform.instance-id=unit");

    @Test
    void registersDefaultBeans() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(PlatformProperties.class)
                .hasSingleBean(AuditSink.class)
                .hasSingleBean(AuditAspect.class)
                .hasSingleBean(PlatformEndpoint.class));
    }

    @Test
    void appliesDefaultsWhenNothingIsConfigured() {
        runner.run(context -> {
            PlatformProperties properties = context.getBean(PlatformProperties.class);
            assertThat(properties.correlation().headerName()).isEqualTo("X-Correlation-Id");
            assertThat(properties.audit().enabled()).isTrue();
            assertThat(properties.http().connectTimeout().toMillis()).isEqualTo(2000);
        });
    }

    /** The most important property of a starter: the application always wins. */
    @Test
    void backsOffWhenTheApplicationDefinesItsOwnSink() {
        runner.withUserConfiguration(CustomSinkConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(AuditSink.class)
                        .getBean(AuditSink.class)
                        .isInstanceOf(CountingSink.class));
    }

    @Test
    void auditCanBeDisabled() {
        runner.withPropertyValues("platform.audit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AuditAspect.class));
    }

    /** Proves @ConditionalOnClass(Endpoint.class): no actuator on the classpath, no endpoint. */
    @Test
    void endpointIsAbsentWithoutActuator() {
        runner.withClassLoader(new FilteredClassLoader(
                        "org.springframework.boot.actuate.endpoint.annotation.Endpoint"))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(PlatformEndpoint.class)
                        .hasSingleBean(AuditSink.class));
    }

    /** The correlation filter is a servlet concern - it must not appear in a plain context. */
    @Test
    void correlationFilterOnlyInServletContexts() {
        runner.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));

        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformConversionAutoConfiguration.class, PlatformAutoConfiguration.class))
                .withPropertyValues("platform.application-name=test-app")
                .run(context -> {
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                    assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
                            .isInstanceOf(CorrelationIdFilter.class);
                });
    }

    /** The custom property type and its @ConfigurationPropertiesBinding converter. */
    @Test
    void bindsAndMasksTheApiKey() {
        runner.withPropertyValues("platform.http.api-key=super-secret-value")
                .run(context -> {
                    MaskedValue key = context.getBean(PlatformProperties.class).http().apiKey();
                    assertThat(key.reveal()).isEqualTo("super-secret-value");
                    assertThat(key.toString()).isEqualTo("****alue");
                });
    }

    /** Validation failures must stop the context, not surface at 3am. */
    @Test
    void rejectsBlankApplicationName() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PlatformConversionAutoConfiguration.class, PlatformAutoConfiguration.class))
                .withPropertyValues("platform.application-name=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        // The validation failure is nested a few levels down:
                        // UnsatisfiedDependency -> ConfigurationPropertiesBindException ->
                        // BindException -> BindValidationException.
                        .rootCause()
                        .hasMessageContaining("applicationName"));
    }

    /** Cross-field validation that annotations cannot express, plus its FailureAnalyzer. */
    @Test
    void rejectsReadTimeoutSmallerThanConnectTimeout() {
        runner.withPropertyValues("platform.http.connect-timeout=5s", "platform.http.read-timeout=1s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(PlatformConfigurationException.class)
                        .hasMessageContaining("read-timeout"));
    }

    @Test
    void defaultSinkIsTheLoggingOne() {
        runner.run(context -> assertThat(context.getBean(AuditSink.class))
                .isInstanceOf(LoggingAuditSink.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomSinkConfiguration {

        @Bean
        AuditSink auditSink() {
            return new CountingSink();
        }
    }

    static class CountingSink implements AuditSink {

        int count;

        @Override
        public void accept(AuditRecord record) {
            count++;
        }
    }
}
