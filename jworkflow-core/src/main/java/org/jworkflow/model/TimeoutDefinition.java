package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Duration;
import java.util.Objects;

public record TimeoutDefinition(
        Duration duration,
        String targetNode,
        EventName emittedEvent
) {
    public TimeoutDefinition {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("timeout duration must be positive");
        }
    }
}
