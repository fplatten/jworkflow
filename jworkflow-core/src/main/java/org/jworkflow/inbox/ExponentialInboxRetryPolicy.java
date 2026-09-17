package org.jworkflow.inbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ExponentialInboxRetryPolicy(int maxAttempts, Duration initialDelay, Duration maximumDelay)
        implements InboxRetryPolicy {
    public ExponentialInboxRetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        Objects.requireNonNull(initialDelay);Objects.requireNonNull(maximumDelay);
        if (initialDelay.isNegative() || initialDelay.isZero() || maximumDelay.compareTo(initialDelay)<0)
            throw new IllegalArgumentException("retry delays are invalid");
    }
    @Override public boolean exhausted(int attempt){return attempt>=maxAttempts;}
    @Override public Instant nextAttemptAt(int attempt,Instant now){long factor=1L<<Math.min(Math.max(attempt-1,0),30);Duration delay;try{delay=initialDelay.multipliedBy(factor);}catch(ArithmeticException e){delay=maximumDelay;}if(delay.compareTo(maximumDelay)>0)delay=maximumDelay;return now.plus(delay);}
}
