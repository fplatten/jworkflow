package org.jworkflow.application;
import java.time.Instant;
public interface RetryBackoffPolicy {boolean exhausted(int failedAttemptNumber);Instant nextAttemptAt(int failedAttemptNumber,Instant now);}
