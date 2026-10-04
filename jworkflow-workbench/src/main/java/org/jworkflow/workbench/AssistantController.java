package org.jworkflow.workbench;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated assistant endpoints: status, session consent, editor context, conversations and proposal claims. */
@RestController
@RequestMapping("/api/ai")
public final class AssistantController {
    private final SessionAuthority authority; private final AssistantService assistant;
    public AssistantController(SessionAuthority authority, AssistantService assistant) { this.authority = authority; this.assistant = assistant; }

    @GetMapping("/status") ResponseEntity<?> status(HttpServletRequest request) { return execute(request, assistant::status); }
    @GetMapping("/files") ResponseEntity<?> files(HttpServletRequest request) { return execute(request, assistant::candidates); }
    @PostMapping("/files") ResponseEntity<?> share(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return execute(request, () -> body.containsKey("workflow") ? assistant.shareWorkflow(Boolean.TRUE.equals(body.get("workflow")))
                : assistant.share(String.valueOf(body.get("path")), Boolean.TRUE.equals(body.get("shared"))));
    }
    @PostMapping("/context") ResponseEntity<?> context(@RequestBody AssistantService.EditorContext body, HttpServletRequest request) {
        return execute(request, () -> { assistant.editorContext(body); return Map.of("accepted", true); });
    }
    @GetMapping("/conversations") ResponseEntity<?> conversations(HttpServletRequest request) {
        return execute(request, () -> Map.of("current", assistant.currentConversation(), "saved", assistant.conversations()));
    }
    @PostMapping("/conversations/new") ResponseEntity<?> startNew(HttpServletRequest request) { return execute(request, assistant::startNew); }
    @PostMapping("/conversations/{id}/resume") ResponseEntity<?> resume(@PathVariable String id, HttpServletRequest request) { return execute(request, () -> assistant.resume(id)); }
    @DeleteMapping("/conversations/{id}") ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest request) {
        return execute(request, () -> { assistant.delete(id); return Map.of("deleted", id); });
    }
    @PostMapping("/proposals/{id}/claim") ResponseEntity<?> claim(@PathVariable String id, HttpServletRequest request) { return execute(request, () -> assistant.claimProposal(id)); }

    private ResponseEntity<?> execute(HttpServletRequest request, Operation operation) {
        if (!authority.authenticates(request.getHeader("X-Workbench-Credential"))) return ResponseEntity.status(401).body(Map.of("error", "Session is not authorized."));
        try { return ResponseEntity.ok(operation.run()); }
        catch (IllegalStateException conflict) { return ResponseEntity.status(409).body(Map.of("error", conflict.getMessage())); }
        catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage())); }
        catch (java.nio.file.NoSuchFileException missing) { return ResponseEntity.status(404).body(Map.of("error", missing.getMessage())); }
        catch (IOException failure) { return ResponseEntity.internalServerError().body(Map.of("error", failure.getMessage())); }
    }
    @FunctionalInterface private interface Operation { Object run() throws IOException; }
}
