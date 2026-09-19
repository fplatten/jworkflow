package org.jworkflow.example;

import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.inbox.*;
import org.jworkflow.jdbc.JdbcWorkflowEngine;
import org.jworkflow.model.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** The same close/recreate, inbox-driven order example on both durable backends. */
final class DurableOrderInboxRestartScenario {
    static void run(Supplier<WorkflowEngineBuilder> builders) throws Exception {
        WorkflowDefinition definition=WorkflowDefinition.of("durable-order","1.0.0","awaiting-approval",
                WorkflowNode.waitFor("awaiting-approval",new WaitDefinition(new EventName("order.approved"),"businessKey","completed"),null),
                WorkflowNode.end("completed"));
        var start=new StartWorkflowCommand("durable-order","1.0.0","order-1001",
                Map.of("lines",List.of("sku-1","sku-2")),metadata("create-order-1001",null));
        WorkflowInstanceId id; UUID inboxId; String revision;
        InboxMessage approval=new InboxMessage(null,"approval-1001","orders",EventMessage.json(Map.of("approved",true)),
                "order-1001",null,Instant.now(),null,InboxMessageStatus.RECEIVED,0,null,null,null,null);
        try(var first=(JdbcWorkflowEngine)builders.get().definition(definition).build()) {
            id=first.start(start).workflowInstanceId(); revision=first.snapshot(id).workflowRevision();
            try(var inbox=first.inbox(message->List.of())){inboxId=inbox.accept(approval).message().messageId();}
            assertEquals("awaiting-approval",first.snapshot(id).state());
        }
        // New engine, no definition registration and no object state copied from the closed engine.
        try(var restarted=(JdbcWorkflowEngine)builders.get().build();
            var inbox=restarted.inbox(message->List.of(new SignalWorkflowCommand(id,
                    new WorkflowSignal("order.approved",message.correlationId(),message.causationId(),"order-1001",message.receivedAt(),Map.of(),message.message()),
                    metadata("approve-order-1001",id))))) {
            assertEquals(revision,restarted.snapshot(id).workflowRevision());
            assertEquals(List.of("sku-1","sku-2"),restarted.snapshot(id).variables().get("lines"));
            assertEquals(1,inbox.pollOnce());
            assertEquals(InboxMessageStatus.PROCESSED,inbox.find(inboxId).orElseThrow().status());
            assertEquals(1,inbox.attempts(inboxId).size());
            assertFalse(inbox.acceptAndProcess(approval).inserted());
            assertEquals(1,inbox.attempts(inboxId).size());
            assertEquals(WorkflowStatus.COMPLETED,restarted.snapshot(id).status());
        }
        List<UUID> published=new ArrayList<>();
        try(var publisherEngine=(JdbcWorkflowEngine)builders.get().build();
            var outbox=publisherEngine.outbox(Map.of("workflow.events",message->published.add(message.messageId())))) {
            var replay=publisherEngine.start(start);
            assertTrue(replay.idempotentRepeat()); assertEquals(id,replay.workflowInstanceId());
            assertEquals(WorkflowStatus.COMPLETED,publisherEngine.snapshot(id).status());
            assertTrue(outbox.pollOnce()>=2,"start and approval events must survive both engine restarts");
            assertEquals(0,outbox.pollOnce());
            assertEquals(published.size(),new HashSet<>(published).size());
            for(UUID messageId:published){
                assertEquals(org.jworkflow.outbox.OutboxMessageStatus.PUBLISHED,outbox.find(messageId).orElseThrow().status());
                assertEquals(1,outbox.attempts(messageId).size());
            }
        }
    }
    private static WorkflowCommandMetadata metadata(String key,WorkflowInstanceId id){
        return new WorkflowCommandMetadata(null,key,"durable-order","1.0.0",id,"order-1001","order-1001",null,null,null,"example",null,null,Map.of());
    }
}
