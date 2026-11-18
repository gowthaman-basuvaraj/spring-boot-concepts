package com.example.orders.service;

import com.example.orders.domain.OutboxMessage;
import com.example.orders.repo.OutboxRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes outbox rows.
 *
 * <p>{@code MANDATORY} again, and for the same reason: an outbox row written in its own
 * transaction defeats the entire pattern. The row must commit with - or not at all with - the
 * business change.
 */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outbox, ObjectMapper objectMapper) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String aggregateType, String aggregateId, String eventType, Object payload) {
        outbox.save(new OutboxMessage(aggregateType, aggregateId, eventType, serialise(payload)));
    }

    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Serialisation failure is a programming error: fail the transaction loudly.
            throw new IllegalStateException("cannot serialise outbox payload: " + payload, ex);
        }
    }
}
