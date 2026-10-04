package org.jworkflow.workbench;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Owner loss closes the context; it never terminates the VM from a request thread. */
@Component
public final class ApplicationLifecycle {
    private final ConfigurableApplicationContext context;
    private final SessionAuthority authority;
    private final boolean openBrowser;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile boolean ready;

    public ApplicationLifecycle(ConfigurableApplicationContext context, SessionAuthority authority,
            @Value("${workbench.browser.enabled:true}") boolean openBrowser) {
        this.context = context;
        this.authority = authority;
        this.openBrowser = openBrowser;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready = true;
        if (!openBrowser && System.console() == null) return;
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        URI address = URI.create("http://127.0.0.1:" + port + "/#" + authority.bootstrapToken());
        if (!openBrowser) { consoleFallback(address); return; }
        try {
            new ProcessBuilder(browserCommand(System.getProperty("os.name"), address)).start();
        } catch (IOException | UnsupportedOperationException failed) {
            // A terminal-only instruction must not leak the bootstrap into redirected logs.
            consoleFallback(address);
        }
    }

    private static void consoleFallback(URI address) {
        if (System.console() != null) System.console().printf("Open this one-use local address before startup timeout: %s%n", address);
        else System.err.println("Browser launch failed. Relaunch Workbench in an interactive terminal for the fallback address.");
    }

    static java.util.List<String> browserCommand(String os, URI address) {
        if (os.startsWith("Windows")) return java.util.List.of("rundll32.exe", "url.dll,FileProtocolHandler", address.toASCIIString());
        if (os.startsWith("Mac")) return java.util.List.of("/usr/bin/open", address.toASCIIString());
        throw new UnsupportedOperationException("Supported platforms are Windows and macOS");
    }

    @Scheduled(fixedDelay = 1000)
    public void checkOwner() { if (ready && authority.expired()) requestShutdown(); }

    public void requestShutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        authority.close();
        Thread.ofPlatform().name("workbench-shutdown").start(() -> {
            try { Thread.sleep(150); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            context.close();
        });
    }
}
