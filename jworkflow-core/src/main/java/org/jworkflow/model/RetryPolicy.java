package org.jworkflow.model;


import java.time.Duration;

public record RetryPolicy(
        int maxAttempts,
        Duration backoff
) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        backoff = backoff == null ? Duration.ZERO : backoff;
        if (backoff.isNegative()) {
            throw new IllegalArgumentException("backoff must not be negative");
        }
    }
}
