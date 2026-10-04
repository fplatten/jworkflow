package org.jworkflow.workbench;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated /build and /test endpoints: confinement setup and self-test, exact-command approval, cancel. */
@RestController
@RequestMapping("/api/commands")
public final class CommandController {
    private final SessionAuthority authority; private final CommandService commands; private final AssistantService assistant;
    public CommandController(SessionAuthority authority, CommandService commands, AssistantService assistant) { this.authority = authority; this.commands = commands; this.assistant = assistant; }

    @GetMapping("/setup") ResponseEntity<?> setup(HttpServletRequest request) { return execute(request, commands::setup); }
    @PostMapping("/setup/verify") ResponseEntity<?> verify(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return execute(request, () -> { try { return commands.verify(Boolean.TRUE.equals(body.get("grantProjectFolder"))); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); } });
    }
    @PostMapping("/{id}/approve") ResponseEntity<?> approve(@PathVariable String id, HttpServletRequest request) { return execute(request, () -> commands.approve(id)); }
    @PostMapping("/{id}/deny") ResponseEntity<?> deny(@PathVariable String id, HttpServletRequest request) { return execute(request, () -> { commands.deny(id); return Map.of("denied", id); }); }
    @GetMapping("/excerpt") ResponseEntity<?> excerpt(HttpServletRequest request) { return execute(request, commands::excerpt); }
    @PostMapping("/excerpt/{id}/share") ResponseEntity<?> share(@PathVariable String id, HttpServletRequest request) {
        return execute(request, () -> { assistant.shareDiagnosticExcerpt(commands.takeExcerpt(id)); return Map.of("shared", true); });
    }
    @PostMapping("/cancel") ResponseEntity<?> cancel(HttpServletRequest request) { return execute(request, () -> { commands.cancel(); return Map.of("cancelling", true); }); }

    private ResponseEntity<?> execute(HttpServletRequest request, Operation operation) {
        if (!authority.authenticates(request.getHeader("X-Workbench-Credential"))) return ResponseEntity.status(401).body(Map.of("error", "Session is not authorized."));
        try { return ResponseEntity.ok(operation.run()); }
        catch (IllegalStateException conflict) { return ResponseEntity.status(409).body(Map.of("error", conflict.getMessage())); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage())); }
        catch (IOException failure) { return ResponseEntity.internalServerError().body(Map.of("error", failure.getMessage())); }
    }
    @FunctionalInterface private interface Operation { Object run() throws IOException; }
}
