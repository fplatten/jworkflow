package org.jworkflow.workbench;

import org.jworkflow.application.Command;
import org.springframework.shell.core.InputReader;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.DefaultCommandParser;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

/** Browser input adapter to a deliberately bounded Spring Shell registry. No system shell. */
public final class FoundationShell {
    private final CommandRegistry registry = new CommandRegistry();
    private final CommandExecutor executor = new CommandExecutor(registry);
    private final DefaultCommandParser parser = new DefaultCommandParser(registry);
    private final java.util.function.Function<String, String> assistant;

    /** /clear handler; it deletes the current conversation or explains why it cannot. */
    @FunctionalInterface public interface Clear { String run() throws java.io.IOException; }

    public FoundationShell(Runnable shutdown) {
        this(shutdown, () -> "No conversation to clear.");
    }

    public FoundationShell(Runnable shutdown, Clear clear) {
        this(shutdown, input -> "Simulated assistant: message received. No provider was contacted; no project files were accessed.", clear);
    }

    FoundationShell(Runnable shutdown, java.util.function.Function<String, String> assistant) { this(shutdown, assistant, () -> "No conversation to clear."); }

    FoundationShell(Runnable shutdown, java.util.function.Function<String, String> assistant, Clear clear) {
        this.assistant = assistant;
        register("help", context -> context.outputWriter().print("JWorkflow Workbench\n/help    commands and availability\n/core    pinned core version\n/clear   delete the current conversation and start a new one\n/build   review and run a confined, offline build without tests\n/test    review and run the full configured test suite, confined and offline\n/cancel  stop the active AI request (deleting its conversation) or the running build/test\n/exit    shut down Workbench\nAny other text is sent to the AI assistant (needs OPENAI_API_KEY and a confirmed project). Ctrl+C copies selected text; it does not cancel."));
        register("clear", context -> { try { context.outputWriter().print(clear.run()); } catch (java.io.IOException failure) { context.outputWriter().print("Could not delete the conversation: " + failure.getMessage()); } });
        register("core", context -> context.outputWriter().print("org.jworkflow:jworkflow-core:0.1.0-SNAPSHOT\n" + new CoreProbe("browser-shell").getClass().getInterfaces()[0].getName()));
        register("cancel", context -> context.outputWriter().print("No active operation."));
        register("build", context -> context.outputWriter().print("Build and test are not available in this session."));
        register("test", context -> context.outputWriter().print("Build and test are not available in this session."));
        register("exit", context -> { context.outputWriter().print("Workbench is shutting down."); shutdown.run(); });
    }

    private void register(String name, Consumer<CommandContext> command) {
        registry.registerCommand(org.springframework.shell.core.command.Command.builder().name(name).execute(command));
    }

    public String evaluate(String input) {
        if (input == null || input.isBlank()) return "";
        if (input.length() > 4096) return "Input exceeds the 4096-character limit.";
        if (input.indexOf('\u0003') >= 0) return "Ctrl+C does not cancel operations. Use /cancel or /exit.";
        String text = input.strip();
        if (!text.startsWith("/")) return assistant.apply(text);
        String name = text.substring(1);
        if (registry.getCommandByName(name) == null) return "Unknown command. Use /help; commands must match exactly.";
        StringWriter output = new StringWriter();
        executor.execute(new CommandContext(parser.parse(name), registry, new PrintWriter(output), new InputReader() { }));
        return output.toString();
    }

    /** Harmless reachability proof of the real core command boundary, without workflow execution. */
    public record CoreProbe(String source) implements Command { }
}
