package org.jworkflow.application;
import java.time.Instant;
/**
 * Determines whether a failed attempt has exhausted its budget and when another attempt may run. Attempt numbers
 * are supplied by the caller; implementations do not sleep or execute work.
 */
public interface RetryBackoffPolicy {

    /**
     * Tests whether the failed attempt has reached the configured maximum attempts.
     * @param failedAttemptNumber one-based number of the attempt that just failed
     * @return true when the condition described above holds; false otherwise
     */
    boolean exhausted(int failedAttemptNumber);

    /**
     * Calculates the next capped exponential retry deadline from the failed attempt and supplied clock instant.
     * @param failedAttemptNumber one-based number of the attempt that just failed
     * @param now clock instant used for eligibility or retry calculation
     * @return the timestamp, or null when the stored timestamp is absent
     */
    Instant nextAttemptAt(int failedAttemptNumber,Instant now);}
