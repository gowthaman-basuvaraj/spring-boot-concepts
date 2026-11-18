package com.example.orders.support.extension;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * A home-made {@code @Enable*} annotation, so the mechanism stops being magic.
 *
 * <p><b>Every {@code @Enable*} in Spring is this:</b> an annotation that is itself
 * {@code @Import}-ing something. {@code @EnableTransactionManagement} imports a selector that
 * registers the transaction advisor; {@code @EnableFeignClients} imports a registrar that scans for
 * {@code @FeignClient} interfaces and registers a proxy definition per interface;
 * {@code @EnableCaching} imports the caching advisor. There is no other trick.
 *
 * <p>{@code @Import} accepts three kinds of thing:
 * <ul>
 *   <li>a {@code @Configuration} class - static, simplest;</li>
 *   <li>an {@code ImportSelector} - returns class NAMES to import, decided at runtime (this is how
 *       {@code @EnableAutoConfiguration} reads the {@code .imports} files);</li>
 *   <li>an {@code ImportBeanDefinitionRegistrar} - registers bean definitions directly, which is
 *       what you need when the number and shape of beans depends on the annotation's attributes.
 *       See {@link GreeterRegistrar}.</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(GreeterRegistrar.class)
public @interface EnableGreeters {

    /** One bean is registered per name. */
    String[] names();

    /** Prefix each greeting with this. */
    String prefix() default "Hello";
}
