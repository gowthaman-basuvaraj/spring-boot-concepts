package com.example.orders.support.extension;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * A {@link BeanFactoryPostProcessor} - the extension point one level deeper than
 * {@code BeanPostProcessor}.
 *
 * <table border="1">
 *   <caption>The post-processor family, in the order they run</caption>
 *   <tr><th>Type</th><th>Sees</th><th>Can</th></tr>
 *   <tr><td>{@code BeanDefinitionRegistryPostProcessor}</td><td>the registry</td>
 *       <td><b>add or remove</b> definitions. How {@code @ComponentScan}, Spring Data repositories,
 *       {@code @FeignClient} and {@code @MapperScan} all work</td></tr>
 *   <tr><td>{@code BeanFactoryPostProcessor} (this)</td><td>all definitions, no instances</td>
 *       <td><b>edit</b> definitions: scope, lazy-init, property values, primary flag</td></tr>
 *   <tr><td>{@code BeanPostProcessor}</td><td>each instance</td>
 *       <td><b>wrap or replace</b> instances - this is where AOP happens</td></tr>
 *   <tr><td>{@code SmartInitializingSingleton}</td><td>a fully-built context</td>
 *       <td>react once every singleton exists</td></tr>
 * </table>
 *
 * <p><b>The critical rule:</b> a BFPP must NOT instantiate beans. Calling
 * {@code beanFactory.getBean(...)} here forces early creation, before other post-processors are
 * registered, and the victim silently misses out on proxying - no transactions, no caching, no
 * metrics, no warning. Inspect and edit {@code BeanDefinition}s; never ask for instances.
 *
 * <p>What this one does: logs an inventory of the application's own bean definitions, and
 * demonstrates editing one (forcing lazy initialisation on the dev-only stubs so they cost nothing
 * until first use).
 */
public class BeanDefinitionAuditor implements BeanFactoryPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BeanDefinitionAuditor.class);

    private final Environment environment;

    public BeanDefinitionAuditor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String[] names = beanFactory.getBeanDefinitionNames();

        long lazified = Arrays.stream(names)
                .filter(name -> name.endsWith("StubController"))
                .peek(name -> beanFactory.getBeanDefinition(name).setLazyInit(true))
                .count();

        if (log.isInfoEnabled()) {
            String summary = Arrays.stream(names)
                    .map(beanFactory::getBeanDefinition)
                    .map(BeanDefinition::getBeanClassName)
                    .filter(className -> className != null && className.startsWith("com.example"))
                    .map(className -> className.substring(0, className.lastIndexOf('.')))
                    .collect(Collectors.groupingBy(pkg -> pkg, Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .map(e -> "    " + e.getKey() + " = " + e.getValue())
                    .collect(Collectors.joining("\n"));
            log.info("bean definitions: {} total, {} marked lazy by this BFPP, own beans by package:\n{}",
                    names.length, lazified, summary);
        }
        log.debug("active profiles at BFPP time: {}", Arrays.toString(environment.getActiveProfiles()));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
