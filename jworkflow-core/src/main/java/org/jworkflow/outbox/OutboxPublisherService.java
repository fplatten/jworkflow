package org.jworkflow.outbox;

import org.jworkflow.application.RetryBackoffPolicy;
import org.jworkflow.events.*;
import org.jworkflow.persistence.*;
import org.jworkflow.observability.*;
import java.time.Clock;
import java.util.Objects;

/** Publishes outside a transaction, then records the immutable attempt in a short transaction. */
public final class OutboxPublisherService {
    private static final String TEXT_PUBLICATION_FAILED = "publication_failed";
    private final OutboxRepository outbox;
        private final EventStatusRepository statuses;
        private final WorkflowTransactionManager transactions;
    private final DestinationPublisher publisher;
        private final RetryBackoffPolicy retry;
        private final Clock clock;
    private final WorkflowLifecycleObserver observer;
    public OutboxPublisherService(OutboxRepository outbox,EventStatusRepository statuses,WorkflowTransactionManager transactions,DestinationPublisher publisher,RetryBackoffPolicy retry,Clock clock){this(outbox,statuses,transactions,publisher,retry,clock,NoOpWorkflowLifecycleObserver.INSTANCE);
    }
    public OutboxPublisherService(OutboxRepository outbox,EventStatusRepository statuses,WorkflowTransactionManager transactions,DestinationPublisher publisher,RetryBackoffPolicy retry,Clock clock,WorkflowLifecycleObserver observer){this.outbox=Objects.requireNonNull(outbox);
        this.statuses=Objects.requireNonNull(statuses);
        this.transactions=Objects.requireNonNull(transactions);
        this.publisher=Objects.requireNonNull(publisher);
        this.retry=Objects.requireNonNull(retry);
        this.clock=Objects.requireNonNull(clock);
        this.observer=SafeWorkflowLifecycleObserver.isolate(Objects.requireNonNull(observer));
    }
    public OutboxMessage publish(OutboxMessage claimed,String owner){if(claimed.status()!=OutboxMessageStatus.CLAIMED||!Objects.equals(owner,claimed.claimedBy()))throw new OutboxClaimException("Outbox message is not claimed by "+owner+": "+claimed.messageId());
        int attempt=claimed.attemptCount()+1;
        try{publisher.publish(claimed);
        }catch(Exception failure){return recordFailure(claimed,owner,attempt,failure);
        }
        OutboxMessage published=transactions.inTransaction(()->{outbox.appendAttempt(new OutboxAttempt(null,claimed.messageId(),attempt,OutboxMessageStatus.PUBLISHED,null,null,clock.instant()));
            statuses.append(status(claimed,attempt,EventStatusValue.SUCCESSFUL,false,null,null));
            outbox.markPublished(claimed.messageId(),owner,clock.instant());
            return outbox.findById(claimed.messageId()).orElseThrow();
        });
            observe(WorkflowLifecycleEventType.OUTBOX_PUBLISHED,published,null);
            return published;
        }
    private OutboxMessage recordFailure(OutboxMessage message,String owner,int attempt,Exception failure){String error=safe(failure);
        boolean exhausted=retry.exhausted(attempt);
        java.time.Instant next=exhausted?null:retry.nextAttemptAt(attempt,clock.instant());
        OutboxMessage failed=transactions.inTransaction(()->{OutboxMessageStatus state=exhausted?OutboxMessageStatus.DEAD_LETTER:OutboxMessageStatus.RETRY_SCHEDULED;
        outbox.appendAttempt(new OutboxAttempt(null,message.messageId(),attempt,state,TEXT_PUBLICATION_FAILED,error,clock.instant()));
        statuses.append(status(message,attempt,exhausted?EventStatusValue.DEAD_LETTERED:EventStatusValue.RETRYING,!exhausted,next,error));
        if(exhausted)outbox.markDeadLetter(message.messageId(),owner,error,clock.instant());
        else outbox.scheduleRetry(message.messageId(),owner,next,error);
        return outbox.findById(message.messageId()).orElseThrow();
    });
        observe(WorkflowLifecycleEventType.OUTBOX_PUBLICATION_FAILED,failed,TEXT_PUBLICATION_FAILED);
        return failed;
    }
    private void observe(WorkflowLifecycleEventType type,OutboxMessage message,String category){java.util.LinkedHashMap<String,String>a=new java.util.LinkedHashMap<>();
        a.put("destination",message.destination());
        a.put("status",message.status().name());
        if(category!=null)a.put("failureCategory",category);
        observer.observe(new WorkflowLifecycleEvent(type,clock.instant(),null,null,null,null,null,message.correlationId(),message.causationId(),null,a));
    }
    private EventStatusAttempt status(OutboxMessage message,int attempt,EventStatusValue value,boolean retryable,java.time.Instant next,String error){return new EventStatusAttempt(null,message.eventId(),null,attempt,message.idempotencyKey(),null,message.correlationId(),EventStatusScope.OUTBOX,"destination-publisher",message.destination(),value,attempt-1,next,retryable,value==EventStatusValue.DEAD_LETTERED,error==null?null:TEXT_PUBLICATION_FAILED,error,clock.instant());
    }
    private static String safe(Throwable failure){String message=failure.getMessage();
        return message==null?failure.getClass().getSimpleName():message.substring(0,Math.min(500,message.length()));
    }
}
