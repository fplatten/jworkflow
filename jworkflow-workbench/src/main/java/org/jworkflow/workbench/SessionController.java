package org.jworkflow.workbench;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** One-use local bootstrap, with a bounded body even for chunked HTTP requests. */
@RestController
public final class SessionController {
    private final SessionAuthority authority;
    private final LastProjectPreference preference;
    private final String launchProject;
    public SessionController(SessionAuthority authority, LastProjectPreference preference,
            @Value("${workbench.project:}") String launchProject) {
        this.authority = authority;
        this.preference = preference;
        this.launchProject = launchProject;
    }

    @PostMapping("/api/session")
    public ResponseEntity<Map<String, String>> claim(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) return ResponseEntity.status(413).build();
        String token;
        try { token = JsonMapper.builder().build().readTree(new String(body, StandardCharsets.UTF_8)).path("bootstrap").asText(); }
        catch (RuntimeException invalid) { return ResponseEntity.badRequest().build(); }
        String session = authority.claim(token);
        if (session == null) return ResponseEntity.status(403).build();
        if (!launchProject.isBlank()) return ResponseEntity.ok(Map.of("credential", session, "lastProject", launchProject, "projectSource", "argument"));
        String path;
        try { path = preference.read(); }
        catch (IOException unavailable) { path = ""; }
        return ResponseEntity.ok(Map.of("credential", session, "lastProject", path, "projectSource", "preference"));
    }
}
