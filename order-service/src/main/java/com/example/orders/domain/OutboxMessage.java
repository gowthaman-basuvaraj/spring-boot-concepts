package com.example.orders.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Transactional outbox.
 *
 * <p>The problem it solves: you cannot atomically commit a database transaction AND publish to
 * Kafka/SQS. Two-phase commit across a DB and a broker is not worth the pain. So you write the
 * message into the SAME transaction as the business change; a separate poller publishes it and
 * marks it sent. You get at-least-once delivery with no lost events, at the cost of requiring
 * idempotent consumers.
 */
@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /**
     * Plain varchar, not {@code @Lob}. LOB handling differs across drivers (PostgreSQL maps
     * {@code @Lob String} to an OID large object unless you force {@code text}), and a 4000-char
     * limit is both portable and a healthy constraint on event size.
     */
    @Column(nullable = false, length = 4000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected OutboxMessage() {
    }

    public OutboxMessage(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.attempts++;
    }

    public void markFailed() {
        this.attempts++;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
