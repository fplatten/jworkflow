# Groovy DSL reference

Workflow definitions can be written in a restricted Groovy DSL and compiled with `GroovyWorkflowDslCompiler`, or
loaded with `ClasspathWorkflowDefinitionSource` / `FileSystemWorkflowDefinitionSource`. The DSL produces the same
immutable model as the Java `WorkflowDefinitionBuilder`.

The source is only parsed, never compiled or executed. Every construct is denied unless listed here: imports,
classes, methods, annotations, variables, loops, string interpolation and arbitrary method calls are all rejected.
This is not an operating-system sandbox, so load definitions only from trusted locations.

## A complete example

```groovy
workflow("order-fulfillment") {
    version "1.0.0"
    correlateBy "orderId"
    start when: "order.created"

    step("reserveInventory") {
        on "order.created"
        retry maxAttempts: 3, backoff: "PT30S"
        run { event, context ->
            context.listener("inventoryListener").onEvent(event)
        }
        then waitFor "inventory.reserved", goTo: "chargePayment"
        onFailure goTo: "failed"
    }

    step("chargePayment") {
        on "inventory.reserved"
        timeout "PT5M", emit: "payment.delayed"
        then waitFor "payment.charged", goTo: "route"
        onFailure goTo: "failed"
    }

    gateway("route", type: "exclusive") {
        when variable: "express", eq: true, goTo: "shipExpress"
        otherwise goTo: "awaitShipment"
    }

    step("shipExpress") {
        action "shipment.expedite"
        onSuccess goTo: "completed"
    }

    waitFor("awaitShipment") {
        event "shipment.created"
        correlateBy "orderId"
        then goTo: "completed"
        timeout "P2D", goTo: "failed"
    }

    end("completed")
    end("failed")
}
```

## Names and values

- **Event names** use `subject.action`: one dot, lowercase letters, digits and underscores, and a past-tense action
  ending in `ed` (or `taken`), e.g. `order.created`, `tax_form.completed`.
- **Durations** are ISO-8601 strings: `"PT30S"`, `"PT5M"`, `"P2D"`.
- **Literals** may be strings, numbers, booleans, `null`, lists and maps of these.
- Each construct may be declared once per block unless stated otherwise.

## Workflow

```groovy
workflow("name") { ... }
```

Exactly one top-level `workflow` per source.

| Statement | Meaning |
| --- | --- |
| `version "1.0.0"` | Required. Definitions are immutable per name and version. |
| `correlateBy "field"` | Variable used to correlate events with instances. |
| `start at: "node"` | Start at a named node. A name that does not exist is reported as `start.unknown`. |
| `start when: "event.name"` | Start when this event arrives; the first declared node is the start node. |
| `step`, `waitFor`, `gateway`, `fork`, `loop`, `subWorkflow`, `end` | Declare nodes (below). |

## step

```groovy
step("name") { ... }
```

| Statement | Meaning |
| --- | --- |
| `action "name"` | Step-handler action. Defaults to the step name, or is derived from `then waitFor`. |
| `on "event.name"` | The event that brings the workflow into this step. It is checked: it must be the workflow's start event (for the start step) or be emitted on a transition into the step. |
| `run { event, context -> context.listener("id").method(arg) }` | Invoke a registered listener instead of a step handler. `arg` is `event`, `context` or a literal; at most one argument. `Object` methods such as `getClass` are rejected. |
| `retry maxAttempts: 3, backoff: "PT30S"` | Retry a failed step automatically. |
| `timeout "PT5M", goTo: "node"` | Move to `node` if the step is still current after the duration. |
| `timeout "PT5M", emit: "event.name"` | Emit an event and stay in the step. `goTo:` and `emit:` can be combined; at least one is required. |
| `onSuccess goTo: "node"` | Success transition. |
| `onFailure goTo: "node"` | Failure transition, taken once retries are used up. |
| `then waitFor "event.name", goTo: "node"` | Success transition that emits `event.name`. |
| `then end("node")` | Success transition to an end node. |

`sla` is not supported; use an emit-only `timeout` to announce a breach.

## waitFor

```groovy
waitFor("name") {
    event "approval.granted"
    correlateBy "orderId"
    then goTo: "next"
    timeout "P1D", goTo: "expired"
}
```

`event` and `then goTo:` are required; `correlateBy` and `timeout` are optional.

## gateway

```groovy
gateway("name", type: "exclusive") {
    when variable: "amount", gt: 1000, goTo: "review"
    when predicate: "isVip", arguments: [tier: "gold"], goTo: "fastTrack"
    otherwise goTo: "standard"
}
```

The first matching `when` wins; `otherwise` is the fallback. Only `exclusive` is supported: `parallel` and `inclusive`
are rejected by validation.

Conditions compare a variable with one operator: `eq`, `ne`, `gt`, `gte`, `lt`, `lte` or `contains`. A
`predicate:` names a function registered with `WorkflowEngineBuilder.branchPredicate(...)`, with optional `arguments:`.

## loop

```groovy
loop("retryNotification") {
    whileCondition variable: "pending", eq: true
    maxIterations 3
    doStep "notify"
    then goTo: "done"
}
```

While the condition holds and fewer than `maxIterations` iterations have run, the loop enters `doStep`; that step
normally routes back to the loop. Otherwise it continues at `then`. The counter resets whenever the loop exits.

## fork and join

```groovy
fork("fulfil") {
    branch "pick", goTo: "pickItems"
    branch "bill", goTo: "sendInvoice"
    join "fulfilled", whenComplete: ["pick", "bill"], goTo: "ship", emit: "order.fulfilled"
}
```

A workflow instance has one current state, so branch targets are not executed by the engine. On entering the fork,
the engine publishes one `branch.started` event per branch (carrying a `forkExecutionId`) and waits at the join. The
join completes when signals carrying that `forkExecutionId` report every branch in `whenComplete`.

## subWorkflow

```groovy
subWorkflow("checkCredit") {
    workflow "credit-check", version: "1"
    input variable: "customerId", as: "customer"
    onSuccess emit: "credit.approved", goTo: "approved"
    onFailure emit: "credit.rejected", goTo: "rejected"
}
```

The child runs to completion inside the calling step. Only a COMPLETED child takes `onSuccess`. A child that fails or
is cancelled takes `onFailure`, and so does a child that would have to wait for an event or timer (it is cancelled).
Nesting is limited to 16 levels; deeper calls take `onFailure`.

## end

```groovy
end("completed")
```

A terminal node. Reaching it completes the workflow.

## Validation

After parsing, every definition goes through the same validation as Java-built definitions. Notable rules:

- every transition target must exist, and an end node must be reachable from the start;
- a cycle made only of gateways, forks, sub-workflow calls and loop exits is rejected (`routing.cycle`), because it
  would never wait for input;
- a timeout needs a target, an event, or both (`timeout.action.required`);
- gateways must be exclusive (`gateway.type.unsupported`).

## Limits

Sources are bounded by `DslCompilerOptions` (defaults in brackets): source characters (256,000), AST nodes (20,000),
nesting depth (64), collection entries (1,000), workflow nodes (2,000), transitions (10,000), string length (32,000)
and numeric digits (128). Pass custom limits with `WorkflowEngineBuilder.dslCompilerOptions(...)`.
