package org.jworkflow.inbox;
import java.util.List;import java.util.UUID;
/**
 * Immutable message identity and ordered results of the commands dispatched while processing it.
 * @param messageId durable message identity
 * @param commandResults immutable command replay-result repository
 */
public record InboxProcessingResult(UUID messageId,List<Object> commandResults){

    /**
     * Creates this value from the supplied components.
     * @param messageId durable message identity
     * @param commandResults ordered outcomes of dispatched commands
     */
    public InboxProcessingResult{commandResults=commandResults==null?List.of():List.copyOf(commandResults);}}
