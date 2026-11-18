package com.example.orders.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON customization.
 *
 * <p>Two ways to influence Jackson in Boot:
 * <ol>
 *   <li><b>Properties</b> ({@code spring.jackson.*}) - do this when a property exists.</li>
 *   <li><b>{@link Jackson2ObjectMapperBuilderCustomizer}</b> - for anything else. It composes with
 *       Boot's defaults.</li>
 * </ol>
 * What you should NOT do is declare your own {@code ObjectMapper} bean: that replaces Boot's,
 * dropping the auto-registered modules (JavaTimeModule, Jdk8Module, ParameterNamesModule) that make
 * {@code Instant} and records serialise correctly, plus every {@code spring.jackson.*} property.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                // ISO-8601 strings, not epoch numbers. The single most common API bug in Boot
                // services is timestamps changing shape between environments.
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Reject unknown fields on INPUT: a client sending {"amout": 10} should get a 400,
                // not a silently-zero order. (Set to false if you need forwards compatibility with
                // older/newer producers - it is a conscious API-versioning decision either way.)
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Omit nulls to keep payloads small and stop clients depending on null vs absent.
                .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    }
}
