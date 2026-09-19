package org.jworkflow.definition;

import java.util.List;

/**
 * Describes an activation attempt without replacing a last-known-good definition on rejection. The error list is
 *  defensively copied.
 * @param status activation outcome
 * @param source source provenance recorded on successful activation
 * @param active active definition retained after the attempt, when available
 * @param errors validation error details
 */
public record DefinitionActivationResult(
        DefinitionActivationStatus status,
        WorkflowDefinitionSourceMetadata source,
        ActivatedWorkflowDefinition active,
        List<String> errors
) {
    /**
     * Creates this value from the supplied components.
     * @param status activation outcome
     * @param source source provenance recorded on successful activation
     * @param active active definition retained after the attempt, when available
     * @param errors validation error details
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public DefinitionActivationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (status != DefinitionActivationStatus.REJECTED && active == null) {
            throw new IllegalArgumentException("Successful activation requires an active definition");
        }
        if (status == DefinitionActivationStatus.REJECTED && errors.isEmpty()) {
            throw new IllegalArgumentException("Rejected activation requires an error");
        }
    }

    /**
     * Tests whether this activation or execution result represents success.
     * @return true when the condition described above holds; false otherwise
     */
    public boolean successful() { return status != DefinitionActivationStatus.REJECTED; }
}
