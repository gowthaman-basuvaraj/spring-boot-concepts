package com.example.platform.audit;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tiny shared state between the aspect (writer) and the actuator endpoint (reader).
 *
 * <p>Deliberately NOT implemented by decorating {@link AuditSink}: a second {@code AuditSink} bean
 * would make injection ambiguous and break the {@code @ConditionalOnMissingBean} contract that lets
 * applications replace the sink. Keep the "pluggable strategy" and the "observable counters"
 * concerns separate.
 */
public class AuditCounters {

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void record(AuditRecord record) {
        calls.incrementAndGet();
        if (record.failed()) {
            failures.incrementAndGet();
        }
    }

    public void reset() {
        calls.set(0);
        failures.set(0);
    }

    public Map<String, Long> snapshot() {
        return Map.of("calls", calls.get(), "failures", failures.get());
    }
}
