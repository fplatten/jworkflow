package org.jworkflow.model;


import java.time.Duration;

/**
 * Maximum attempts and fixed backoff for a workflow step; this is distinct from the capped exponential
 * inbox/outbox policy.
 * @param maxAttempts maximum allowed attempts, including the initial attempt
 * @param backoff nonnegative delay before another attempt; null becomes zero
 */
public record RetryPolicy(
        int maxAttempts,
        Duration backoff
) {
    /**
     * Creates this value from the supplied components.
     * @param maxAttempts maximum allowed attempts, including the initial attempt
     * @param backoff nonnegative delay before another attempt; null becomes zero
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
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
