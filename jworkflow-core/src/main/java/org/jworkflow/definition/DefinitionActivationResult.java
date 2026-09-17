package org.jworkflow.definition;

import java.util.List;

public record DefinitionActivationResult(
        DefinitionActivationStatus status,
        WorkflowDefinitionSourceMetadata source,
        ActivatedWorkflowDefinition active,
        List<String> errors
) {
    public DefinitionActivationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (status != DefinitionActivationStatus.REJECTED && active == null) {
            throw new IllegalArgumentException("Successful activation requires an active definition");
        }
        if (status == DefinitionActivationStatus.REJECTED && errors.isEmpty()) {
            throw new IllegalArgumentException("Rejected activation requires an error");
        }
    }

    public boolean successful() { return status != DefinitionActivationStatus.REJECTED; }
}
