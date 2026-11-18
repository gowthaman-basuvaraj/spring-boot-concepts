package com.example.orders.support.extension;

import java.lang.reflect.Proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * A {@link BeanPostProcessor} that <b>replaces</b> the bean with a proxy.
 *
 * <p>This is the lesson worth internalising: a BPP may return a <em>different object</em> from the
 * one it was given, and whatever it returns is what the rest of the application gets injected. That
 * single capability is how {@code @Transactional}, {@code @Cacheable}, {@code @Async} and
 * {@code @Retryable} all work - {@code AbstractAutoProxyCreator} is "just" a BPP that returns
 * proxies. Writing a tiny one yourself demystifies the whole framework.
 *
 * <p>It also explains the two rules you keep meeting:
 * <ul>
 *   <li><b>Why self-invocation fails.</b> The proxy wraps the target; the target's own
 *       {@code this} is not the proxy, so an internal call never passes through this
 *       {@code invoke} method.</li>
 *   <li><b>Why you inject interfaces.</b> A JDK dynamic proxy (used here) can only implement
 *       interfaces. Spring falls back to CGLIB subclassing for classes - which cannot proxy a
 *       {@code final} class or a {@code final}/{@code private} method.</li>
 * </ul>
 *
 * <p>Rules for writing a BPP, all of which bite:
 * <ol>
 *   <li>It is created <b>before</b> application beans, so it must not depend on them. Injecting a
 *       {@code @ConfigurationProperties} bean here triggers the "is not eligible for getting
 *       processed by all BeanPostProcessors" warning and silently leaves beans unproxied. Read the
 *       {@code Environment} instead.</li>
 *   <li>Declare the {@code @Bean} factory method {@code static}, for the same reason.</li>
 *   <li>Implement {@link PriorityOrdered} so it is registered before other post-processors.</li>
 *   <li>Proxy in {@code postProcessAfterInitialization}, not {@code Before} - the bean is not fully
 *       initialised yet in the latter.</li>
 * </ol>
 */
public class TimingBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(TimingBeanPostProcessor.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Monitored monitored = AnnotatedElementUtils.findMergedAnnotation(bean.getClass(), Monitored.class);
        if (monitored == null) {
            return bean; // returning the bean unchanged is the normal case
        }
        Class<?>[] interfaces = bean.getClass().getInterfaces();
        if (interfaces.length == 0) {
            // Honest failure beats silent no-op: tell the developer why nothing happened.
            log.warn("@Monitored bean '{}' implements no interface, so it cannot be JDK-proxied; "
                    + "annotate an interface-backed bean or use CGLIB", beanName);
            return bean;
        }
        log.debug("wrapping bean '{}' in a timing proxy", beanName);
        return Proxy.newProxyInstance(bean.getClass().getClassLoader(), interfaces,
                (proxy, method, args) -> {
                    long start = System.nanoTime();
                    try {
                        return method.invoke(bean, args);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        // Unwrap, or callers see InvocationTargetException instead of their own
                        // exception - and every catch block downstream stops matching.
                        throw ex.getTargetException();
                    } finally {
                        long millis = (System.nanoTime() - start) / 1_000_000;
                        if (millis >= monitored.slowerThanMillis()) {
                            log.warn("slow call {}.{} took {}ms",
                                    beanName, method.getName(), millis);
                        } else {
                            log.debug("{}.{} took {}ms", beanName, method.getName(), millis);
                        }
                    }
                });
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }
}
