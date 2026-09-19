package org.jworkflow.definition;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable provenance recorded only after a definition is successfully activated.
 * @param location source location used for provenance and diagnostics
 * @param sourceChecksum checksum of the original source text
 * @param activatedAt successful activation time
 */
public record WorkflowDefinitionSourceMetadata(
        String location,
        String sourceChecksum,
        Instant activatedAt
) {
    /**
     * Creates this value from the supplied components.
     * @param location source location used for provenance and diagnostics
     * @param sourceChecksum checksum of the original source text
     * @param activatedAt successful activation time
     * @throws NullPointerException if activatedAt is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public WorkflowDefinitionSourceMetadata {
        if (location == null || location.isBlank()) throw new IllegalArgumentException("location is required");
        if (sourceChecksum == null || sourceChecksum.isBlank()) throw new IllegalArgumentException("sourceChecksum is required");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }
}
