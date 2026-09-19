package org.jworkflow.jdbc;

import org.jworkflow.application.Command;
import org.jworkflow.engine.*;
import org.jworkflow.events.EventStatusValue;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.observability.*;
import org.jworkflow.routing.RouteWorkflowEventCommand;
import org.jworkflow.routing.WorkflowRoutingException;
import org.jworkflow.routing.WorkflowRoutingOutcome;
import org.jworkflow.routing.InvalidWorkflowRouteException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** JDBC application adapter coordinating inbox leases with durable workflow commands. */
public final class JdbcInboxApplication implements AutoCloseable {
    private final JdbcWorkflowEngine engine;
        private final JdbcWorkflowPersistence persistence;
        private final Clock clock;
    private final InboxProcessingService processing;
        private final InboxReprocessingService reprocessing;
    private final InboxRetryPolicy retryPolicy;
        private final Duration lease;
        private final Duration pollInterval;
        private final int batchSize;
        private final String workerId=UUID.randomUUID().toString();
    private final WorkflowLifecycleObserver lifecycleObserver;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean reconciliationRequired=new AtomicBoolean();
        private ScheduledExecutorService poller;
    JdbcInboxApplication(JdbcWorkflowEngine engine,JdbcWorkflowPersistence persistence,InboxEventTranslator translator,Clock clock,Map<String,String> settings,WorkflowLifecycleObserver observer){
        this.engine=Objects.requireNonNull(engine);
            this.persistence=Objects.requireNonNull(persistence);
            this.clock=Objects.requireNonNull(clock);
        this.lifecycleObserver=SafeWorkflowLifecycleObserver.isolate(Objects.requireNonNull(observer));
        Map<String,String>s=settings==null?Map.of():settings;
            this.lease=Duration.ofMillis(number(s,"inbox.lease-ms",30_000,100,3_600_000));
            this.pollInterval=Duration.ofMillis(number(s,"inbox.poll-interval-ms",250,10,60_000));
            this.batchSize=(int)number(s,"inbox.batch-size",32,1,1_000);
        this.retryPolicy=new ExponentialInboxRetryPolicy((int)number(s,"inbox.max-attempts",5,1,100),Duration.ofMillis(number(s,"inbox.retry-initial-ms",1_000,10,3_600_000)),Duration.ofMillis(number(s,"inbox.retry-maximum-ms",60_000,10,86_400_000)));
        this.processing=new InboxProcessingService(persistence.inbox(),persistence.eventStatuses(),persistence.transactions(),translator,this::dispatch,clock);
            this.reprocessing=new InboxReprocessingService(persistence.inbox(),persistence.transactions());
    }
    public InboxInsertResult accept(InboxMessage message){message=capture(Objects.requireNonNull(message));
        InboxMessage safe=message;
        return persistence.jdbcTransactions().inWriteTransaction(()->persistence.inbox().insertIfAbsent(safe));
    }
    public InboxInsertResult acceptAndProcess(InboxMessage message){InboxInsertResult accepted=accept(message);
        if(accepted.inserted())pollOnce();
        return accepted;
    }
    public int pollOnce(){
        if(reconciliationRequired.get())throw new WorkflowInfrastructureException("Inbox polling paused: reconcile the failed transaction before recreating this worker",null);
        Instant now=clock.instant();
        persistence.transactions().execute(()->persistence.inbox().releaseExpiredClaims(now));
        List<InboxMessage> claimed=persistence.jdbcTransactions().inWriteTransaction(()->persistence.inbox().claimEligibleFenced(now,workerId,now.plus(lease),batchSize));
        for(InboxMessage message:claimed)process(message);
        return claimed.size();
    }
    public InboxMessage reprocess(ReprocessInboxMessageCommand command){InboxMessage message=reprocessing.reprocess(command);
        pollOnce();
        return persistence.inbox().findById(message.messageId()).orElseThrow();
    }
    public void start(){if(poller!=null)return;
        poller=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"jworkflow-jdbc-inbox-"+workerId.substring(0,8));
        t.setDaemon(true);
        return t;
    });
        poller.scheduleWithFixedDelay(this::pollSafely,pollInterval.toMillis(),pollInterval.toMillis(),TimeUnit.MILLISECONDS);
    }
    private void pollSafely(){if(closed.get())return;
        try{pollOnce();
    }catch(RuntimeException ignored){
            // Durable claims remain retryable; the next scheduled poll will try again.
        }}
    private void process(InboxMessage message){try{
        persistence.jdbcTransactions().inWriteTransaction(() -> processing.process(message,workerId));
    }catch(RuntimeException failure){
        try{finalizeFailure(message,failure);}
        catch(RuntimeException finalizationFailure){
            if(JdbcTransactionException.requiresReconciliation(failure))reconciliationRequired.set(true);
            failure.addSuppressed(finalizationFailure);throw failure;
        }
    }}
    private void finalizeFailure(InboxMessage message,RuntimeException failure){int attempt=message.attemptCount()+1;
        String error=safe(failure);
        String errorCode=failure instanceof WorkflowRoutingException routing?"workflow_routing_"+routing.outcome().name().toLowerCase(java.util.Locale.ROOT):"inbox_processing_failed";
        boolean exhausted=retryPolicy.exhausted(attempt)||JdbcTransactionException.requiresReconciliation(failure);
        Instant next=exhausted?null:retryPolicy.nextAttemptAt(attempt,clock.instant());
        persistence.transactions().execute(()->{
        persistence.inbox().requireClaim(message.messageId(),workerId,message.claimToken());
        InboxMessageStatus status=exhausted?InboxMessageStatus.DEAD_LETTER:InboxMessageStatus.RETRY_SCHEDULED;
            persistence.inbox().appendAttempt(new InboxAttempt(null,message.messageId(),attempt,status,errorCode,error,clock.instant()));
        persistence.eventStatuses().append(processing.status(message,attempt,null,exhausted?EventStatusValue.DEAD_LETTERED:EventStatusValue.RETRYING,!exhausted,next,error));
        if(exhausted)persistence.inbox().markDeadLetter(message.messageId(),workerId,message.claimToken(),error,clock.instant());
            else persistence.inbox().scheduleRetry(message.messageId(),workerId,message.claimToken(),next,error);
        });
            if(exhausted)persistence.transactions().afterCommit(()->lifecycleObserver.observe(new WorkflowLifecycleEvent(WorkflowLifecycleEventType.INBOX_DEAD_LETTERED,clock.instant(),null,null,null,null,null,message.correlationId(),message.causationId(),null,Map.of("sourceSystem",message.sourceSystem(),"failureCategory","inbox_processing_failed"))));
        }
    private Object dispatch(Command command){if(command instanceof StartWorkflowCommand c)return engine.start(c);
        if(command instanceof SignalWorkflowCommand c)return engine.signal(c);
        if(command instanceof RouteWorkflowEventCommand c){var result=engine.route(c.event(),c.route());
        if(result.outcome()!=WorkflowRoutingOutcome.ROUTED)throw new InvalidWorkflowRouteException(result.outcome(),result.detail());
        return result;
    }
    if(command instanceof RetryFailedStepCommand c) {
        return engine.retryFailedStep(c);
    }
        if(command instanceof CancelWorkflowCommand c) {
            return engine.cancel(c);
        }
        if(command instanceof ResumeWorkflowCommand c) {
            return engine.resume(c);
        }
        throw new InboxStateException("Unsupported inbox command: "+command.getClass().getName());
    }
    public Optional<InboxMessage> find(UUID id){return persistence.inbox().findById(id);
    }public List<InboxAttempt> attempts(UUID id){return persistence.inbox().findAttempts(id);
    }
    private InboxMessage capture(InboxMessage message){WorkflowEvent filtered=engine.capture(new WorkflowEvent(new EventMetadata(null,new EventName("inbox.received"),message.sourceSystem(),message.correlationId(),message.causationId(),null,null,null,null,"1",message.receivedAt(),message.receivedAt(),Map.of()),message.message()));
        return new InboxMessage(message.messageId(),message.externalEventId(),message.sourceSystem(),filtered.message(),filtered.metadata().correlationId(),filtered.metadata().causationId(),message.receivedAt(),message.processedAt(),message.status(),message.attemptCount(),message.nextAttemptAt(),message.lastError(),message.claimedBy(),message.claimUntil(),message.claimToken());
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;
        if(poller!=null){poller.shutdown();
        try{if(!poller.awaitTermination(5,TimeUnit.SECONDS))poller.shutdownNow();
    }catch(InterruptedException e){poller.shutdownNow();
        Thread.currentThread().interrupt();
    }}}
    private static String safe(Throwable t){String m=t.getMessage();
        return m==null?t.getClass().getSimpleName():m.substring(0,Math.min(500,m.length()));
    }private static long number(Map<String,String>s,String key,long fallback,long min,long max){long value=Long.parseLong(s.getOrDefault(key,Long.toString(fallback)));
        if(value<min||value>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max);
        return value;
    }
}
