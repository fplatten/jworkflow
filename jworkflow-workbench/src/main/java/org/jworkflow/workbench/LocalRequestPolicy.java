package org.jworkflow.workbench;

import java.net.URI;

/** Validate exact loopback authority and same-origin mutating/browser requests. */
public final class LocalRequestPolicy {
    private LocalRequestPolicy() { }

    public static boolean allowed(String host, String origin, int port, boolean requireOrigin) {
        if (!("127.0.0.1:" + port).equals(host)) return false;
        if (origin == null) return !requireOrigin;
        try {
            URI uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() == port && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
