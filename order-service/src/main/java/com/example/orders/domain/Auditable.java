package com.example.orders.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Shared audit columns.
 *
 * <p>{@code @MappedSuperclass} contributes mappings without being an entity itself (no table, not
 * queryable). Contrast with {@code @Inheritance}, which models a real type hierarchy.
 *
 * <p>Auditing is driven by {@link AuditingEntityListener} plus {@code @EnableJpaAuditing} and an
 * {@code AuditorAware} bean - see {@code JpaConfig}. Doing this in the database with triggers or
 * {@code DEFAULT now()} is also valid; what you must not do is set these by hand at 40 call sites.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
