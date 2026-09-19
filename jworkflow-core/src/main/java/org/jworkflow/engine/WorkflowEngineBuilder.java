package org.jworkflow.engine;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import org.jworkflow.observability.*;
import org.jworkflow.security.*;

import java.lang.reflect.InvocationTargetException;
import java.sql.Driver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;
import java.time.Clock;

/**
 * Mutable, non-thread-safe engine configuration. The default backend is in-memory and schema initialization is
 * disabled. For JDBC, a host DataSource takes precedence over a supplied Driver and URL; otherwise the adapter
 * loads the selected optional driver. The host owns DataSource lifetime, credentials, TLS and pooling.
 * Configuration methods return this builder; build creates an engine that callers must close.
 */
public final class WorkflowEngineBuilder {
    /** Creates an in-memory engine configuration with schema initialization disabled. */
    public WorkflowEngineBuilder() {
    }

    private WorkflowEngine.Type type = WorkflowEngine.Type.IN_MEMORY;
    private String jdbcUrl;
    private String username;
    private String password;
    private Driver driver;
    private DataSource dataSource;
    private boolean initializeSchema;
    private final WorkflowDefinitionRegistry definitions = new WorkflowDefinitionRegistry();
    private EventPublisher eventPublisher = NoOpEventPublisher.INSTANCE;
    private WorkflowLifecycleObserver lifecycleObserver = NoOpWorkflowLifecycleObserver.INSTANCE;
    private WorkflowPersistence persistence;
    private final Map<String, StepHandler> stepHandlers = new LinkedHashMap<>();
    private final Map<String, Object> listenerInstances = new LinkedHashMap<>();
    private final Map<String, String> workflowStartEvents = new LinkedHashMap<>();
    private final BranchConditionEvaluator branchConditionEvaluator = new BranchConditionEvaluator();
    private final Map<String, String> settings = new LinkedHashMap<>();
    private DslCompilerOptions dslCompilerOptions = DslCompilerOptions.DEFAULT;
    private Clock clock = Clock.systemUTC();
    private EventCapturePolicy eventCapturePolicy = CaptureAllEventPolicy.INSTANCE;

    /**
     * Sets the clock used by the JDBC adapter for command, lease and recovery timing.
     * @param clock clock used for recorded times and lease/retry decisions
     * @return this builder for further configuration
     * @throws NullPointerException if clock is null
     */
    public WorkflowEngineBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /**
     * Enables or disables the engine-owned durable timer polling loop.
     * @param enabled whether the named option is enabled
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder timerPolling(boolean enabled) {
        return setting("recovery.timer-poll-enabled", Boolean.toString(enabled));
    }

    /**
     * Sets the durable timer poll interval, from 10 through 60,000 milliseconds.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowEngineBuilder timerPollIntervalMillis(long milliseconds) {
        if (milliseconds < 10 || milliseconds > 60_000) throw new IllegalArgumentException("timer poll interval must be between 10 and 60000 ms");
        return setting("recovery.timer-poll-interval-ms", Long.toString(milliseconds));
    }

    /**
     * Sets the durable recovery lease, from 100 through 3,600,000 milliseconds. Synchronize worker clocks and
     * allow for processing pauses.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowEngineBuilder recoveryLeaseMillis(long milliseconds) {
        if (milliseconds < 100 || milliseconds > 3_600_000) throw new IllegalArgumentException("recovery lease must be between 100 and 3600000 ms");
        return setting("recovery.lease-ms", Long.toString(milliseconds));
    }

    /**
     * Sets the maximum timers acquired per poll, from 1 through 1,000.
     * @param size maximum entries in the configured batch or candidate set
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder timerBatchSize(int size) {
        return boundedSetting("recovery.timer-batch-size", size, 1, 1_000);
    }

    /**
     * Sets the timer retry delay, from 10 through 3,600,000 milliseconds.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder timerRetryDelayMillis(long milliseconds) {
        return boundedSetting("recovery.timer-retry-delay-ms", milliseconds, 10, 3_600_000);
    }

    /**
     * Sets the eager startup validation page size, from 1 through 10,000 instances.
     * @param size maximum entries in the configured batch or candidate set
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder startupValidationBatchSize(int size) {
        return boundedSetting("recovery.startup-validation-batch-size", size, 1, 10_000);
    }

    /**
     * Sets the maximum candidates considered by a durable route, from 1 through 10,000.
     * @param size maximum entries in the configured batch or candidate set
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder eventRoutingMaximumCandidates(int size) {
        return boundedSetting("routing.maximum-candidates", size, 1, 10_000);
    }

    /**
     * Selects lazy validation of instance definition references instead of eagerly visiting active instance pages
     * during startup.
     * @param enabled whether the named option is enabled
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder lazyDefinitionValidation(boolean enabled) {
        return setting("recovery.lazy-definition-validation", Boolean.toString(enabled));
    }

    /**
     * Sets the inbox worker poll interval, from 10 through 60,000 milliseconds.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder inboxPollingMillis(long milliseconds) {
        return boundedSetting("inbox.poll-interval-ms", milliseconds, 10, 60_000);
    }

    /**
     * Sets the inbox acquisition lease, from 100 through 3,600,000 milliseconds.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder inboxClaimLeaseMillis(long milliseconds) {
        return boundedSetting("inbox.lease-ms", milliseconds, 100, 3_600_000);
    }

    /**
     * Sets the maximum inbox messages acquired per poll, from 1 through 1,000.
     * @param size maximum entries in the configured batch or candidate set
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder inboxBatchSize(int size) {
        return boundedSetting("inbox.batch-size", size, 1, 1_000);
    }

    /**
     * Sets inbox attempt and exponential backoff bounds. Attempts must be 1 through 100; backoffs are validated in
     * milliseconds.
     * @param maximumAttempts maximum allowed attempts, including the initial attempt
     * @param initialBackoffMillis initial retry delay in milliseconds
     * @param maximumBackoffMillis maximum retry delay in milliseconds, not less than the initial delay
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder inboxRetry(int maximumAttempts, long initialBackoffMillis, long maximumBackoffMillis) {
        boundedSetting("inbox.max-attempts", maximumAttempts, 1, 100);
        boundedBackoff("inbox", initialBackoffMillis, maximumBackoffMillis);
        return this;
    }

    /**
     * Sets the outbox worker poll interval, from 10 through 60,000 milliseconds.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder outboxPollingMillis(long milliseconds) {
        return boundedSetting("outbox.poll-interval-ms", milliseconds, 10, 60_000);
    }

    /**
     * Sets the outbox acquisition lease, from 100 through 3,600,000 milliseconds.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder outboxClaimLeaseMillis(long milliseconds) {
        return boundedSetting("outbox.lease-ms", milliseconds, 100, 3_600_000);
    }

    /**
     * Sets the maximum outbox messages acquired per poll, from 1 through 1,000.
     * @param size maximum entries in the configured batch or candidate set
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder outboxBatchSize(int size) {
        return boundedSetting("outbox.batch-size", size, 1, 1_000);
    }

    /**
     * Sets outbox attempt and exponential backoff bounds. Attempts must be 1 through 100; receivers must still
     * tolerate duplicate sends.
     * @param maximumAttempts maximum allowed attempts, including the initial attempt
     * @param initialBackoffMillis initial retry delay in milliseconds
     * @param maximumBackoffMillis maximum retry delay in milliseconds, not less than the initial delay
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder outboxRetry(int maximumAttempts, long initialBackoffMillis, long maximumBackoffMillis) {
        boundedSetting("outbox.max-attempts", maximumAttempts, 1, 100);
        boundedBackoff("outbox", initialBackoffMillis, maximumBackoffMillis);
        return this;
    }

    /**
     * Selects IN_MEMORY, SQLITE or POSTGRESQL; actual JDBC metadata must match a selected durable backend.
     * @param type selected engine backend
     * @return this builder for further configuration
     * @throws NullPointerException if type is null
     */
    public WorkflowEngineBuilder type(WorkflowEngine.Type type) {
        this.type = Objects.requireNonNull(type, "type");
        return this;
    }

    /**
     * Sets the JDBC URL used when no host DataSource is configured.
     * @param jdbcUrl JDBC connection URL; supply credentials separately and select a trusted schema
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder jdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        return this;
    }

    /**
     * Sets the database username for URL or supplied-Driver connections; a DataSource uses its own credentials.
     * @param username host-provided database username
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder username(String username) {
        this.username = username;
        return this;
    }

    /**
     * Sets the database password for URL or supplied-Driver connections; a DataSource uses its own credentials.
     * @param password host-provided database password; do not log this value
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder password(String password) {
        this.password = password;
        return this;
    }

    /**
     * Supplies a JDBC driver instead of loading the selected backend driver. A configured DataSource takes
     * precedence.
     * @param driver optional supplied JDBC driver; ignored when a DataSource is selected
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder driver(Driver driver) {
        this.driver = driver;
        return this;
    }

    /**
     * Selects a host-owned DataSource, overriding URL, Driver and credential fields. Its borrowed connections must
     * be idle and auto-commit enabled.
     * @param dataSource host-owned source of idle JDBC connections; the adapter does not close the source
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder dataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    /**
     * Selects built-in schema migration ownership. Disable initialization when Flyway, Liquibase or another
     * deployment tool owns the schema.
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder initialize() {
        this.initializeSchema = true;
        return this;
    }

    /**
     * Selects built-in schema migration ownership. Disable initialization when Flyway, Liquibase or another
     * deployment tool owns the schema.
     * @param initializeSchema whether this adapter owns built-in schema migration; false for external migration
     *     owners
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder initialize(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
        return this;
    }

    /**
     * Validates and registers a programmatically supplied workflow definition.
     * @param definition immutable workflow definition
     * @return this builder for further configuration
     * @throws NullPointerException if definition is null
     */
    public WorkflowEngineBuilder definition(WorkflowDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        new DefinitionValidator().validate(definition).throwIfInvalid();
        definitions.register(definition);
        return this;
    }

    /**
     * Loads, compiles, validates and activates source definitions using the currently configured compiler limits
     *  and clock.
     * @param source source provider to load and activate
     * @return this builder for further configuration
     * @throws NullPointerException if source is null
     */
    public WorkflowEngineBuilder definitions(WorkflowDefinitionSource source) {
        Objects.requireNonNull(source, "source");
        new WorkflowDefinitionActivationService(definitions, new GroovyWorkflowDslCompiler(dslCompilerOptions),
                new DefinitionValidator(), clock).activateOrThrow(source);
        return this;
    }

    /**
     * Validates and registers a programmatically supplied workflow definition.
     * @param builder definition builder to materialize and register
     * @return this builder for further configuration
     * @throws NullPointerException if builder is null
     */
    public WorkflowEngineBuilder definition(org.jworkflow.definition.WorkflowDefinitionBuilder builder) {
        return definition(Objects.requireNonNull(builder, "builder").build());
    }

    /**
     * Sets the application event sink. Use the durable outbox for reliable external delivery; ordinary callbacks
     * are best effort.
     * @param eventPublisher host event sink; publication guarantees depend on the supplied implementation
     * @return this builder for further configuration
     * @throws NullPointerException if eventPublisher is null
     */
    public WorkflowEngineBuilder eventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        return this;
    }

    /**
     * Sets a safely isolated lifecycle observer whose failures cannot control workflow state.
     * @param observer best-effort lifecycle observer
     * @return this builder for further configuration
     * @throws NullPointerException if observer is null
     */
    public WorkflowEngineBuilder lifecycleObserver(WorkflowLifecycleObserver observer) {
        this.lifecycleObserver = SafeWorkflowLifecycleObserver.isolate(
                Objects.requireNonNull(observer, "observer"));
        return this;
    }

    /**
     * Sets filtering/redaction before events cross durable or observable boundaries.
     * @param policy event capture/redaction policy
     * @return this builder for further configuration
     * @throws NullPointerException if policy is null
     */
    public WorkflowEngineBuilder eventCapturePolicy(EventCapturePolicy policy) {
        this.eventCapturePolicy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * Registers a handler by action name; handlers may execute inside JDBC transactions and must manage external
     * side effects explicitly.
     * @param action registered handler action name
     * @param handler application callback for the selected action
     * @return this builder for further configuration
     * @throws NullPointerException if handler is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowEngineBuilder stepHandler(String action, StepHandler handler) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required");
        }
        stepHandlers.put(action, Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /**
     * Sets resource limits used for subsequent DSL source activation.
     * @param options resource limits for restricted DSL compilation
     * @return this builder for further configuration
     * @throws NullPointerException if options is null
     */
    public WorkflowEngineBuilder dslCompilerOptions(DslCompilerOptions options) {
        this.dslCompilerOptions = Objects.requireNonNull(options, "options");
        return this;
    }

    /**
     * Supplies persistence hooks to the in-memory runtime; this does not select a JDBC durable backend.
     * @param persistence repository bundle sharing a transaction boundary
     * @return this builder for further configuration
     * @throws NullPointerException if persistence is null
     */
    public WorkflowEngineBuilder persistence(WorkflowPersistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        return this;
    }

    /**
     * Maps an event name to a workflow start in the in-memory runtime. JDBC event routing does not automatically
     * start new instances.
     * @param eventName event name matched by workflow transitions or subscribers
     * @param workflowKey registered workflow name used to resolve a definition
     * @return this builder for further configuration
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowEngineBuilder startWorkflowOn(String eventName, String workflowKey) {
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("eventName is required");
        }
        if (workflowKey == null || workflowKey.isBlank()) {
            throw new IllegalArgumentException("workflowKey is required");
        }
        workflowStartEvents.put(new EventName(eventName).value(), workflowKey);
        return this;
    }

    /**
     * Registers an infrastructure listener for declarative invocation; the one-argument overload uses its class
     * name as ID.
     * @param listener host listener object to register or invoke
     * @return this builder for further configuration
     * @throws NullPointerException if listener is null
     */
    public WorkflowEngineBuilder listener(Object listener) {
        Objects.requireNonNull(listener, "listener");
        listenerInstances.put(listener.getClass().getName(), listener);
        return this;
    }

    /**
     * Registers an infrastructure listener for declarative invocation; the one-argument overload uses its class
     * name as ID.
     * @param listenerId registered infrastructure listener identity
     * @param listener host listener object to register or invoke
     * @return this builder for further configuration
     * @throws NullPointerException if listener is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowEngineBuilder listener(String listenerId, Object listener) {
        if (listenerId == null || listenerId.isBlank()) {
            throw new IllegalArgumentException("listenerId is required");
        }
        listenerInstances.put(listenerId, Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /**
     * Registers a named application predicate for declarative branch conditions.
     * @param name name used to invoke this registered predicate
     * @param predicate host predicate invoked with workflow variables and configured arguments
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder branchPredicate(
            String name,
            java.util.function.BiPredicate<Map<String, Object>, Map<String, Object>> predicate
    ) {
        branchConditionEvaluator.registerPredicate(name, predicate);
        return this;
    }

    /**
     * Adds an adapter setting. Prefer typed methods for documented bounds; unknown keys are not a guarantee of
     *  supported behavior.
     * @param key adapter setting name
     * @param value adapter setting value
     * @return this builder for further configuration
     * @throws NullPointerException if key is null
     */
    public WorkflowEngineBuilder setting(String key, String value) {
        settings.put(Objects.requireNonNull(key, "key"), value);
        return this;
    }

    /**
     * Sets SQLite's busy timeout from 0 through 600,000 milliseconds. Explicit SQLite settings are rejected for
     * PostgreSQL.
     * @param milliseconds duration in milliseconds, within the bounds stated by this setting
     * @return this builder for further configuration
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowEngineBuilder sqliteBusyTimeoutMillis(int milliseconds) {
        if (milliseconds < 0 || milliseconds > 600_000) throw new IllegalArgumentException("SQLite busy timeout must be between 0 and 600000 ms");
        return setting("sqlite.busy-timeout-ms", Integer.toString(milliseconds));
    }

    /**
     * WAL is opt-in because journal mode is a database-wide operational choice.
     * @param enabled whether the named option is enabled
     * @return this builder for further configuration
     */
    public WorkflowEngineBuilder sqliteWalEnabled(boolean enabled) {
        return setting("sqlite.wal-enabled", Boolean.toString(enabled));
    }

    /**
     * Replaces backend, connection, initialization and adapter settings with the supplied property value.
     * @param properties engine configuration to apply
     * @return this builder for further configuration
     * @throws NullPointerException if properties is null
     */
    public WorkflowEngineBuilder properties(WorkflowEngineProperties properties) {
        Objects.requireNonNull(properties, "properties");
        type = properties.type();
        jdbcUrl = properties.jdbcUrl();
        username = properties.username();
        password = properties.password();
        driver = properties.driver();
        dataSource = properties.dataSource();
        initializeSchema = properties.initializeSchema();
        settings.clear();
        settings.putAll(properties.settings());
        return this;
    }

    /**
     * Merges recognized jworkflow.* string properties into this builder. Missing properties retain current values;
     * environment variables are not expanded.
     * @param properties engine configuration to apply
     * @return this builder for further configuration
     * @throws NullPointerException if properties is null
     */
    public WorkflowEngineBuilder properties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String configuredType = properties.getProperty("jworkflow.engine.type");
        if (configuredType != null && !configuredType.isBlank()) {
            type(WorkflowEngine.Type.valueOf(configuredType.trim().replace('-', '_').toUpperCase()));
        }

        jdbcUrl(properties.getProperty("jworkflow.jdbc.url", jdbcUrl));
        username(properties.getProperty("jworkflow.jdbc.username", username));
        password(properties.getProperty("jworkflow.jdbc.password", password));
        initializeSchema = Boolean.parseBoolean(properties.getProperty(
                "jworkflow.schema.initialize",
                Boolean.toString(initializeSchema)));
        if (properties.containsKey("jworkflow.sqlite.busy-timeout-ms")) {
            sqliteBusyTimeoutMillis(Integer.parseInt(properties.getProperty("jworkflow.sqlite.busy-timeout-ms")));
        }
        if (properties.containsKey("jworkflow.sqlite.wal-enabled")) {
            sqliteWalEnabled(Boolean.parseBoolean(properties.getProperty("jworkflow.sqlite.wal-enabled")));
        }

        for (String propertyName : properties.stringPropertyNames()) {
            if (propertyName.startsWith("jworkflow.setting.")) {
                setting(propertyName.substring("jworkflow.setting.".length()), properties.getProperty(propertyName));
            }
        }
        return this;
    }

    /**
     * Validates definitions/configuration and constructs the selected engine. JDBC construction borrows a
     * connection even when initialization is disabled; close the returned engine when finished.
     * @return the configured engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    public WorkflowEngine build() throws ClassNotFoundException {
        validateConfiguration();
        DefinitionValidator validator = new DefinitionValidator();
        for (WorkflowDefinition definition : definitions.snapshot().values()) {
            validator.validate(definition, definitions).throwIfInvalid();
            if (persistence != null) {
                persistence.transactions().execute(() -> persistence.definitions().save(definition));
            }
        }
        WorkflowEngineProperties properties = new WorkflowEngineProperties(
                type,
                jdbcUrl,
                username,
                password,
                driver,
                dataSource,
                initializeSchema,
                settings);

        EventPublisher configuredPublisher = eventPublisher;
        EventPublisher lifecyclePublisher = new WorkflowEventLifecycleAdapter(lifecycleObserver);
        EventPublisher observedPublisher = event -> {
            configuredPublisher.publish(event);
            lifecyclePublisher.publish(event);
        };
        return switch (properties.type()) {
            case IN_MEMORY -> InMemoryWorkflowEngine.create(
                    definitions,
                    observedPublisher,
                    stepHandlers,
                    branchConditionEvaluator,
                    workflowStartEvents,
                    listenerInstances,
                    persistence,
                    eventCapturePolicy);
            case SQLITE, POSTGRESQL -> createJdbcEngine(properties, definitions, eventPublisher, stepHandlers,
                    branchConditionEvaluator, workflowStartEvents, listenerInstances, lifecycleObserver, clock,
                    eventCapturePolicy);
        };
    }

    private void validateConfiguration() {
        if (type != WorkflowEngine.Type.IN_MEMORY) {
            if (dataSource == null && (jdbcUrl == null || jdbcUrl.isBlank())) {
                throw new IllegalStateException(type + " durable mode requires jdbcUrl or dataSource");
            }
            if (persistence != null) {
                throw new IllegalStateException(type + " durable mode owns its JDBC persistence; do not also configure persistence(...)");
            }
        } else if (jdbcUrl != null || driver != null || dataSource != null || initializeSchema) {
            throw new IllegalStateException("JDBC configuration requires explicit SQLITE or POSTGRESQL engine type");
        }
    }

    private WorkflowEngineBuilder boundedBackoff(String prefix, long initial, long maximum) {
        if (initial > maximum) throw new IllegalArgumentException(prefix + " initial backoff must not exceed maximum backoff");
        boundedSetting(prefix + ".retry-initial-ms", initial, 10, 3_600_000);
        return boundedSetting(prefix + ".retry-maximum-ms", maximum, 10, 86_400_000);
    }

    private WorkflowEngineBuilder boundedSetting(String key, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return setting(key, Long.toString(value));
    }

    /**
     * Builds an engine and installs it as the process-wide default. The caller retains responsibility for closing
     * it.
     * @return the configured engine; the caller must close it
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    public WorkflowEngine buildAndSetInstance() throws ClassNotFoundException {
        WorkflowEngine engine = build();
        WorkflowEngine.setInstance(engine);
        return engine;
    }

    @SuppressWarnings("java:S107") // Reflection bridges the optional JDBC module without a core dependency.
    private static WorkflowEngine createJdbcEngine(
            WorkflowEngineProperties properties,
            WorkflowDefinitionRegistry definitions,
            EventPublisher eventPublisher,
            Map<String, StepHandler> stepHandlers,
            BranchConditionEvaluator branchConditionEvaluator,
            Map<String, String> workflowStartEvents,
            Map<String, Object> listenerInstances,
            WorkflowLifecycleObserver lifecycleObserver,
            Clock clock,
            EventCapturePolicy eventCapturePolicy
    ) throws ClassNotFoundException {
        Class<?> engineClass = Class.forName("org.jworkflow.jdbc.JdbcWorkflowEngine");
        try {
            Object engine = engineClass.getMethod(
                            "create",
                            WorkflowEngine.Type.class,
                            String.class,
                            String.class,
                            String.class,
                            Driver.class,
                            DataSource.class,
                            Boolean.TYPE,
                            WorkflowDefinitionRegistry.class,
                            EventPublisher.class,
                            Map.class,
                            BranchConditionEvaluator.class,
                            Map.class,
                            Map.class,
                            Map.class,
                            WorkflowLifecycleObserver.class,
                            Clock.class,
                            EventCapturePolicy.class)
                    .invoke(
                            null,
                            properties.type(),
                            properties.jdbcUrl(),
                            properties.username(),
                            properties.password(),
                            properties.driver(),
                            properties.dataSource(),
                            properties.initializeSchema(),
                            definitions,
                            eventPublisher,
                            stepHandlers,
                            branchConditionEvaluator,
                            workflowStartEvents,
                            properties.settings(),
                            listenerInstances,
                            lifecycleObserver,
                            clock,
                            eventCapturePolicy);
            return (WorkflowEngine) engine;
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("jworkflow-jdbc is present but does not expose JdbcWorkflowEngine.create", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ClassNotFoundException classNotFoundException) {
                throw classNotFoundException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to create JDBC workflow engine", cause);
        }
    }
}
