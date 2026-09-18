package org.jworkflow.definition;

import org.jworkflow.events.EventName;
import org.jworkflow.engine.WorkflowEngineBuilder;
import org.jworkflow.engine.WorkflowValidationException;
import org.jworkflow.model.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Optional fluent Java authoring API. Groovy remains the preferred production process format. */
public final class WorkflowDefinitionBuilder {
    private static final String TEXT_NODE_NAME = "node name";
    private static final String TEXT_TARGET = "target";
    private final String name;
    private String version;
    private String startNode;
    private final LinkedHashMap<String,WorkflowNode> nodes=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> metadata=new LinkedHashMap<>();

    private WorkflowDefinitionBuilder(String name){this.name=text(name,"name");
    }
    public static WorkflowDefinitionBuilder workflow(String name){return new WorkflowDefinitionBuilder(name);
    }
    public WorkflowDefinitionBuilder version(String version){this.version=text(version,"version");
        return this;
    }
    public WorkflowDefinitionBuilder startAt(String node){this.startNode=text(node,"start node");
        return this;
    }
    public WorkflowDefinitionBuilder startWhen(String eventName){metadata.put("startEvent",new EventName(eventName).value());
        return this;
    }
    public WorkflowDefinitionBuilder correlateBy(String field){metadata.put("correlateBy",text(field,"correlation field"));
        return this;
    }
    public WorkflowDefinitionBuilder metadata(String key,String value){metadata.put(text(key,"metadata key"),Objects.requireNonNull(value,"value"));
        return this;
    }

    public WorkflowDefinitionBuilder step(String name,Consumer<StepBuilder> configure){StepBuilder b=apply(new StepBuilder(name),configure);
        return add(b.build());
    }
    public WorkflowDefinitionBuilder waitFor(String name,Consumer<WaitBuilder> configure){WaitBuilder b=apply(new WaitBuilder(name),configure);
        return add(b.build());
    }
    public WorkflowDefinitionBuilder subWorkflow(String name,Consumer<SubWorkflowBuilder> configure){SubWorkflowBuilder b=apply(new SubWorkflowBuilder(name),configure);
        return add(b.build());
    }
    public WorkflowDefinitionBuilder gateway(String name,Consumer<GatewayBuilder> configure){GatewayBuilder b=apply(new GatewayBuilder(name),configure);
        return add(b.build());
    }
    /** A conditional branch is represented by the runtime's exclusive gateway model. */
    public WorkflowDefinitionBuilder branch(String name,Consumer<GatewayBuilder> configure){GatewayBuilder b=apply(new GatewayBuilder(name).type(GatewayType.EXCLUSIVE),configure);
        return add(b.build());
    }
    public WorkflowDefinitionBuilder fork(String name,Consumer<ForkBuilder> configure){ForkBuilder b=apply(new ForkBuilder(name),configure);
        return add(b.build());
    }
    public WorkflowDefinitionBuilder join(String name,Consumer<JoinBuilder> configure){JoinBuilder b=apply(new JoinBuilder(name),configure);
        return add(b.build());
    }
    public WorkflowDefinitionBuilder loop(String name,Consumer<LoopBuilder> configure){LoopBuilder b=apply(new LoopBuilder(name),configure);
        return add(b.build());
    }
    public WorkflowDefinitionBuilder end(String name){return add(WorkflowNode.end(name));
    }

    public WorkflowDefinition build(){
        if(version==null)throw invalid("Workflow version is required");
        if(startNode==null){if(metadata.containsKey("startEvent")&&!nodes.isEmpty())startNode=nodes.keySet().iterator().next();
            else throw invalid("Workflow start node is required");
        }
        if (!nodes.containsKey(startNode)) throw invalid("Start node does not exist: " + startNode);
        WorkflowDefinition definition=new WorkflowDefinition(name,version,startNode,nodes,metadata,null);
        new DefinitionValidator().validate(definition).throwIfInvalid();
            return definition;
    }
    public WorkflowEngineBuilder registerWith(WorkflowEngineBuilder engineBuilder){return Objects.requireNonNull(engineBuilder,"engineBuilder").definition(build());
    }
    private WorkflowDefinitionBuilder add(WorkflowNode node){if(nodes.putIfAbsent(node.name(),node)!=null)throw invalid("Duplicate workflow node: "+node.name());
        return this;
    }
    private static <T>T apply(T builder,Consumer<T> consumer){Objects.requireNonNull(consumer,"configure").accept(builder);
        return builder;
    }
    private static String text(String value,String field){if(value==null||value.isBlank())throw new IllegalArgumentException(field+" is required");
        return value;
    }
    private static WorkflowValidationException invalid(String message){return new WorkflowValidationException(List.of(message));
    }

    public abstract static class TransitionBuilder<T extends TransitionBuilder<T>>{
        final String name;
            final ArrayList<WorkflowTransition> transitions=new ArrayList<>();
            RetryPolicy retry;
            TimeoutDefinition timeout;
        TransitionBuilder(String name){this.name=text(name,TEXT_NODE_NAME);
        }
        @SuppressWarnings("unchecked") public T transition(String transitionName,String target){transitions.add(new WorkflowTransition(transitionName,text(target,TEXT_TARGET),null,null));
            return(T)this;
        }
        @SuppressWarnings("unchecked") public T onSuccess(String target){transitions.add(new WorkflowTransition("success",text(target,TEXT_TARGET),null,null));
            return(T)this;
        }
        @SuppressWarnings("unchecked") public T onSuccess(String event,String target){transitions.add(new WorkflowTransition("success",text(target,TEXT_TARGET),null,new EventName(event)));
            return(T)this;
        }
        @SuppressWarnings("unchecked") public T onFailure(String target){transitions.add(new WorkflowTransition("failure",text(target,TEXT_TARGET),null,null));
            return(T)this;
        }
        @SuppressWarnings("unchecked") public T onFailure(String event,String target){transitions.add(new WorkflowTransition("failure",text(target,TEXT_TARGET),null,new EventName(event)));
            return(T)this;
        }
        @SuppressWarnings("unchecked") public T retry(Consumer<RetryBuilder> configure){retry=apply(new RetryBuilder(),configure).build();
            return(T)this;
        }
        @SuppressWarnings("unchecked") public T timeout(Consumer<TimeoutBuilder> configure){timeout=apply(new TimeoutBuilder(),configure).build();
            return(T)this;
        }
    }
    public static final class StepBuilder extends TransitionBuilder<StepBuilder>{String action;
        String listenerId;
        String listenerMethod;
        StepBuilder(String n){super(n);
    }public StepBuilder action(String value){action=text(value,"action");
        return this;
    }public StepBuilder listener(String id,String method){listenerId=text(id,"listener ID");
        listenerMethod=text(method,"listener method");
        return this;
    }WorkflowNode build(){if(action==null&&listenerId==null)throw invalid("Step "+name+" requires an action or listener");
        return new WorkflowNode(name,WorkflowNodeType.STEP,action,listenerId,listenerMethod,null,null,null,null,null,null,retry,timeout,transitions,null);
    }}
    public static final class RetryBuilder{int attempts;
        Duration backoff=Duration.ZERO;
        public RetryBuilder maxAttempts(int value){attempts=value;
        return this;
    }public RetryBuilder backoff(Duration value){backoff=Objects.requireNonNull(value);
        return this;
    }RetryPolicy build(){return new RetryPolicy(attempts,backoff);
    }}
    public static final class TimeoutBuilder{Duration duration;
        String target;
        EventName event;
        public TimeoutBuilder after(Duration value){duration=Objects.requireNonNull(value);
        return this;
    }public TimeoutBuilder goTo(String value){target=text(value,"timeout target");
        return this;
    }public TimeoutBuilder emit(String value){event=new EventName(value);
        return this;
    }TimeoutDefinition build(){return new TimeoutDefinition(duration,target,event);
    }}
    public static final class WaitBuilder{final String name;
        EventName event;
        String correlateBy;
        String target;
        TimeoutDefinition timeout;
        WaitBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }public WaitBuilder event(String value){event=new EventName(value);
        return this;
    }public WaitBuilder correlateBy(String value){correlateBy=text(value,"correlateBy");
        return this;
    }public WaitBuilder then(String value){target=text(value,"wait target");
        return this;
    }public WaitBuilder timeout(Consumer<TimeoutBuilder> c){timeout=apply(new TimeoutBuilder(),c).build();
        return this;
    }WorkflowNode build(){return WorkflowNode.waitFor(name,new WaitDefinition(event,correlateBy,target),timeout);
    }}
    public static final class SubWorkflowBuilder{final String name;
        String workflow;
        String version;
        String successTarget;
        String failureTarget;
        EventName successEvent;
        EventName failureEvent;
        final LinkedHashMap<String,String> inputs=new LinkedHashMap<>();
        SubWorkflowBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }public SubWorkflowBuilder workflow(String value,String version){workflow=text(value,"workflow");
        this.version=text(version,"workflow version");
        return this;
    }public SubWorkflowBuilder input(String variable,String as){inputs.put(text(variable,"input variable"),text(as,"input target"));
        return this;
    }public SubWorkflowBuilder onSuccess(String event,String target){successEvent=new EventName(event);
        successTarget=text(target,"success target");
        return this;
    }public SubWorkflowBuilder onFailure(String event,String target){failureEvent=new EventName(event);
        failureTarget=text(target,"failure target");
        return this;
    }WorkflowNode build(){return WorkflowNode.subWorkflow(name,new SubWorkflowDefinition(workflow,version,inputs,successEvent,failureEvent,successTarget,failureTarget));
    }}
    public static class GatewayBuilder{final String name;
        GatewayType type=GatewayType.EXCLUSIVE;
        final ArrayList<WorkflowTransition> routes=new ArrayList<>();
        GatewayBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }public GatewayBuilder type(GatewayType value){type=Objects.requireNonNull(value);
        return this;
    }public GatewayBuilder when(Consumer<BranchBuilder> configure){routes.add(apply(new BranchBuilder(),configure).build());
        return this;
    }public GatewayBuilder otherwise(String target){routes.add(new WorkflowTransition("otherwise",text(target,TEXT_TARGET),null,null));
        return this;
    }WorkflowNode build(){return new WorkflowNode(name,WorkflowNodeType.GATEWAY,null,null,null,null,null,null,type,null,null,null,null,routes,null);
    }}
    public static final class BranchBuilder{String variable;
        String operator;
        String predicate;
        String target;
        String name;
        Object value;
        EventName event;
        Map<String,Object> arguments=Map.of();
        public BranchBuilder named(String value){name=text(value,"branch name");
        return this;
    }public BranchBuilder variable(String variable,String operator,Object value){this.variable=text(variable,"variable");
        this.operator=text(operator,"operator");
        this.value=value;
        return this;
    }public BranchBuilder predicate(String predicate,Map<String,Object> arguments){this.predicate=text(predicate,"predicate");
        this.arguments=arguments==null?Map.of():Map.copyOf(arguments);
        return this;
    }public BranchBuilder goTo(String value){target=text(value,TEXT_TARGET);
        return this;
    }public BranchBuilder emit(String value){event=new EventName(value);
        return this;
    }WorkflowTransition build(){return new WorkflowTransition(name,target,new BranchCondition(variable,operator,value,predicate,arguments),event);
    }}
    public static final class ForkBuilder{final String name;
        final LinkedHashMap<String,String> branches=new LinkedHashMap<>();
        String join;
        ForkBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }public ForkBuilder branch(String branch,String target){branches.put(text(branch,"branch"),text(target,TEXT_TARGET));
        return this;
    }public ForkBuilder joinAt(String value){join=text(value,"join node");
        return this;
    }WorkflowNode build(){return WorkflowNode.fork(name,new ForkDefinition(branches,join));
    }}
    public static final class JoinBuilder{final String name;
        final ArrayList<String> required=new ArrayList<>();
        String next;
        EventName event;
        JoinBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }public JoinBuilder require(String...branches){required.addAll(Arrays.stream(branches).map(v->text(v,"branch")).toList());
        return this;
    }public JoinBuilder then(String target){next=text(target,TEXT_TARGET);
        return this;
    }public JoinBuilder emit(String value){event=new EventName(value);
        return this;
    }WorkflowNode build(){return WorkflowNode.join(name,new JoinDefinition(required,next,event));
    }}
    public static final class LoopBuilder{final String name;
        BranchCondition condition;
        int maximum;
        String step;
        String next;
        LoopBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }public LoopBuilder whileVariable(String variable,String operator,Object value){condition=new BranchCondition(variable,operator,value,null,Map.of());
        return this;
    }public LoopBuilder whilePredicate(String predicate,Map<String,Object> arguments){condition=new BranchCondition(null,null,null,predicate,arguments);
        return this;
    }public LoopBuilder maxIterations(int value){maximum=value;
        return this;
    }public LoopBuilder doStep(String value){step=text(value,"loop step");
        return this;
    }public LoopBuilder then(String value){next=text(value,"loop target");
        return this;
    }WorkflowNode build(){return new WorkflowNode(name,WorkflowNodeType.LOOP,null,null,null,null,null,null,null,new LoopDefinition(condition,maximum,step,next),null,null,null,List.of(),null);
    }}
}
