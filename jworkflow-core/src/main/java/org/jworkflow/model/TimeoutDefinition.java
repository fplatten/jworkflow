package org.jworkflow.model;

import org.jworkflow.definition.*;
import org.jworkflow.dsl.*;
import org.jworkflow.engine.*;
import org.jworkflow.events.*;
import org.jworkflow.model.*;
import org.jworkflow.persistence.*;

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
