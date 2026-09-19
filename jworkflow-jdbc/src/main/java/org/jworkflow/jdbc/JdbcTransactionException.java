package org.jworkflow.jdbc;

import org.jworkflow.persistence.WorkflowPersistenceException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Safe diagnostics; failed commits can have unknown outcomes. Does not imply automatic retry. */
public final class JdbcTransactionException extends WorkflowPersistenceException {
    public enum Category { CONSTRAINT, TIMEOUT, DEADLOCK, SERIALIZATION, CONNECTION, ABORTED, OTHER }
    private final String phase;
    private final Category category;

    JdbcTransactionException(String phase, Throwable cause) {
        super("JDBC transaction " + phase + " failed (" + classify(cause) + ")", cause);
        this.phase = phase;
        this.category = classify(cause);
    }

    public String phase() { return phase; }
    public Category category() { return category; }

    /** True when durable workers must stop automatic handler replay and request reconciliation. */
    public static boolean requiresReconciliation(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current=failure; current!=null && seen.add(current); current=current.getCause())
            if (current instanceof JdbcTransactionException transaction && transaction.phase.equals("commit")) return true;
        return switch(classify(failure)) {
            case DEADLOCK, SERIALIZATION, CONNECTION, ABORTED -> true;
            default -> false;
        };
    }

    /** Classifies repository/driver cause chains without copying server messages or SQL. */
    public static Category classify(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (!(current instanceof SQLException sql)) continue;
            String state = sql.getSQLState();
            if (state == null) continue;
            if (state.equals("40P01")) return Category.DEADLOCK;
            if (state.equals("40001")) return Category.SERIALIZATION;
            if (state.equals("25P02")) return Category.ABORTED;
            if (state.equals("57014") || state.equals("55P03") || state.equals("HYT00") || state.equals("HYT01")) return Category.TIMEOUT;
            if (state.startsWith("23")) return Category.CONSTRAINT;
            if (state.startsWith("08") || state.equals("57P01") || state.equals("57P02") || state.equals("57P03")) return Category.CONNECTION;
        }
        return Category.OTHER;
    }
}
