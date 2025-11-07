package com.example.demo.utils;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AppConfig {
    @Value("${spring.application.name}")
    private String appName;

    public String getAppName() {
        return appName;
    }

    @ConditionalOnProperty(name = "spring.datasource.platform", havingValue = "postgresql")
    @Bean
    public DBConfig postgresConfig() {
        return new DBConfig(true);
    }

    @Bean
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


}
