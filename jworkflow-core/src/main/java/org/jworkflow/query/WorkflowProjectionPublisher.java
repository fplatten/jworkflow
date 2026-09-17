package org.jworkflow.query;
import org.jworkflow.events.*;import java.util.Objects;import java.util.concurrent.CopyOnWriteArrayList;
/** Thread-safe projection listener hub suitable for registration as an engine EventPublisher. */
public final class WorkflowProjectionPublisher implements EventPublisher{
 private final CopyOnWriteArrayList<WorkflowProjection> listeners=new CopyOnWriteArrayList<>();
 public EventSubscription subscribe(WorkflowProjection projection){listeners.add(Objects.requireNonNull(projection));return()->listeners.remove(projection);}
 @Override public void publish(WorkflowEvent event){for(WorkflowProjection listener:listeners)listener.onEvent(event);}
}
