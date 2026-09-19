package org.jworkflow.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Failure injection around real JDBC operations, never replacing the database under test. */
final class TransactionTestDataSource implements DataSource {
    final DataSource delegate;
    final AtomicInteger borrowed = new AtomicInteger();
    final AtomicInteger closed = new AtomicInteger();
    SQLException commitFailure, rollbackFailure, closeFailure, restoreFailure;
    boolean commitBeforeFailure;
    String failAfterSql;

    TransactionTestDataSource(DataSource delegate) { this.delegate = delegate; }

    static Statement validationStatement() {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
            if(m.getName().equals("executeQuery")) return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},
                    (rp,rm,ra)->rm.getName().equals("next") ? true : null);
            return null;
        });
    }

    @Override public Connection getConnection() throws SQLException {
        Connection real = delegate.getConnection();
        borrowed.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("commit") && commitFailure != null) {
                if (commitBeforeFailure) real.commit();
                throw commitFailure;
            }
            if (name.equals("rollback") && rollbackFailure != null) throw rollbackFailure;
            if (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0]) && restoreFailure != null) throw restoreFailure;
            if (name.equals("close")) {
                real.close(); closed.incrementAndGet();
                if (closeFailure != null) throw closeFailure;
                return null;
            }
            Object result = invoke(real, method, args);
            if (name.equals("prepareStatement")) {
                String sql = (String) args[0];
                PreparedStatement statement = (PreparedStatement) result;
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                    Object value = invoke(statement, m, a);
                    if (m.getName().equals("executeUpdate") && failAfterSql != null && sql.contains(failAfterSql))
                        throw new SQLException("Injected write failure", "XX000");
                    return value;
                });
            }
            return result;
        });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { return delegate.unwrap(type); }
    @Override public boolean isWrapperFor(Class<?> type) throws SQLException { return delegate.isWrapperFor(type); }
}
