package com.example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>{@code @SpringBootApplication} is a composition of three annotations - knowing this is the
 * difference between guessing and understanding:
 * <ul>
 *   <li>{@code @SpringBootConfiguration} - marks the class as THE configuration root. Tests locate
 *       it by walking up the package tree, which is why your test package must mirror the main
 *       package.</li>
 *   <li>{@code @EnableAutoConfiguration} - imports every class listed in every
 *       {@code AutoConfiguration.imports} file on the classpath, then filters them through their
 *       {@code @Conditional}s.</li>
 *   <li>{@code @ComponentScan} - scans THIS package and below. It does not see
 *       {@code com.example.platform}; that code is contributed by auto-configuration instead.</li>
 * </ul>
 *
 * <p>{@code @ConfigurationPropertiesScan} means {@code @ConfigurationProperties} classes in this
 * application do not each need {@code @EnableConfigurationProperties} or {@code @Component}.
 *
 * <p>Prefer putting {@code @Enable*} switches here (or in small {@code @Configuration} classes)
 * rather than scattering them: it gives one place to answer "is retry even on in this service?".
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableRetry // activates @Retryable; proxy-based, so the usual self-invocation rules apply
// Scans for @FeignClient interfaces. Without it the interfaces are just interfaces, and injecting
// one fails with "No qualifying bean of type CatalogFeignClient" - with nothing to hint that an
// annotation on the interface was meant to do something.
@EnableFeignClients
public class OrderServiceApplication {

    public static void main(String[] args) {
        // Equivalent to new SpringApplicationBuilder(...).run(args), which you would use to
        // customise the banner, add initializers, or build a parent/child context hierarchy.
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
