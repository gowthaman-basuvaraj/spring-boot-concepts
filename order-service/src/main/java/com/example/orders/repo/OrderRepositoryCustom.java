package com.example.orders.repo;

import java.time.Instant;
import java.util.List;

/** Fragment interface - implemented by {@code OrderRepositoryCustomImpl} (the suffix matters). */
public interface OrderRepositoryCustom {

    /**
     * Aggregated totals per status. Deliberately typed as a record rather than {@code Object[]}:
     * tuple arrays in service code are how query results become unreadable.
     */
    List<StatusTotal> statusTotalsSince(Instant since);
}
