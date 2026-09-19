package org.jworkflow.inbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Computes capped exponential retry deadlines without sleeping. The configured maximum attempt count includes the
 * failed attempt being evaluated.
 * @param maxAttempts maximum allowed attempts, including the initial attempt
 * @param initialDelay initial nonnegative retry delay
 * @param maximumDelay upper bound on the exponential retry delay
 */
public record ExponentialInboxRetryPolicy(int maxAttempts, Duration initialDelay, Duration maximumDelay)
        implements InboxRetryPolicy {
    /**
     * Creates this value from the supplied components.
     * @param maxAttempts maximum allowed attempts, including the initial attempt
     * @param initialDelay initial nonnegative retry delay
     * @param maximumDelay upper bound on the exponential retry delay
     * @throws NullPointerException if initialDelay, maximumDelay is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public ExponentialInboxRetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        Objects.requireNonNull(initialDelay);
            Objects.requireNonNull(maximumDelay);
        if (initialDelay.isNegative() || initialDelay.isZero() || maximumDelay.compareTo(initialDelay)<0)
            throw new IllegalArgumentException("retry delays are invalid");
    }
    /**
     * {@inheritDoc}
     */
    @Override public boolean exhausted(int attempt){return attempt>=maxAttempts;
    }
    /**
     * {@inheritDoc}
     */
    @Override public Instant nextAttemptAt(int attempt,Instant now){long factor=1L<<Math.min(Math.max(attempt-1,0),30);
        Duration delay;
        try{delay=initialDelay.multipliedBy(factor);
    }catch(ArithmeticException e){delay=maximumDelay;
    }
        if(delay.compareTo(maximumDelay)>0) {
            delay=maximumDelay;
        }
        return now.plus(delay);
    }
}
