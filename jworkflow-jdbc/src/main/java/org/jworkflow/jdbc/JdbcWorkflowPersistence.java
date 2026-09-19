package org.jworkflow.jdbc;

import org.jworkflow.events.EventStatusRepository;
import org.jworkflow.persistence.*;

import javax.sql.DataSource;
import java.sql.Driver;
import java.util.Map;

/**
 * Complete JDBC adapter bundle sharing one transaction-scoped connection factory.
 *
 * <p>Creates repositories for the database identified by actual JDBC metadata, even when schema initialization is
 * disabled. All repository work must use this bundle's transaction manager for atomic multi-repository changes.
 * Select exactly one migration owner per schema. Host pools and credentials remain caller-owned.</p>
 */
public final class JdbcWorkflowPersistence implements WorkflowPersistence {
    private final JdbcWorkflowDefinitionRepository definitions;
    private final JdbcWorkflowInstanceRepository instances;
    private final JdbcWorkflowEventRepository events;
    private final JdbcEventStatusRepository statuses;
    private final JdbcWorkflowTimerRepository timers;
    private final JdbcInboxRepository inbox;
    private final JdbcOutboxRepository outbox;
    private final JdbcCommandResultRepository commandResults;
    private final JdbcTransactionManager transactions;

    private JdbcWorkflowPersistence(JdbcConnectionFactory connections) {
        definitions=new JdbcWorkflowDefinitionRepository(connections);instances=new JdbcWorkflowInstanceRepository(connections);
        events=new JdbcWorkflowEventRepository(connections);statuses=new JdbcEventStatusRepository(connections);
        timers=new JdbcWorkflowTimerRepository(connections);inbox=new JdbcInboxRepository(connections);outbox=new JdbcOutboxRepository(connections);
        commandResults=new JdbcCommandResultRepository(connections);transactions=new JdbcTransactionManager(connections);
    }
    static JdbcWorkflowPersistence from(JdbcConnectionFactory connections){return new JdbcWorkflowPersistence(connections);}

    /**
     * Resolves the database from connection metadata and creates one strategy/transaction bundle. Optional
     * initialization selects the corresponding resource tree. The host retains ownership of any DataSource.
     * @param jdbcUrl JDBC connection URL; supply credentials separately and select a trusted schema
     * @param username host-provided database username
     * @param password host-provided database password; do not log this value
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @param dataSource host-owned source of idle JDBC connections; the adapter does not close the source
     * @param initializeSchema whether this adapter owns built-in schema migration; false for external migration
     *     owners
     * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
     * @return the resulting jdbc workflow persistence
     */
    public static JdbcWorkflowPersistence create(String jdbcUrl,String username,String password,Driver driver,DataSource dataSource,
                                                  boolean initializeSchema,Map<String,String> settings){
        JdbcConnectionFactory factory=new JdbcConnectionFactory(null,jdbcUrl,username,password,driver,dataSource,settings);
        factory.strategy();
        if(initializeSchema)JdbcSchemaInitializer.initialize(factory);
        return new JdbcWorkflowPersistence(factory);
    }
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowDefinitionRepository definitions(){return definitions;}

    /**
     * {@inheritDoc}
     */
    @Override public WorkflowInstanceRepository instances(){return instances;}
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowEventRepository events(){return events;}

    /**
     * {@inheritDoc}
     */
    @Override public EventStatusRepository eventStatuses(){return statuses;}
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowTimerRepository timers(){return timers;}

    /**
     * {@inheritDoc}
     */
    @Override public InboxRepository inbox(){return inbox;}
    /**
     * {@inheritDoc}
     */
    @Override public OutboxRepository outbox(){return outbox;}

    /**
     * {@inheritDoc}
     */
    @Override public CommandResultRepository commandResults(){return commandResults;}
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowTransactionManager transactions(){return transactions;}
    /**
     * Returns shared transaction manager coordinating related writes.
     * @return shared transaction manager coordinating related writes
     */
    public JdbcTransactionManager jdbcTransactions(){return transactions;}
}
