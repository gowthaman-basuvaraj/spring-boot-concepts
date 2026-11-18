package com.example.platform.audit;

/**
 * Where audit records go. The default implementation logs; a product team can publish a bean of
 * their own (Kafka, DB table, SIEM) and the auto-configuration will back off thanks to
 * {@code @ConditionalOnMissingBean}. That is the single most important pattern in a shared starter:
 * <em>provide a default, never win an argument with the application.</em>
 */
public interface AuditSink {

    void accept(AuditRecord record);
}
