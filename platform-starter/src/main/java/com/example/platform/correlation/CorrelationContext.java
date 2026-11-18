package com.example.platform.correlation;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Access to the current request's correlation id.
 *
 * <p>Stored in SLF4J's {@link MDC} so every log line can carry it (see logging.pattern in
 * application.yml). MDC is a ThreadLocal: it does NOT cross {@code @Async} / executor boundaries
 * unless you copy it - see {@code AsyncConfig.MdcTaskDecorator} in the application module.
 */
public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
    }

    public static String getOrGenerate() {
        String existing = MDC.get(MDC_KEY);
        return existing != null ? existing : newId();
    }

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String id) {
        MDC.put(MDC_KEY, id);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
