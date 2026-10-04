package org.jworkflow.internal.engine;

import org.jworkflow.engine.StartWorkflowCommand;
import org.jworkflow.engine.WorkflowCommandMetadata;
import org.jworkflow.events.WorkflowEvent;
import org.jworkflow.internal.model.WorkflowDefinitionRegistry;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.model.WorkflowNode;
import org.jworkflow.model.WorkflowNodeType;
import org.jworkflow.model.WorkflowSnapshot;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The rules every engine uses to turn a published event into workflow starts and deliveries, so an event has the
 * same effect in memory and in a database.
 */
public final class EventCorrelation {
    private static final String BUSINESS_KEY = "businessKey";

    private EventCorrelation() {
    }

    /**
     * Returns the workflows an event starts: the latest version of each workflow that declares the event as its
     * start event, or that the engine builder maps the event to.
     * @param definitions registered definitions
     * @param starts event name to workflow name mappings from the engine builder
     * @param eventName published event name
     * @return one definition per workflow name, ordered by name
     */
    public static List<WorkflowDefinition> startedBy(
            WorkflowDefinitionRegistry definitions, Map<String, String> starts, String eventName) {
        String configured = starts.get(eventName);
        return definitions.snapshot().values().stream()
                .filter(definition -> eventName.equals(definition.metadata().get("startEvent"))
                        || definition.name().equals(configured))
                .map(WorkflowDefinition::name)
                .distinct()
                .sorted()
                .map(definitions::latest)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Reads the value an event carries for a correlation field: a header, then a payload field, then the event's
     * business key.
     * @param field correlation field, or null to use the business key
     * @param event published event
     * @return the value, or null when the event carries none
     */
    public static String correlationValue(String field, WorkflowEvent event) {
        Object value = null;
        if (field != null && !field.isBlank()) {
            value = event.metadata().headers().get(field);
            if (value == null && event.message().payload() instanceof Map<?, ?> payload) {
                value = payload.get(field);
            }
        }
        if (value == null) {
            value = event.metadata().businessKey();
        }
        if (value == null) {
            value = event.metadata().headers().get(BUSINESS_KEY);
        }
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /**
     * Reads the value an event carries for an instance's current correlation field: the waiting node's
     * {@code correlateBy} when it has one, otherwise the workflow's.
     * @param definition the instance's definition
     * @param snapshot the instance
     * @param event published event
     * @return the value, or null when the event carries none
     */
    public static String correlationValue(WorkflowDefinition definition, WorkflowSnapshot snapshot, WorkflowEvent event) {
        WorkflowNode node = definition.nodes().get(snapshot.state());
        String field = node != null && node.waitDefinition() != null
                && node.waitDefinition().correlateBy() != null && !node.waitDefinition().correlateBy().isBlank()
                ? node.waitDefinition().correlateBy()
                : definition.metadata().get("correlateBy");
        return correlationValue(field, event);
    }

    /**
     * Returns every business key an event could address in a workflow: its value for the workflow's
     * {@code correlateBy} field and for each wait node's own field.
     * @param definition workflow definition
     * @param event published event
     * @return the distinct values the event carries, possibly none
     */
    public static Set<String> candidateBusinessKeys(WorkflowDefinition definition, WorkflowEvent event) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.add(correlationValue(definition.metadata().get("correlateBy"), event));
        definition.nodes().values().stream()
                .filter(node -> node.type() == WorkflowNodeType.WAIT && node.waitDefinition() != null)
                .map(node -> node.waitDefinition().correlateBy())
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .forEach(field -> values.add(correlationValue(field, event)));
        values.remove(null);
        return values;
    }

    /**
     * Returns the variables a workflow started by an event begins with: the event's headers and payload fields.
     * @param event published event
     * @param businessKey the started instance's business key
     * @return the initial variables
     */
    public static Map<String, Object> eventVariables(WorkflowEvent event, String businessKey) {
        LinkedHashMap<String, Object> variables = new LinkedHashMap<>(event.metadata().headers());
        if (event.message().payload() instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    variables.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }
        variables.putIfAbsent(BUSINESS_KEY, businessKey);
        return variables;
    }

    /**
     * Builds the command that starts a workflow for an event. Its idempotency key is derived from the event id, so
     * the same event never starts the same workflow twice.
     * @param definition definition to start
     * @param businessKey the new instance's business key
     * @param event published event
     * @return the start command
     */
    public static StartWorkflowCommand startCommand(WorkflowDefinition definition, String businessKey, WorkflowEvent event) {
        var metadata = event.metadata();
        String correlationId = metadata.correlationId() == null || metadata.correlationId().isBlank()
                ? metadata.eventId().toString()
                : metadata.correlationId();
        return new StartWorkflowCommand(
                definition.name(),
                definition.version(),
                businessKey,
                eventVariables(event, businessKey),
                new WorkflowCommandMetadata(
                        null, metadata.eventId() + ":" + definition.key(), definition.name(), definition.version(),
                        null, businessKey, correlationId,
                        metadata.eventId().toString(), metadata.traceId(),
                        metadata.tenantId(), metadata.sourceSystem(), null,
                        metadata.receivedAt(), metadata.headers()));
    }
}
