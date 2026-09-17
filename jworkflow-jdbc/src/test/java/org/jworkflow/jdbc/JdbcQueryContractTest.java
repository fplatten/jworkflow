package org.jworkflow.jdbc;

import org.jworkflow.events.*;
import org.jworkflow.engine.WorkflowEngine;
import org.jworkflow.model.*;
import org.jworkflow.outbox.*;
import org.jworkflow.query.*;
import org.sqlite.JDBC;
import java.nio.file.Files;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;

public final class JdbcQueryContractTest {
 private static final Instant NOW=Instant.parse("2026-09-17T12:00:00Z");
 public static void main(String[]args)throws Exception{queriesCompleteReadModelsWithoutMutation();customProjectionsSubscribeAndReplay();}
 private static void queriesCompleteReadModelsWithoutMutation()throws Exception{
  Fixture f=fixture();WorkflowDefinition definition=definition();f.persistence.definitions().save(definition);
  WorkflowSnapshot waiting=snapshot("wait-order","corr-w",WorkflowStatus.RUNNING,"waiting",NOW.minusSeconds(600),definition),failed=snapshot("failed-order","corr-f",WorkflowStatus.FAILED,"done",NOW.minusSeconds(500),definition),completed=snapshot("done-order","corr-d",WorkflowStatus.COMPLETED,"done",NOW.minusSeconds(100),definition);
  f.persistence.instances().insert(waiting);f.persistence.instances().insert(failed);f.persistence.instances().insert(completed);
  WorkflowEvent started=event(waiting,"workflow.started",NOW.minusSeconds(600)),received=event(waiting,"approval.received",NOW.minusSeconds(550));f.persistence.events().append(started);f.persistence.events().append(received);
  WorkflowTimer timer=new WorkflowTimer(null,waiting.instanceId(),"waiting",NOW.plusSeconds(60),"done",new EventName("approval.expired"),WorkflowTimerStatus.PENDING,0,NOW.plusSeconds(60),null,null,NOW,NOW);f.persistence.timers().save(timer);
  OutboxMessage outbox=new OutboxMessage(null,received.metadata().eventId(),"audit.topic","query-key",received.message(),"corr-w",started.metadata().eventId().toString(),NOW,null,null,0,null,null,null,null);f.persistence.outbox().enqueue(outbox);
  int instances=count(f,"workflow_instance"),events=count(f,"workflow_event"),timers=count(f,"workflow_timer"),outboxes=count(f,"workflow_outbox");long lock=waiting.lockVersion();WorkflowQueryService queries=new PersistenceWorkflowQueryService(f.persistence);
  check(queries.list(2,0).size()==2&&queries.list(2,2).size()==1,"pagination must include all lifecycle states");
  check(queries.findByBusinessKey("query-flow","wait-order").orElseThrow().snapshot().instanceId().equals(waiting.instanceId()),"business-key detail lookup failed");check(queries.findByCorrelationId("corr-w").orElseThrow().snapshot().instanceId().equals(waiting.instanceId()),"correlation detail lookup failed");
  check(queries.failed(10).size()==1&&queries.failed(10).get(0).instanceId().equals(failed.instanceId()),"failed query must return failed workflows only");check(queries.stuck(NOW.minusSeconds(300),10).size()==1&&queries.stuck(NOW.minusSeconds(300),10).get(0).instanceId().equals(waiting.instanceId()),"stuck query must return old active workflows only");
  WorkflowTimeline timeline=queries.timeline(waiting.instanceId());check(timeline.entries().size()==2&&timeline.entries().get(0).eventName().equals("workflow.started")&&timeline.entries().get(1).eventName().equals("approval.received"),"timeline must be complete and ordered");
  WorkflowDetail detail=queries.findById(waiting.instanceId()).orElseThrow();check(detail.pendingWait().orElseThrow().eventName().equals("approval.received")&&detail.pendingTimers().size()==1&&detail.timeline().entries().size()==2,"detail must combine current state, wait, timers, and timeline");
  check(queries.pendingWaits(10).size()==1&&queries.pendingTimers(10).size()==1,"pending wait/timer views failed");check(queries.pendingOutbox(10).size()==1&&queries.pendingOutbox(10).get(0).messageId().equals(outbox.messageId()),"pending outbox view failed");
  check(count(f,"workflow_instance")==instances&&count(f,"workflow_event")==events&&count(f,"workflow_timer")==timers&&count(f,"workflow_outbox")==outboxes,"queries must not mutate persistence");check(f.persistence.instances().findById(waiting.instanceId()).orElseThrow().lockVersion()==lock,"queries must not advance lock versions");
  try(var engine=WorkflowEngine.builder().type(WorkflowEngine.Type.SQLITE).jdbcUrl(f.url).initialize(true).timerPolling(false).build()){check(engine.queries().list(10,0).size()==3,"durable engine must expose the persistence-backed query API");}
 }
 private static void customProjectionsSubscribeAndReplay()throws Exception{Fixture f=fixture();WorkflowDefinition definition=definition();f.persistence.definitions().save(definition);WorkflowSnapshot snapshot=snapshot("projection","corr-p",WorkflowStatus.RUNNING,"waiting",NOW,definition);f.persistence.instances().insert(snapshot);WorkflowEvent one=event(snapshot,"workflow.started",NOW),two=event(snapshot,"approval.received",NOW.plusSeconds(1));f.persistence.events().append(one);f.persistence.events().append(two);ArrayList<String> replayed=new ArrayList<>();new PersistenceWorkflowQueryService(f.persistence).replay(snapshot.instanceId(),e->replayed.add(e.eventName().value()));check(replayed.equals(List.of("workflow.started","approval.received")),"projection replay must be ordered");WorkflowProjectionPublisher live=new WorkflowProjectionPublisher();AtomicInteger deliveries=new AtomicInteger();EventSubscription subscription=live.subscribe(e->deliveries.incrementAndGet());live.publish(one);subscription.unsubscribe();live.publish(two);check(deliveries.get()==1,"live projection subscribe/unsubscribe failed");}
 private static WorkflowDefinition definition(){return WorkflowDefinition.of("query-flow","1","waiting",WorkflowNode.waitFor("waiting",new WaitDefinition(new EventName("approval.received"),"businessKey","done"),null),WorkflowNode.end("done"));}
 private static WorkflowSnapshot snapshot(String business,String correlation,WorkflowStatus status,String state,Instant updated,WorkflowDefinition d){return new WorkflowSnapshot(WorkflowInstanceId.random(),d.name(),d.version(),d.revision(),business,correlation,state,status,Map.of("businessKey",business),0,NOW.minusSeconds(700),updated);}
 private static WorkflowEvent event(WorkflowSnapshot s,String name,Instant at){return new WorkflowEvent(new EventMetadata(null,new EventName(name),"query-test",s.correlationId(),null,"trace",s.instanceId(),s.businessKey(),null,"1",at,at,Map.of("view","test")),new EventMessage(Map.of("businessKey",s.businessKey()),"application/json","query-event","1",false,Map.of()));}
 private static Fixture fixture()throws Exception{String url="jdbc:sqlite:"+Files.createTempFile("jworkflow-query-",".sqlite").toAbsolutePath();return new Fixture(url,JdbcWorkflowPersistence.create(url,null,null,new JDBC(),null,true,Map.of()));}private static int count(Fixture f,String table)throws Exception{try(var c=java.sql.DriverManager.getConnection(f.url);var s=c.createStatement();var r=s.executeQuery("select count(*) from "+table)){return r.next()?r.getInt(1):0;}}private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}private record Fixture(String url,JdbcWorkflowPersistence persistence){}
}
