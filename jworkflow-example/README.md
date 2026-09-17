# Order fulfillment example

This module demonstrates how an application can use jworkflow without allowing workflow events to leak into
client or domain code.

## Package responsibilities

- `org.jworkflow.example.domain` contains the order domain model and has no jworkflow dependency.
- `org.jworkflow.example.application.command` contains the commands accepted by the application.
- `org.jworkflow.example.application.result` contains use-case results.
- `org.jworkflow.example.application.service` handles commands and implements application behavior without
  publishing workflow events.
- `org.jworkflow.example.application.port` defines the command-facing port used by clients.
- `org.jworkflow.example.infrastructure.workflow` is the anti-corruption boundary around jworkflow. Listeners
  translate workflow events into commands; the workflow command dispatcher translates application results back
  into workflow events.

The interaction is:

```text
client -> CreateOrderCommand -> application service -> order.created event
workflow event -> listener -> application command -> application service -> workflow event
```

`OrderFulfillmentExample` is the composition root. It is the only place that wires application and infrastructure
objects together.
