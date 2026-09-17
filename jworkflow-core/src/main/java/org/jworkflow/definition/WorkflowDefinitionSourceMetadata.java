package org.jworkflow.definition;

import java.time.Instant;
import java.util.Objects;

/** Immutable provenance recorded only after a definition is successfully activated. */
public record WorkflowDefinitionSourceMetadata(
        String location,
        String sourceChecksum,
        Instant activatedAt
) {
    public WorkflowDefinitionSourceMetadata {
        if (location == null || location.isBlank()) throw new IllegalArgumentException("location is required");
        if (sourceChecksum == null || sourceChecksum.isBlank()) throw new IllegalArgumentException("sourceChecksum is required");
        Objects.requireNonNull(activatedAt, "activatedAt");
    }
}
