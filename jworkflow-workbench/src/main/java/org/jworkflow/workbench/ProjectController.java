package org.jworkflow.workbench;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated M2 endpoints; every project operation delegates to the root-confined workspace. */
@RestController
@RequestMapping("/api/project")
public final class ProjectController {
    private final SessionAuthority authority; private final ProjectWorkspace workspace;
    public ProjectController(SessionAuthority authority, ProjectWorkspace workspace) { this.authority = authority; this.workspace = workspace; }

    @PostMapping("/confirm") ResponseEntity<?> confirm(@RequestBody Map<String, String> body, HttpServletRequest request) { return execute(request, () -> workspace.confirm(body.get("root"))); }
    @GetMapping ResponseEntity<?> state(HttpServletRequest request) { return execute(request, workspace::state); }
    @PostMapping("/refresh") ResponseEntity<?> refresh(HttpServletRequest request) { return execute(request, workspace::refresh); }
    @GetMapping("/draft") ResponseEntity<?> draft(HttpServletRequest request) { return execute(request, workspace::loadDraft); }
    @PutMapping("/draft") ResponseEntity<?> save(@RequestBody ProjectWorkspace.SaveDraft body, HttpServletRequest request) { return execute(request, () -> workspace.saveDraft(body)); }
    @PostMapping("/generate") ResponseEntity<?> generate(@RequestBody ProjectWorkspace.GenerateRequest body, HttpServletRequest request) { return execute(request, () -> workspace.generate(body)); }
    @PostMapping("/changes") ResponseEntity<?> proposeChanges(@RequestBody ProjectWorkspace.GenerateRequest body, HttpServletRequest request) { return execute(request, () -> workspace.proposeChanges(body)); }
    @PostMapping("/changes/apply") ResponseEntity<?> applyChanges(@RequestBody ProjectWorkspace.ApplyRequest body, HttpServletRequest request) { return execute(request, () -> workspace.applyChanges(body)); }
    @GetMapping("/changes/revert") ResponseEntity<?> proposeRevert(HttpServletRequest request) { return execute(request, workspace::proposeRevert); }
    @PostMapping("/changes/revert") ResponseEntity<?> applyRevert(@RequestBody Map<String, String> body, HttpServletRequest request) { return execute(request, () -> workspace.applyRevert(body.get("token"))); }
    @GetMapping("/plan") ResponseEntity<?> plan(HttpServletRequest request) { return execute(request, () -> Map.of("markdown", workspace.plan(), "path", IntegrationPlan.PATH)); }
    @GetMapping("/gitignore") ResponseEntity<?> gitignore(HttpServletRequest request) { return execute(request, workspace::proposeGitIgnore); }
    @PostMapping("/gitignore") ResponseEntity<?> applyGitignore(@RequestBody Map<String, String> body, HttpServletRequest request) { return execute(request, () -> { workspace.applyGitIgnore(body.get("beforeHash")); return workspace.proposeGitIgnore(); }); }

    private ResponseEntity<?> execute(HttpServletRequest request, Operation operation) {
        if (!authority.authenticates(request.getHeader("X-Workbench-Credential"))) return ResponseEntity.status(401).body(Map.of("error", "Session is not authorized."));
        try { return ResponseEntity.ok(operation.run()); }
        catch (ProjectWorkspace.Conflict conflict) { return ResponseEntity.status(409).body(Map.of("error", conflict.getMessage())); }
        catch (IllegalArgumentException | IllegalStateException invalid) { return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage())); }
        catch (IOException failure) { return ResponseEntity.internalServerError().body(Map.of("error", failure.getMessage())); }
    }
    @FunctionalInterface private interface Operation { Object run() throws IOException; }
}
