package org.jworkflow.security;

import org.jworkflow.events.*;
import java.util.Map;
import java.util.Objects;

/** Retains routing metadata while replacing the payload and message attributes with a redaction marker. */
public enum MetadataOnlyEventPolicy implements EventCapturePolicy {
    /**
     * Shared stateless instance.
     */
    INSTANCE;
    /**
     * {@inheritDoc}
     */
    @Override public WorkflowEvent filter(WorkflowEvent event) {
        Objects.requireNonNull(event, "event");
        EventMessage original = event.message();
        return new WorkflowEvent(event.metadata(), new EventMessage(null, original.contentType(),
                original.schemaName(), original.schemaVersion(), true, Map.of()));
    }
}
