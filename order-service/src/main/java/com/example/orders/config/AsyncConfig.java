package com.example.orders.config;

import java.util.Map;
import java.util.concurrent.Executor;

import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async execution.
 *
 * <p>Points that separate working code from code that works in production:
 * <ul>
 *   <li><b>Never use the default executor.</b> Without a configured {@code Executor}, Spring uses
 *       {@code SimpleAsyncTaskExecutor}, which creates an UNBOUNDED number of threads - a fine way
 *       to OOM under load. Give it a bounded pool and a bounded queue.</li>
 *   <li><b>ThreadLocals do not propagate.</b> MDC (correlation id), the SecurityContext and the
 *       transaction are all thread-bound. A {@link TaskDecorator} copies what you need. This is
 *       also why {@code @Async} + {@code @Transactional} on the same method is almost always a
 *       bug: the async method runs on another thread, with a NEW transaction, and the caller's
 *       transaction may already have committed - or rolled back.</li>
 *   <li><b>Exceptions vanish.</b> A {@code void @Async} method's exception goes nowhere unless you
 *       install an {@link AsyncUncaughtExceptionHandler}. Prefer returning
 *       {@code CompletableFuture} so the caller can handle failure.</li>
 *   <li><b>Shutdown.</b> {@code setWaitForTasksToCompleteOnShutdown} + an await timeout prevents
 *       losing in-flight work on a rolling deploy.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig implements AsyncConfigurer {

    /** Copies the MDC (and anything else thread-bound) onto the worker thread. */
    static class MdcTaskDecorator implements TaskDecorator {

        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    // Restore, do not just clear: pooled threads outlive the task.
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        }
    }

    /**
     * Named {@code applicationTaskExecutor} so Boot's {@code TaskExecutionAutoConfiguration} backs
     * off ({@code @ConditionalOnMissingBean}) and MVC async / {@code @Async} both pick it up.
     *
     * <p>Note we do NOT override {@code AsyncConfigurer.getAsyncExecutor()} to return this: with
     * {@code proxyBeanMethods = false}, a direct call to a {@code @Bean} method is not intercepted,
     * so Spring would build a second, unmanaged executor. Leaving it null makes Spring look the
     * bean up instead - which is what we want.
     *
     * <p>On Java 21 you can often delete this whole method and set
     * {@code spring.threads.virtual.enabled=true}: with virtual threads the pool-sizing problem
     * mostly disappears for IO-bound work. Keep a bounded pool for CPU-bound work.
     */
    @Bean("applicationTaskExecutor")
    Executor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("orders-async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        // Back-pressure instead of silent discard: the caller runs the task itself when the
        // queue is full. The alternative (AbortPolicy) surfaces as RejectedExecutionException.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // Logs instead of swallowing. Replace with a metric + alert in a real service.
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
