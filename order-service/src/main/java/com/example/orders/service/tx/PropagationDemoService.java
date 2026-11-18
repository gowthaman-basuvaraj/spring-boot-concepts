package com.example.orders.service.tx;

import com.example.orders.domain.TxNote;
import com.example.orders.repo.TxNoteRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "inner" half of the propagation demos. Every method records the ambient transaction and
 * writes a row, so a test can prove what survived.
 *
 * <p>Keeping these in a SEPARATE bean from the outer methods is not stylistic: a call from one
 * method of a bean to another method of the same bean does not pass through the proxy, so the
 * inner {@code @Transactional} would be ignored entirely. See
 * {@link TransactionBoundaryDemoService#selfInvocationBroken()}.
 */
@Service
public class PropagationDemoService {

    private static final Logger log = LoggerFactory.getLogger(PropagationDemoService.class);

    private final TxNoteRepository notes;

    public PropagationDemoService(TxNoteRepository notes) {
        this.notes = notes;
    }

    /**
     * REQUIRED (the default): join the caller's transaction, or start one if there is none.
     *
     * <p>Consequence people miss: if this method throws and the CALLER catches the exception, the
     * caller's commit still fails with {@code UnexpectedRollbackException} - "Transaction silently
     * rolled back because it has been marked as rollback-only". The inner failure marked the
     * SHARED transaction for rollback; there was never a separate one to abandon.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public TxInfo required(String label, boolean fail) {
        notes.save(new TxNote(label));
        TxInfo info = TxInfo.capture();
        log.debug("REQUIRED -> {}", info);
        if (fail) {
            throw new IllegalStateException("failing inside REQUIRED: " + label);
        }
        return info;
    }

    /**
     * REQUIRES_NEW: suspend the caller's transaction, run in a brand-new physical transaction,
     * then resume.
     *
     * <p>Use it for work that must survive the caller's rollback: audit rows, attempt counters,
     * dead-letter records. Two warnings:
     * <ul>
     *   <li>It consumes a SECOND connection from the pool while the first is still held. Nested
     *       REQUIRES_NEW under load is a classic pool-exhaustion deadlock.</li>
     *   <li>The new transaction cannot see the suspended one's uncommitted rows, and may block on
     *       its locks - a self-deadlock that only shows up in production.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TxInfo requiresNew(String label, boolean fail) {
        notes.save(new TxNote(label));
        TxInfo info = TxInfo.capture();
        log.debug("REQUIRES_NEW -> {}", info);
        if (fail) {
            throw new IllegalStateException("failing inside REQUIRES_NEW: " + label);
        }
        return info;
    }

    /**
     * NESTED: a SAVEPOINT inside the caller's transaction - which <b>does not work under
     * {@code JpaTransactionManager}</b>.
     *
     * <p>In principle NESTED is ideal: same physical transaction, same connection, no extra pool
     * pressure, and a failure rolls back only to the savepoint so the caller can carry on and
     * still commit the rest. In practice, {@code JpaTransactionManager} delegates savepoint
     * support to the {@code JpaDialect}'s transaction-data object, and Hibernate's
     * {@code SessionTransactionData} does not implement {@code SavepointManager}. Calling this
     * method therefore throws
     *
     * <pre>NestedTransactionNotSupportedException: JpaDialect does not support savepoints</pre>
     *
     * even with {@code nestedTransactionAllowed = true} and a DataSource bound to the manager.
     *
     * <p>This method is kept, and called by a test, precisely to pin that behaviour down. For
     * NESTED that really works, see {@code SavepointDemoService}, which performs the same
     * scenario over {@code JdbcClient} under the {@code jdbcTransactionManager} bean.
     */
    @Transactional(propagation = Propagation.NESTED)
    public TxInfo nested(String label, boolean fail) {
        notes.save(new TxNote(label));
        TxInfo info = TxInfo.capture();
        log.debug("NESTED -> {}", info);
        if (fail) {
            throw new IllegalStateException("failing inside NESTED: " + label);
        }
        return info;
    }

    /** MANDATORY: throws {@code IllegalTransactionStateException} if there is no transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TxInfo mandatory() {
        return TxInfo.capture();
    }

    /** NEVER: throws if a transaction IS active. Guards genuinely non-transactional work. */
    @Transactional(propagation = Propagation.NEVER)
    public TxInfo never() {
        return TxInfo.capture();
    }

    /**
     * NOT_SUPPORTED: suspends any active transaction and runs without one.
     *
     * <p>Useful for a long report query that must not hold the caller's locks or connection. Note
     * that the write below is auto-committed immediately and cannot be rolled back by the caller.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TxInfo notSupported(String label) {
        notes.save(new TxNote(label));
        return TxInfo.capture();
    }

    /**
     * SUPPORTS: join if there is a transaction, otherwise run without one. The subtle part is that
     * {@code isActualTransactionActive()} is false but synchronization IS active, so
     * {@code @TransactionalEventListener} and {@code TransactionSynchronization} callbacks behave
     * differently than you might expect. Prefer REQUIRED or NOT_SUPPORTED unless you really mean
     * "I do not care".
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public TxInfo supports() {
        return TxInfo.capture();
    }

    /** Read-only, so Hibernate skips dirty checking and the routing DataSource picks the replica. */
    @Transactional(readOnly = true)
    public TxInfo readOnly() {
        return TxInfo.capture();
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public long countByLabel(String label) {
        return notes.countByLabel(label);
    }
}
