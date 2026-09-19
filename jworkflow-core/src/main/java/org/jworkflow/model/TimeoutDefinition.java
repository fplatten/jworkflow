package org.jworkflow.model;

import org.jworkflow.events.*;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeout duration and the optional transition/event to apply when the corresponding timer fires.
 * @param duration duration used by the timeout or measurement
 * @param targetNode node to enter after the transition
 * @param emittedEvent event emitted by the transition when configured
 */
public record TimeoutDefinition(
        Duration duration,
        String targetNode,
        EventName emittedEvent
) {
    /**
     * Creates this value from the supplied components.
     * @param duration duration used by the timeout or measurement
     * @param targetNode node to enter after the transition
     * @param emittedEvent event emitted by the transition when configured
     * @throws NullPointerException if duration is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public TimeoutDefinition {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("timeout duration must be positive");
        }
    }
}
