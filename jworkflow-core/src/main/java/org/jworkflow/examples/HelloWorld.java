package org.jworkflow.examples;

import org.jworkflow.engine.*;
import org.jworkflow.model.*;

import java.util.Map;

public final class HelloWorld {
    private HelloWorld() {
    }

    public static void main(String[] args) throws ClassNotFoundException {
        WorkflowEngine workflowEngine = WorkflowEngine.builder()
                .type(WorkflowEngine.Type.IN_MEMORY)
                .build();
        WorkflowInstanceId instanceId = workflowEngine.start(
                "hello-world",
                "hello-1",
                Map.of("message", messageFrom(args)));

        WorkflowSnapshot snapshot = workflowEngine.snapshot(instanceId);

        System.out.println(snapshot.variables().get("message"));
        System.out.println("Workflow instance: " + snapshot.instanceId());
        System.out.println("Workflow key: " + snapshot.workflowKey());
        System.out.println("State: " + snapshot.state());
        System.out.println("Status: " + snapshot.status());
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
