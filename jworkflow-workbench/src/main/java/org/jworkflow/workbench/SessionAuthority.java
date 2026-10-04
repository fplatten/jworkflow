package org.jworkflow.workbench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/** One-use bootstrap and one live owner connection; credentials never enter URLs sent to HTTP. */
public final class SessionAuthority {
    private final String bootstrap = randomToken();
    private final String credential = randomToken();
    private final Clock clock;
    private final long disconnectGrace;
    private final long heartbeatTimeout;
    private final long startupDeadline;
    private boolean claimed;
    private boolean closed;
    private String socketId;
    private long lastActivity;
    private Long disconnectedAt;

    public SessionAuthority(Clock clock, Duration grace, Duration heartbeat, Duration startup) {
        this.clock = clock;
        this.disconnectGrace = positive(grace);
        this.heartbeatTimeout = positive(heartbeat);
        this.startupDeadline = clock.millis() + positive(startup);
    }

    private static long positive(Duration value) {
        if (value.toMillis() < 1) throw new IllegalArgumentException("Timeout must be at least one millisecond");
        return value.toMillis();
    }

    public String bootstrapToken() { return bootstrap; }

    public synchronized String claim(String token) {
        if (closed || claimed || clock.millis() >= startupDeadline || !matches(bootstrap, token)) return null;
        claimed = true;
        lastActivity = clock.millis();
        disconnectedAt = lastActivity;
        return credential;
    }

    public synchronized boolean authenticates(String token) {
        return claimed && !expired() && matches(credential, token);
    }

    public synchronized boolean attach(String token, String connection) {
        if (!authenticates(token) || socketId != null) return false;
        socketId = connection;
        disconnectedAt = null;
        lastActivity = clock.millis();
        return true;
    }

    public synchronized boolean touch(String connection) {
        if (expired() || !connection.equals(socketId)) return false;
        lastActivity = clock.millis();
        return true;
    }

    public synchronized void detach(String connection) {
        if (connection.equals(socketId)) {
            socketId = null;
            disconnectedAt = clock.millis();
        }
    }

    public synchronized boolean expired() {
        if (closed) return true;
        if (!claimed) return clock.millis() >= startupDeadline;
        if (disconnectedAt != null) return clock.millis() - disconnectedAt >= disconnectGrace;
        return clock.millis() - lastActivity >= heartbeatTimeout;
    }

    public synchronized void close() { closed = true; }

    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
