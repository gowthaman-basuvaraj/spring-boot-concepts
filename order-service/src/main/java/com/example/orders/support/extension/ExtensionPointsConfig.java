package com.example.orders.support.extension;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires up the container extension points, and demonstrates the last two members of the family.
 *
 * <p>Note the {@code static} factory methods: a {@code BeanPostProcessor} or
 * {@code BeanFactoryPostProcessor} is needed before ordinary beans exist, so if its factory method
 * is an instance method the enclosing configuration class must be built too early - and its own
 * dependencies then escape post-processing. Boot warns about this; most people do not read the
 * warning.
 */
@Configuration(proxyBeanMethods = false)
@EnableGreeters(names = { "world", "spring" }, prefix = "Hello")
public class ExtensionPointsConfig {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPointsConfig.class);

    @Bean
    static TimingBeanPostProcessor timingBeanPostProcessor() {
        return new TimingBeanPostProcessor();
    }

    @Bean
    static BeanFactoryPostProcessor beanDefinitionAuditor(Environment environment) {
        return new BeanDefinitionAuditor(environment);
    }

    /**
     * {@link SmartInitializingSingleton} fires once, after <b>every</b> singleton has been created
     * and post-processed - but still before the context is fully started and before the web server
     * binds its port.
     *
     * <p>It is the right hook for "now that everything exists, check it hangs together": validating
     * that a registry is non-empty, warming a cache, asserting that two configurations agree. Doing
     * this in {@code @PostConstruct} is too early (other beans may not exist) and in
     * {@code ApplicationReadyEvent} is arguably too late (you are already serving traffic).
     */
    @Bean
    SmartInitializingSingleton extensionPointSelfCheck(List<Greeter> greeters) {
        return () -> log.info("""
                container extension points active:
                    BeanFactoryPostProcessor      BeanDefinitionAuditor (edits definitions)
                    BeanPostProcessor             TimingBeanPostProcessor (returns proxies)
                    ImportBeanDefinitionRegistrar GreeterRegistrar -> {} beans: {}
                    SmartInitializingSingleton    this message
                """,
                greeters.size(), greeters.stream().map(Greeter::getName).toList());
    }
}
