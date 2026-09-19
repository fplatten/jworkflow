package org.jworkflow.jdbc;

import java.sql.*;

/** Handles a PostgreSQL race where the same row violates both the primary and natural key. */
final class JdbcDuplicateInsert {
    @FunctionalInterface interface Winner { boolean matches() throws SQLException; }
    private JdbcDuplicateInsert(){ }
    static int execute(JdbcConnectionFactory factory,Connection connection,PreparedStatement statement,
                       String primaryConstraint,Winner winner) throws SQLException {
        Savepoint savepoint=!connection.getAutoCommit()?connection.setSavepoint():null;
        int affected;
        try { affected=statement.executeUpdate(); }
        catch(SQLException failure){
            if(!primaryConflict(failure,primaryConstraint))throw failure;
            // Never query an aborted transaction, nor replay the insert/handler.
            if(savepoint!=null)try{connection.rollback(savepoint);}catch(SQLException rollback){failure.addSuppressed(rollback);throw failure;}
            boolean matches;
            try{matches=winner.matches();}catch(SQLException lookup){failure.addSuppressed(lookup);throw failure;}
            if(!matches){factory.markRollbackOnly(failure);throw failure;}
            affected=0;
        }
        if(savepoint!=null)connection.releaseSavepoint(savepoint);
        return affected;
    }
    private static boolean primaryConflict(SQLException failure,String constraint){
        if(!"23505".equals(failure.getSQLState()))return false;
        // pgJDBC is optional runtime: read its structured diagnostic without a compile-time linkage.
        // Unknown drivers/diagnostics fail closed; never parse localized error messages.
        try{
            Object diagnostic=failure.getClass().getMethod("getServerErrorMessage").invoke(failure);
            return diagnostic!=null && constraint.equals(diagnostic.getClass().getMethod("getConstraint").invoke(diagnostic));
        }catch(ReflectiveOperationException | RuntimeException unavailable){return false;}
    }
}
