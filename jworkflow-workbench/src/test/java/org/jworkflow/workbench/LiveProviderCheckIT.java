package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.mock.env.MockEnvironment;

/**
 * Opt-in live provider compatibility check (G-06): real streaming, a structured tool call and cancellation against
 * the fixed model. It makes billed requests with OPENAI_API_KEY and sends only the synthetic prompts below. Run with
 * {@code WORKBENCH_LIVE_AI=1 mvnw -Dtest=LiveProviderCheckIT test}; ordinary builds and CI never run it.
 */
@EnabledIfEnvironmentVariable(named = "WORKBENCH_LIVE_AI", matches = "1")
class LiveProviderCheckIT {
    private static ResponsesChatModel model() {
        var environment = new MockEnvironment().withProperty("spring.ai.openai.api-key", System.getenv().getOrDefault("OPENAI_API_KEY", ""));
        return new ResponsesConfiguration().responsesChatModel(environment, "https://api.openai.com", ResponsesChatModel.MODEL, "", Duration.ofSeconds(120),
                2, Duration.ofSeconds(2), 2, Duration.ofSeconds(10));
    }

    @Test void streamsTextAndReturnsAStructuredToolCall() {
        ToolCallback tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(WorkflowEditProposals.TOOL).description(WorkflowEditProposals.description()).inputSchema(WorkflowEditProposals.schema()).build();
            }
            @Override public String call(String input) { throw new UnsupportedOperationException(); }
        };
        var prompt = new Prompt(List.of(new SystemMessage(AssistantService.SYSTEM),
                new UserMessage("Discovered commands: OrderCommand. The workflow has a step 'charge' followed by end 'done'. Briefly say what you will do, then add an end node named 'archived' after 'done'.")),
                ToolCallingChatOptions.builder().toolCallbacks(tool).build());
        List<ChatResponse> responses = model().stream(prompt).collectList().block(Duration.ofMinutes(3));
        assertNotNull(responses);
        var last = responses.get(responses.size() - 1).getResult().getOutput();
        assertEquals(ResponsesChatModel.MODEL, responses.get(responses.size() - 1).getMetadata().getModel().replaceAll("-\\d{4}-\\d{2}-\\d{2}$", ""));
        assertFalse(last.getToolCalls().isEmpty(), "Expected a propose_workflow_edit call");
        var proposal = WorkflowEditProposals.validate(last.getToolCalls().get(0).arguments(), java.util.Set.of("OrderCommand"), java.util.Map.of("charge", "step", "done", "end"), "");
        assertFalse(proposal.operations().isEmpty());
        System.out.println("Live check: " + responses.size() + " stream events; proposal " + proposal.preview());
    }

    @Test void cancellationStopsALongStreamPromptly() throws Exception {
        List<String> chunks = new CopyOnWriteArrayList<>();
        var subscription = model().stream(new Prompt("Count from 1 to 400, one number per line.")).subscribe(r -> chunks.add(r.getResult().getOutput().getText()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (chunks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(chunks.isEmpty(), "No streamed output within 60 seconds");
        subscription.dispose();
        int seen = chunks.size(); Thread.sleep(1500);
        assertTrue(chunks.size() - seen <= 1, "Output continued after cancellation");
        System.out.println("Live check: cancelled after " + seen + " chunk(s)");
    }
}
