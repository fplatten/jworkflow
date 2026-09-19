package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.engine.WorkflowEngineBuilder;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Backend selection for shared application scenarios; credentials never enter fixture URLs. */
final class ContractBackend implements AutoCloseable {
    private static final ThreadLocal<ContractBackend> ACTIVE = new ThreadLocal<>();
    private static final Map<String,DataSource> SOURCES = new ConcurrentHashMap<>();
    private final PostgresTestDatabase database;
    private final List<PostgresTestDatabase.Schema> schemas = new ArrayList<>();
    private final List<String> urls = new ArrayList<>();

    ContractBackend(PostgresTestDatabase database) {
        if (ACTIVE.get()!=null) throw new IllegalStateException("Nested contract fixture");
        this.database=database; ACTIVE.set(this);
    }
    static String url(Object sqlitePath) throws Exception {
        ContractBackend active=ACTIVE.get();
        if(active==null) return "jdbc:sqlite:"+sqlitePath;
        var schema=active.database.createSchema(); active.schemas.add(schema);
        var source=(PGSimpleDataSource)schema.dataSource();
        String url=source.getURL(); SOURCES.put(url,source); active.urls.add(url);
        return url;
    }
    static WorkflowEngineBuilder engine(String url) {
        DataSource source=SOURCES.get(url);
        var builder=WorkflowEngine.builder();
        return source==null ? builder.type(WorkflowEngine.Type.SQLITE).jdbcUrl(url)
                : builder.type(WorkflowEngine.Type.POSTGRESQL).dataSource(source);
    }
    static Connection open(String url) throws SQLException {
        DataSource source=SOURCES.get(url);
        return source==null ? DriverManager.getConnection(url) : source.getConnection();
    }
    static JdbcWorkflowPersistence persistence(String url,String user,String password,Driver driver,DataSource supplied,boolean initialize,Map<String,String> settings) {
        DataSource source=SOURCES.get(url);
        if(source==null) return JdbcWorkflowPersistence.create(url,user,password,driver,supplied,initialize,settings);
        // These scenarios' only SQLite-specific setting is its busy timeout; tested separately.
        Map<String,String> portable=new HashMap<>(settings); portable.remove("sqlite.busy-timeout-ms");
        return JdbcWorkflowPersistence.create(null,null,null,null,source,initialize,portable);
    }
    @Override public void close() throws Exception {
        ACTIVE.remove(); Exception failure=null;
        for(var schema:schemas) try { schema.close(); } catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        urls.forEach(SOURCES::remove);
        if(failure!=null)throw failure;
    }
}
