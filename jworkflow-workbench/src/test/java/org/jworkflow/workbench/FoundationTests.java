package org.jworkflow.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

class FoundationTests {
    static final class Time extends Clock {
        long now;
        public ZoneId getZone() {return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone) {return this;}
        public Instant instant() {return Instant.ofEpochMilli(now);}
    }
    static SessionAuthority session(Clock clock) {
        return new SessionAuthority(clock, Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofSeconds(120));
    }
    @Test void oneUseClaimOwnerRefreshAndImpersonation() {
        Time time = new Time(); var auth = session(time);
        assertFalse(auth.authenticates("bad")); assertNull(auth.claim(null)); assertNull(auth.claim("wrong"));
        String credential = auth.claim(auth.bootstrapToken()); assertNotNull(credential);
        assertNull(auth.claim(auth.bootstrapToken())); assertFalse(auth.attach("wrong", "first"));
        assertTrue(auth.attach(credential, "first")); assertFalse(auth.attach(credential, "second"));
        auth.detach("second"); assertTrue(auth.touch("first")); assertFalse(auth.touch("second"));
        auth.detach("first"); time.now = 4999; assertTrue(auth.attach(credential, "refresh"));
        time.now += 19000; assertTrue(auth.touch("refresh"));
        time.now += 20000; assertTrue(auth.expired()); assertFalse(auth.touch("refresh"));
        assertFalse(auth.authenticates(credential));
    }
    @Test void allLeaseDeadlinesFailClosed() {
        Time time = new Time(); var pending = session(time); assertFalse(pending.expired());
        time.now = 120000; assertTrue(pending.expired()); assertNull(pending.claim(pending.bootstrapToken()));
        var disconnected = session(time); disconnected.claim(disconnected.bootstrapToken());
        time.now += 5000; assertTrue(disconnected.expired());
        var closed = session(time); closed.close(); assertTrue(closed.expired()); assertNull(closed.claim(closed.bootstrapToken()));
        assertThrows(IllegalArgumentException.class, () -> new SessionAuthority(time, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new SessionAuthority(time, Duration.ofNanos(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
    @Test void shellOnlyRoutesExactCommandsAndNeverExecutesBareExit() {
        AtomicInteger stopped = new AtomicInteger(); AtomicInteger messages = new AtomicInteger();
        var shell = new FoundationShell(stopped::incrementAndGet, input -> {messages.incrementAndGet(); return "fake:" + input;});
        assertEquals("", shell.evaluate(null)); assertEquals("", shell.evaluate("  "));
        assertTrue(shell.evaluate("x".repeat(4097)).contains("limit"));
        assertTrue(shell.evaluate("\u0003").contains("does not cancel"));
        assertEquals("fake:exit", shell.evaluate("exit")); assertEquals("fake:exit criteria", shell.evaluate("exit criteria"));
        for (String input : new String[]{"/exitSomething", "/exit now", "/unknown", "/"}) assertTrue(shell.evaluate(input).contains("Unknown"));
        assertEquals(0, stopped.get()); assertEquals(2, messages.get());
        assertTrue(shell.evaluate(" /help ").contains("/core"));
        assertTrue(shell.evaluate("/core").contains("org.jworkflow.application.Command"));
        assertEquals("No active operation.", shell.evaluate("/cancel"));
        assertTrue(shell.evaluate("/exit").contains("shutting down")); assertEquals(1, stopped.get());
        assertTrue(new FoundationShell(() -> {}).evaluate("hello").contains("Simulated"));
    }
    @Test void preferencesAreOnlySuggestionsAndBounded(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("preferences/last-project.txt"); var prefs = new LastProjectPreference(file);
        assertEquals("", prefs.read()); Path absent = temp.resolve("space 漢字/nonexistent");
        prefs.remember(absent); assertEquals(absent.toString(), prefs.read()); assertFalse(Files.exists(absent));
        Files.writeString(file, "x".repeat(4097)); assertEquals("", prefs.read());
        assertThrows(IllegalArgumentException.class, () -> prefs.remember(Path.of("x".repeat(4097))));
        assertTrue(LastProjectPreference.defaultLocation("Windows 11", temp.toString(), temp.toString()).startsWith(temp));
        assertTrue(LastProjectPreference.defaultLocation("Mac OS X", temp.toString(), null).toString().contains("Application Support"));
        assertTrue(LastProjectPreference.defaultLocation("Windows 11", temp.toString(), "").toString().contains("Application Support"));
        assertTrue(LastProjectPreference.defaultLocation("Windows 11", temp.toString(), null).toString().contains("Application Support"));
    }
    @Test void ownerTimeoutClosesContextOnlyAfterReadinessAndOnlyOnce() {
        var context = mock(ConfigurableApplicationContext.class); var auth = session(Clock.systemUTC());
        var lifecycle = new ApplicationLifecycle(context, auth, false);
        auth.close(); lifecycle.checkOwner(); verifyNoInteractions(context);
        lifecycle.ready(); lifecycle.checkOwner(); lifecycle.requestShutdown();
        verify(context, timeout(2000).times(1)).close();
    }
    @Test void supportedBrowserLaunchUsesArgumentsWithoutShellInterpolation() {
        URI address = URI.create("http://127.0.0.1:1234/#safe");
        assertEquals(3, ApplicationLifecycle.browserCommand("Windows 11", address).size());
        assertEquals(address.toString(), ApplicationLifecycle.browserCommand("Mac OS X", address).get(1));
        assertThrows(UnsupportedOperationException.class, () -> ApplicationLifecycle.browserCommand("Linux", address));
    }
}
