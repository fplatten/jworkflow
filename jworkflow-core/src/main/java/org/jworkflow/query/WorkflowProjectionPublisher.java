package org.jworkflow.query;
import org.jworkflow.events.*;import java.util.Objects;import java.util.concurrent.CopyOnWriteArrayList;
/** Thread-safe projection listener hub suitable for registration as an engine EventPublisher. */
public final class WorkflowProjectionPublisher implements EventPublisher{
    /** Creates a projection publisher with no subscribers. */
    public WorkflowProjectionPublisher() {
        // Default construction requires no additional setup.
    }

 private final CopyOnWriteArrayList<WorkflowProjection> listeners=new CopyOnWriteArrayList<>();
 /**
  * Registers a projection for event publication and returns a removal handle.
  * @param projection host projection consuming ordered history
  * @return a handle for removing the subscription
  * @throws NullPointerException if projection is null
  */
 public EventSubscription subscribe(WorkflowProjection projection){listeners.add(Objects.requireNonNull(projection));return()->listeners.remove(projection);}
 /**
  * {@inheritDoc}
  */
 @Override public void publish(WorkflowEvent event){for(WorkflowProjection listener:listeners)listener.onEvent(event);}
}
