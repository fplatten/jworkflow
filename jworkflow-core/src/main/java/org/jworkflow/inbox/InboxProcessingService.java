package org.jworkflow.inbox;

import org.jworkflow.application.Command;
import org.jworkflow.engine.StartWorkflowResult;
import org.jworkflow.engine.WorkflowCommandResult;
import org.jworkflow.events.*;
import org.jworkflow.model.WorkflowInstanceId;
import org.jworkflow.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.jworkflow.routing.WorkflowRoutingResult;

/**
 * Translates and dispatches a leased inbox message in the shared transaction, then appends successful
 * attempt/status history and completes it. A token guard protects dependent writes. Failures propagate so the
 * enclosing worker can apply its retry policy in a separate transaction.
 */
public final class InboxProcessingService {
    private final InboxRepository inbox;
        private final EventStatusRepository statuses;
        private final WorkflowTransactionManager transactions;
    private final InboxEventTranslator translator;
        private final InboxCommandDispatcher dispatcher;
        private final Clock clock;
    /**
     * Constructs InboxProcessingService with the supplied collaborators and configuration.
     * @param inbox durable inbound-message repository
     * @param statuses append-only event-status repository
     * @param transactions shared transaction manager coordinating related writes
     * @param translator mapping from an inbox envelope to commands or an explicit route
     * @param dispatcher application command executor used by inbox processing
     * @param clock clock used for recorded times and lease/retry decisions
     * @throws NullPointerException if inbox, statuses, transactions, translator, dispatcher, clock is null
     */
    public InboxProcessingService(InboxRepository inbox,EventStatusRepository statuses,WorkflowTransactionManager transactions,InboxEventTranslator translator,InboxCommandDispatcher dispatcher,Clock clock){this.inbox=Objects.requireNonNull(inbox);
        this.statuses=Objects.requireNonNull(statuses);
        this.transactions=Objects.requireNonNull(transactions);
        this.translator=Objects.requireNonNull(translator);
        this.dispatcher=Objects.requireNonNull(dispatcher);
        this.clock=Objects.requireNonNull(clock);
    }
    /**
     * Validates the acquisition generation, dispatches translated commands and records success atomically. A stale
     * guard rolls back dependent writes.
     * @param claimed message returned by lease acquisition, including its generation token
     * @param owner worker identity that acquired the lease
     * @return the resulting inbox processing result
     */
    public InboxProcessingResult process(InboxMessage claimed,String owner){
        if(claimed.status()!=InboxMessageStatus.CLAIMED||!Objects.equals(owner,claimed.claimedBy()))throw new InboxClaimException("Inbox message is not claimed by "+owner+": "+claimed.messageId());
        return transactions.inTransaction(()->{
            inbox.requireClaim(claimed.messageId(),owner,claimed.claimToken());
            List<Command> commands=translator.translate(claimed);
            if(commands==null||commands.isEmpty())throw new InboxStateException("Inbox translator produced no commands for "+claimed.messageId());
            ArrayList<Object> results=new ArrayList<>();
                WorkflowInstanceId workflowId=null;
                for(Command command:commands){Object result=dispatcher.dispatch(command);
                results.add(result);
                if(result instanceof StartWorkflowResult r)workflowId=r.workflowInstanceId();
                else if(result instanceof WorkflowCommandResult r)workflowId=r.workflowInstanceId();
                else if(result instanceof WorkflowRoutingResult r&&!r.routedInstances().isEmpty())workflowId=r.routedInstances().get(0);
            }
            int attempt=claimed.attemptCount()+1;
                inbox.markProcessed(claimed.messageId(),owner,claimed.claimToken(),clock.instant());
                inbox.appendAttempt(new InboxAttempt(null,claimed.messageId(),attempt,InboxMessageStatus.PROCESSED,null,null,clock.instant()));
            statuses.append(status(claimed,attempt,workflowId,EventStatusValue.SUCCESSFUL,false,null,null));
                return new InboxProcessingResult(claimed.messageId(),results);
            });
    }
    /**
     * Builds an append-only event-status record from inbox processing metadata.
     * @param message durable incoming message envelope
     * @param attempt one-based attempt number
     * @param workflowId workflow instance identity or business identifier used by the operation
     * @param value processing outcome to record
     * @param retry whether another processing attempt is scheduled
     * @param next deadline for the next eligible attempt
     * @param error failure detail to record or report
     * @return the resulting event status attempt
     */
    public EventStatusAttempt status(InboxMessage message,int attempt,WorkflowInstanceId workflowId,EventStatusValue value,boolean retry,java.time.Instant next,String error){UUID eventId=UUID.nameUUIDFromBytes(message.deduplicationKey().getBytes(StandardCharsets.UTF_8));
        return new EventStatusAttempt(null,eventId,null,attempt,message.deduplicationKey(),workflowId,message.correlationId(),EventStatusScope.INBOX,"inbox-command-dispatcher","",value,attempt-1,next,retry,value==EventStatusValue.DEAD_LETTERED,error==null?null:"inbox_processing_failed",error,clock.instant());
    }
}
