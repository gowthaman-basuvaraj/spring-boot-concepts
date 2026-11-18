package com.example.platform.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;

/**
 * Reports beans that are slow to initialise - the cheapest way to answer
 * "why does this service take 40 seconds to start?".
 *
 * <p>{@link BeanPostProcessor} is the lowest-level container extension point you will normally
 * touch. Two rules:
 * <ol>
 *   <li>A BPP must not depend on application beans - it is created before them, and injecting a
 *       {@code @ConfigurationProperties} bean here causes "is not eligible for getting processed by
 *       all BeanPostProcessors" warnings and silently unproxied beans. Read the
 *       {@code Environment} instead, or make the BPP static/lazy.</li>
 *   <li>Implement {@link PriorityOrdered} so it is registered before other BPPs.</li>
 * </ol>
 */
public class StartupTimingBeanPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(StartupTimingBeanPostProcessor.class);

    private final Map<String, Long> startNanos = new ConcurrentHashMap<>();
    private final long thresholdMillis;

    public StartupTimingBeanPostProcessor(long thresholdMillis) {
        this.thresholdMillis = thresholdMillis;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        startNanos.put(beanName, System.nanoTime());
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Long start = startNanos.remove(beanName);
        if (start != null) {
            long millis = (System.nanoTime() - start) / 1_000_000;
            if (millis >= thresholdMillis) {
                log.info("slow bean init: {} took {}ms ({})", beanName, millis, bean.getClass().getName());
            }
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }
}
