package org.jworkflow.workbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Apply loopback/CSRF policy before controller and WebSocket dispatch. */
@Component
public final class LocalRequestFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // xterm generates styles; Blockly uses inline data SVGs for dropdown arrows. Scripts and workers stay same-origin.
        response.setHeader("Content-Security-Policy", "default-src 'self'; connect-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        boolean mutates = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod());
        boolean websocket = "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
        if (!LocalRequestPolicy.allowed(request.getHeader("Host"), request.getHeader("Origin"), request.getLocalPort(), mutates || websocket)) {
            response.sendError(403);
        } else if (request.getContentLengthLong() > (request.getRequestURI().startsWith("/api/project") ? 2_097_152 : 8192)) {
            response.sendError(413);
        } else {
            chain.doFilter(request, response);
        }
    }
}
