package org.jworkflow.events;


import java.util.Map;
import org.jworkflow.model.ImmutableData;

/**
 * Payload, media/schema metadata and redaction state shared by inbox, outbox and workflow events. Construction
 * defensively copies supported payload containers/arrays and the attributes map. A null attributes map becomes
 * empty. Persistence accepts the documented JSON value model or binary bodies, not arbitrary application objects.
 * @param payload supported event body; null denotes an absent body
 * @param contentType media type describing the body
 * @param schemaName optional payload schema name
 * @param schemaVersion optional payload schema version
 * @param redacted whether the body has been deliberately removed by a capture policy
 * @param attributes message attributes subject to the capture policy
 */
public record EventMessage(
        Object payload,
        String contentType,
        String schemaName,
        String schemaVersion,
        boolean redacted,
        Map<String, String> attributes
) {
    /**
     * Creates this value from the supplied components.
     * @param payload supported event body; null denotes an absent body
     * @param contentType media type describing the body
     * @param schemaName optional payload schema name
     * @param schemaVersion optional payload schema version
     * @param redacted whether the body has been deliberately removed by a capture policy
     * @param attributes message attributes subject to the capture policy
     */
    public EventMessage {
        payload = ImmutableData.copy(payload);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Wraps the supplied payload with application/json media type and empty optional schema/attribute metadata.
     * @param payload supported event body; null denotes an absent body
     * @return the resulting event message
     */
    public static EventMessage json(Object payload) {
        return new EventMessage(payload, "application/json", null, null, false, Map.of());
    }

    /**
     * Creates a message with no payload, media/schema metadata or attributes.
     * @return the resulting event message
     */
    public static EventMessage empty() {
        return new EventMessage(null, null, null, null, false, Map.of());
    }
}
