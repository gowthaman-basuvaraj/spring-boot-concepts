package com.example.orders.service.tx;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A snapshot of the ambient transaction, so the demos can assert on observable facts rather than
 * on folklore.
 *
 * <p>{@link TransactionSynchronizationManager} is the thread-bound registry that connects
 * declarative {@code @Transactional} with infrastructure code. Everything here is also what you
 * would inspect in a debugger when a transaction is not behaving.
 */
public record TxInfo(boolean active, String name, boolean readOnly, Integer isolationLevel) {

    public static TxInfo capture() {
        return new TxInfo(
                // True only when a REAL transaction is running. Note the difference from
                // isSynchronizationActive(), which is also true for a read-only "empty"
                // transaction created by PROPAGATION_SUPPORTS.
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getCurrentTransactionName(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
    }

    /** Two snapshots share a physical transaction when the transaction names are equal. */
    public boolean sameTransactionAs(TxInfo other) {
        return other != null && name != null && name.equals(other.name());
    }

    @Override
    public String toString() {
        return "TxInfo[active=" + active + ", name=" + name + ", readOnly=" + readOnly + "]";
    }
}
