package com.example.platform.autoconfigure;

import com.example.platform.http.PlatformRestClientCustomizer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Contributes platform HTTP defaults.
 *
 * <p>{@code @AutoConfiguration(before = ...)} is how you order auto-configurations. Our customizer
 * must be registered <em>before</em> Boot builds its {@code RestClient.Builder} bean, otherwise the
 * builder is already created and never sees us. {@code before}/{@code after} accept classes or
 * (for optional dependencies) class names as strings.
 */
@AutoConfiguration(before = RestClientAutoConfiguration.class)
@ConditionalOnClass(RestClient.class)
public class PlatformHttpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "platformRestClientCustomizer")
    RestClientCustomizer platformRestClientCustomizer(PlatformProperties properties) {
        return new PlatformRestClientCustomizer(properties);
    }
}
