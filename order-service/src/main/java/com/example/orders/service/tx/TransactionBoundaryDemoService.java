package com.example.orders.service.tx;

import java.util.ArrayList;
import java.util.List;

import com.example.orders.domain.TxNote;
import com.example.orders.repo.TxNoteRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The "outer" half: transaction boundaries, the proxy self-invocation trap, rollback rules and
 * programmatic transactions.
 */
@Service
public class TransactionBoundaryDemoService {

    private static final Logger log = LoggerFactory.getLogger(TransactionBoundaryDemoService.class);

    private final PropagationDemoService inner;
    private final TxNoteRepository notes;
    private final TransactionTemplate transactionTemplate;
    private final TransactionBoundaryDemoService self;

    /**
     * {@code self} is this bean's own PROXY, injected lazily to break the cycle. It is the
     * explicit, honest fix for self-invocation: calling {@code self.method()} goes through the
     * proxy, so the annotation applies.
     *
     * <p>Alternatives: look the bean up from the {@code ApplicationContext}, use
     * {@code AopContext.currentProxy()} with {@code exposeProxy = true}, or - usually best - move
     * the inner method into another bean, which is what {@link PropagationDemoService} does.
     */
    public TransactionBoundaryDemoService(PropagationDemoService inner, TxNoteRepository notes,
            @Qualifier("transactionManager") PlatformTransactionManager transactionManager,
            @Lazy TransactionBoundaryDemoService self) {
        this.inner = inner;
        this.notes = notes;
        this.self = self;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate.setTimeout(5);
    }

    /**
     * One outer transaction; the inner REQUIRED call joins it. Both snapshots carry the same
     * transaction name, which is how you tell a logical transaction from a physical one.
     */
    @Transactional
    public List<TxInfo> requiredJoinsCaller() {
        List<TxInfo> trace = new ArrayList<>();
        trace.add(TxInfo.capture());
        trace.add(inner.required("required-inner", false));
        return trace;
    }

    /**
     * The inner REQUIRES_NEW commits even though the outer transaction then rolls back: exactly one
     * of the two rows survives.
     */
    @Transactional
    public void requiresNewSurvivesOuterRollback() {
        notes.save(new TxNote("outer-will-rollback"));
        inner.requiresNew("inner-requires-new-commits", false);
        throw new IllegalStateException("outer fails after the inner transaction committed");
    }

    /**
     * Pins down the JPA/NESTED limitation: the inner call fails with
     * {@code NestedTransactionNotSupportedException} rather than creating a savepoint. See
     * {@code PropagationDemoService#nested} and {@code SavepointDemoService} for the full story
     * and for a version that works.
     */
    @Transactional
    public String nestedUnderJpaIsUnsupported() {
        notes.save(new TxNote("outer-survives"));
        inner.nested("nested-will-rollback", true);
        return "unexpected: NESTED succeeded under JpaTransactionManager";
    }

    /**
     * The REQUIRED contrast, on JPA: the inner call fails and we swallow the exception, but the
     * inner failure already marked the SHARED transaction rollback-only, so the outer commit
     * throws {@code UnexpectedRollbackException} - "Transaction silently rolled back because it
     * has been marked as rollback-only" - and NOTHING is written. Catching an exception does not
     * un-poison a transaction.
     */
    @Transactional
    public String requiredFailurePoisonsOuterTransaction() {
        notes.save(new TxNote("poisoned-outer"));
        try {
            inner.required("poisoned-inner", true);
        } catch (IllegalStateException swallowed) {
            log.info("swallowed the inner failure - the transaction is still rollback-only");
        }
        return "about to fail on commit";
    }

    /**
     * The trap. {@code this.requiresNewInternal()} is a plain Java call: the proxy is not involved,
     * so {@code @Transactional(REQUIRES_NEW)} has no effect and the work joins the OUTER
     * transaction - and rolls back with it.
     *
     * <p>Symptoms in the wild: "my REQUIRES_NEW audit row disappeared", "@Cacheable does nothing",
     * "@Async runs on the caller's thread", "@Retryable never retries". One cause.
     */
    @Transactional
    public TxInfo selfInvocationBroken() {
        return requiresNewInternal("self-invocation-broken");
    }

    /** The same call through the injected proxy: the annotation now applies. */
    @Transactional
    public TxInfo selfInvocationFixed() {
        return self.requiresNewInternal("self-invocation-fixed");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TxInfo requiresNewInternal(String label) {
        notes.save(new TxNote(label));
        return TxInfo.capture();
    }

    /**
     * Default rollback rules: this CHECKED exception lets the transaction COMMIT. The row written
     * before the throw is still there afterwards.
     */
    @Transactional
    public void checkedExceptionCommitsByDefault() throws CheckedBusinessException {
        notes.save(new TxNote("checked-commits"));
        throw new CheckedBusinessException("checked exception: the transaction still commits");
    }

    /** The fix: declare it. */
    @Transactional(rollbackFor = CheckedBusinessException.class)
    public void checkedExceptionWithRollbackFor() throws CheckedBusinessException {
        notes.save(new TxNote("checked-rolls-back"));
        throw new CheckedBusinessException("checked exception with rollbackFor: rolls back");
    }

    /** And the inverse: an unchecked exception you explicitly do NOT want to roll back. */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void runtimeExceptionWithNoRollbackFor() {
        notes.save(new TxNote("runtime-no-rollback"));
        throw new IllegalArgumentException("unchecked, but committed because of noRollbackFor");
    }

    /**
     * Rolling back without throwing. Useful when "do nothing" is a valid outcome you still want to
     * abandon - but prefer throwing: this API is easy to forget and invisible to callers.
     */
    @Transactional
    public void markRollbackOnlyWithoutThrowing() {
        notes.save(new TxNote("marked-rollback-only"));
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }

    /**
     * Programmatic transactions with {@link TransactionTemplate}: no proxy, no self-invocation
     * trap, exact control over the boundary. The right tool when the boundary is narrower than a
     * method - a tight transaction inside a long loop, for instance.
     */
    public TxInfo programmatic() {
        return transactionTemplate.execute(status -> {
            notes.save(new TxNote("programmatic"));
            return TxInfo.capture();
        });
    }

    /** Programmatic rollback: {@code status.setRollbackOnly()} inside the callback. */
    public void programmaticRollback() {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                notes.save(new TxNote("programmatic-rollback"));
                status.setRollbackOnly();
            }
        });
    }

    /**
     * Post-commit side effects, the low-level way.
     *
     * <p>{@link TransactionSynchronization} is what {@code @TransactionalEventListener} is built
     * on. Register one when you need a hook but not an event: clearing a cache, releasing a
     * distributed lock, sending a notification only if the data really landed.
     *
     * <p>{@code afterCommit} runs OUTSIDE the transaction, so a write there needs its own
     * transaction, and an exception there cannot undo the commit.
     */
    @Transactional
    public void withSynchronization(List<String> callbackLog) {
        notes.save(new TxNote("with-synchronization"));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void beforeCommit(boolean readOnly) {
                callbackLog.add("beforeCommit");
            }

            @Override
            public void afterCommit() {
                callbackLog.add("afterCommit");
            }

            @Override
            public void afterCompletion(int status) {
                callbackLog.add("afterCompletion:"
                        + (status == STATUS_COMMITTED ? "COMMITTED" : "ROLLED_BACK"));
            }
        });
    }

    /** Convenience for the tests. */
    public PropagationDemoService inner() {
        return inner;
    }
}
