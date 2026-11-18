package com.example.platform.support;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns an ugly 200-line stack trace into an actionable startup message.
 *
 * <p>This is how Boot produces its famously readable "APPLICATION FAILED TO START / Description /
 * Action" banners (port already in use, missing datasource URL, ...). Shipping analyzers with your
 * platform library is a huge quality-of-life win for the teams consuming it.
 *
 * <p>Registered in {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}.
 */
public class PlatformFailureAnalyzer extends AbstractFailureAnalyzer<PlatformConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, PlatformConfigurationException cause) {
        return new FailureAnalysis(
                "Invalid platform configuration: " + cause.getMessage(),
                cause.getRemedy(),
                cause);
    }
}
