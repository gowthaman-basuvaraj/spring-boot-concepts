package com.example.orders.service;

import java.util.List;

import com.example.orders.config.AppProperties;
import com.example.orders.domain.OutboxMessage;
import com.example.orders.repo.OutboxRepository;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox and "publishes" (here: logs) the messages.
 *
 * <p>Scheduling notes:
 * <ul>
 *   <li>{@code fixedDelay} measures from the END of the previous run - the right choice for a
 *       poller. {@code fixedRate} measures from the start and will queue up runs if one is slow.</li>
 *   <li>The default scheduler has a pool size of ONE: a slow job delays every other job in the
 *       application. Set {@code spring.task.scheduling.pool.size}.</li>
 *   <li>With more than one instance deployed, every instance runs every job. Use a lock
 *       (ShedLock, a DB advisory lock, or {@code FOR UPDATE SKIP LOCKED} as the claim) or accept
 *       duplicates - which, for an outbox with idempotent consumers, is fine.</li>
 *   <li>Guard it with a property so tests and local runs can switch it off.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "publisher-enabled", havingValue = "true")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outbox;
    private final AppProperties.Outbox config;
    private final MeterRegistry metrics;

    public OutboxPublisher(OutboxRepository outbox, AppProperties properties, MeterRegistry metrics) {
        this.outbox = outbox;
        this.config = properties.outbox();
        this.metrics = metrics;
    }

    /**
     * Claim, publish, mark - all in one short transaction. The lock is held only for the duration
     * of the batch, so keep the batch small.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> batch = outbox.claimUnpublished(Limit.of(config.batchSize()));
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxMessage message : batch) {
            try {
                // Real implementation: kafkaTemplate.send(...).get() or an SNS/SQS publish.
                log.info("publishing outbox message {} {}/{}", message.getId(),
                        message.getAggregateType(), message.getEventType());
                message.markPublished();
                metrics.counter("outbox.published", "type", message.getEventType()).increment();
            } catch (RuntimeException ex) {
                // One bad message must not block the batch. Count attempts and move it to a
                // dead-letter table once a threshold is crossed.
                message.markFailed();
                metrics.counter("outbox.failed", "type", message.getEventType()).increment();
                log.error("failed to publish outbox message {}", message.getId(), ex);
            }
        }
    }
}
