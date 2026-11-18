package com.example.platform.audit;

import java.time.Instant;
import java.util.List;

/** Immutable audit event. A record is the natural shape for this. */
public record AuditRecord(
        Instant at,
        String action,
        String correlationId,
        List<String> args,
        String result,
        long durationMillis,
        Outcome outcome,
        String failure) {

    public enum Outcome { SUCCESS, FAILURE }

    public boolean failed() {
        return outcome == Outcome.FAILURE;
    }
}
