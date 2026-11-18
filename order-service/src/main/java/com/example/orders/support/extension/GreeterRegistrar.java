package com.example.orders.support.extension;

import java.util.Map;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers one {@link Greeter} bean definition per name listed on {@link EnableGreeters}.
 *
 * <p>This is the mechanism behind {@code @EnableFeignClients}, {@code @EnableJpaRepositories},
 * {@code @MapperScan} and every other annotation that turns a <em>declaration</em> into a variable
 * number of beans. Spring Data, for instance, finds your repository interfaces and registers a
 * factory-bean definition for each one - there is no code generation and no reflection magic beyond
 * this.
 *
 * <p>Two things to note:
 * <ul>
 *   <li>You work with {@link AnnotationMetadata}, not the annotation instance - the class is being
 *       read from bytecode and may not be loadable yet. {@code getAnnotationAttributes} returns a
 *       plain map.</li>
 *   <li>You register <b>definitions</b>, not instances, so the beans you create still get the full
 *       lifecycle: property injection, post-processing, AOP, scoping.</li>
 * </ul>
 */
public class GreeterRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(EnableGreeters.class.getName());
        if (attributes == null) {
            return;
        }
        String prefix = (String) attributes.get("prefix");
        for (String name : (String[]) attributes.get("names")) {
            registry.registerBeanDefinition("greeter_" + name,
                    BeanDefinitionBuilder.genericBeanDefinition(Greeter.class)
                            .addConstructorArgValue(prefix)
                            .addConstructorArgValue(name)
                            .getBeanDefinition());
        }
    }
}
