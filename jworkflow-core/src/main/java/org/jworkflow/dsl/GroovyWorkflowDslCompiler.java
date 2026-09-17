package org.jworkflow.dsl;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.*;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;
import org.jworkflow.definition.WorkflowDefinitionText;
import org.jworkflow.events.EventName;
import org.jworkflow.model.*;

import java.time.Duration;
import java.util.*;

/**
 * Interprets a restricted Groovy AST as declarative workflow data. No submitted script or closure is executed.
 */
public final class GroovyWorkflowDslCompiler {
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "contains");
    private static final Set<String> FORBIDDEN_LISTENER_METHODS = Set.of(
            "getClass", "wait", "notify", "notifyAll", "hashCode", "equals", "toString", "clone", "finalize");
    private final DslCompilerOptions options;

    public GroovyWorkflowDslCompiler() { this(DslCompilerOptions.DEFAULT); }
    public GroovyWorkflowDslCompiler(DslCompilerOptions options) { this.options = Objects.requireNonNull(options); }

    public WorkflowDefinition compile(WorkflowDefinitionText text) {
        Objects.requireNonNull(text, "definitionText");
        if (text.content().length() > options.maxSourceCharacters())
            throw limit(text.location(), null, "DSL source exceeds " + options.maxSourceCharacters() + " characters");
        ModuleNode module = parse(text.location(), text.content());
        validateEnvelope(module, text.location());
        enforceAstLimit(module, text.location());
        secureCompile(text.location(), text.content());
        List<MethodCallExpression> root = calls(module.getStatementBlock(), text.location(), "script", 0);
        if (root.size() != 1 || !"workflow".equals(root.get(0).getMethodAsString()))
            throw grammar(text.location(), root.isEmpty() ? module : root.get(0),
                    "A source must contain exactly one top-level workflow declaration");
        WorkflowDefinition result = workflow(root.get(0), text.location(), text.content());
        DefinitionValidationResult validation = new DefinitionValidator().validate(result);
        if (!validation.valid()) {
            throw new DslCompilationException(validation.errors().stream().map(error -> new DslDiagnostic(
                    error.code(), DslDiagnosticCategory.DOMAIN_VALIDATION, pos(text.location(), null),
                    error.location(), error.message())).toList());
        }
        return result;
    }

    private WorkflowDefinition workflow(MethodCallExpression root, String source, String sourceText) {
        requireImplicit(root, source);
        Args rootArgs = args(root, source).positional(1, source, root).named(Set.of(), source, root);
        String name = string(rootArgs.positionals.get(0), source, "workflow name");
        ClosureExpression body = rootArgs.closure(source, root);
        String version = null, start = null, startEvent = null, correlation = null;
        LinkedHashMap<String, WorkflowNode> nodes = new LinkedHashMap<>();
        for (MethodCallExpression call : calls(body.getCode(), source, "workflow", 1)) {
            switch (method(call, source)) {
                case "version" -> version = once(version, singleString(call, source), source, call, "version");
                case "correlateBy" -> correlation = once(correlation, singleString(call, source), source, call, "correlateBy");
                case "start" -> {
                    requireImplicit(call, source);
                    Args a = args(call, source).positional(0, source, call).named(Set.of("at", "when"), source, call).noClosure(source, call);
                    if (a.named.isEmpty()) throw grammar(source, call, "start requires at or when");
                    if (a.named.containsKey("at")) start = once(start, string(a.named.get("at"), source, "start at"), source, call, "start at");
                    if (a.named.containsKey("when")) startEvent = once(startEvent, event(a.named.get("when"), source), source, call, "start when");
                }
                case "step" -> put(nodes, step(call, source), call, source);
                case "waitFor" -> put(nodes, waitNode(call, source), call, source);
                case "subWorkflow" -> put(nodes, subWorkflow(call, source), call, source);
                case "fork" -> {
                    ForkNodes pair = fork(call, source);
                    put(nodes, pair.fork, call, source); put(nodes, pair.join, call, source);
                }
                case "gateway" -> put(nodes, gateway(call, source), call, source);
                case "loop" -> put(nodes, loop(call, source), call, source);
                case "end" -> put(nodes, WorkflowNode.end(singleString(call, source)), call, source);
                default -> throw unknown(source, call, "workflow");
            }
            if (nodes.size() > options.maxWorkflowNodes()) throw limit(source, call, "Too many workflow nodes");
        }
        if (version == null) throw grammar(source, root, "workflow must declare version");
        if (start == null) {
            if (startEvent == null) throw grammar(source, root, "workflow must declare start at or start when");
            if (nodes.isEmpty()) throw grammar(source, root, "event-started workflow requires a node");
            start = nodes.keySet().iterator().next();
        }
        if (!nodes.containsKey(start) && !nodes.isEmpty()) {
            nodes.put(start, new WorkflowNode(start, WorkflowNodeType.GATEWAY, null, null, null, null, null, null,
                    GatewayType.EXCLUSIVE, null, null, null, null,
                    List.of(new WorkflowTransition("start", nodes.keySet().iterator().next(), null, null)), null));
        }
        long transitionCount = nodes.values().stream().mapToLong(node -> node.transitions().size()).sum();
        if (transitionCount > options.maxTransitions()) throw limit(source, root, "Workflow has too many transitions");
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        if (startEvent != null) metadata.put("startEvent", startEvent);
        if (correlation != null) metadata.put("correlateBy", correlation);
        return new WorkflowDefinition(name, version, start, nodes, metadata, sourceText);
    }

    private WorkflowNode step(MethodCallExpression call, String source) {
        requireImplicit(call, source);
        Args a = args(call, source).positional(1, source, call).named(Set.of(), source, call);
        String name = string(a.positionals.get(0), source, "step name");
        String action = null, expectedEvent = null, success = null, failure = null;
        RetryPolicy retry = null; TimeoutDefinition timeout = null; ListenerInvocation listener = null;
        for (MethodCallExpression item : calls(a.closure(source, call).getCode(), source, "step", 2)) {
            switch (method(item, source)) {
                case "on" -> event(single(item, source), source);
                case "action" -> action = once(action, singleString(item, source), source, item, "action");
                case "retry" -> {
                    if (retry != null) throw grammar(source, item, "retry may be declared only once");
                    Args r = args(item, source).positional(0, source, item).named(Set.of("maxAttempts", "backoff"), source, item)
                            .required(Set.of("maxAttempts", "backoff"), source, item).noClosure(source, item);
                    retry = new RetryPolicy(integer(r.named.get("maxAttempts"), source, "maxAttempts"),
                            duration(r.named.get("backoff"), source, "backoff"));
                }
                case "timeout" -> timeout = once(timeout, timeout(item, source), source, item, "timeout");
                case "sla" -> validateSla(item, source);
                case "run" -> listener = once(listener, listener(item, source), source, item, "run");
                case "onSuccess" -> success = once(success, target(item, source), source, item, "onSuccess");
                case "onFailure" -> failure = once(failure, target(item, source), source, item, "onFailure");
                case "waitFor" -> {
                    if (!(item.getObjectExpression() instanceof VariableExpression v) || !"then".equals(v.getName()))
                        throw grammar(source, item, "step waitFor must use 'then waitFor'");
                    Args w = args(item, source).positional(1, source, item).named(Set.of("goTo"), source, item)
                            .required(Set.of("goTo"), source, item).noClosure(source, item);
                    expectedEvent = once(expectedEvent, event(w.positionals.get(0), source), source, item, "wait event");
                    success = once(success, string(w.named.get("goTo"), source, "goTo"), source, item, "success target");
                }
                case "then" -> {
                    requireImplicit(item, source);
                    Expression nested = single(item, source);
                    if (!(nested instanceof MethodCallExpression end) || !"end".equals(end.getMethodAsString()))
                        throw grammar(source, item, "then accepts only end(\"node\") in a step");
                    success = once(success, singleString(end, source), source, item, "success target");
                }
                default -> throw unknown(source, item, "step");
            }
        }
        if (action == null) action = expectedEvent == null ? name : actionName(expectedEvent);
        ArrayList<WorkflowTransition> transitions = new ArrayList<>();
        if (success != null) transitions.add(new WorkflowTransition("success", success, null,
                expectedEvent == null ? null : new EventName(expectedEvent)));
        if (failure != null) transitions.add(new WorkflowTransition("failure", failure, null, null));
        transitionLimit(transitions, source, call);
        return new WorkflowNode(name, WorkflowNodeType.STEP, action,
                listener == null ? null : listener.listenerId(), listener == null ? null : listener.methodName(),
                null, null, null, null, null, null, retry, timeout, transitions, listener);
    }

    private WorkflowNode waitNode(MethodCallExpression call, String source) {
        requireImplicit(call, source);
        Args a = args(call, source).positional(1, source, call).named(Set.of(), source, call);
        String name = string(a.positionals.get(0), source, "wait name");
        String event = null, correlation = null, target = null; TimeoutDefinition timeout = null;
        for (MethodCallExpression item : calls(a.closure(source, call).getCode(), source, "waitFor", 2)) {
            switch (method(item, source)) {
                case "event" -> event = once(event, event(single(item, source), source), source, item, "event");
                case "correlateBy" -> correlation = once(correlation, singleString(item, source), source, item, "correlateBy");
                case "then" -> target = once(target, target(item, source), source, item, "then");
                case "timeout" -> timeout = once(timeout, timeout(item, source), source, item, "timeout");
                default -> throw unknown(source, item, "waitFor");
            }
        }
        if (event == null || target == null) throw grammar(source, call, "waitFor requires event and then goTo");
        return WorkflowNode.waitFor(name, new WaitDefinition(new EventName(event), correlation, target), timeout);
    }

    private WorkflowNode subWorkflow(MethodCallExpression call, String source) {
        requireImplicit(call, source);
        Args a = args(call, source).positional(1, source, call).named(Set.of(), source, call);
        String name = string(a.positionals.get(0), source, "sub-workflow node");
        String workflow = null, version = null; Route success = null, failure = null;
        LinkedHashMap<String, String> inputs = new LinkedHashMap<>();
        for (MethodCallExpression item : calls(a.closure(source, call).getCode(), source, "subWorkflow", 2)) {
            Args i = args(item, source);
            switch (method(item, source)) {
                case "workflow" -> {
                    i.positional(1, source, item).named(Set.of("version"), source, item).required(Set.of("version"), source, item).noClosure(source, item);
                    workflow = once(workflow, string(i.positionals.get(0), source, "workflow"), source, item, "workflow");
                    version = once(version, string(i.named.get("version"), source, "version"), source, item, "version");
                }
                case "input" -> {
                    i.positional(0, source, item).named(Set.of("variable", "as"), source, item).required(Set.of("variable", "as"), source, item).noClosure(source, item);
                    String variable = string(i.named.get("variable"), source, "input variable");
                    if (inputs.putIfAbsent(variable, string(i.named.get("as"), source, "input alias")) != null)
                        throw grammar(source, item, "Duplicate input variable: " + variable);
                }
                case "onSuccess" -> success = once(success, route(item, source), source, item, "onSuccess");
                case "onFailure" -> failure = once(failure, route(item, source), source, item, "onFailure");
                default -> throw unknown(source, item, "subWorkflow");
            }
        }
        if (workflow == null || version == null || success == null || failure == null)
            throw grammar(source, call, "subWorkflow requires workflow, onSuccess, and onFailure");
        return WorkflowNode.subWorkflow(name, new SubWorkflowDefinition(workflow, version, inputs,
                new EventName(success.event), new EventName(failure.event), success.target, failure.target));
    }

    private ForkNodes fork(MethodCallExpression call, String source) {
        requireImplicit(call, source);
        Args a = args(call, source).positional(1, source, call).named(Set.of(), source, call);
        String name = string(a.positionals.get(0), source, "fork name");
        LinkedHashMap<String, String> branches = new LinkedHashMap<>();
        String joinName = null, next = null, emitted = null; List<String> required = null;
        for (MethodCallExpression item : calls(a.closure(source, call).getCode(), source, "fork", 2)) {
            Args i = args(item, source);
            switch (method(item, source)) {
                case "branch" -> {
                    i.positional(1, source, item).named(Set.of("goTo"), source, item).required(Set.of("goTo"), source, item).noClosure(source, item);
                    String branch = string(i.positionals.get(0), source, "branch");
                    if (branches.putIfAbsent(branch, string(i.named.get("goTo"), source, "branch target")) != null)
                        throw grammar(source, item, "Duplicate branch: " + branch);
                }
                case "join" -> {
                    if (joinName != null) throw grammar(source, item, "fork may declare one join");
                    i.positional(1, source, item).named(Set.of("whenComplete", "goTo", "emit"), source, item)
                            .required(Set.of("whenComplete", "goTo"), source, item).noClosure(source, item);
                    joinName = string(i.positionals.get(0), source, "join name");
                    required = strings(i.named.get("whenComplete"), source, "whenComplete");
                    next = string(i.named.get("goTo"), source, "join target");
                    if (i.named.containsKey("emit")) emitted = event(i.named.get("emit"), source);
                }
                default -> throw unknown(source, item, "fork");
            }
        }
        if (branches.isEmpty() || joinName == null || required == null) throw grammar(source, call, "fork requires branches and one join");
        if (!branches.keySet().containsAll(required) || new LinkedHashSet<>(required).size() != required.size())
            throw grammar(source, call, "join must reference unique declared branch IDs");
        return new ForkNodes(WorkflowNode.fork(name, new ForkDefinition(branches, joinName)),
                WorkflowNode.join(joinName, new JoinDefinition(required, next, emitted == null ? null : new EventName(emitted))));
    }

    private WorkflowNode gateway(MethodCallExpression call, String source) {
        requireImplicit(call, source);
        Args a = args(call, source).positional(1, source, call).named(Set.of("type"), source, call).required(Set.of("type"), source, call);
        String name = string(a.positionals.get(0), source, "gateway name");
        GatewayType type;
        try { type = GatewayType.valueOf(string(a.named.get("type"), source, "gateway type").toUpperCase()); }
        catch (IllegalArgumentException e) { throw grammar(source, call, "Unsupported gateway type"); }
        ArrayList<WorkflowTransition> transitions = new ArrayList<>(); boolean fallback = false;
        for (MethodCallExpression item : calls(a.closure(source, call).getCode(), source, "gateway", 2)) {
            switch (method(item, source)) {
                case "when" -> {
                    BranchCondition condition = condition(item, source);
                    Args route = args(item, source);
                    route.required(Set.of("goTo"), source, item);
                    transitions.add(new WorkflowTransition(null,
                            string(route.named.get("goTo"), source, "goTo"), condition, null));
                }
                case "otherwise" -> {
                    if (fallback) throw grammar(source, item, "otherwise may be declared once");
                    fallback = true; transitions.add(WorkflowTransition.goTo(target(item, source)));
                }
                default -> throw unknown(source, item, "gateway");
            }
        }
        transitionLimit(transitions, source, call);
        return new WorkflowNode(name, WorkflowNodeType.GATEWAY, null, null, null, null, null, null,
                type, null, null, null, null, transitions, null);
    }

    private WorkflowNode loop(MethodCallExpression call, String source) {
        requireImplicit(call, source);
        Args a = args(call, source).positional(1, source, call).named(Set.of(), source, call);
        String name = string(a.positionals.get(0), source, "loop name"), step = null, next = null;
        BranchCondition condition = null; Integer max = null;
        for (MethodCallExpression item : calls(a.closure(source, call).getCode(), source, "loop", 2)) {
            switch (method(item, source)) {
                case "whileCondition" -> condition = once(condition, condition(item, source), source, item, "whileCondition");
                case "maxIterations" -> max = once(max, integer(single(item, source), source, "maxIterations"), source, item, "maxIterations");
                case "doStep" -> step = once(step, singleString(item, source), source, item, "doStep");
                case "then" -> next = once(next, target(item, source), source, item, "then");
                default -> throw unknown(source, item, "loop");
            }
        }
        if (condition == null || max == null || step == null || next == null)
            throw grammar(source, call, "loop requires whileCondition, maxIterations, doStep, and then");
        return new WorkflowNode(name, WorkflowNodeType.LOOP, null, null, null, null, null, null, null,
                new LoopDefinition(condition, max, step, next), null, null, null, List.of(), null);
    }

    private ListenerInvocation listener(MethodCallExpression run, String source) {
        requireImplicit(run, source);
        Args a = args(run, source).positional(0, source, run).named(Set.of(), source, run);
        ClosureExpression closure = a.closure(source, run);
        Parameter[] p = closure.getParameters();
        if (p == null || p.length != 2 || !"event".equals(p[0].getName()) || !"context".equals(p[1].getName()))
            throw security(source, closure, "run must declare exactly: event, context");
        List<MethodCallExpression> body = calls(closure.getCode(), source, "run", 3);
        if (body.size() != 1) throw security(source, closure, "run must contain exactly one listener invocation");
        MethodCallExpression terminal = body.get(0);
        if (terminal.isImplicitThis() || terminal.getMethodAsString() == null
                || !(terminal.getObjectExpression() instanceof MethodCallExpression lookup)
                || !"listener".equals(lookup.getMethodAsString())
                || !(lookup.getObjectExpression() instanceof VariableExpression receiver)
                || !"context".equals(receiver.getName()))
            throw security(source, terminal, "Only context.listener(\"id\").method(...) is allowed in run");
        Args lookupArgs = args(lookup, source).positional(1, source, lookup)
                .named(Set.of(), source, lookup).noClosure(source, lookup);
        String listenerId = string(lookupArgs.positionals.get(0), source, "listener ID");
        Args terminalArgs = args(terminal, source).named(Set.of(), source, terminal).noClosure(source, terminal);
        ArrayList<ListenerArgument> values = new ArrayList<>();
        for (Expression expression : terminalArgs.positionals) {
            if (expression instanceof VariableExpression v && "event".equals(v.getName())) values.add(new ListenerArgument.CurrentEvent());
            else if (expression instanceof VariableExpression v && "context".equals(v.getName())) values.add(new ListenerArgument.CurrentContext());
            else if (isLiteral(expression)) values.add(new ListenerArgument.Literal(literal(expression, source, "listener argument")));
            else throw security(source, expression, "Unsupported listener argument");
        }
        if (values.size() > 1) throw security(source, terminal, "MVP listener methods accept at most one argument");
        String listenerMethod = terminal.getMethodAsString();
        if (FORBIDDEN_LISTENER_METHODS.contains(listenerMethod))
            throw security(source, terminal, "Object/runtime methods are not valid listener operations");
        return new ListenerInvocation(listenerId, listenerMethod, values);
    }

    private BranchCondition condition(MethodCallExpression call, String source) {
        Args a = args(call, source).positional(0, source, call).noClosure(source, call);
        if (a.named.containsKey("predicate")) {
            a.named(Set.of("predicate", "arguments", "goTo"), source, call).required(Set.of("predicate"), source, call);
            Map<String, Object> arguments = a.named.containsKey("arguments") ? objectMap(a.named.get("arguments"), source, "arguments") : Map.of();
            return new BranchCondition(null, null, null, string(a.named.get("predicate"), source, "predicate"), arguments);
        }
        if (!a.named.containsKey("variable")) throw grammar(source, call, "condition requires variable or predicate");
        String op = a.named.keySet().stream().filter(OPERATORS::contains).findFirst()
                .orElseThrow(() -> grammar(source, call, "condition requires a supported operator"));
        a.named(Set.of("variable", op, "goTo"), source, call);
        return new BranchCondition(string(a.named.get("variable"), source, "variable"), op,
                literal(a.named.get(op), source, op), null, Map.of());
    }

    private TimeoutDefinition timeout(MethodCallExpression call, String source) {
        Args a = args(call, source).positional(1, source, call).named(Set.of("goTo"), source, call).noClosure(source, call);
        return new TimeoutDefinition(duration(a.positionals.get(0), source, "timeout"),
                a.named.containsKey("goTo") ? string(a.named.get("goTo"), source, "goTo") : null, null);
    }

    private void validateSla(MethodCallExpression call, String source) {
        Args a = args(call, source).positional(1, source, call).named(Set.of("onBreach"), source, call)
                .required(Set.of("onBreach"), source, call).noClosure(source, call);
        duration(a.positionals.get(0), source, "sla"); string(a.named.get("onBreach"), source, "onBreach");
    }

    private Route route(MethodCallExpression call, String source) {
        Args a = args(call, source).positional(0, source, call).named(Set.of("emit", "goTo"), source, call)
                .required(Set.of("emit", "goTo"), source, call).noClosure(source, call);
        return new Route(event(a.named.get("emit"), source), string(a.named.get("goTo"), source, "goTo"));
    }

    private String target(MethodCallExpression call, String source) {
        Args a = args(call, source).positional(0, source, call).named(Set.of("goTo"), source, call)
                .required(Set.of("goTo"), source, call).noClosure(source, call);
        return string(a.named.get("goTo"), source, "goTo");
    }

    private ModuleNode parse(String source, String content) {
        SourceUnit unit = SourceUnit.create(source, content);
        try { unit.parse(); unit.completePhase(); unit.nextPhase(); unit.convert(); return unit.getAST(); }
        catch (Exception e) {
            List<DslDiagnostic> diagnostics = unit.getErrorCollector().getErrors().stream().map(error -> syntax(source, error)).toList();
            if (!diagnostics.isEmpty()) throw new DslCompilationException(diagnostics);
            throw failure("dsl.syntax", DslDiagnosticCategory.SYNTAX, source, null, "syntax", safe(e));
        }
    }

    private void validateEnvelope(ModuleNode module, String source) {
        if (module.getPackageName() != null || !module.getImports().isEmpty() || !module.getStarImports().isEmpty()
                || !module.getStaticImports().isEmpty() || !module.getStaticStarImports().isEmpty())
            throw security(source, module, "Packages and imports are not allowed");
        for (ClassNode type : module.getClasses()) {
            if (!type.isScript()) throw security(source, type, "Class declarations are not allowed");
            if (type.getAnnotations().stream().anyMatch(a -> a.getLineNumber() > 0))
                throw security(source, type, "Annotations and AST transforms are not allowed");
            if (type.getFields().stream().anyMatch(field -> field.getLineNumber() > 0))
                throw security(source, type, "Field declarations are not allowed");
            if (type.getMethods().stream().anyMatch(method -> method.getLineNumber() > 0
                    && !"run".equals(method.getName()) && !"main".equals(method.getName())))
                throw security(source, type, "Method declarations are not allowed");
        }
    }

    private void secureCompile(String source, String content) {
        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setPackageAllowed(false); secure.setMethodDefinitionAllowed(false); secure.setClosuresAllowed(true);
        secure.setAllowedImports(List.of()); secure.setAllowedStaticImports(List.of());
        secure.setAllowedStarImports(List.of()); secure.setAllowedStaticStarImports(List.of());
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.addCompilationCustomizers(secure);
        CompilationUnit unit = new CompilationUnit(configuration); unit.addSource(source, content);
        try { unit.compile(Phases.CANONICALIZATION); }
        catch (Exception e) { throw failure("dsl.secure-compiler", DslDiagnosticCategory.SECURITY, source, null, "compiler", safe(e)); }
    }

    private void enforceAstLimit(ModuleNode module, String source) {
        int[] count = {0};
        module.getStatementBlock().visit(new CodeVisitorSupport() {
            private void add(ASTNode n) { if (++count[0] > options.maxAstNodes()) throw limit(source, n, "Too many AST nodes"); }
            @Override public void visitMethodCallExpression(MethodCallExpression e) { add(e); super.visitMethodCallExpression(e); }
            @Override public void visitClosureExpression(ClosureExpression e) { add(e); super.visitClosureExpression(e); }
            @Override public void visitConstantExpression(ConstantExpression e) { add(e); super.visitConstantExpression(e); }
            @Override public void visitVariableExpression(VariableExpression e) { add(e); super.visitVariableExpression(e); }
            @Override public void visitListExpression(ListExpression e) { add(e); super.visitListExpression(e); }
            @Override public void visitMapExpression(MapExpression e) { add(e); super.visitMapExpression(e); }
            @Override public void visitGStringExpression(GStringExpression e) { add(e); super.visitGStringExpression(e); }
        });
    }

    private List<MethodCallExpression> calls(Statement statement, String source, String context, int depth) {
        if (depth > options.maxAstDepth()) throw limit(source, statement, "DSL nesting is too deep");
        List<Statement> list = statement instanceof BlockStatement block ? block.getStatements() : List.of(statement);
        ArrayList<MethodCallExpression> result = new ArrayList<>();
        for (Statement child : list) {
            if (!(child instanceof ExpressionStatement expression) || !(expression.getExpression() instanceof MethodCallExpression call))
                throw security(source, child, "Only documented DSL calls are allowed in " + context);
            result.add(call);
        }
        return result;
    }

    private Args args(MethodCallExpression call, String source) {
        List<Expression> raw = call.getArguments() instanceof TupleExpression tuple
                ? new ArrayList<>(tuple.getExpressions()) : List.of(call.getArguments());
        LinkedHashMap<String, Expression> named = new LinkedHashMap<>(); ArrayList<Expression> positional = new ArrayList<>();
        ClosureExpression closure = null;
        for (Expression expression : raw) {
            if (expression instanceof MapExpression map) {
                if (map.getMapEntryExpressions().size() > options.maxCollectionEntries()) throw limit(source, map, "Too many named arguments");
                for (MapEntryExpression entry : map.getMapEntryExpressions()) {
                    String key = string(entry.getKeyExpression(), source, "argument name");
                    if (named.putIfAbsent(key, entry.getValueExpression()) != null) throw grammar(source, entry, "Duplicate argument: " + key);
                }
            } else if (expression instanceof ClosureExpression found) {
                if (closure != null) throw grammar(source, expression, "Only one closure is allowed");
                closure = found;
            } else positional.add(expression);
        }
        return new Args(named, positional, closure);
    }

    private Object literal(Expression expression, String source, String construct) {
        return literal(expression, source, construct, 0);
    }

    private Object literal(Expression expression, String source, String construct, int depth) {
        if (depth > options.maxAstDepth()) throw limit(source, expression, construct + " nesting is too deep");
        if (expression instanceof ConstantExpression constant) {
            Object value = constant.getValue();
            if (value instanceof String s) {
                if (s.length() > options.maxStringLength()) throw limit(source, expression, construct + " is too long");
                return s;
            }
            if (value == null || value instanceof Boolean || value instanceof Number) {
                if (value instanceof Number && value.toString().replace("-", "").length() > options.maxNumericDigits())
                    throw limit(source, expression, construct + " is too large");
                return value;
            }
        }
        if (expression instanceof ListExpression list) {
            if (list.getExpressions().size() > options.maxCollectionEntries()) throw limit(source, expression, "List is too large");
            return list.getExpressions().stream().map(v -> literal(v, source, construct, depth + 1)).toList();
        }
        if (expression instanceof MapExpression) return objectMap(expression, source, construct, depth + 1);
        throw security(source, expression, construct + " must be a literal");
    }

    private Map<String, Object> objectMap(Expression expression, String source, String construct) {
        return objectMap(expression, source, construct, 0);
    }

    private Map<String, Object> objectMap(Expression expression, String source, String construct, int depth) {
        if (depth > options.maxAstDepth()) throw limit(source, expression, construct + " nesting is too deep");
        if (!(expression instanceof MapExpression map)) throw security(source, expression, construct + " must be a literal map");
        if (map.getMapEntryExpressions().size() > options.maxCollectionEntries()) throw limit(source, expression, "Map is too large");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (MapEntryExpression entry : map.getMapEntryExpressions()) {
            String key = string(entry.getKeyExpression(), source, construct + " key");
            if (result.putIfAbsent(key, literal(entry.getValueExpression(), source, construct, depth + 1)) != null)
                throw grammar(source, entry, "Duplicate map key: " + key);
        }
        return Collections.unmodifiableMap(result);
    }

    private List<String> strings(Expression expression, String source, String construct) {
        if (!(expression instanceof ListExpression list)) throw security(source, expression, construct + " must be a literal list");
        if (list.getExpressions().size() > options.maxCollectionEntries()) throw limit(source, expression, "List is too large");
        return list.getExpressions().stream().map(v -> string(v, source, construct)).toList();
    }

    private boolean isLiteral(Expression e) { return e instanceof ConstantExpression || e instanceof ListExpression || e instanceof MapExpression; }
    private String string(Expression e, String source, String name) {
        Object value = literal(e, source, name);
        if (!(value instanceof String s) || s.isBlank()) throw grammar(source, e, name + " must be a non-blank string");
        return s;
    }
    private int integer(Expression e, String source, String name) {
        Object value = literal(e, source, name);
        if (!(value instanceof Number n) || n.intValue() < 1 || n.doubleValue() != n.intValue())
            throw grammar(source, e, name + " must be a positive integer");
        return n.intValue();
    }
    private Duration duration(Expression e, String source, String name) {
        try { return Duration.parse(string(e, source, name)); }
        catch (IllegalArgumentException x) { throw grammar(source, e, name + " must be an ISO-8601 duration"); }
    }
    private String event(Expression e, String source) {
        try { return new EventName(string(e, source, "event name")).value(); }
        catch (IllegalArgumentException x) { throw grammar(source, e, x.getMessage()); }
    }
    private Expression single(MethodCallExpression call, String source) {
        requireImplicit(call, source);
        Args a = args(call, source).positional(1, source, call).named(Set.of(), source, call).noClosure(source, call);
        return a.positionals.get(0);
    }
    private String singleString(MethodCallExpression call, String source) { return string(single(call, source), source, method(call, source)); }
    private String method(MethodCallExpression call, String source) {
        String result = call.getMethodAsString(); if (result == null) throw security(source, call, "Dynamic method names are not allowed"); return result;
    }
    private void requireImplicit(MethodCallExpression call, String source) {
        if (!call.isImplicitThis()) throw security(source, call, "Qualified method calls are not allowed here");
    }
    private void put(Map<String, WorkflowNode> nodes, WorkflowNode node, ASTNode at, String source) {
        if (nodes.putIfAbsent(node.name(), node) != null) throw grammar(source, at, "Duplicate workflow node: " + node.name());
    }
    private void transitionLimit(List<?> transitions, String source, ASTNode at) {
        if (transitions.size() > options.maxTransitions()) throw limit(source, at, "Too many transitions");
    }
    private <T> T once(T current, T value, String source, ASTNode at, String name) {
        if (current != null) throw grammar(source, at, name + " may be declared only once"); return value;
    }
    private DslCompilationException unknown(String source, MethodCallExpression call, String context) {
        return grammar(source, call, "Unknown DSL method '" + method(call, source) + "' in " + context);
    }
    private static String actionName(String event) {
        int dot = event.indexOf('.'); if (dot < 1 || dot == event.length() - 1) return event;
        String subject = event.substring(0, dot), action = event.substring(dot + 1);
        if (action.endsWith("ied")) action = action.substring(0, action.length() - 3) + "y";
        else if (action.endsWith("ed")) action = action.substring(0, action.length() - 1);
        return subject + "." + action;
    }

    private DslCompilationException grammar(String source, ASTNode n, String message) { return failure("dsl.grammar", DslDiagnosticCategory.GRAMMAR, source, n, text(n), message); }
    private DslCompilationException security(String source, ASTNode n, String message) { return failure("dsl.security", DslDiagnosticCategory.SECURITY, source, n, text(n), message); }
    private DslCompilationException limit(String source, ASTNode n, String message) { return failure("dsl.resource-limit", DslDiagnosticCategory.RESOURCE_LIMIT, source, n, text(n), message); }
    private DslCompilationException failure(String code, DslDiagnosticCategory category, String source, ASTNode n, String construct, String message) {
        return new DslCompilationException(List.of(new DslDiagnostic(code, category, pos(source, n), construct, message)));
    }
    private static String text(ASTNode n) { return n == null ? "" : n.getText(); }
    private static DslSourcePosition pos(String source, ASTNode n) {
        return n == null || n.getLineNumber() < 1 ? new DslSourcePosition(source, 1, 1, 1, 1)
                : new DslSourcePosition(source, n.getLineNumber(), n.getColumnNumber(), n.getLastLineNumber(), n.getLastColumnNumber());
    }
    private DslDiagnostic syntax(String source, Object error) {
        if (error instanceof SyntaxErrorMessage message) {
            SyntaxException x = message.getCause();
            return new DslDiagnostic("dsl.syntax", DslDiagnosticCategory.SYNTAX,
                    new DslSourcePosition(source, x.getLine(), x.getStartColumn(), x.getEndLine(), x.getEndColumn()), "syntax", x.getOriginalMessage());
        }
        return new DslDiagnostic("dsl.syntax", DslDiagnosticCategory.SYNTAX, pos(source, null), "syntax", error.toString());
    }
    private static String safe(Exception e) {
        String message = e.getMessage(); if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        int line = message.indexOf('\n'); return line < 0 ? message : message.substring(0, line);
    }

    private final class Args {
        private final Map<String, Expression> named; private final List<Expression> positionals; private final ClosureExpression closure;
        private Args(Map<String, Expression> named, List<Expression> positionals, ClosureExpression closure) {
            this.named = named; this.positionals = positionals; this.closure = closure;
        }
        private Args named(Set<String> allowed, String source, ASTNode at) {
            for (String key : named.keySet()) if (!allowed.contains(key)) throw grammar(source, at, "Unknown named argument: " + key); return this;
        }
        private Args required(Set<String> required, String source, ASTNode at) {
            for (String key : required) if (!named.containsKey(key)) throw grammar(source, at, "Missing named argument: " + key); return this;
        }
        private Args positional(int count, String source, ASTNode at) {
            if (positionals.size() != count) throw grammar(source, at, "Expected " + count + " positional argument(s), found " + positionals.size()); return this;
        }
        private ClosureExpression closure(String source, ASTNode at) {
            if (closure == null) throw grammar(source, at, "A nested DSL closure is required"); return closure;
        }
        private Args noClosure(String source, ASTNode at) {
            if (closure != null) throw grammar(source, at, "A closure is not allowed here"); return this;
        }
    }
    private record Route(String event, String target) {}
    private record ForkNodes(WorkflowNode fork, WorkflowNode join) {}
}
