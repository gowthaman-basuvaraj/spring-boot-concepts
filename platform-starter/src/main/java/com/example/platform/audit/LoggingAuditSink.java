package com.example.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger("audit");

    private final long slowCallThresholdMillis;

    public LoggingAuditSink(long slowCallThresholdMillis) {
        this.slowCallThresholdMillis = slowCallThresholdMillis;
    }

    @Override
    public void accept(AuditRecord record) {
        if (record.failed()) {
            log.warn("action={} outcome=FAILURE durationMs={} args={} failure={}",
                    record.action(), record.durationMillis(), record.args(), record.failure());
        } else if (record.durationMillis() >= slowCallThresholdMillis) {
            log.warn("action={} outcome=SUCCESS durationMs={} args={} (SLOW, threshold={}ms)",
                    record.action(), record.durationMillis(), record.args(), slowCallThresholdMillis);
        } else if (log.isInfoEnabled()) {
            log.info("action={} outcome=SUCCESS durationMs={} args={}{}",
                    record.action(), record.durationMillis(), record.args(),
                    record.result() == null ? "" : " result=" + record.result());
        }
    }
}
