package org.jworkflow.jdbc;

import org.jworkflow.events.EventStatusRepository;
import org.jworkflow.persistence.*;

import javax.sql.DataSource;
import java.sql.Driver;
import java.util.Map;

/** Complete JDBC adapter bundle sharing one transaction-scoped connection factory. */
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

    public static JdbcWorkflowPersistence create(String jdbcUrl,String username,String password,Driver driver,DataSource dataSource,
                                                  boolean initializeSchema,Map<String,String> settings){
        Map<String,String> safe=settings==null?Map.of():settings;
        JdbcConnectionFactory factory=new JdbcConnectionFactory(jdbcUrl,username,password,driver,dataSource,
                Integer.parseInt(safe.getOrDefault("sqlite.busy-timeout-ms","5000")),
                Boolean.parseBoolean(safe.getOrDefault("sqlite.wal-enabled","false")));
        if(initializeSchema)JdbcSchemaInitializer.initialize(factory);
        return new JdbcWorkflowPersistence(factory);
    }
    @Override public WorkflowDefinitionRepository definitions(){return definitions;}@Override public WorkflowInstanceRepository instances(){return instances;}
    @Override public WorkflowEventRepository events(){return events;}@Override public EventStatusRepository eventStatuses(){return statuses;}
    @Override public WorkflowTimerRepository timers(){return timers;}@Override public InboxRepository inbox(){return inbox;}
    @Override public OutboxRepository outbox(){return outbox;}@Override public CommandResultRepository commandResults(){return commandResults;}
    @Override public WorkflowTransactionManager transactions(){return transactions;}
    public JdbcTransactionManager jdbcTransactions(){return transactions;}
}
