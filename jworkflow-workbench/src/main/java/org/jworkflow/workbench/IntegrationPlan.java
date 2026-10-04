package org.jworkflow.workbench;

import java.util.List;
import java.util.Objects;

/**
 * Renders the Markdown command-integration plan saved under {@code .jworkflow/} (INT-05). It keeps proposed, applied,
 * statically validated and tested states distinct and never claims verification for a revision it did not see.
 */
final class IntegrationPlan {
    static final String PATH = ".jworkflow/integration-plan.md";

    /**
     * What Workbench last applied, or null when nothing has been applied. {@code earlierEngineIds}/{@code earlierPaths}
     * cover the latest and the one previous applied set, so renames stay visible after the new name is applied.
     */
    record Applied(long revision, String engineId, String version, List<String> earlierEngineIds, List<String> earlierPaths) {}

    /** Last /build or /test outcome; {@code current} is false once sources changed after it ran. */
    record Verification(String kind, String status, String at, boolean current) {}

    private IntegrationPlan() {}

    static String render(ProjectWorkspace.DraftView draft, ProjectWorkspace.ProjectProfile profile,
            WorkflowGenerationService.GenerationResult generation, SourceIntegration.Result integration, Applied applied) {
        return render(draft, profile, generation, integration, applied, null);
    }

    static String render(ProjectWorkspace.DraftView draft, ProjectWorkspace.ProjectProfile profile,
            WorkflowGenerationService.GenerationResult generation, SourceIntegration.Result integration, Applied applied, Verification verification) {
        StringBuilder md = new StringBuilder();
        md.append("# Integration plan: ").append(text(draft.name().isBlank() ? draft.engineId() : draft.name())).append("\n\n");
        md.append("This plan was written by JWorkflow Workbench. It lists what Workbench proposes or applied and what you wire yourself. ")
          .append("Workbench never edits services, dispatchers, application configuration, build files or tests.\n\n");

        md.append("## Status\n\n");
        md.append("| Item | State |\n| --- | --- |\n");
        md.append("| Workflow | `").append(text(draft.engineId())).append("` version `").append(text(draft.version())).append("`, document revision ").append(draft.revision()).append(" |\n");
        md.append("| Static validation (Generate) | ").append(generation.state().equals("ready") ? "Passed for revision " + draft.revision() : "Blocked for revision " + draft.revision() + " — see problems below").append(" |\n");
        if (applied == null) md.append("| Source changes | Proposed only; nothing has been applied |\n");
        else if (applied.revision() == draft.revision()) md.append("| Source changes | Applied for revision ").append(applied.revision()).append(" |\n");
        else md.append("| Source changes | Last applied for revision ").append(applied.revision()).append("; the workflow has changed since, so the current proposal is not applied |\n");
        if (verification == null) md.append("| Build and tests | Not run by Workbench. |\n\n");
        else md.append("| Build and tests | Last `/").append(verification.kind()).append("` ").append(verification.status()).append(" at ").append(verification.at())
                .append(verification.current() ? " for the current sources |\n\n" : "; sources changed afterwards, so it does not describe the current sources |\n\n");

        List<WorkflowGenerationService.Diagnostic> problems = generation.diagnostics();
        if (!problems.isEmpty()) {
            md.append("## Problems from Generate\n\n");
            for (var d : problems) md.append("- **").append(d.severity()).append("** `").append(d.code()).append("`: ").append(text(d.message())).append('\n');
            md.append('\n');
        }

        if (integration != null) {
            md.append("## Artifacts\n\n| Kind | Class or file | Path | Proposal |\n| --- | --- | --- | --- |\n");
            for (var a : integration.artifacts())
                md.append("| ").append(a.kind()).append(" | `").append(text(a.className())).append("` | `").append(text(a.path())).append("` | ").append(a.status()).append(" |\n");
            md.append('\n');

            md.append("## Commands, listeners and payload mapping\n\n");
            md.append("Each step action runs through a listener registered as the step handler for that action. ")
              .append("The listener builds the command from the step's event context and calls the service method you pass in.\n\n");
            for (var a : integration.artifacts()) {
                if (!a.kind().equals("listener")) continue;
                md.append("### Action `").append(text(a.action())).append("` → `").append(text(a.className())).append("`\n\n");
                if (!a.note().isBlank()) md.append(text(a.note())).append("\n\n");
                for (String line : a.mapping()) md.append("- ").append(text(line)).append('\n');
                md.append('\n');
            }

            md.append("## Wiring you add\n\n");
            md.append("Register the workflow definition and one step handler per action, passing the service method that handles each command:\n\n```java\n");
            md.append("WorkflowEngine engine = WorkflowEngine.builder()\n");
            md.append("        .definitions(").append(definitionSource(draft, integration.groovyPath())).append(")\n");
            for (var a : integration.artifacts())
                if (a.kind().equals("listener"))
                    md.append("        .stepHandler(\"").append(a.action()).append("\", new ").append(simple(a.className())).append("(yourService::handle))\n");
            md.append("        .build();\n```\n\n");
            md.append("Replace `yourService::handle` with the service method for each command. If no service or dispatcher exists yet, write it; Workbench does not generate one.\n\n");
            md.append("A generated step runs when its workflow instance receives an event or signal, not when the instance is created. ")
              .append("Start the workflow with `engine.start(\"").append(text(draft.engineId())).append("\", businessKey, variables)` and deliver the event that drives each step, ")
              .append("for example with `engine.signal(instanceId, signal)` or by publishing the event your listener receives.\n\n");

            md.append("## Build, resources and dependencies\n\n");
            md.append("- Dependency: `org.jworkflow:jworkflow-core:0.1.0-SNAPSHOT`");
            md.append(profile == null || profile.coreVersion().equals("unresolved") ? " — Workbench could not confirm it in your build file; add it if it is missing.\n" : " (found in your build file).\n");
            md.append("- Java target: ").append(profile == null ? "unresolved" : text(profile.javaTarget())).append(". Generated Java needs Java 17 or later (records).\n");
            md.append("- Workflow file: `").append(text(integration.groovyPath())).append("`").append(integration.groovyPath().startsWith("src/main/resources/")
                    ? " is packaged on the classpath by your build.\n" : " is outside `src/main/resources`; load it with `FileSystemWorkflowDefinitionSource` or move it.\n");
            md.append('\n');

            md.append("## Discrepancies and manual cleanup\n\n");
            List<String> cleanup = new java.util.ArrayList<>(integration.discrepancies());
            if (applied != null) {
                for (String earlier : applied.earlierEngineIds())
                    if (!Objects.equals(earlier, draft.engineId()))
                        cleanup.add("The engine ID changed from `" + earlier + "` to `" + draft.engineId() + "`. Running instances and callers that use the old ID are not migrated.");
                List<String> current = integration.artifacts().stream().map(SourceIntegration.Artifact::path).toList();
                for (String path : applied.earlierPaths())
                    if (!current.contains(path)) cleanup.add("`" + path + "` was applied earlier but is no longer part of this workflow. Workbench left it in place; delete it and its references yourself if it is obsolete.");
            }
            if (cleanup.isEmpty()) md.append("None.\n");
            else for (String line : cleanup) md.append("- ").append(text(line)).append('\n');
        }
        return md.toString();
    }

    private static String definitionSource(ProjectWorkspace.DraftView draft, String groovyPath) {
        String prefix = "src/main/resources/";
        return groovyPath.startsWith(prefix)
                ? "new ClasspathWorkflowDefinitionSource(List.of(\"" + groovyPath.substring(prefix.length()) + "\"))"
                : "new FileSystemWorkflowDefinitionSource(/* directory containing " + draft.engineId() + ".groovy */)";
    }
    private static String simple(String qualified) { return qualified.substring(qualified.lastIndexOf('.') + 1); }
    /** Keeps user-controlled text on one line and out of table syntax; Workbench shows the plan as plain text. */
    static String text(String value) { return value == null ? "" : value.replace("|", "\\|").replace("\r", " ").replace("\n", " "); }
}
