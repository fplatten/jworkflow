package org.jworkflow.definition;

import org.jworkflow.events.EventName;
import org.jworkflow.engine.WorkflowEngineBuilder;
import org.jworkflow.engine.WorkflowValidationException;
import org.jworkflow.model.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Optional fluent Java authoring API. Groovy remains the preferred production process format.
 *
 * <p>Each builder is mutable and intended for one authoring thread. Node callbacks configure declarative data;
 * they are not runtime handlers. Build the definition, validate it and register it with an engine before starting
 * instances.</p>
 */
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
    /**
     * Begins a mutable definition builder for the supplied workflow name.
     * @param name workflow definition name
     * @return a new definition builder
     */
    public static WorkflowDefinitionBuilder workflow(String name){return new WorkflowDefinitionBuilder(name);
    }
    /**
     * Sets the declared workflow version before building the immutable graph.
     * @param version declared workflow or format version
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder version(String version){this.version=text(version,"version");
        return this;
    }
    /**
     * Selects the initial node, which must exist in the completed definition.
     * @param node initial node name
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder startAt(String node){this.startNode=text(node,"start node");
        return this;
    }
    /**
     * Records a start-event declaration for runtime modes supporting event-driven starts.
     * @param eventName event name matched by workflow transitions or subscribers
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder startWhen(String eventName){metadata.put("startEvent",new EventName(eventName).value());
        return this;
    }
    /**
     * Records the correlation field in definition metadata.
     * @param field variable or metadata field used for correlation
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder correlateBy(String field){metadata.put("correlateBy",text(field,"correlation field"));
        return this;
    }
    /**
     * Adds definition metadata, which participates in its semantic revision.
     * @param key definition metadata key
     * @param value definition metadata value
     * @return this builder for further configuration
     * @throws NullPointerException if value is null
     */
    public WorkflowDefinitionBuilder metadata(String key,String value){metadata.put(text(key,"metadata key"),Objects.requireNonNull(value,"value"));
        return this;
    }

    /**
     * Configures and adds a step node; the callback authors data rather than executing a workflow step.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder step(String name,Consumer<StepBuilder> configure){StepBuilder b=apply(new StepBuilder(name),configure);
        return add(b.build());
    }
    /**
     * Configures and adds an external-event wait node.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder waitFor(String name,Consumer<WaitBuilder> configure){WaitBuilder b=apply(new WaitBuilder(name),configure);
        return add(b.build());
    }
    /**
     * Configures and adds a child-workflow invocation node.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder subWorkflow(String name,Consumer<SubWorkflowBuilder> configure){SubWorkflowBuilder b=apply(new SubWorkflowBuilder(name),configure);
        return add(b.build());
    }
    /**
     * Configures and adds a conditional gateway node.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder gateway(String name,Consumer<GatewayBuilder> configure){GatewayBuilder b=apply(new GatewayBuilder(name),configure);
        return add(b.build());
    }
    /**
     * A conditional branch is represented by the runtime's exclusive gateway model.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder branch(String name,Consumer<GatewayBuilder> configure){GatewayBuilder b=apply(new GatewayBuilder(name).type(GatewayType.EXCLUSIVE),configure);
        return add(b.build());
    }
    /**
     * Configures and adds named parallel branch targets.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder fork(String name,Consumer<ForkBuilder> configure){ForkBuilder b=apply(new ForkBuilder(name),configure);
        return add(b.build());
    }
    /**
     * Configures and adds a branch-completion join node.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder join(String name,Consumer<JoinBuilder> configure){JoinBuilder b=apply(new JoinBuilder(name),configure);
        return add(b.build());
    }
    /**
     * Configures and adds a bounded loop node.
     * @param name unique node name within the workflow
     * @param configure callback configuring this node before it is added to the graph
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder loop(String name,Consumer<LoopBuilder> configure){LoopBuilder b=apply(new LoopBuilder(name),configure);
        return add(b.build());
    }
    /**
     * Adds a named terminal node.
     * @param name unique node name within the workflow
     * @return this builder for further configuration
     */
    public WorkflowDefinitionBuilder end(String name){return add(WorkflowNode.end(name));
    }

    /**
     * Materializes the immutable definition and validates its graph; invalid definitions fail before registration.
     * @return the immutable workflow definition
     */
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
    /**
     * Builds this definition and registers it with the supplied engine builder.
     * @param engineBuilder engine builder that receives the resulting definition
     * @return the supplied engine builder with this definition registered
     * @throws NullPointerException if engineBuilder is null
     */
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

    /**
     * Shared fluent transition, retry and timeout configuration for node builders. The type parameter preserves
     * the concrete fluent return type.
     * @param <T> the value type
     */
    public abstract static class TransitionBuilder<T extends TransitionBuilder<T>>{
        final String name;
            final ArrayList<WorkflowTransition> transitions=new ArrayList<>();
            RetryPolicy retry;
            TimeoutDefinition timeout;
        TransitionBuilder(String name){this.name=text(name,TEXT_NODE_NAME);
        }
        /**
         * Adds a named transition to the supplied target.
         * @param transitionName transition name selected by the runtime
         * @param target node or workflow destination
         * @return the value produced by the work
         */
        @SuppressWarnings("unchecked") public T transition(String transitionName,String target){transitions.add(new WorkflowTransition(transitionName,text(target,TEXT_TARGET),null,null));
            return(T)this;
        }
        /**
         * Configures the successful continuation and optional event emitted on that transition.
         * @param target node or workflow destination
         * @return the value produced by the work
         */
        @SuppressWarnings("unchecked") public T onSuccess(String target){transitions.add(new WorkflowTransition("success",text(target,TEXT_TARGET),null,null));
            return(T)this;
        }
        /**
         * Configures the successful continuation and optional event emitted on that transition.
         * @param event event to deliver or inspect
         * @param target node or workflow destination
         * @return the value produced by the work
         */
        @SuppressWarnings("unchecked") public T onSuccess(String event,String target){transitions.add(new WorkflowTransition("success",text(target,TEXT_TARGET),null,new EventName(event)));
            return(T)this;
        }
        /**
         * Configures the failed continuation and optional event emitted on that transition.
         * @param target node or workflow destination
         * @return the value produced by the work
         */
        @SuppressWarnings("unchecked") public T onFailure(String target){transitions.add(new WorkflowTransition("failure",text(target,TEXT_TARGET),null,null));
            return(T)this;
        }
        /**
         * Configures the failed continuation and optional event emitted on that transition.
         * @param event event to deliver or inspect
         * @param target node or workflow destination
         * @return the value produced by the work
         */
        @SuppressWarnings("unchecked") public T onFailure(String event,String target){transitions.add(new WorkflowTransition("failure",text(target,TEXT_TARGET),null,new EventName(event)));
            return(T)this;
        }
        /**
         * Configures the step retry budget and delay.
         * @param configure callback configuring this node before it is added to the graph
         * @return the value produced by the work
         */
        @SuppressWarnings("unchecked") public T retry(Consumer<RetryBuilder> configure){retry=apply(new RetryBuilder(),configure).build();
            return(T)this;
        }
        /**
         * Configures timeout duration, transition and optional event.
         * @param configure callback configuring this node before it is added to the graph
         * @return the value produced by the work
         */
        @SuppressWarnings("unchecked") public T timeout(Consumer<TimeoutBuilder> configure){timeout=apply(new TimeoutBuilder(),configure).build();
            return(T)this;
        }
    }
    /**
     * Configures a handler action or declared listener invocation for a step node.
     */
    public static final class StepBuilder extends TransitionBuilder<StepBuilder>{String action;
        String listenerId;
        String listenerMethod;
        StepBuilder(String n){super(n);
    }

        /**
         * Selects the registered handler action invoked for this step.
         * @param value registered step-handler action name
         * @return this builder for further configuration
         */
        public StepBuilder action(String value){action=text(value,"action");
        return this;
    }

        /**
         * Declares the registered listener and method used for this step.
         * @param id registered infrastructure listener ID
         * @param method listener method name
         * @return this builder for further configuration
         */
        public StepBuilder listener(String id,String method){listenerId=text(id,"listener ID");
        listenerMethod=text(method,"listener method");
        return this;
    }WorkflowNode build(){if(action==null&&listenerId==null)throw invalid("Step "+name+" requires an action or listener");
        return new WorkflowNode(name,WorkflowNodeType.STEP,action,listenerId,listenerMethod,null,null,null,null,null,null,retry,timeout,transitions,null);
    }}
    /**
     * Configures a step attempt budget and fixed retry delay.
     */
    public static final class RetryBuilder{int attempts;
        /** Creates an unconfigured attempt budget with zero backoff. Set the budget before building a step. */
        public RetryBuilder() {
            // Default construction requires no additional setup.
        }

        Duration backoff=Duration.ZERO;
        /**
         * Sets the maximum step attempts, including the initial execution.
         * @param value positive maximum attempts, including the first attempt
         * @return this builder for further configuration
         */
        public RetryBuilder maxAttempts(int value){attempts=value;
        return this;
    }

        /**
         * Sets the nonnegative fixed delay between step attempts.
         * @param value nonnegative fixed retry delay
         * @return this builder for further configuration
         * @throws NullPointerException if value is null
         */
        public RetryBuilder backoff(Duration value){backoff=Objects.requireNonNull(value);
        return this;
    }RetryPolicy build(){return new RetryPolicy(attempts,backoff);
    }}
    /**
     * Configures a timeout duration, target node and optional emitted event.
     */
    public static final class TimeoutBuilder{Duration duration;
        /** Creates an unconfigured timeout. Set a duration and the desired continuation before use. */
        public TimeoutBuilder() {
            // Default construction requires no additional setup.
        }

        String target;
        EventName event;
        /**
         * Sets the timeout duration.
         * @param value timeout duration
         * @return this builder for further configuration
         * @throws NullPointerException if value is null
         */
        public TimeoutBuilder after(Duration value){duration=Objects.requireNonNull(value);
        return this;
    }

        /**
         * Sets the continuation target node.
         * @param value continuation node name
         * @return this builder for further configuration
         */
        public TimeoutBuilder goTo(String value){target=text(value,"timeout target");
        return this;
    }

        /**
         * Sets the event emitted when this transition or timeout occurs.
         * @param value event name emitted on the configured transition
         * @return this builder for further configuration
         */
        public TimeoutBuilder emit(String value){event=new EventName(value);
        return this;
    }TimeoutDefinition build(){return new TimeoutDefinition(duration,target,event);
    }}
    /**
     * Configures an external-event wait, correlation field and continuation.
     */
    public static final class WaitBuilder{final String name;
        EventName event;
        String correlateBy;
        String target;
        TimeoutDefinition timeout;
        WaitBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }

        /**
         * Sets the event name accepted by the wait.
         * @param value expected external event name
         * @return this builder for further configuration
         */
        public WaitBuilder event(String value){event=new EventName(value);
        return this;
    }

        /**
         * Sets the field used to correlate the external event.
         * @param value correlation field name
         * @return this builder for further configuration
         */
        public WaitBuilder correlateBy(String value){correlateBy=text(value,"correlateBy");
        return this;
    }

        /**
         * Sets the continuation node after completion.
         * @param value continuation node name
         * @return this builder for further configuration
         */
        public WaitBuilder then(String value){target=text(value,"wait target");
        return this;
    }

        /**
         * Configures timeout duration, transition and optional event.
         * @param c the consumer&lt;timeout builder&gt; value
         * @return this builder for further configuration
         */
        public WaitBuilder timeout(Consumer<TimeoutBuilder> c){timeout=apply(new TimeoutBuilder(),c).build();
        return this;
    }WorkflowNode build(){return WorkflowNode.waitFor(name,new WaitDefinition(event,correlateBy,target),timeout);
    }}
    /**
     * Configures the child workflow version, input mappings and success/failure continuations.
     */
    public static final class SubWorkflowBuilder{final String name;
        String workflow;
        String version;
        String successTarget;
        String failureTarget;
        EventName successEvent;
        EventName failureEvent;
        final LinkedHashMap<String,String> inputs=new LinkedHashMap<>();
        SubWorkflowBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }

        /**
         * Selects the referenced child workflow and its version.
         * @param value child workflow name
         * @param version declared workflow or format version
         * @return a new definition builder
         */
        public SubWorkflowBuilder workflow(String value,String version){workflow=text(value,"workflow");
        this.version=text(version,"workflow version");
        return this;
    }

        /**
         * Maps a parent variable to a named child input.
         * @param variable workflow variable name
         * @param as child input name receiving the mapped parent variable
         * @return this builder for further configuration
         */
        public SubWorkflowBuilder input(String variable,String as){inputs.put(text(variable,"input variable"),text(as,"input target"));
        return this;
    }

        /**
         * Configures the successful continuation and optional event emitted on that transition.
         * @param event event to deliver or inspect
         * @param target node or workflow destination
         * @return this builder for further configuration
         */
        public SubWorkflowBuilder onSuccess(String event,String target){successEvent=new EventName(event);
        successTarget=text(target,"success target");
        return this;
    }

        /**
         * Configures the failed continuation and optional event emitted on that transition.
         * @param event event to deliver or inspect
         * @param target node or workflow destination
         * @return this builder for further configuration
         */
        public SubWorkflowBuilder onFailure(String event,String target){failureEvent=new EventName(event);
        failureTarget=text(target,"failure target");
        return this;
    }WorkflowNode build(){return WorkflowNode.subWorkflow(name,new SubWorkflowDefinition(workflow,version,inputs,successEvent,failureEvent,successTarget,failureTarget));
    }}
    /**
     * Configures a gateway selection policy, conditional routes and fallback target.
     */
    public static class GatewayBuilder{final String name;
        GatewayType type=GatewayType.EXCLUSIVE;
        final ArrayList<WorkflowTransition> routes=new ArrayList<>();
        GatewayBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }

        /**
         * Selects the gateway branch policy.
         * @param value gateway branch selection policy
         * @return this builder for further configuration
         * @throws NullPointerException if value is null
         */
        public GatewayBuilder type(GatewayType value){type=Objects.requireNonNull(value);
        return this;
    }

        /**
         * Adds a conditional branch configured by the callback.
         * @param configure callback configuring this node before it is added to the graph
         * @return this builder for further configuration
         */
        public GatewayBuilder when(Consumer<BranchBuilder> configure){routes.add(apply(new BranchBuilder(),configure).build());
        return this;
    }

        /**
         * Sets the fallback target when no condition selects a route.
         * @param target node or workflow destination
         * @return this builder for further configuration
         */
        public GatewayBuilder otherwise(String target){routes.add(new WorkflowTransition("otherwise",text(target,TEXT_TARGET),null,null));
        return this;
    }WorkflowNode build(){return new WorkflowNode(name,WorkflowNodeType.GATEWAY,null,null,null,null,null,null,type,null,null,null,null,routes,null);
    }}
    /**
     * Configures one named condition, destination and optional emitted event within a gateway.
     */
    public static final class BranchBuilder{String variable;
        /** Creates an unconfigured branch condition and destination. */
        public BranchBuilder() {
            // Default construction requires no additional setup.
        }

        String operator;
        String predicate;
        String target;
        String name;
        Object value;
        EventName event;
        Map<String,Object> arguments=Map.of();
        /**
         * Sets the branch identity used in the graph.
         * @param value branch name
         * @return this builder for further configuration
         */
        public BranchBuilder named(String value){name=text(value,"branch name");
        return this;
    }

        /**
         * Defines a declarative variable/operator/literal comparison.
         * @param variable workflow variable name
         * @param operator declarative comparison operator
         * @param value literal comparison value
         * @return this builder for further configuration
         */
        public BranchBuilder variable(String variable,String operator,Object value){this.variable=text(variable,"variable");
        this.operator=text(operator,"operator");
        this.value=value;
        return this;
    }

        /**
         * Selects a named host predicate and its immutable arguments.
         * @param predicate registered predicate name
         * @param arguments named immutable arguments supplied to a registered predicate
         * @return this builder for further configuration
         */
        public BranchBuilder predicate(String predicate,Map<String,Object> arguments){this.predicate=text(predicate,"predicate");
        this.arguments=arguments==null?Map.of():Map.copyOf(arguments);
        return this;
    }

        /**
         * Sets the continuation target node.
         * @param value continuation node name
         * @return this builder for further configuration
         */
        public BranchBuilder goTo(String value){target=text(value,TEXT_TARGET);
        return this;
    }

        /**
         * Sets the event emitted when this transition or timeout occurs.
         * @param value event name emitted on the configured transition
         * @return this builder for further configuration
         */
        public BranchBuilder emit(String value){event=new EventName(value);
        return this;
    }WorkflowTransition build(){return new WorkflowTransition(name,target,new BranchCondition(variable,operator,value,predicate,arguments),event);
    }}
    /**
     * Configures named parallel branch targets and their join node.
     */
    public static final class ForkBuilder{final String name;
        final LinkedHashMap<String,String> branches=new LinkedHashMap<>();
        String join;
        ForkBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }

        /**
         * Adds a named parallel branch and its initial target.
         * @param branch branch identity
         * @param target node or workflow destination
         * @return this builder for further configuration
         */
        public ForkBuilder branch(String branch,String target){branches.put(text(branch,"branch"),text(target,TEXT_TARGET));
        return this;
    }

        /**
         * Selects the node coordinating branch completion.
         * @param value join node coordinating branch completion
         * @return this builder for further configuration
         */
        public ForkBuilder joinAt(String value){join=text(value,"join node");
        return this;
    }WorkflowNode build(){return WorkflowNode.fork(name,new ForkDefinition(branches,join));
    }}
    /**
     * Configures required branch identities and the continuation after joining.
     */
    public static final class JoinBuilder{final String name;
        final ArrayList<String> required=new ArrayList<>();
        String next;
        EventName event;
        JoinBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }

        /**
         * Lists branch identities that must complete before joining.
         * @param branches named parallel or conditional branches
         * @return this builder for further configuration
         */
        public JoinBuilder require(String...branches){required.addAll(Arrays.stream(branches).map(v->text(v,"branch")).toList());
        return this;
    }

        /**
         * Sets the continuation node after completion.
         * @param target node or workflow destination
         * @return this builder for further configuration
         */
        public JoinBuilder then(String target){next=text(target,TEXT_TARGET);
        return this;
    }

        /**
         * Sets the event emitted when this transition or timeout occurs.
         * @param value event name emitted on the configured transition
         * @return this builder for further configuration
         */
        public JoinBuilder emit(String value){event=new EventName(value);
        return this;
    }WorkflowNode build(){return WorkflowNode.join(name,new JoinDefinition(required,next,event));
    }}
    /**
     * Configures a bounded loop condition, body node and exit node.
     */
    public static final class LoopBuilder{final String name;
        BranchCondition condition;
        int maximum;
        String step;
        String next;
        LoopBuilder(String n){name=text(n,TEXT_NODE_NAME);
    }

        /**
         * Configures a declarative variable comparison as the loop continuation condition.
         * @param variable workflow variable name
         * @param operator declarative comparison operator
         * @param value literal comparison value
         * @return this builder for further configuration
         */
        public LoopBuilder whileVariable(String variable,String operator,Object value){condition=new BranchCondition(variable,operator,value,null,Map.of());
        return this;
    }

        /**
         * Configures a registered predicate as the loop continuation condition.
         * @param predicate registered predicate name
         * @param arguments named immutable arguments supplied to a registered predicate
         * @return this builder for further configuration
         */
        public LoopBuilder whilePredicate(String predicate,Map<String,Object> arguments){condition=new BranchCondition(null,null,null,predicate,arguments);
        return this;
    }

        /**
         * Sets the positive upper bound on loop body executions.
         * @param value positive maximum number of loop iterations
         * @return this builder for further configuration
         */
        public LoopBuilder maxIterations(int value){maximum=value;
        return this;
    }

        /**
         * Selects the loop body node.
         * @param value loop body node
         * @return this builder for further configuration
         */
        public LoopBuilder doStep(String value){step=text(value,"loop step");
        return this;
    }

        /**
         * Sets the continuation node after completion.
         * @param value continuation node name
         * @return this builder for further configuration
         */
        public LoopBuilder then(String value){next=text(value,"loop target");
        return this;
    }WorkflowNode build(){return new WorkflowNode(name,WorkflowNodeType.LOOP,null,null,null,null,null,null,null,new LoopDefinition(condition,maximum,step,next),null,null,null,List.of(),null);
    }}
}
