package com.example.orders.service;

import java.util.ArrayList;
import java.util.List;

import com.example.orders.repo.TxNoteRepository;
import com.example.orders.service.tx.CheckedBusinessException;
import com.example.orders.service.tx.PropagationDemoService;
import com.example.orders.service.tx.SavepointDemoService;
import com.example.orders.service.tx.TransactionBoundaryDemoService;
import com.example.orders.service.tx.TxInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.UnexpectedRollbackException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Executable documentation for Spring's transaction semantics.
 *
 * <p>Note what this test does NOT do: it is not annotated {@code @Transactional}. A test method
 * wrapped in its own rolled-back transaction cannot observe commit behaviour at all - the usual
 * convenience (automatic rollback between tests) is precisely what makes it useless here. Data is
 * cleaned up explicitly instead.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Transaction propagation, rollback rules and boundaries")
class TransactionPropagationTest {

    @Autowired
    private TransactionBoundaryDemoService boundaries;

    @Autowired
    private PropagationDemoService propagation;

    @Autowired
    private SavepointDemoService savepoints;

    @Autowired
    private TxNoteRepository notes;

    @BeforeEach
    void clean() {
        notes.deleteAll();
    }

    @Nested
    @DisplayName("Propagation")
    class Propagation {

        @Test
        @DisplayName("REQUIRED joins the caller's physical transaction")
        void requiredJoinsCaller() {
            List<TxInfo> trace = boundaries.requiredJoinsCaller();

            assertThat(trace).hasSize(2);
            assertThat(trace.get(0).active()).isTrue();
            // Same physical transaction: the name is the OUTER method, because the inner
            // @Transactional created no new transaction to name.
            assertThat(trace.get(1).sameTransactionAs(trace.get(0))).isTrue();
            assertThat(trace.get(1).name()).contains("requiredJoinsCaller");
        }

        @Test
        @DisplayName("REQUIRES_NEW commits independently of the caller's rollback")
        void requiresNewSurvivesOuterRollback() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> boundaries.requiresNewSurvivesOuterRollback());

            assertThat(notes.countByLabel("outer-will-rollback")).isZero();
            assertThat(notes.countByLabel("inner-requires-new-commits")).isEqualTo(1);
        }

        @Test
        @DisplayName("MANDATORY refuses to run without a transaction")
        void mandatoryRequiresCaller() {
            assertThatExceptionOfType(IllegalTransactionStateException.class)
                    .isThrownBy(() -> propagation.mandatory());
        }

        @Test
        @DisplayName("NEVER and NOT_SUPPORTED run without a transaction")
        void nonTransactionalModes() {
            assertThat(propagation.never().active()).isFalse();
            assertThat(propagation.notSupported("not-supported").active()).isFalse();
            // NOT_SUPPORTED work is auto-committed and cannot be undone by any caller.
            assertThat(notes.countByLabel("not-supported")).isEqualTo(1);
        }

        @Test
        @DisplayName("readOnly=true is visible to infrastructure (it is the replica routing key)")
        void readOnlyFlagIsPropagated() {
            TxInfo info = propagation.readOnly();
            assertThat(info.active()).isTrue();
            assertThat(info.readOnly()).isTrue();
        }
    }

    @Nested
    @DisplayName("NESTED / savepoints")
    class Nested1 {

        @Test
        @DisplayName("NESTED is NOT supported by JpaTransactionManager + Hibernate")
        void nestedUnsupportedOnJpa() {
            assertThatExceptionOfType(NestedTransactionNotSupportedException.class)
                    .isThrownBy(() -> boundaries.nestedUnderJpaIsUnsupported())
                    .withMessageContaining("savepoints");
        }

        @Test
        @DisplayName("NESTED works over JDBC: the savepoint rolls back, the outer work commits")
        void nestedIsolatesFailure() {
            assertThat(savepoints.nestedFailureIsIsolated()).isEqualTo("recovered");

            assertThat(savepoints.countByLabel("savepoint-outer")).isEqualTo(1);
            assertThat(savepoints.countByLabel("savepoint-inner")).isZero();
        }

        @Test
        @DisplayName("NESTED success commits both levels")
        void nestedSuccessCommitsBoth() {
            assertThat(savepoints.nestedSuccessCommitsBoth()).isEqualTo("committed");

            assertThat(savepoints.countByLabel("savepoint-outer-ok")).isEqualTo(1);
            assertThat(savepoints.countByLabel("savepoint-inner-ok")).isEqualTo(1);
        }

        @Test
        @DisplayName("REQUIRED: swallowing the inner exception poisons the outer commit")
        void requiredFailurePoisonsOuter() {
            assertThatExceptionOfType(UnexpectedRollbackException.class)
                    .isThrownBy(() -> savepoints.requiredFailurePoisonsOuter())
                    .withMessageContaining("rollback-only");

            assertThat(savepoints.countByLabel("required-outer")).isZero();
            assertThat(savepoints.countByLabel("required-inner")).isZero();
        }
    }

    @Nested
    @DisplayName("Proxy self-invocation")
    class SelfInvocation {

        @Test
        @DisplayName("this.method() bypasses the proxy, so REQUIRES_NEW is silently ignored")
        void selfInvocationIsIgnored() {
            TxInfo info = boundaries.selfInvocationBroken();

            // The transaction name is the OUTER method: no new transaction was ever created.
            assertThat(info.name()).contains("selfInvocationBroken");
            assertThat(info.name()).doesNotContain("requiresNewInternal");
        }

        @Test
        @DisplayName("calling through the injected proxy applies the annotation")
        void selfInvocationViaProxyWorks() {
            TxInfo info = boundaries.selfInvocationFixed();

            assertThat(info.name()).contains("requiresNewInternal");
        }
    }

    @Nested
    @DisplayName("Rollback rules")
    class RollbackRules {

        @Test
        @DisplayName("a CHECKED exception COMMITS by default - the most surprising default in Spring")
        void checkedExceptionCommits() {
            assertThatExceptionOfType(CheckedBusinessException.class)
                    .isThrownBy(() -> boundaries.checkedExceptionCommitsByDefault());

            assertThat(notes.countByLabel("checked-commits")).isEqualTo(1);
        }

        @Test
        @DisplayName("rollbackFor fixes it")
        void checkedExceptionWithRollbackFor() {
            assertThatExceptionOfType(CheckedBusinessException.class)
                    .isThrownBy(() -> boundaries.checkedExceptionWithRollbackFor());

            assertThat(notes.countByLabel("checked-rolls-back")).isZero();
        }

        @Test
        @DisplayName("noRollbackFor makes an unchecked exception commit")
        void noRollbackFor() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> boundaries.runtimeExceptionWithNoRollbackFor());

            assertThat(notes.countByLabel("runtime-no-rollback")).isEqualTo(1);
        }

        @Test
        @DisplayName("setRollbackOnly() rolls back without throwing")
        void rollbackOnlyWithoutThrowing() {
            assertThatCode(() -> boundaries.markRollbackOnlyWithoutThrowing()).doesNotThrowAnyException();

            assertThat(notes.countByLabel("marked-rollback-only")).isZero();
        }
    }

    @Nested
    @DisplayName("Programmatic transactions and synchronization")
    class Programmatic {

        @Test
        @DisplayName("TransactionTemplate commits")
        void programmaticCommit() {
            TxInfo info = boundaries.programmatic();

            assertThat(info.active()).isTrue();
            assertThat(notes.countByLabel("programmatic")).isEqualTo(1);
        }

        @Test
        @DisplayName("status.setRollbackOnly() inside the callback rolls back")
        void programmaticRollback() {
            boundaries.programmaticRollback();

            assertThat(notes.countByLabel("programmatic-rollback")).isZero();
        }

        @Test
        @DisplayName("TransactionSynchronization callbacks fire in order, afterCommit last")
        void synchronizationCallbacks() {
            List<String> callbacks = new ArrayList<>();

            boundaries.withSynchronization(callbacks);

            assertThat(callbacks).containsExactly("beforeCommit", "afterCommit", "afterCompletion:COMMITTED");
            assertThat(notes.countByLabel("with-synchronization")).isEqualTo(1);
        }
    }
}
