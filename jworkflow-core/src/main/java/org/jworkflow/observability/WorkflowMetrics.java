package org.jworkflow.observability;

import java.time.Duration;
import java.util.Map;

public interface WorkflowMetrics {
    void increment(String metric, Map<String, String> tags);

    default void recordDuration(String metric, Duration duration, Map<String, String> tags) {
    }
}
