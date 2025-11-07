package com.example.demo.config;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = Logger.getLogger(AsyncConfig.class.getName());

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncUncaughtExceptionHandler() {
            @Override
            public void handleUncaughtException(Throwable ex, Method method, Object... params) {
                // Log the exception!
                log.severe("Exception in async method '" + method.getName() + "': " + ex.getMessage());
                // You could also send this to an alert system.
            }
        };
    }

    // You can also configure your custom thread pool here (see below)
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);   // Set to your needs
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25); // Set a *bounded* queue
        executor.setThreadNamePrefix("MyAsync-");
        executor.initialize();
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }
}