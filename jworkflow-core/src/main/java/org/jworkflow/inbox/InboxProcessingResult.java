package org.jworkflow.inbox;
import java.util.List;import java.util.UUID;
public record InboxProcessingResult(UUID messageId,List<Object> commandResults){public InboxProcessingResult{commandResults=commandResults==null?List.of():List.copyOf(commandResults);}}
