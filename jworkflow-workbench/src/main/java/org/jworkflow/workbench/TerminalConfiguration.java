package org.jworkflow.workbench;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Bootstrap credentials are carried in WebSocket headers, never query strings. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class TerminalConfiguration implements WebSocketConfigurer {
    private final TerminalSocket socket;
    private final SessionAuthority authority;
    public TerminalConfiguration(TerminalSocket socket, SessionAuthority authority) { this.socket = socket; this.authority = authority; }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/terminal").addInterceptors(new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
                String protocols = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
                String credential = credential(protocols);
                if (!authority.authenticates(credential)) { response.setStatusCode(HttpStatus.FORBIDDEN); return false; }
                attributes.put("credential", credential);
                return true;
            }
            @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception failure) { }
        });
    }

    static String credential(String protocols) {
        if (protocols == null) return null;
        for (String value : protocols.split(",")) {
            String token = value.strip();
            if (token.startsWith("credential.")) return token.substring(11);
        }
        return null;
    }
}
