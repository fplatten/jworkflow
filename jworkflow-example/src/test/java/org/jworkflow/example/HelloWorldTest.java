package org.jworkflow.example;

import org.jworkflow.model.WorkflowSnapshot;
import org.jworkflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The first example a new user runs must work as written. */
class HelloWorldTest {
    @Test
    void runsToCompletionWithTheSuppliedMessage() {
        WorkflowSnapshot snapshot = HelloWorld.run(new String[]{"--other", "--message=Hi there"});
        assertEquals("hello-world", snapshot.workflowKey());
        assertEquals("done", snapshot.state());
        assertEquals(WorkflowStatus.COMPLETED, snapshot.status());
        assertEquals("Hi there", snapshot.variables().get("message"));
    }

    @Test
    void usesTheDefaultGreetingWithoutAMessageArgument() {
        assertEquals("Hello from org.jworkflow core", HelloWorld.run(new String[0]).variables().get("message"));
    }

    @Test
    void mainLogsTheGreetingAndFinalStatus() {
        Logger logger = Logger.getLogger(HelloWorld.class.getName());
        List<String> messages = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord logRecord) { messages.add(logRecord.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(capture);
        try {
            HelloWorld.main(new String[]{"--message=Logged"});
        } finally {
            logger.removeHandler(capture);
        }
        assertTrue(messages.contains("Logged"), messages.toString());
        assertTrue(messages.contains("Status: COMPLETED"), messages.toString());
    }
}
