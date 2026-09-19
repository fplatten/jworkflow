package org.jworkflow.jdbc;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.outbox.*;
import org.jworkflow.inbox.InboxEventTranslator;
import org.jworkflow.persistence.*;
import org.jworkflow.observability.*;
import org.jworkflow.routing.*;
import org.jworkflow.security.*;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Driver;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Repository-backed engine; this object never owns authoritative workflow state.
 *
 * <p>Durable commands load the current snapshot and its exact definition revision. PostgreSQL protects complete
 * replay keys through the outer commit and preserves optimistic locking for distinct keys. Successful callbacks
 * wait for outermost commit and connection cleanup. Handler/network effects are not rolled back and are never
 * automatically replayed after ambiguous commit or deadlock. Closing stops engine-owned workers but never closes
 * the host DataSource.</p>
 */
public final class JdbcWorkflowEngine implements WorkflowEngine {
    static final int RESIDENT_WORKFLOW_COUNT = 0;
    private static final String TEXT_START = "start";
    private static final String TEXT_SIGNAL = "signal";
    private static final String TEXT_EVENT = "event";
    private static final String TEXT_STATUS = "status";
    private static final String TEXT_COMMAND_ID = "commandId";
    private final Type type;
        private final JdbcConnectionFactory connections;
        private final JdbcWorkflowPersistence persistence;
    private final WorkflowStateMachine machine;
        private final EventPublisher observer;
        private final Map<String,Object> listeners;
    private final WorkflowLifecycleObserver lifecycleObserver;
    private final EventCapturePolicy eventCapturePolicy;
    private final WorkflowEventLifecycleAdapter lifecycleEvents;
    private final AtomicReference<java.util.function.Consumer<String>> writeProbe =
            new AtomicReference<>(ignored -> {});
    private final WorkflowEngineContext context=new RepositoryContext();
    private final Clock clock;
        private final RecoveryOptions recovery;
        private final Map<String,String> settings;
        private final String workerId=UUID.randomUUID().toString();
    private final OutboxEnqueueService outboxEnqueue;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean timerReconciliationRequired=new AtomicBoolean();
        private ScheduledExecutorService timerPoller;
    private int startupValidationQueryCount;
        private int startupValidationPeakBatchSize;

    @SuppressWarnings("java:S107") // Internal composition root populated by the builder/factories.
    private JdbcWorkflowEngine(Type type,JdbcConnectionFactory connections,WorkflowDefinitionRegistry supplied,EventPublisher observer,
      Map<String,StepHandler> handlers,BranchConditionEvaluator conditions,Map<String,String> starts,Map<String,Object> listeners,
      Map<String,String> settings,WorkflowLifecycleObserver lifecycleObserver,Clock clock,EventCapturePolicy eventCapturePolicy){
        this.type=Objects.requireNonNull(type);
            this.connections=Objects.requireNonNull(connections);
            this.persistence=JdbcWorkflowPersistence.from(connections);
        this.observer=Objects.requireNonNull(observer);
            this.listeners=new java.util.concurrent.ConcurrentHashMap<>(listeners==null?Map.of():listeners);
        this.lifecycleObserver=SafeWorkflowLifecycleObserver.isolate(Objects.requireNonNull(lifecycleObserver,"lifecycleObserver"));
        this.eventCapturePolicy=Objects.requireNonNull(eventCapturePolicy,"eventCapturePolicy");
        this.lifecycleEvents=new WorkflowEventLifecycleAdapter(this.lifecycleObserver);
        this.clock=Objects.requireNonNull(clock,"clock");
            this.settings=Map.copyOf(settings==null?Map.of():settings);
            this.recovery=RecoveryOptions.from(settings);
        this.outboxEnqueue=new OutboxEnqueueService(persistence.outbox(),new OutboxRoutingService(event->{String configured=this.settings.get("outbox.destination."+event.eventName().value());
            if(configured==null||configured.isBlank())configured=this.settings.getOrDefault("outbox.default-destination","workflow.events");
            return Arrays.stream(configured.split(",")).map(String::trim).filter(v->!v.isBlank()).toList();
        }));
        persistence.transactions().execute(()->supplied.snapshot().values().forEach(persistence.definitions()::save));
        WorkflowDefinitionRegistry recovered=new WorkflowDefinitionRegistry();
            supplied.snapshot().values().forEach(recovered::register);
        persistence.definitions().findAll().stream().filter(d->recovered.find(d.name(),d.version()).isEmpty()).forEach(recovered::register);
        this.machine=new WorkflowStateMachine(recovered,handlers,conditions,starts,this.listeners,clock);
        recoverStartup();
            startTimerPoller();
    }
    /**
     * Constructs a durable engine with a shared JDBC persistence bundle. Actual database metadata must match an
     *  explicitly supplied type; legacy overloads infer it. Host DataSources remain caller-owned.
     * @param type selected JDBC engine backend
     * @param url JDBC URL selecting the database and trusted schema
     * @param user host-provided database username
     * @param password host-provided database password; do not log this value
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @param source host-owned source of idle auto-commit connections; takes precedence over URL and Driver
     * @param init whether to apply built-in schema migrations
     * @return the configured durable engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    public static JdbcWorkflowEngine create(Type type,String url,String user,String password,Driver driver,DataSource source,boolean init)throws ClassNotFoundException{
        return create(type,url,user,password,driver,source,init,new WorkflowDefinitionRegistry(),NoOpEventPublisher.INSTANCE,Map.of(),new BranchConditionEvaluator(),Map.of(),Map.of(),Map.of(),NoOpWorkflowLifecycleObserver.INSTANCE,Clock.systemUTC(),CaptureAllEventPolicy.INSTANCE);
    }
    /**
     * Constructs a durable engine with a shared JDBC persistence bundle. Actual database metadata must match an
     *  explicitly supplied type; legacy overloads infer it. Host DataSources remain caller-owned.
     * @param type selected JDBC engine backend
     * @param url JDBC URL selecting the database and trusted schema
     * @param user host-provided database username
     * @param password host-provided database password; do not log this value
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @param source host-owned source of idle auto-commit connections; takes precedence over URL and Driver
     * @param init whether to apply built-in schema migrations
     * @param definitions registry of selected workflow definitions
     * @param publisher destination or event publisher used by this adapter
     * @param handlers registered action handlers
     * @param conditions declarative condition evaluator and registered predicates
     * @param starts event-to-workflow start mapping for supported runtime modes
     * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
     * @param listeners infrastructure listeners indexed by registered identity
     * @param clock clock used for recorded times and lease/retry decisions
     * @return the configured durable engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    @SuppressWarnings("java:S107") // Compatibility factory retained for existing clients.
    public static JdbcWorkflowEngine create(Type type,String url,String user,String password,Driver driver,DataSource source,boolean init,
      WorkflowDefinitionRegistry definitions,EventPublisher publisher,Map<String,StepHandler> handlers,BranchConditionEvaluator conditions,
      Map<String,String> starts,Map<String,String> settings,Map<String,Object> listeners,Clock clock)throws ClassNotFoundException{
        return create(type,url,user,password,driver,source,init,definitions,publisher,handlers,conditions,starts,settings,listeners,NoOpWorkflowLifecycleObserver.INSTANCE,clock);
    }
    /**
     * Constructs a durable engine with a shared JDBC persistence bundle. Actual database metadata must match an
     *  explicitly supplied type; legacy overloads infer it. Host DataSources remain caller-owned.
     * @param type selected JDBC engine backend
     * @param url JDBC URL selecting the database and trusted schema
     * @param user host-provided database username
     * @param password host-provided database password; do not log this value
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @param source host-owned source of idle auto-commit connections; takes precedence over URL and Driver
     * @param init whether to apply built-in schema migrations
     * @param definitions registry of selected workflow definitions
     * @param publisher destination or event publisher used by this adapter
     * @param handlers registered action handlers
     * @param conditions declarative condition evaluator and registered predicates
     * @param starts event-to-workflow start mapping for supported runtime modes
     * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
     * @param listeners infrastructure listeners indexed by registered identity
     * @param lifecycleObserver best-effort lifecycle observer
     * @param clock clock used for recorded times and lease/retry decisions
     * @return the configured durable engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    @SuppressWarnings("java:S107") // Compatibility factory retained for existing clients.
    public static JdbcWorkflowEngine create(Type type,String url,String user,String password,Driver driver,DataSource source,boolean init,
      WorkflowDefinitionRegistry definitions,EventPublisher publisher,Map<String,StepHandler> handlers,BranchConditionEvaluator conditions,
      Map<String,String> starts,Map<String,String> settings,Map<String,Object> listeners,WorkflowLifecycleObserver lifecycleObserver,Clock clock)throws ClassNotFoundException{
        return create(type,url,user,password,driver,source,init,definitions,publisher,handlers,conditions,starts,settings,listeners,lifecycleObserver,clock,CaptureAllEventPolicy.INSTANCE);
    }
    /**
     * Constructs a durable engine with a shared JDBC persistence bundle. Actual database metadata must match an
     *  explicitly supplied type; legacy overloads infer it. Host DataSources remain caller-owned.
     * @param type selected JDBC engine backend
     * @param url JDBC URL selecting the database and trusted schema
     * @param user host-provided database username
     * @param password host-provided database password; do not log this value
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @param source host-owned source of idle auto-commit connections; takes precedence over URL and Driver
     * @param init whether to apply built-in schema migrations
     * @param definitions registry of selected workflow definitions
     * @param publisher destination or event publisher used by this adapter
     * @param handlers registered action handlers
     * @param conditions declarative condition evaluator and registered predicates
     * @param starts event-to-workflow start mapping for supported runtime modes
     * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
     * @param listeners infrastructure listeners indexed by registered identity
     * @param lifecycleObserver best-effort lifecycle observer
     * @param clock clock used for recorded times and lease/retry decisions
     * @param eventCapturePolicy policy applied before durable or observable event boundaries
     * @return the configured durable engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     * @throws NullPointerException if type is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    @SuppressWarnings("java:S107") // Optional-module factory mirrors the builder configuration contract.
    public static JdbcWorkflowEngine create(Type type,String url,String user,String password,Driver driver,DataSource source,boolean init,
      WorkflowDefinitionRegistry definitions,EventPublisher publisher,Map<String,StepHandler> handlers,BranchConditionEvaluator conditions,
      Map<String,String> starts,Map<String,String> settings,Map<String,Object> listeners,WorkflowLifecycleObserver lifecycleObserver,Clock clock,
      EventCapturePolicy eventCapturePolicy)throws ClassNotFoundException{
        if (type == Type.IN_MEMORY) {
            throw new IllegalArgumentException("In-memory workflows do not use JDBC");
        }
        Map<String,String> s=settings==null?Map.of():settings;
        Objects.requireNonNull(type,"type");
        if(source==null&&(url==null||url.isBlank()))throw new IllegalArgumentException("jdbcUrl or dataSource is required for "+type);
        if(source==null&&driver==null) JdbcDatabaseStrategy.loadDriver(type);
        JdbcConnectionFactory c=new JdbcConnectionFactory(type,url,user,password,driver,source,s);
        c.strategy();
        if(init) {
            JdbcSchemaInitializer.initialize(c);
        }
        return new JdbcWorkflowEngine(type,c,definitions,publisher,handlers,conditions,starts,listeners,s,lifecycleObserver,clock,eventCapturePolicy);
    }
    /**
     * Compatibility overload retained for callers compiled before clock injection was added.
     * @param type selected JDBC engine backend
     * @param url JDBC URL selecting the database and trusted schema
     * @param user host-provided database username
     * @param password host-provided database password; do not log this value
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @param source host-owned source of idle auto-commit connections; takes precedence over URL and Driver
     * @param init whether to apply built-in schema migrations
     * @param definitions registry of selected workflow definitions
     * @param publisher destination or event publisher used by this adapter
     * @param handlers registered action handlers
     * @param conditions declarative condition evaluator and registered predicates
     * @param starts event-to-workflow start mapping for supported runtime modes
     * @param settings adapter-specific settings; explicit SQLite settings are rejected in PostgreSQL mode
     * @param listeners infrastructure listeners indexed by registered identity
     * @return the configured durable engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    @SuppressWarnings("java:S107") // Compatibility factory retained for existing clients.
    public static JdbcWorkflowEngine create(Type type,String url,String user,String password,Driver driver,DataSource source,boolean init,
      WorkflowDefinitionRegistry definitions,EventPublisher publisher,Map<String,StepHandler> handlers,BranchConditionEvaluator conditions,
      Map<String,String> starts,Map<String,String> settings,Map<String,Object> listeners)throws ClassNotFoundException{
        return create(type,url,user,password,driver,source,init,definitions,publisher,handlers,conditions,starts,settings,listeners,NoOpWorkflowLifecycleObserver.INSTANCE,Clock.systemUTC());
    }
    /**
     * {@inheritDoc}
     */
    @Override public StartWorkflowResult start(StartWorkflowCommand command){
        Committed<StartWorkflowResult> c=persistence.jdbcTransactions().inWriteTransaction(()->{String h=hash(TEXT_START,command.workflowKey(),command.workflowVersion(),command.businessKey(),new JdbcJsonCodec().write(command.variables()));
            Optional<CommandResultRecord> prior=prior(command.metadata().idempotencyKey(),TEXT_START,h);
                if(prior.isPresent())return new Committed<>(repeatStart(prior.get()),List.of());
            WorkflowTransitionResult<StartWorkflowResult> r=machine.start(command);
                List<WorkflowEvent> captured=persist(r.mutation(),true);
                save(command.metadata().idempotencyKey(),TEXT_START,h,r.commandResult());
                return new Committed<>(r.commandResult(),captured);
            });
        notifyObservers(c.events);
            return c.result;
    }
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowCommandResult signal(SignalWorkflowCommand c){return mutate(TEXT_SIGNAL,c.instanceId(),c.metadata(),signalBody(c.signal()),(s,t)->machineFor(s).signal(s,t,c));
    }
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowCommandResult retryFailedStep(RetryFailedStepCommand c){return mutate("retryFailedStep",c.instanceId(),c.metadata(),c.stepId(),(s,t)->machineFor(s).retry(s,t,c));
    }
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowCommandResult cancel(CancelWorkflowCommand c){return mutate("cancel",c.instanceId(),c.metadata(),"cancel",(s,t)->machineFor(s).cancel(s,t,c));
    }
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowCommandResult resume(ResumeWorkflowCommand c){return mutate("resume",c.instanceId(),c.metadata(),"resume",(s,t)->machineFor(s).resume(s,t,c));
    }
    private WorkflowCommandResult mutate(String type,WorkflowInstanceId id,WorkflowCommandMetadata metadata,String body,Transition transition){
        Committed<WorkflowCommandResult> c=persistence.jdbcTransactions().inWriteTransaction(()->{String h=hash(type,id.toString(),body);
            Optional<CommandResultRecord> prior=prior(metadata.idempotencyKey(),type,h);
            if(prior.isPresent()) {
                return new Committed<>(repeat(prior.get()),List.of());
            }
            WorkflowSnapshot current=require(id);
            requireDefinition(current);
            WorkflowTransitionResult<WorkflowCommandResult> r=transition.apply(current,persistence.timers().findByWorkflowInstance(id));
                List<WorkflowEvent> captured=persist(r.mutation(),false);
                save(metadata.idempotencyKey(),type,h,r.commandResult());
                return new Committed<>(r.commandResult(),captured);
            });
        notifyObservers(c.events);
            return c.result;
    }
    private List<WorkflowEvent> persist(WorkflowMutation m,boolean insert){if(insert)persistence.instances().insert(m.nextSnapshot());
        else persistence.instances().update(m.nextSnapshot(),m.previousSnapshot().lockVersion());
        writeProbe.get().accept("snapshot");
        ArrayList<WorkflowEvent> captured=new ArrayList<>();
        for(WorkflowEvent source:m.events()){WorkflowEvent e=capture(source);
            captured.add(e);
            persistence.events().append(e);
            writeProbe.get().accept(TEXT_EVENT);
            outboxEnqueue.enqueue(e);
            writeProbe.get().accept("outbox");
        }
    for(WorkflowTimer t:m.timersAfter()){
            if(t.status()==WorkflowTimerStatus.CLAIMED&&(m.nextSnapshot().status()==WorkflowStatus.CANCELED||m.nextSnapshot().status()==WorkflowStatus.COMPLETED))
                persistence.timers().cancel(t.timerId(),clock.instant());
            else persistence.timers().save(t);
            writeProbe.get().accept("timer");
        }
    return List.copyOf(captured);
        }
    private Optional<CommandResultRecord> prior(String key,String type,String hash){if(key==null||key.isBlank())return Optional.empty();
        try {
            connections.strategy().lockCommand(connections.currentTransactionConnection(), key);
        } catch(java.sql.SQLException failure) {
            throw new JdbcTransactionException("command lock", failure);
        }
        Optional<CommandResultRecord> r=persistence.commandResults().find(key);
        r.ifPresent(v->{if(!v.idempotencyKey().equals(key)||!v.commandType().equals(type)||!v.requestHash().equals(hash))throw new WorkflowIdempotencyConflictException(key);
    });
        return r;
    }
    private void save(String key,String type,String hash,Object result){if(key==null||key.isBlank())return;
        WorkflowInstanceId id;
        LinkedHashMap<String,Object>d=new LinkedHashMap<>();
        if(result instanceof StartWorkflowResult r){id=r.workflowInstanceId();
            d.put(TEXT_COMMAND_ID,r.commandId().toString());
            d.put("acceptedAt",r.acceptedAt().toString());
            d.put(TEXT_STATUS,"ACCEPTED");
        }
        else{WorkflowCommandResult r=(WorkflowCommandResult)result;
            id=r.workflowInstanceId();
            d.put(TEXT_COMMAND_ID,r.commandId().toString());
            d.put(TEXT_STATUS,r.status().name());
        }
        if(type()==Type.POSTGRESQL) {
            WorkflowSnapshot snapshot=result instanceof StartWorkflowResult r?r.snapshot():((WorkflowCommandResult)result).snapshot();
            d.put("snapshot", JdbcCommandSnapshot.encode(snapshot));
            d.put("emittedEventIds",result instanceof StartWorkflowResult r?r.emittedEventIds():((WorkflowCommandResult)result).emittedEventIds());
            if(result instanceof WorkflowCommandResult r)d.put("eventStatusAttemptIds",r.eventStatusAttemptIds());
        }
        CommandResultRecord stored=persistence.commandResults().save(new CommandResultRecord(key,type,hash,id,d,Instant.now()));
        if(!Objects.equals(stored.workflowInstanceId(),id)||!new JdbcJsonCodec().write(stored.result()).equals(new JdbcJsonCodec().write(d)))
            throw new org.jworkflow.persistence.PersistenceConstraintException("Command result changed outside command key protection; transaction must roll back");
            writeProbe.get().accept("commandResult");
        }
    private WorkflowSnapshot resultSnapshot(CommandResultRecord r){
        return r.result().get("snapshot") instanceof Map<?,?> snapshot?JdbcCommandSnapshot.decode(snapshot):require(r.workflowInstanceId());
    }
    private static List<String> resultIds(CommandResultRecord r,String key){
        return r.result().get(key) instanceof List<?> values?values.stream().map(String.class::cast).toList():List.of();
    }
    private StartWorkflowResult repeatStart(CommandResultRecord r){WorkflowSnapshot s=resultSnapshot(r);
        return new StartWorkflowResult(UUID.fromString((String)r.result().get(TEXT_COMMAND_ID)),s.instanceId(),s.workflowKey(),s.workflowVersion(),s.businessKey(),s.correlationId(),Instant.parse((String)r.result().get("acceptedAt")),s,resultIds(r,"emittedEventIds"),true);
    }
    private WorkflowCommandResult repeat(CommandResultRecord r){WorkflowSnapshot s=resultSnapshot(r);
        return new WorkflowCommandResult(UUID.fromString((String)r.result().get(TEXT_COMMAND_ID)),s.instanceId(),WorkflowCommandStatus.valueOf((String)r.result().get(TEXT_STATUS)),s,resultIds(r,"emittedEventIds"),resultIds(r,"eventStatusAttemptIds"),true);
    }
    private WorkflowSnapshot require(WorkflowInstanceId id){return persistence.instances().findById(id).orElseThrow(()->new WorkflowInstanceNotFoundException(id));
    }
    private void requireDefinition(WorkflowSnapshot s){if(persistence.definitions().findRevision(s.workflowKey(),s.workflowVersion(),s.workflowRevision()).isEmpty())throw new WorkflowDefinitionNotFoundException(s.workflowKey(),s.workflowVersion());
    }
    private WorkflowStateMachine machineFor(WorkflowSnapshot snapshot){
        return machine.withDefinitionRevision(persistence.definitions()
                .findRevision(snapshot.workflowKey(),snapshot.workflowVersion(),snapshot.workflowRevision())
                .orElseThrow(()->new WorkflowDefinitionNotFoundException(snapshot.workflowKey(),snapshot.workflowVersion())));
    }
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowSnapshot snapshot(WorkflowInstanceId id){return require(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override public WorkflowSnapshot snapshot(String key,String business){return persistence.instances().findByBusinessKey(key,business).orElseThrow(()->new WorkflowInstanceNotFoundException("No workflow instance for "+key+" and "+business));
    }
    /**
     * {@inheritDoc}
     */
    @Override public org.jworkflow.query.WorkflowQueryService queries(){return new org.jworkflow.query.PersistenceWorkflowQueryService(persistence);
    }
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowEngineContext context(){return context;
    }

    /**
     * {@inheritDoc}
     */
    @Override public void registerListener(String id,Object listener){if(id==null||id.isBlank())throw new IllegalArgumentException("listenerId is required");
        listeners.put(id,Objects.requireNonNull(listener));
    }
    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("java:S3776") // Route selection validates all mutually exclusive identity modes.
    @Override public void publish(WorkflowEvent event){EventMetadata metadata=Objects.requireNonNull(event,TEXT_EVENT).metadata();
        WorkflowEventRoute requested;
        if(metadata.workflowInstanceId()!=null)requested=WorkflowEventRoute.exact(metadata.workflowInstanceId(),event.eventName());
        else{String workflowKey=metadata.headers().get("workflowKey");
        if(workflowKey==null||workflowKey.isBlank())throw new InvalidWorkflowRouteException(WorkflowRoutingOutcome.NO_MATCH,"Correlation routing requires workflowKey metadata");
        requested=metadata.correlationId()!=null&&!metadata.correlationId().isBlank()?WorkflowEventRoute.correlated(workflowKey,metadata.correlationId(),event.eventName()):WorkflowEventRoute.businessKey(workflowKey,metadata.businessKey(),event.eventName());
    }WorkflowRoutingResult result=route(event,requested);
        if(result.outcome()!=WorkflowRoutingOutcome.ROUTED)throw new InvalidWorkflowRouteException(result.outcome(),result.detail());
    }
    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("java:S3776") // Routing handles explicit outcomes and independent fan-out failures.
    @Override public WorkflowRoutingResult route(WorkflowEvent event,WorkflowEventRoute requested){event=capture(event);
        Objects.requireNonNull(requested,"route");
        if(!requested.expectedEvent().equals(event.eventName()))throw new InvalidWorkflowRouteException(WorkflowRoutingOutcome.EVENT_NOT_ACCEPTED,"Route expected "+requested.expectedEvent()+" but received "+event.eventName());
        if(requested.tenantId()!=null&&!requested.tenantId().isBlank())throw new InvalidWorkflowRouteException(WorkflowRoutingOutcome.NO_MATCH,"Tenant-aware routing is not configured by this persistence model");
        observeIncoming(WorkflowLifecycleEventType.EVENT_RECEIVED,event,null);
        List<WorkflowSnapshot> candidates=resolveCandidates(requested);
        if(candidates.isEmpty()){observeIncoming(WorkflowLifecycleEventType.EVENT_IGNORED,event,null);
        return new WorkflowRoutingResult(WorkflowRoutingOutcome.NO_MATCH,List.of(),List.of(),"No active workflow matches the route");
    }WorkflowEvent safeEvent=event;
        List<WorkflowSnapshot> eligible=candidates.stream().filter(snapshot->routeEligible(safeEvent,snapshot)).toList();
        if(eligible.isEmpty()){WorkflowRoutingOutcome outcome=candidates.stream().allMatch(s->s.status()==WorkflowStatus.COMPLETED||s.status()==WorkflowStatus.CANCELED)?WorkflowRoutingOutcome.TERMINAL_WORKFLOW:WorkflowRoutingOutcome.EVENT_NOT_ACCEPTED;
        observeIncoming(WorkflowLifecycleEventType.EVENT_IGNORED,event,candidates.get(0));
        return new WorkflowRoutingResult(outcome,List.of(),List.of(),"Matching workflow does not accept "+event.eventName());
    }
    if(requested.mode()==WorkflowRoutingMode.SINGLE&&eligible.size()!=1) {
        throw new AmbiguousWorkflowRouteException("Route matched "+eligible.size()+" workflows for "+requested.workflowKey());
    }
        ArrayList<WorkflowInstanceId> routed=new ArrayList<>();
        ArrayList<WorkflowInstanceId> failed=new ArrayList<>();
        for(WorkflowSnapshot candidate:eligible){WorkflowSnapshot target=Objects.requireNonNull(candidate,"eligible workflow");
        try{RouteAttempt attempt=routeOne(event,target);
        if(attempt.outcome()==WorkflowRoutingOutcome.ROUTED){routed.add(target.instanceId());
        observeIncoming(WorkflowLifecycleEventType.EVENT_CORRELATED,event,target);
    }else if(requested.mode()==WorkflowRoutingMode.SINGLE){observeIncoming(WorkflowLifecycleEventType.EVENT_IGNORED,event,target);
        return new WorkflowRoutingResult(attempt.outcome(),List.of(),List.of(),attempt.detail());
    }else failed.add(target.instanceId());
    }catch(RuntimeException failure){failed.add(target.instanceId());
        if(requested.mode()==WorkflowRoutingMode.SINGLE)throw failure;
    }}WorkflowRoutingOutcome outcome=failed.isEmpty()?WorkflowRoutingOutcome.ROUTED:WorkflowRoutingOutcome.PARTIAL_FAILURE;
        return new WorkflowRoutingResult(outcome,routed,failed,failed.isEmpty()?"Routed":"Fan-out completed with independent target failures");
    }
    @SuppressWarnings("java:S3776") // One transaction contains all idempotency and optimistic-routing guards.
    private RouteAttempt routeOne(WorkflowEvent event,WorkflowSnapshot candidate){
        Committed<RouteAttempt> committed=persistence.jdbcTransactions().inWriteTransaction(()->{
                SignalWorkflowCommand command=routedSignal(event,candidate);
            String body=signalBody(command.signal());
                String requestHash=hash(TEXT_SIGNAL,candidate.instanceId().toString(),body);
                Optional<CommandResultRecord> existing=prior(command.metadata().idempotencyKey(),TEXT_SIGNAL,requestHash);
            if(existing.isPresent())return new Committed<>(new RouteAttempt(WorkflowRoutingOutcome.ROUTED,"Routed idempotent repeat"),List.of());
            WorkflowSnapshot current=require(candidate.instanceId());
                requireDefinition(current);
            if(!machineFor(current).accepts(current,event)){WorkflowRoutingOutcome outcome=current.status()==WorkflowStatus.COMPLETED||current.status()==WorkflowStatus.CANCELED?WorkflowRoutingOutcome.TERMINAL_WORKFLOW:WorkflowRoutingOutcome.EVENT_NOT_ACCEPTED;
                return new Committed<>(new RouteAttempt(outcome,"Matching workflow no longer accepts "+event.eventName()),List.of());
            }
            WorkflowTransitionResult<WorkflowCommandResult> result=machineFor(current).signal(current,persistence.timers().findByWorkflowInstance(current.instanceId()),command);
                List<WorkflowEvent> captured=persist(result.mutation(),false);
                save(command.metadata().idempotencyKey(),TEXT_SIGNAL,requestHash,result.commandResult());
                return new Committed<>(new RouteAttempt(WorkflowRoutingOutcome.ROUTED,"Routed"),captured);
        });
            notifyObservers(committed.events);
            return committed.result;
    }
    private boolean routeEligible(WorkflowEvent event,WorkflowSnapshot candidate){
        if(type!=Type.POSTGRESQL){requireDefinition(candidate);return machineFor(candidate).accepts(candidate,event);}
        return persistence.jdbcTransactions().inWriteTransaction(()->{
            SignalWorkflowCommand command=routedSignal(event,candidate);
            String requestHash=hash(TEXT_SIGNAL,candidate.instanceId().toString(),signalBody(command.signal()));
            if(prior(command.metadata().idempotencyKey(),TEXT_SIGNAL,requestHash).isPresent())return true;
            WorkflowSnapshot current=require(candidate.instanceId());
            requireDefinition(current);
            return machineFor(current).accepts(current,event);
        });
    }
    private List<WorkflowSnapshot> resolveCandidates(WorkflowEventRoute route){if(route.workflowInstanceId()!=null){return resolveExactCandidate(route);
    }int limit=recovery.maximumRoutingCandidates+1;
        List<WorkflowSnapshot> values=route.correlationId()!=null?persistence.instances().findActiveByCorrelation(route.workflowKey(),route.correlationId(),limit):persistence.instances().findActiveByBusinessKey(route.workflowKey(),route.businessKey(),limit);
        if(route.businessKey()!=null&&route.correlationId()!=null)values=values.stream().filter(value->route.businessKey().equals(value.businessKey())).toList();
        if(values.size()>recovery.maximumRoutingCandidates)throw new AmbiguousWorkflowRouteException("Route exceeds configured candidate limit "+recovery.maximumRoutingCandidates);
        return values;
    }
    private List<WorkflowSnapshot> resolveExactCandidate(WorkflowEventRoute route){WorkflowSnapshot snapshot=persistence.instances().findById(route.workflowInstanceId()).orElse(null);
        if(snapshot==null)return List.of();
        if(route.workflowKey()!=null&&!route.workflowKey().equals(snapshot.workflowKey()))return List.of();
        if(route.businessKey()!=null&&!route.businessKey().equals(snapshot.businessKey()))return List.of();
        if(route.correlationId()!=null&&!Objects.equals(route.correlationId(),snapshot.correlationId()))return List.of();
        return List.of(snapshot);
    }
    private SignalWorkflowCommand routedSignal(WorkflowEvent event,WorkflowSnapshot target){EventMetadata metadata=event.metadata();
        String correlation=metadata.correlationId()==null||metadata.correlationId().isBlank()?target.correlationId():metadata.correlationId();
        if(correlation==null||correlation.isBlank())correlation=metadata.eventId().toString();
        WorkflowSignal signal=new WorkflowSignal(event.eventName().value(),correlation,metadata.causationId(),target.businessKey(),metadata.occurredAt(),metadata.headers(),event.message());
        WorkflowCommandMetadata command=new WorkflowCommandMetadata(null,metadata.eventId()+":"+target.instanceId(),target.workflowKey(),target.workflowVersion(),target.instanceId(),target.businessKey(),correlation,metadata.causationId(),metadata.traceId(),metadata.tenantId(),metadata.sourceSystem(),null,metadata.receivedAt(),metadata.headers());
        return new SignalWorkflowCommand(target.instanceId(),signal,command);
    }
    private void recoverStartup(){
        persistence.transactions().execute(()->{
            Instant now=clock.instant();
                persistence.timers().releaseExpiredClaims(now);
                persistence.inbox().releaseExpiredClaims(now);
                persistence.outbox().releaseExpiredClaims(now);
        });
        if(!recovery.lazyDefinitionValidation)doAuditActiveDefinitions(recovery.startupValidationBatchSize);
    }
    /**
     * Validates every active instance against its exact stored definition using bounded startup pages; returns the
     * number checked.
     * @return the number of active instances checked
     */
    public int auditActiveDefinitions(){return doAuditActiveDefinitions(recovery.startupValidationBatchSize);
    }
    private int doAuditActiveDefinitions(int batchSize){int scanned=0;
        org.jworkflow.persistence.ActiveWorkflowCursor cursor=null;
        while(true){List<WorkflowSnapshot> page=persistence.instances().findActiveAfter(cursor,batchSize);
        startupValidationQueryCount++;
        startupValidationPeakBatchSize=Math.max(startupValidationPeakBatchSize,page.size());
        for(WorkflowSnapshot snapshot:page)requireDefinition(snapshot);
        scanned+=page.size();
        if(page.size()<batchSize)return scanned;
        WorkflowSnapshot last=page.get(page.size()-1);
        cursor=new org.jworkflow.persistence.ActiveWorkflowCursor(last.updatedAt(),last.instanceId());
    }}
    private void startTimerPoller(){if(!recovery.timerPolling)return;
        timerPoller=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"jworkflow-jdbc-timers-"+workerId.substring(0,8));
        t.setDaemon(true);
        return t;
    });
        timerPoller.scheduleWithFixedDelay(this::pollSafely,recovery.pollInterval.toMillis(),recovery.pollInterval.toMillis(),TimeUnit.MILLISECONDS);
        }
    private void pollSafely(){if(closed.get())return;
        try{pollTimersOnce();
    }catch(RuntimeException ignored){/* a later bounded poll retries durable work */}}
    int pollTimersOnce(){
        if(timerReconciliationRequired.get())throw new WorkflowInfrastructureException("Timer polling paused: reconcile the failed transaction before recreating this engine",null);
        Instant now=clock.instant();
            persistence.transactions().execute(()->{persistence.timers().releaseExpiredClaims(now);
            persistence.inbox().releaseExpiredClaims(now);
            persistence.outbox().releaseExpiredClaims(now);
        });
        List<WorkflowTimer> claimed=persistence.jdbcTransactions().inWriteTransaction(()->persistence.timers().claimDueFenced(now,workerId,now.plus(recovery.lease),recovery.batchSize));
        for(WorkflowTimer timer:claimed) {
            processClaimedTimer(timer);
        }
        return claimed.size();
    }
    private void processClaimedTimer(WorkflowTimer claimed){
        try{
            Committed<WorkflowTimer> committed=persistence.jdbcTransactions().inWriteTransaction(()->{
                persistence.timers().requireClaim(claimed.timerId(),workerId,claimed.claimToken());
                WorkflowSnapshot current=require(claimed.workflowInstanceId());
                requireDefinition(current);
                WorkflowTransitionResult<WorkflowTimer> result=machineFor(current).fireTimer(current,persistence.timers().findByWorkflowInstance(current.instanceId()),claimed,clock.instant());
                WorkflowMutation mutation=result.mutation();
                    persistence.instances().update(mutation.nextSnapshot(),current.lockVersion());
                    writeProbe.get().accept("snapshot");
                ArrayList<WorkflowEvent> captured=new ArrayList<>();
                    for(WorkflowEvent source:mutation.events()){WorkflowEvent event=capture(source);
                    captured.add(event);
                    persistence.events().append(event);
                    writeProbe.get().accept(TEXT_EVENT);
                    outboxEnqueue.enqueue(event);
                    writeProbe.get().accept("outbox");
                }
                for(WorkflowTimer timer:mutation.timersAfter())if(!timer.timerId().equals(claimed.timerId()))persistence.timers().save(timer);
                persistence.timers().appendAttempt(new WorkflowTimerAttempt(null,claimed.timerId(),claimed.attemptCount()+1,WorkflowTimerStatus.FIRED,workerId,null,clock.instant()));
                persistence.timers().markFired(claimed.timerId(),workerId,claimed.claimToken(),clock.instant());
                    writeProbe.get().accept("timer");
                    return new Committed<>(result.commandResult(),List.copyOf(captured));
                });
            notifyObservers(committed.events);
        }catch(RuntimeException failure){
            boolean reconcile=JdbcTransactionException.requiresReconciliation(failure);
            try{persistence.transactions().execute(()->{
                persistence.timers().requireClaim(claimed.timerId(),workerId,claimed.claimToken());
                persistence.timers().appendAttempt(new WorkflowTimerAttempt(null,claimed.timerId(),claimed.attemptCount()+1,reconcile?WorkflowTimerStatus.DEAD_LETTER:WorkflowTimerStatus.RETRY_SCHEDULED,workerId,safeMessage(failure),clock.instant()));
                if(reconcile)persistence.timers().markDeadLetter(claimed.timerId(),workerId,claimed.claimToken(),safeMessage(failure),clock.instant());
                else persistence.timers().markFailed(claimed.timerId(),workerId,claimed.claimToken(),safeMessage(failure),clock.instant().plus(recovery.retryDelay));
            });
            }
            catch(RuntimeException ignored){
                if(reconcile)timerReconciliationRequired.set(true);
                failure.addSuppressed(ignored);
            }
    throw failure;
        }
    }
    private static String safeMessage(Throwable failure){String message=failure.getMessage();
        return message==null?failure.getClass().getSimpleName():message.substring(0,Math.min(message.length(),500));
    }
    /**
     * {@inheritDoc}
     */
    @Override public void close(){if(!closed.compareAndSet(false,true))return;
        ScheduledExecutorService poller=timerPoller;
        if(poller!=null){poller.shutdown();
        try{if(!poller.awaitTermination(5,TimeUnit.SECONDS))poller.shutdownNow();
    }catch(InterruptedException e){poller.shutdownNow();
        Thread.currentThread().interrupt();
    }}}
    /**
     * Returns the JDBC backend selected for this engine.
     * @return SQLITE or POSTGRESQL
     */
    public Type type(){return type;
    }

    /**
     * Returns the connections.
     * @return the connections
     */
    public JdbcConnectionFactory connectionFactory(){return connections;
    }

    /**
     * Returns the transaction manager shared by this engine and its repositories.
     * @return the transaction manager shared by this engine and its repositories
     */
    public WorkflowTransactionManager transactionManager(){return persistence.transactions();
    }
    /**
     * Creates a JDBC inbox application sharing this engine's persistence and command boundary. Close the
     * application to stop its owned polling worker.
     * @param translator mapping from an inbox envelope to commands or an explicit route
     * @return the resulting jdbc inbox application
     */
    public JdbcInboxApplication inbox(InboxEventTranslator translator){if(closed.get())throw new WorkflowInvalidStateException("Workflow engine is closed");
        return new JdbcInboxApplication(this,persistence,translator,clock,settings,lifecycleObserver);
    }
    /**
     * Creates an at-least-once outbox application using the supplied destination transports; close it to stop its
     * owned worker.
     * @param destinations publication destinations selected for the event
     * @return the resulting jdbc outbox application
     */
    public JdbcOutboxApplication outbox(Map<String,DestinationPublisher> destinations){if(closed.get())throw new WorkflowInvalidStateException("Workflow engine is closed");
        return new JdbcOutboxApplication(persistence,destinations,clock,settings,lifecycleObserver);
    }
    void writeProbe(java.util.function.Consumer<String> probe){this.writeProbe.set(probe==null?ignored->{}:probe);
    }
    int startupValidationQueryCount(){return startupValidationQueryCount;
    }int startupValidationPeakBatchSize(){return startupValidationPeakBatchSize;
    }
    WorkflowEvent capture(WorkflowEvent event){return SafeEventCapturePolicy.filter(eventCapturePolicy,event);
    }
    private void notifyObservers(List<WorkflowEvent> events){events.forEach(event->{persistence.transactions().afterCommit(()->observer.publish(event));
        persistence.transactions().afterCommit(()->lifecycleEvents.publish(event));
        persistence.transactions().afterCommit(()->observeOutboxCreated(event));
    });
    }
    private void observeOutboxCreated(WorkflowEvent event){String configured=settings.get("outbox.destination."+event.eventName().value());
        if(configured==null||configured.isBlank())configured=settings.getOrDefault("outbox.default-destination","workflow.events");
        for(String destination:configured.split(",")){String value=destination.trim();
        if(!value.isBlank())lifecycleObserver.observe(new WorkflowLifecycleEvent(WorkflowLifecycleEventType.OUTBOX_CREATED,event.metadata().occurredAt(),event.metadata().workflowInstanceId(),event.metadata().headers().get("workflowKey"),event.metadata().headers().get("workflowVersion"),event.metadata().headers().get("state"),event.metadata().headers().get("step"),event.metadata().correlationId(),event.metadata().causationId(),event.metadata().traceId(),Map.of("destination",value)));
    }}
    private void observeIncoming(WorkflowLifecycleEventType type,WorkflowEvent event,WorkflowSnapshot snapshot){EventMetadata m=event.metadata();
        persistence.transactions().afterCommit(()->lifecycleObserver.observe(new WorkflowLifecycleEvent(type,m.occurredAt(),snapshot==null?m.workflowInstanceId():snapshot.instanceId(),snapshot==null?null:snapshot.workflowKey(),snapshot==null?null:snapshot.workflowVersion(),snapshot==null?null:snapshot.state(),null,m.correlationId(),m.causationId(),m.traceId(),Map.of("eventName",event.eventName().value()))));
    }
    private String signalBody(WorkflowSignal signal){
        if(type!=Type.POSTGRESQL)return signal.toString();
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("eventType",signal.eventType());value.put("correlationId",signal.correlationId());
        value.put("causationId",signal.causationId());value.put("businessKey",signal.businessKey());
        value.put("occurredAt",signal.occurredAt().toString());value.put("metadata",signal.metadata());
        EventMessage message=signal.message();
        boolean binary=message.payload() instanceof byte[];
        value.put("binary",binary);value.put("payload",binary?Base64.getEncoder().encodeToString((byte[])message.payload()):message.payload());
        value.put("contentType",message.contentType());value.put("schemaName",message.schemaName());
        value.put("schemaVersion",message.schemaVersion());value.put("redacted",message.redacted());value.put("attributes",message.attributes());
        return new JdbcJsonCodec().write(value);
    }
    private String hash(String...p){try{MessageDigest d=MessageDigest.getInstance("SHA-256");
        if(type==Type.POSTGRESQL)return HexFormat.of().formatHex(d.digest(new JdbcJsonCodec().write(Arrays.asList(p)).getBytes(StandardCharsets.UTF_8)));
        for(String s:p){d.update((s==null?"<null>":s).getBytes(StandardCharsets.UTF_8));
        d.update((byte)0);
    }
    return HexFormat.of().formatHex(d.digest());
    }catch(Exception e){throw new IllegalStateException(e);
    }}
    /**
     * Calculates a command result and durable effects from the current snapshot and timer state.
     */
    private interface Transition{

        /**
         * Calculates a command result and persistence effects from the supplied snapshot and timer state.
         * @param s the immutable instance snapshot
         * @param t the list&lt;workflow timer&gt; value
         * @return the resulting workflow transition result&lt;workflow command result&gt;
         */
        WorkflowTransitionResult<WorkflowCommandResult> apply(WorkflowSnapshot s,List<WorkflowTimer> t);
    }

    /**
     * Command result and observations retained for dispatch after successful outer commit.
     * @param <T> the value type
     * @param result result data associated with the operation
     * @param events workflow events in their supplied order
     */
    private record Committed<T>(T result,List<WorkflowEvent> events){}

    /**
     * Per-target route result accumulated while resolving explicit event delivery.
     * @param outcome event-routing resolution outcome
     * @param detail diagnostic or workflow detail associated with the observation
     */
    private record RouteAttempt(WorkflowRoutingOutcome outcome,String detail){}
    /**
     * Read-only engine context backed by repository snapshot queries.
     */
    private final class RepositoryContext implements WorkflowEngineContext{

        /**
         * {@inheritDoc}
         */
        @Override public List<WorkflowSnapshot> getWorkflows(){ArrayList<WorkflowSnapshot> all=new ArrayList<>();
        org.jworkflow.persistence.ActiveWorkflowCursor cursor=null;
        while(true){List<WorkflowSnapshot> page=persistence.instances().findActiveAfter(cursor,recovery.startupValidationBatchSize);
        all.addAll(page);
        if(page.size()<recovery.startupValidationBatchSize)return List.copyOf(all);
        WorkflowSnapshot last=page.get(page.size()-1);
        cursor=new org.jworkflow.persistence.ActiveWorkflowCursor(last.updatedAt(),last.instanceId());
    }}

        /**
         * {@inheritDoc}
         */
        @Override public Optional<WorkflowSnapshot> getWorkflow(WorkflowInstanceId id){return persistence.instances().findById(id);
    }

        /**
         * {@inheritDoc}
         */
        @Override public Optional<WorkflowSnapshot> getWorkflow(String key,String business){return persistence.instances().findByBusinessKey(key,business);
    }}
    /**
     * Validated polling, lease, batch and startup settings for durable recovery.
     * @param timerPolling the timer polling
     * @param pollInterval the poll interval
     * @param lease the lease
     * @param retryDelay the retry delay
     * @param batchSize the batch size
     * @param startupValidationBatchSize the startup validation batch size
     * @param maximumRoutingCandidates the maximum routing candidates
     * @param lazyDefinitionValidation the lazy definition validation
     */
    private record RecoveryOptions(boolean timerPolling,Duration pollInterval,Duration lease,Duration retryDelay,int batchSize,int startupValidationBatchSize,int maximumRoutingCandidates,boolean lazyDefinitionValidation){
        static RecoveryOptions from(Map<String,String> settings){Map<String,String>s=settings==null?Map.of():settings;
            long poll=number(s,"recovery.timer-poll-interval-ms",100,10,60_000);
            long lease=number(s,"recovery.lease-ms",30_000,100,3_600_000);
            long retry=number(s,"recovery.timer-retry-delay-ms",1_000,10,3_600_000);
            int batch=(int)number(s,"recovery.timer-batch-size",32,1,1_000);
            int startup=(int)number(s,"recovery.startup-validation-batch-size",500,1,10_000);
            int candidates=(int)number(s,"routing.maximum-candidates",100,1,10_000);
            boolean lazy=Boolean.parseBoolean(s.getOrDefault("recovery.lazy-definition-validation","true"));
            return new RecoveryOptions(Boolean.parseBoolean(s.getOrDefault("recovery.timer-poll-enabled","true")),Duration.ofMillis(poll),Duration.ofMillis(lease),Duration.ofMillis(retry),batch,startup,candidates,lazy);
        }
        private static long number(Map<String,String>s,String key,long fallback,long min,long max){long value=Long.parseLong(s.getOrDefault(key,Long.toString(fallback)));
            if(value<min||value>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max);
            return value;
        }
    }
}
