package com.example.demo.utils;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Duration;

@Configuration
@EnableAsync
public class AppConfig {
    @Value("${spring.application.name}")
    private String appName;

    public String getAppName() {
        return appName;
    }

    @ConditionalOnProperty(name = "spring.jpa.database", havingValue = "postgresql")
    @Bean
    public DBConfig postgresConfig() {
        return new DBConfig(true);
    }

    @Bean
    @Primary
    public DBConfig defaultDbConfig() {
        return new DBConfig(false);
    }

    @Bean
    public RoleConfig defaultRoleConfig() {
        return new RoleConfig("ANON", false);
    }


    @Bean
    @Qualifier("adminRoleConfig")
    public RoleConfig adminRoleConfig() {
        return new RoleConfig("ANON", false);
    }

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(4)).build())
                .circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
                .build());
    }

}
