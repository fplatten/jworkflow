package org.jworkflow.jdbc;

import org.jworkflow.persistence.WorkflowPersistenceException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Safe diagnostics; failed commits can have unknown outcomes. Does not imply automatic retry.
 *
 * <p>Inspect category, SQLState, phase and commit-uncertainty flags rather than parsing localized messages.
 * Reconcile uncertain outcomes using the original idempotency key. Underlying causes and suppressed cleanup
 * failures are retained; diagnostic consumers must avoid logging secrets.</p>
 */
public final class JdbcTransactionException extends WorkflowPersistenceException {
    /**
     * SQLState-based failure class for diagnostics and explicit caller reconciliation; no category automatically
     * replays handlers.
     */
    public enum Category {

        /**
         * A database constraint rejected the write.
         */
        CONSTRAINT,

        /**
         * A bounded lock or statement wait expired.
         */
        TIMEOUT,

        /**
         * The database detected a deadlock; handlers are not automatically replayed.
         */
        DEADLOCK,

        /**
         * The database rejected the transaction for a serialization conflict.
         */
        SERIALIZATION,

        /**
         * The connection failed; commit outcome may require reconciliation.
         */
        CONNECTION,

        /**
         * The transaction is already aborted or rollback-only.
         */
        ABORTED,

        /**
         * No more specific supported SQLState category applies.
         */
        OTHER }
    /** Transaction phase in which the failure occurred. */
    private final String phase;
    /** SQLState-based classification used for diagnostics and reconciliation. */
    private final Category category;

    JdbcTransactionException(String phase, Throwable cause) {
        super("JDBC transaction " + phase + " failed (" + classify(cause) + ")", cause);
        this.phase = phase;
        this.category = classify(cause);
    }

    /**
     * Returns transaction phase in which the failure occurred.
     * @return transaction phase in which the failure occurred
     */
    public String phase() { return phase; }
    /**
     * Returns diagnostic or SQL failure category.
     * @return diagnostic or SQL failure category
     */
    public Category category() { return category; }

    /**
     * True when durable workers must stop automatic handler replay and request reconciliation.
     * @param failure original failure for diagnostics; avoid exposing secrets in logs
     * @return true when the condition described above holds; false otherwise
     */
    public static boolean requiresReconciliation(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current=failure; current!=null && seen.add(current); current=current.getCause())
            if (current instanceof JdbcTransactionException transaction && transaction.phase.equals("commit")) return true;
        return switch(classify(failure)) {
            case DEADLOCK, SERIALIZATION, CONNECTION, ABORTED -> true;
            default -> false;
        };
    }

    /**
     * Classifies repository/driver cause chains without copying server messages or SQL.
     * @param failure original failure for diagnostics; avoid exposing secrets in logs
     * @return the resulting category
     */
    public static Category classify(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof SQLException sql) {
                Category category = classifyState(sql.getSQLState());
                if (category != Category.OTHER) return category;
            }
        }
        return Category.OTHER;
    }
    private static Category classifyState(String state) {
        if (state == null) return Category.OTHER;
        if (state.equals("40P01")) return Category.DEADLOCK;
        if (state.equals("40001")) return Category.SERIALIZATION;
        if (state.equals("25P02")) return Category.ABORTED;
        if (state.equals("57014") || state.equals("55P03") || state.equals("HYT00") || state.equals("HYT01")) return Category.TIMEOUT;
        if (state.startsWith("23")) return Category.CONSTRAINT;
        if (state.startsWith("08") || state.equals("57P01") || state.equals("57P02") || state.equals("57P03")) return Category.CONNECTION;
        return Category.OTHER;
    }
}
