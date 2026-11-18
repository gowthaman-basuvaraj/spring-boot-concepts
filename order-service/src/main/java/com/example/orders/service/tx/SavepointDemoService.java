package com.example.orders.service.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code Propagation.NESTED} that actually works - over plain JDBC, driven by the
 * {@code jdbcTransactionManager} bean.
 *
 * <p>Read {@code JpaConfig#jdbcTransactionManager} first: NESTED needs JDBC savepoints, and
 * Hibernate's {@code JpaDialect} cannot provide them, so a NESTED call under the primary
 * (JPA) manager fails with {@code NestedTransactionNotSupportedException}. Everything here uses
 * {@link JdbcClient} and names the JDBC manager explicitly.
 *
 * <p>What a savepoint buys you: "try this optional step; if it fails, discard just that step and
 * still commit the rest". With REQUIRED the inner failure marks the shared transaction
 * rollback-only and the outer commit blows up with {@code UnexpectedRollbackException}; with
 * REQUIRES_NEW you get a second connection and lose read consistency with the outer work. NESTED
 * is the middle ground - same connection, partial rollback.
 *
 * <p>Caveats worth stating out loud: savepoints are not free (each one is state the database must
 * keep), PostgreSQL in particular degrades with thousands of them in one transaction, and JTA /
 * XA does not support them at all.
 */
@Service
public class SavepointDemoService {

    private static final Logger log = LoggerFactory.getLogger(SavepointDemoService.class);

    private final JdbcClient jdbc;
    private final NestedStep nestedStep;

    public SavepointDemoService(JdbcClient jdbc, NestedStep nestedStep) {
        this.jdbc = jdbc;
        this.nestedStep = nestedStep;
    }

    /**
     * Outer transaction on the JDBC manager. The nested step fails; we catch it, and the outer
     * work still commits. Afterwards {@code savepoint-outer} exists and
     * {@code savepoint-inner} does not.
     */
    @Transactional(transactionManager = "jdbcTransactionManager")
    public String nestedFailureIsIsolated() {
        insert("savepoint-outer");
        try {
            nestedStep.insertThenFail("savepoint-inner");
        } catch (IllegalStateException expected) {
            log.info("nested step rolled back to its savepoint: {}", expected.getMessage());
            return "recovered";
        }
        return "no failure";
    }

    /** Same shape, but the nested step succeeds: both rows are committed together. */
    @Transactional(transactionManager = "jdbcTransactionManager")
    public String nestedSuccessCommitsBoth() {
        insert("savepoint-outer-ok");
        nestedStep.insert("savepoint-inner-ok");
        return "committed";
    }

    /**
     * The contrast case: identical code with REQUIRED instead of NESTED. The inner failure marks
     * the SHARED transaction rollback-only, so catching the exception does not save you - the
     * commit fails with {@code UnexpectedRollbackException} and NOTHING is written.
     */
    @Transactional(transactionManager = "jdbcTransactionManager")
    public String requiredFailurePoisonsOuter() {
        insert("required-outer");
        try {
            nestedStep.insertThenFailRequired("required-inner");
        } catch (IllegalStateException expected) {
            log.info("caught the inner failure - but the transaction is already rollback-only");
        }
        return "about to fail on commit";
    }

    public long countByLabel(String label) {
        return jdbc.sql("select count(*) from tx_note where label = :label")
                .param("label", label)
                .query(Long.class)
                .single();
    }

    private void insert(String label) {
        jdbc.sql("insert into tx_note (label) values (:label)").param("label", label).update();
    }

    /**
     * The inner steps live in a separate bean so the calls go through a proxy - the same
     * self-invocation rule as everywhere else.
     */
    @Service
    public static class NestedStep {

        private final JdbcClient jdbc;

        public NestedStep(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional(transactionManager = "jdbcTransactionManager", propagation = Propagation.NESTED)
        public void insert(String label) {
            write(label);
        }

        @Transactional(transactionManager = "jdbcTransactionManager", propagation = Propagation.NESTED)
        public void insertThenFail(String label) {
            write(label);
            throw new IllegalStateException("failing inside NESTED: " + label);
        }

        @Transactional(transactionManager = "jdbcTransactionManager", propagation = Propagation.REQUIRED)
        public void insertThenFailRequired(String label) {
            write(label);
            throw new IllegalStateException("failing inside REQUIRED: " + label);
        }

        private void write(String label) {
            jdbc.sql("insert into tx_note (label) values (:label)").param("label", label).update();
        }
    }
}
