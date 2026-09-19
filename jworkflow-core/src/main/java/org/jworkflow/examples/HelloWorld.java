package org.jworkflow.examples;

import org.jworkflow.engine.*;
import org.jworkflow.model.*;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Minimal executable demonstration of defining and running an in-memory workflow.
 */
public final class HelloWorld {
    private static final Logger LOGGER = Logger.getLogger(HelloWorld.class.getName());
    private HelloWorld() {
    }

    /**
     * Runs this executable example using the supplied command-line configuration.
     * @param args command-line arguments for the example
     * @throws ClassNotFoundException if a required optional implementation or driver is unavailable
     */
    public static void main(String[] args) throws ClassNotFoundException {
        WorkflowEngine workflowEngine = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.IN_MEMORY)
                .build();
        WorkflowInstanceId instanceId = workflowEngine.start(
                "hello-world",
                "hello-1",
                Map.of("message", messageFrom(args)));

        WorkflowSnapshot snapshot = workflowEngine.snapshot(instanceId);

        LOGGER.info(() -> String.valueOf(snapshot.variables().get("message")));
        LOGGER.info(() -> "Workflow instance: " + snapshot.instanceId());
        LOGGER.info(() -> "Workflow key: " + snapshot.workflowKey());
        LOGGER.info(() -> "State: " + snapshot.state());
        LOGGER.info(() -> "Status: " + snapshot.status());
    }

    private static String messageFrom(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--message=")) {
                return arg.substring("--message=".length());
            }
        }
        return "Hello from org.jworkflow core";
    }
}
