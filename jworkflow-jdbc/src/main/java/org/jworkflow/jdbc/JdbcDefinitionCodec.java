package org.jworkflow.jdbc;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jworkflow.model.ListenerArgument;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.persistence.PersistenceSerializationException;

/**
 * Encodes immutable workflow graphs as deterministic versioned JSON, including declarative listener arguments and
 * optional DSL provenance.
 */
final class JdbcDefinitionCodec {
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .addMixIn(ListenerArgument.class, ListenerArgumentType.class)
            .build();

    String write(WorkflowDefinition definition) {
        try { return mapper.writeValueAsString(definition); }
        catch (Exception failure) { throw new PersistenceSerializationException("Cannot encode workflow definition", failure); }
    }

    WorkflowDefinition read(String json) {
        try { return mapper.readValue(json, WorkflowDefinition.class); }
        catch (Exception failure) { throw new PersistenceSerializationException("Cannot decode workflow definition", failure); }
    }

    /**
     * Jackson type discriminator used when serializing the restricted listener argument variants.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ListenerArgument.CurrentEvent.class, name = "event"),
            @JsonSubTypes.Type(value = ListenerArgument.CurrentContext.class, name = "context"),
            @JsonSubTypes.Type(value = ListenerArgument.Literal.class, name = "literal")
    })
    private interface ListenerArgumentType { }
}
