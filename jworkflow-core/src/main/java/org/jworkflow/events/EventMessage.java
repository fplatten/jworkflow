package org.jworkflow.events;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

import java.util.Map;
import org.jworkflow.model.ImmutableData;

public record EventMessage(
        Object payload,
        String contentType,
        String schemaName,
        String schemaVersion,
        boolean redacted,
        Map<String, String> attributes
) {
    public EventMessage {
        payload = ImmutableData.copy(payload);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static EventMessage json(Object payload) {
        return new EventMessage(payload, "application/json", null, null, false, Map.of());
    }

    public static EventMessage empty() {
        return new EventMessage(null, null, null, null, false, Map.of());
    }
}
