package com.example.orders.repo;

import java.util.List;

import com.example.orders.domain.OutboxMessage;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Claim a batch of unpublished messages.
     *
     * <p>{@code PESSIMISTIC_WRITE} + {@code Limit} is the simple single-poller pattern. With
     * multiple pollers you want {@code FOR UPDATE SKIP LOCKED} (native query on PostgreSQL) so
     * instances do not block each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboxMessage m where m.publishedAt is null order by m.id asc")
    List<OutboxMessage> claimUnpublished(Limit limit);

    long countByPublishedAtIsNull();
}
