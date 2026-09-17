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

public final class InboxProcessingService {
    private final InboxRepository inbox;private final EventStatusRepository statuses;private final WorkflowTransactionManager transactions;
    private final InboxEventTranslator translator;private final InboxCommandDispatcher dispatcher;private final Clock clock;
    public InboxProcessingService(InboxRepository inbox,EventStatusRepository statuses,WorkflowTransactionManager transactions,InboxEventTranslator translator,InboxCommandDispatcher dispatcher,Clock clock){this.inbox=Objects.requireNonNull(inbox);this.statuses=Objects.requireNonNull(statuses);this.transactions=Objects.requireNonNull(transactions);this.translator=Objects.requireNonNull(translator);this.dispatcher=Objects.requireNonNull(dispatcher);this.clock=Objects.requireNonNull(clock);}
    public InboxProcessingResult process(InboxMessage claimed,String owner){
        if(claimed.status()!=InboxMessageStatus.CLAIMED||!Objects.equals(owner,claimed.claimedBy()))throw new InboxClaimException("Inbox message is not claimed by "+owner+": "+claimed.messageId());
        return transactions.inTransaction(()->{List<? extends Command> commands=translator.translate(claimed);if(commands==null||commands.isEmpty())throw new InboxStateException("Inbox translator produced no commands for "+claimed.messageId());
            ArrayList<Object> results=new ArrayList<>();WorkflowInstanceId workflowId=null;for(Command command:commands){Object result=dispatcher.dispatch(command);results.add(result);if(result instanceof StartWorkflowResult r)workflowId=r.workflowInstanceId();else if(result instanceof WorkflowCommandResult r)workflowId=r.workflowInstanceId();else if(result instanceof WorkflowRoutingResult r&&!r.routedInstances().isEmpty())workflowId=r.routedInstances().get(0);}
            int attempt=claimed.attemptCount()+1;inbox.markProcessed(claimed.messageId(),owner,clock.instant());inbox.appendAttempt(new InboxAttempt(null,claimed.messageId(),attempt,InboxMessageStatus.PROCESSED,null,null,clock.instant()));
            statuses.append(status(claimed,attempt,workflowId,EventStatusValue.SUCCESSFUL,false,null,null));return new InboxProcessingResult(claimed.messageId(),results);});
    }
    public EventStatusAttempt status(InboxMessage message,int attempt,WorkflowInstanceId workflowId,EventStatusValue value,boolean retry,java.time.Instant next,String error){UUID eventId=UUID.nameUUIDFromBytes(message.deduplicationKey().getBytes(StandardCharsets.UTF_8));return new EventStatusAttempt(null,eventId,null,attempt,message.deduplicationKey(),workflowId,message.correlationId(),EventStatusScope.INBOX,"inbox-command-dispatcher","",value,attempt-1,next,retry,value==EventStatusValue.DEAD_LETTERED,error==null?null:"inbox_processing_failed",error,clock.instant());}
}
