package org.jworkflow.jdbc;

import org.jworkflow.events.EventMessage;
import org.jworkflow.inbox.*;
import org.jworkflow.outbox.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Duplicate identity/content fixtures shared with SQLite regression coverage. */
final class DuplicateRepositoryContract {
    enum Kind {
        INBOX("workflow_inbox"), OUTBOX("workflow_outbox"), DEFINITION("workflow_definition"), COMMAND("workflow_command_result");
        final String table;
        Kind(String table){this.table=table;}
    }
    static final Instant NOW=Instant.parse("2026-09-18T12:00:00.123456789Z");
    record Saved(Object value,Boolean inserted) { }

    static Object sample(Kind kind,String key) {
        return switch(kind) {
            case INBOX -> new InboxMessage(UUID.randomUUID(),key,"source",message("winner"),"corr","cause",NOW,null,null,0,null,null,null,null);
            case OUTBOX -> new OutboxMessage(UUID.randomUUID(),UUID.randomUUID(),"destination",key,message("winner"),"corr","cause",NOW,null,null,0,null,null,null,null);
            case DEFINITION -> new WorkflowDefinition(key,"1","end",Map.of("end",WorkflowNode.end("end")),Map.of(),"source-one");
            case COMMAND -> new CommandResultRecord(key+"\u0000%", "start","hash",null,Map.of("answer","winner"),NOW);
        };
    }

    static Object duplicate(Object value,boolean conflict) {
        if(value instanceof InboxMessage m) return new InboxMessage(conflict?m.messageId():UUID.randomUUID(),
                conflict?m.externalEventId()+"-different-identity":m.externalEventId(),m.sourceSystem(),m.message(),m.correlationId(),m.causationId(),NOW.plusSeconds(1),null,null,0,null,null,null,null);
        if(value instanceof OutboxMessage m) return new OutboxMessage(UUID.randomUUID(),m.eventId(),m.destination(),m.idempotencyKey(),
                conflict?message("different"):m.message(),m.correlationId(),m.causationId(),NOW.plusSeconds(1),null,null,0,null,null,null,null);
        if(value instanceof WorkflowDefinition d) return new WorkflowDefinition(d.name(),d.version(),d.startNode(),d.nodes(),d.metadata(),conflict?"source-two":d.sourceText());
        CommandResultRecord r=(CommandResultRecord)value;
        return new CommandResultRecord(r.idempotencyKey(),r.commandType(),conflict?"different-hash":r.requestHash(),r.workflowInstanceId(),Map.of("answer","loser"),NOW.plusSeconds(1));
    }

    static Saved save(JdbcWorkflowPersistence ports,Object value) {
        if(value instanceof InboxMessage m){var result=ports.inbox().insertIfAbsent(m);return new Saved(result.message(),result.inserted());}
        if(value instanceof OutboxMessage m)return new Saved(ports.outbox().enqueue(m),null);
        if(value instanceof WorkflowDefinition d){ports.definitions().save(d);return new Saved(d,null);}
        return new Saved(ports.commandResults().save((CommandResultRecord)value),null);
    }

    static Object find(JdbcWorkflowPersistence ports,Object identity) {
        if(identity instanceof InboxMessage m)return ports.inbox().findByExternalIdentity(m.sourceSystem(),m.externalEventId()).orElseThrow();
        if(identity instanceof OutboxMessage m)return ports.outbox().findByIdempotencyKey(m.destination(),m.idempotencyKey()).orElseThrow();
        if(identity instanceof WorkflowDefinition d)return ports.definitions().findRevision(d.name(),d.version(),d.revision()).orElseThrow();
        return ports.commandResults().find(((CommandResultRecord)identity).idempotencyKey()).orElseThrow();
    }

    private static EventMessage message(String value){return new EventMessage(Map.of("value",value),"application/json","test","1",false,Map.of());}

    static void envelopeAndTypeConflicts(JdbcWorkflowPersistence ports) {
        var codec=new JdbcEventMessageCodec();
        for(Object payload:Arrays.asList(null,new byte[0],new byte[]{0,1,-1},Map.of("number",1L,"unicode","\u96ea"))) {
            OutboxMessage base=(OutboxMessage)sample(Kind.OUTBOX,UUID.randomUUID().toString());
            EventMessage message=new EventMessage(payload,"content/type","schema","1",false,Map.of("header","value"));
            OutboxMessage first=withEnvelope(base,message);ports.outbox().enqueue(first);
            OutboxMessage same=withEnvelope(base,new EventMessage(payload,"content/type","schema","1",false,Map.of("header","value")));
            var returned=ports.outbox().enqueue(same);assertEquals(first.messageId(),returned.messageId());assertTrue(codec.sameContent(first.message(),returned.message()));
            for(EventMessage changed:List.of(new EventMessage("different",message.contentType(),message.schemaName(),message.schemaVersion(),message.redacted(),message.attributes()),
                    new EventMessage(payload,"different",message.schemaName(),message.schemaVersion(),message.redacted(),message.attributes()),
                    new EventMessage(payload,message.contentType(),"different",message.schemaVersion(),message.redacted(),message.attributes()),
                    new EventMessage(payload,message.contentType(),message.schemaName(),"2",message.redacted(),message.attributes()),
                    new EventMessage(payload,message.contentType(),message.schemaName(),message.schemaVersion(),true,message.attributes()),
                    new EventMessage(payload,message.contentType(),message.schemaName(),message.schemaVersion(),false,Map.of("header","different"))))
                assertThrows(PersistenceConstraintException.class,()->ports.outbox().enqueue(withEnvelope(base,changed)));
        }
        CommandResultRecord winner=(CommandResultRecord)sample(Kind.COMMAND,"types");save(ports,winner);
        assertThrows(PersistenceConstraintException.class,()->save(ports,new CommandResultRecord(winner.idempotencyKey(),"cancel",winner.requestHash(),null,Map.of(),NOW)));
        assertEquals(winner,find(ports,winner));
    }

    private static OutboxMessage withEnvelope(OutboxMessage base,EventMessage message){return new OutboxMessage(base.messageId(),base.eventId(),base.destination(),base.idempotencyKey(),message,base.correlationId(),base.causationId(),base.createdAt(),null,base.status(),0,null,null,null,null);}
}
