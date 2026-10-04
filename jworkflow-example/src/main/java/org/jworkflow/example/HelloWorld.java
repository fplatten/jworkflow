package org.jworkflow.example;

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
     * @param args command-line arguments for the example; {@code --message=...} sets the greeting
     */
    public static void main(String[] args) {
        WorkflowSnapshot snapshot = run(args);
        LOGGER.info(() -> String.valueOf(snapshot.variables().get("message")));
        LOGGER.info(() -> "Workflow instance: " + snapshot.instanceId());
        LOGGER.info(() -> "Workflow key: " + snapshot.workflowKey());
        LOGGER.info(() -> "State: " + snapshot.state());
        LOGGER.info(() -> "Status: " + snapshot.status());
    }

    /**
     * Defines a one-node workflow, starts it in an in-memory engine and returns the finished instance.
     * @param args command-line arguments; {@code --message=...} sets the greeting variable
     * @return snapshot of the completed hello-world instance
     */
    static WorkflowSnapshot run(String[] args) {
        WorkflowDefinition helloWorld = WorkflowDefinition.of("hello-world", "1", "done", WorkflowNode.end("done"));
        try (WorkflowEngine engine = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.IN_MEMORY)
                .definition(helloWorld)
                .build()) {
            WorkflowInstanceId instanceId = engine.start("hello-world", "hello-1", Map.of("message", messageFrom(args)));
            return engine.snapshot(instanceId);
        }
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
