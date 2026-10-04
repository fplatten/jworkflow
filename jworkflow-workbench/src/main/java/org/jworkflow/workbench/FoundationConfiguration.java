package org.jworkflow.workbench;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Core services. The assistant contacts its provider only for user requests; no target process execution is enabled. */
@Configuration(proxyBeanMethods = false)
public class FoundationConfiguration {
    @Bean SessionAuthority sessionAuthority(
            @Value("${workbench.session.disconnect-grace}") Duration grace,
            @Value("${workbench.session.heartbeat-timeout}") Duration heartbeat,
            @Value("${workbench.session.startup-timeout}") Duration startup) {
        return new SessionAuthority(Clock.systemUTC(), grace, heartbeat, startup);
    }
    @Bean LastProjectPreference lastProjectPreference(@Value("${workbench.preference-file:}") String configured) {
        Path path = configured.isBlank() ? LastProjectPreference.defaultLocation(System.getProperty("os.name"), System.getProperty("user.home"), System.getenv("LOCALAPPDATA")) : Path.of(configured);
        return new LastProjectPreference(path);
    }
    @Bean ProjectWorkspace projectWorkspace(LastProjectPreference preference) { return new ProjectWorkspace(preference); }
    @Bean AssistantService assistantService(ProjectWorkspace workspace, ResponsesChatModel model, org.springframework.core.env.Environment environment,
            @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl, @Value("${workbench.ai.deadline:10m}") Duration deadline) {
        return new AssistantService(workspace, model, () -> !environment.getProperty("spring.ai.openai.api-key", "").isBlank(),
                java.net.URI.create(baseUrl).getHost(), deadline);
    }
    @Bean FoundationShell foundationShell(ApplicationLifecycle lifecycle, AssistantService assistant) { return new FoundationShell(lifecycle::requestShutdown, assistant::clear); }
    @Bean OperationCoordinator operationCoordinator(ProjectWorkspace workspace) {
        OperationCoordinator coordinator = new OperationCoordinator();
        workspace.blockSourceWritesWhen(coordinator::sourceWritesBlocked);
        return coordinator;
    }
    @Bean Sandbox sandbox() { return Sandbox.forCurrentPlatform(); }
    @Bean CommandService commandService(ProjectWorkspace workspace, Sandbox sandbox, OperationCoordinator coordinator, @Value("${workbench.build.deadline:10m}") Duration deadline) {
        return new CommandService(workspace, sandbox, coordinator, System.getenv(), deadline);
    }
    @Bean TerminalSocket terminalSocket(SessionAuthority authority, FoundationShell shell, AssistantService assistant, OperationCoordinator coordinator, CommandService commands) {
        return new TerminalSocket(authority, shell, assistant, coordinator, commands);
    }
}
