package org.jworkflow.observability;

import java.time.Duration;
import java.util.Map;

/**
 * Framework-neutral counter/timing boundary used by the lifecycle metrics adapter.
 */
public interface WorkflowMetrics {
    /**
     * Increments the named counter with the supplied dimensions.
     * @param metric metric name to record
     * @param tags dimensions attached to the metric
     */
    void increment(String metric, Map<String, String> tags);

    /**
     * Records a duration sample with the supplied metric dimensions.
     * @param metric metric name to record
     * @param duration duration used by the timeout or measurement
     * @param tags dimensions attached to the metric
     */
    default void recordDuration(String metric, Duration duration, Map<String, String> tags) {
    }
}
