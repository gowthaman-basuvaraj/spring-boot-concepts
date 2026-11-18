package com.example.platform.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Runs BEFORE the application context exists, while the {@code Environment} is being prepared.
 *
 * <p>Use it for things that must be in place before any bean is created or any
 * {@code @ConfigurationProperties} is bound: injecting a pod/instance id, deriving a property from
 * another, pulling a secret from a vault, activating a profile based on an environment variable.
 *
 * <p>Registration is via {@code META-INF/spring.factories} - NOT the
 * {@code AutoConfiguration.imports} file. Auto-configuration classes moved to the new imports file
 * in Boot 2.7/3.0, but every other Boot SPI (EnvironmentPostProcessor, ApplicationContextInitializer,
 * ApplicationListener, FailureAnalyzer, SpringApplicationRunListener) still uses spring.factories.
 * The class is instantiated reflectively, so it needs a public no-arg (or
 * {@code DeferredLogFactory}/{@code Log}) constructor and cannot be injected into.
 *
 * <p>Adding the source with {@code addLast} means real configuration always wins - a library
 * should supply defaults, not override the application.
 */
public class PlatformEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String SOURCE_NAME = "platformDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return; // Can be invoked more than once (e.g. by devtools restarts).
        }
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("platform.instance-id", resolveInstanceId(environment));
        defaults.put("platform.application-name", environment.getProperty("spring.application.name", "unnamed-app"));
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }

    private String resolveInstanceId(ConfigurableEnvironment environment) {
        // In Kubernetes HOSTNAME is the pod name - a far better instance id than a random UUID.
        String hostname = environment.getProperty("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor (which loads application.yml), so that
        // spring.application.name is already available.
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
