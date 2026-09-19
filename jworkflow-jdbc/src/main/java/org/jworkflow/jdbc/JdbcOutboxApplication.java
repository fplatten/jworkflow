package org.jworkflow.jdbc;

import org.jworkflow.application.RetryBackoffPolicy;
import org.jworkflow.outbox.*;
import org.jworkflow.observability.*;
import java.time.*;
    import java.util.*;
    import java.util.concurrent.*;
    import java.util.concurrent.atomic.AtomicBoolean;

/** Leased at-least-once outbox worker. Destination I/O occurs after the claim commits. */
public final class JdbcOutboxApplication implements AutoCloseable {
    private final JdbcWorkflowPersistence persistence;
        private final Clock clock;
        private final Duration lease;
        private final Duration pollInterval;
        private final int batchSize;
        private final String workerId=UUID.randomUUID().toString();
    private final OutboxPublisherService publisher;
        private final OutboxRepublishingService republishing;
        private final AtomicBoolean closed=new AtomicBoolean();
        private ScheduledExecutorService poller;
    JdbcOutboxApplication(JdbcWorkflowPersistence persistence,Map<String,DestinationPublisher> destinations,Clock clock,Map<String,String> settings,WorkflowLifecycleObserver observer){this.persistence=Objects.requireNonNull(persistence);
        this.clock=Objects.requireNonNull(clock);
        Map<String,String>s=settings==null?Map.of():settings;
        lease=Duration.ofMillis(number(s,"outbox.lease-ms",30_000,100,3_600_000));
            pollInterval=Duration.ofMillis(number(s,"outbox.poll-interval-ms",250,10,60_000));
            batchSize=(int)number(s,"outbox.batch-size",32,1,1_000);
            int max=(int)number(s,"outbox.max-attempts",5,1,100);
            Duration initial=Duration.ofMillis(number(s,"outbox.retry-initial-ms",1_000,10,3_600_000));
            Duration maximum=Duration.ofMillis(number(s,"outbox.retry-maximum-ms",60_000,10,86_400_000));
        RetryBackoffPolicy retry=new RetryBackoffPolicy(){public boolean exhausted(int attempt){return attempt>=max;
        }public Instant nextAttemptAt(int attempt,Instant now){long factor=1L<<Math.min(Math.max(attempt-1,0),30);
            Duration delay;
            try{delay=initial.multipliedBy(factor);
        }catch(ArithmeticException e){delay=maximum;
        }
    return now.plus(delay.compareTo(maximum)>0?maximum:delay);
        }};
        Map<String,DestinationPublisher> safe=Map.copyOf(destinations==null?Map.of():destinations);
            DestinationPublisher routed=message->{DestinationPublisher target=safe.get(message.destination());
            if(target==null)throw new OutboxPublicationException("No publisher registered for destination "+message.destination(),null);
            target.publish(message);
        };
        publisher=new OutboxPublisherService(persistence.outbox(),persistence.eventStatuses(),persistence.transactions(),routed,retry,clock,observer);
            republishing=new OutboxRepublishingService(persistence.outbox(),persistence.transactions());
        }
    /**
     * Releases expired leases and publishes one bounded claimed batch. Rejects an enclosing transaction so network
     * sends occur outside database transactions.
     * @return the number of items claimed in this batch
     */
    public int pollOnce(){
        if(persistence.transactions().isTransactionActive())throw new IllegalStateException("Outbox polling requires its own transaction boundaries");
        Instant now=clock.instant();
        persistence.transactions().execute(()->persistence.outbox().releaseExpiredClaims(now));
        List<OutboxMessage> claimed=persistence.jdbcTransactions().inWriteTransaction(()->persistence.outbox().claimEligibleFenced(now,workerId,now.plus(lease),batchSize));
        for(OutboxMessage message:claimed)publisher.publish(message,workerId);
        return claimed.size();
    }
    /**
     * Schedules manual republication, polls eligible work and returns the refreshed stored envelope. Receivers may
     * observe another delivery.
     * @param command command to validate and execute
     * @return the resulting outbox message
     */
    public OutboxMessage republish(RepublishOutboxMessageCommand command){OutboxMessage message=republishing.republish(command);
        pollOnce();
        return persistence.outbox().findById(message.messageId()).orElseThrow();
    }
    /**
     * Looks up a stored outbox envelope by message ID.
     * @param id identity of the value to look up or update
     * @return the matching value, or an empty optional when absent
     */
    public Optional<OutboxMessage> find(UUID id){return persistence.outbox().findById(id);
    }

    /**
     * Returns the publication attempt history.
     * @param id identity of the value to look up or update
     * @return the matching values in the order defined by this operation
     */
    public List<OutboxAttempt> attempts(UUID id){return persistence.outbox().findAttempts(id);
    }
    /**
     * Starts one owned daemon polling worker; repeated calls while started are ignored.
     */
    public void start(){if(poller!=null)return;
        poller=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"jworkflow-jdbc-outbox-"+workerId.substring(0,8));
        t.setDaemon(true);
        return t;
    });
        poller.scheduleWithFixedDelay(this::pollSafely,pollInterval.toMillis(),pollInterval.toMillis(),TimeUnit.MILLISECONDS);
    }private void pollSafely(){if(closed.get())return;
        try{pollOnce();
    }catch(RuntimeException ignored){
            // Durable claims remain retryable; the next scheduled poll will try again.
        }}
    /**
     * {@inheritDoc}
     */
    @Override public void close(){if(!closed.compareAndSet(false,true))return;
        if(poller!=null){poller.shutdown();
        try{if(!poller.awaitTermination(5,TimeUnit.SECONDS))poller.shutdownNow();
    }catch(InterruptedException e){poller.shutdownNow();
        Thread.currentThread().interrupt();
    }}}
    private static long number(Map<String,String>s,String key,long fallback,long min,long max){long value=Long.parseLong(s.getOrDefault(key,Long.toString(fallback)));
        if(value<min||value>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max);
        return value;
    }
}
