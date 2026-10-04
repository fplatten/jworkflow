package org.jworkflow.workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Controlled /build and /test templates (OPS-02, WB-22). Tools are resolved from the project's wrapper configuration
 * (only when its distribution is already cached) or from PATH, and are launched directly through {@code java}, never
 * through a shell or project script. Caches outside the project are used read-only; every write, cache, temporary file
 * and log stays inside {@code .jworkflow/} in the project. No template downloads anything.
 */
final class BuildTools {
    enum Kind { BUILD, TEST }

    /** A fully resolved launch, shown verbatim for approval. */
    record Plan(String tool, String toolVersion, List<String> command, Map<String, String> environment, List<Path> readOnly, String note) {}

    /** Thrown when a prerequisite is missing; {@code remediation} lists numbered commands the user runs separately. */
    static final class Missing extends Exception {
        final List<String> remediation;
        Missing(String message, List<String> remediation) { super(message); this.remediation = List.copyOf(remediation); }
    }

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private BuildTools() {}

    static Plan plan(Kind kind, Path root, String buildSystem, Path javaHome, Map<String, String> hostEnvironment) throws Missing, IOException {
        Map<String, String> host = new TreeMap<>(windows() ? String.CASE_INSENSITIVE_ORDER : Comparator.<String>naturalOrder());
        host.putAll(hostEnvironment);
        hostEnvironment = host;
        Path home = Path.of(hostEnvironment.getOrDefault(windows() ? "USERPROFILE" : "HOME", System.getProperty("user.home")));
        return buildSystem.startsWith("Maven") ? maven(kind, root, javaHome, home, hostEnvironment) : gradle(kind, root, javaHome, home, hostEnvironment);
    }

    // ---- Maven ----

    private static Plan maven(Kind kind, Path root, Path javaHome, Path home, Map<String, String> host) throws Missing, IOException {
        Path userHome = Path.of(host.getOrDefault("MAVEN_USER_HOME", home.resolve(".m2").toString()));
        Path mavenHome = null; String source;
        Path wrapper = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        if (Files.isRegularFile(wrapper)) {
            String version = property(wrapper, "distributionUrl").replaceAll(".*apache-maven-([^/]+?)(-bin|-all)?\\.zip$", "$1");
            mavenHome = find(userHome.resolve("wrapper/dists"), "apache-maven-" + version, "bin/m2.conf");
            source = "project Maven wrapper (Maven " + version + ")";
            if (mavenHome == null) throw new Missing("The project's Maven wrapper distribution (Maven " + version + ") is not cached, and Workbench never downloads it.",
                    List.of("In " + root + ", run `" + (windows() ? "mvnw.cmd" : "./mvnw") + " -v` yourself (outside Workbench) so the wrapper downloads Maven " + version + " into " + userHome.resolve("wrapper/dists") + "."));
        } else {
            Path executable = onPath(host, windows() ? "mvn.cmd" : "mvn");
            if (executable == null) throw new Missing("No Maven wrapper is configured and `mvn` is not on PATH.", List.of("Install Apache Maven 3.9 or later and put its bin folder on PATH, then restart Workbench."));
            mavenHome = executable.toRealPath().getParent().getParent();
            source = "Maven on PATH";
        }
        String version = jarVersion(mavenHome.resolve("lib"), "maven-core-");
        if (compare(version, "3.9.0") < 0) throw new Missing("Maven " + version + " cannot use the existing local repository read-only (needs Maven 3.9 or later for maven.repo.local.tail).",
                List.of("Install Maven 3.9 or later, or add a Maven wrapper for 3.9+ to the project and run it once outside Workbench."));
        Path classworlds = first(mavenHome.resolve("boot"), "plexus-classworlds-");
        Path repository = localRepository(userHome);
        Files.createDirectories(root.resolve(".jworkflow/m2/repository"));
        Path settings = root.resolve(".jworkflow/m2/settings.xml");
        // User settings may hold credentials; the sandbox gets an empty settings file instead.
        if (!Files.exists(settings)) Files.writeString(settings, "<settings/>\n");
        List<String> command = new ArrayList<>(List.of(java(javaHome)));
        command.addAll(jvmConfig(root));
        command.addAll(List.of("-classpath", classworlds.toString(), "-Dclassworlds.conf=" + mavenHome.resolve("bin/m2.conf"), "-Dmaven.home=" + mavenHome,
                "-Dlibrary.jansi.path=" + mavenHome.resolve("lib/jansi-native"), "-Dmaven.multiModuleProjectDirectory=.", "org.codehaus.plexus.classworlds.launcher.Launcher",
                "--batch-mode", "--offline", "--no-transfer-progress", "-Dstyle.color=never", "--settings", ".jworkflow/m2/settings.xml",
                "-Dmaven.repo.local=.jworkflow/m2/repository", "-Dmaven.repo.local.tail=" + repository));
        if (kind == Kind.BUILD) command.addAll(List.of("-Dmaven.test.skip=true", "package"));
        else command.add("verify");
        return new Plan("Maven", version, List.copyOf(command), environment(root, javaHome, host, Map.of()), List.of(javaHome, mavenHome, repository),
                (kind == Kind.BUILD ? "Compiles and packages without compiling or running any tests (-Dmaven.test.skip=true package)."
                        : "Runs the full configured suite through `verify`, including configured integration tests; no test filters.")
                        + " Uses " + source + ", offline, with " + repository + " read-only and a project-local repository for anything new.");
    }

    private static Path localRepository(Path userHome) throws IOException {
        Path settings = userHome.resolve("settings.xml");
        if (Files.isRegularFile(settings)) {
            Matcher m = Pattern.compile("<localRepository>\\s*([^<]+?)\\s*</localRepository>").matcher(Files.readString(settings, StandardCharsets.UTF_8));
            if (m.find() && !m.group(1).contains("${")) return Path.of(m.group(1));
        }
        return userHome.resolve("repository");
    }

    private static List<String> jvmConfig(Path root) throws IOException {
        Path config = root.resolve(".mvn/jvm.config");
        if (!Files.isRegularFile(config)) return List.of();
        return Arrays.stream(Files.readString(config).split("\\s+")).filter(s -> !s.isBlank()).toList();
    }

    // ---- Gradle ----

    private static Plan gradle(Kind kind, Path root, Path javaHome, Path home, Map<String, String> host) throws Missing, IOException {
        Path userHome = Path.of(host.getOrDefault("GRADLE_USER_HOME", home.resolve(".gradle").toString()));
        Path gradleHome; String source;
        Path wrapper = root.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (Files.isRegularFile(wrapper)) {
            String version = property(wrapper, "distributionUrl").replaceAll(".*gradle-([^/]+?)-(bin|all)\\.zip$", "$1");
            gradleHome = find(userHome.resolve("wrapper/dists"), "gradle-" + version, "lib/gradle-launcher-" + version + ".jar");
            source = "project Gradle wrapper (Gradle " + version + ")";
            if (gradleHome == null) throw new Missing("The project's Gradle wrapper distribution (Gradle " + version + ") is not cached, and Workbench never downloads it.",
                    List.of("In " + root + ", run `" + (windows() ? "gradlew.bat" : "./gradlew") + " --version` yourself (outside Workbench) so the wrapper downloads Gradle " + version + "."));
        } else {
            Path executable = onPath(host, windows() ? "gradle.bat" : "gradle");
            if (executable == null) throw new Missing("No Gradle wrapper is configured and `gradle` is not on PATH.", List.of("Install Gradle and put its bin folder on PATH, then restart Workbench."));
            gradleHome = executable.toRealPath().getParent().getParent();
            source = "Gradle on PATH";
        }
        Path launcher = first(gradleHome.resolve("lib"), "gradle-launcher-");
        String version = launcher.getFileName().toString().replaceAll("gradle-launcher-(.+)\\.jar", "$1");
        Path caches = userHome.resolve("caches");
        Path localHome = root.resolve(".jworkflow/gradle-home");
        Files.createDirectories(localHome);
        List<String> command = new ArrayList<>(List.of(java(javaHome), "-Dorg.gradle.appname=gradle", "-classpath", launcher.toString(), "org.gradle.launcher.GradleMain",
                "--offline", "--no-daemon", "--console=plain", "--no-watch-fs", kind == Kind.BUILD ? "assemble" : "check"));
        Map<String, String> extra = Map.of("GRADLE_USER_HOME", localHome.toString(), "GRADLE_RO_DEP_CACHE", caches.toString());
        return new Plan("Gradle", version, List.copyOf(command), environment(root, javaHome, host, extra), List.of(javaHome, gradleHome, caches),
                (kind == Kind.BUILD ? "Runs `assemble`, which builds outputs without running tests." : "Runs `check`, the full configured verification suite including tests; no test filters.")
                        + " Uses " + source + ", offline, without a daemon, with a project-local Gradle home and " + caches + " as a read-only dependency cache.");
    }

    // ---- Shared ----

    /** Allowlisted environment: no inherited credentials, API keys or proxy settings reach the build (SEC-03). */
    static Map<String, String> environment(Path root, Path javaHome, Map<String, String> hostEnvironment, Map<String, String> extra) throws IOException {
        Path tmp = Files.createDirectories(root.resolve(".jworkflow/tmp"));
        Map<String, String> env = new TreeMap<>();
        // Windows variable names are case-insensitive (the real name may be SYSTEMROOT); Winsock fails without SystemRoot.
        Map<String, String> host = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        host.putAll(hostEnvironment);
        if (windows()) {
            for (String name : List.of("SystemRoot", "windir", "SystemDrive", "PATHEXT", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "USERNAME", "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "OS"))
                if (host.containsKey(name)) env.put(name, host.get(name));
            env.put("PATH", javaHome.resolve("bin") + ";" + host.getOrDefault("SystemRoot", "C:\\Windows") + "\\System32");
            env.put("TEMP", tmp.toString()); env.put("TMP", tmp.toString());
        } else {
            for (String name : List.of("HOME", "USER", "LANG", "LC_ALL")) if (host.containsKey(name)) env.put(name, host.get(name));
            env.put("PATH", javaHome.resolve("bin") + ":/usr/bin:/bin");
            env.put("TMPDIR", tmp.toString());
        }
        env.put("JAVA_HOME", javaHome.toString());
        env.putAll(extra);
        return Collections.unmodifiableMap(env);
    }

    static Path javaHome(Map<String, String> host) {
        String configured = host.get("JAVA_HOME");
        if (configured != null && Files.isRegularFile(Path.of(configured, "bin", windows() ? "java.exe" : "java"))) return Path.of(configured);
        return Path.of(System.getProperty("java.home"));
    }

    private static String java(Path javaHome) { return javaHome.resolve("bin").resolve(windows() ? "java.exe" : "java").toString(); }

    private static String property(Path file, String key) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) { properties.load(in); }
        return properties.getProperty(key, "");
    }

    /** A directory named {@code prefix} (or {@code prefix-…}) below {@code base}, at most four levels deep, containing {@code marker}. */
    private static Path find(Path base, String prefix, String marker) throws IOException {
        if (!Files.isDirectory(base)) return null;
        try (Stream<Path> walk = Files.walk(base, 4)) {
            return walk.filter(p -> {
                String name = p.getFileName().toString();
                return (name.equals(prefix) || name.startsWith(prefix + "-")) && Files.isRegularFile(p.resolve(marker));
            }).findFirst().orElse(null);
        }
    }

    private static Path first(Path directory, String prefix) throws Missing, IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".jar")).sorted().findFirst()
                    .orElseThrow(() -> new Missing("The build tool installation at " + directory.getParent() + " is incomplete (" + prefix + "*.jar missing).", List.of("Reinstall the build tool.")));
        }
    }

    private static String jarVersion(Path lib, String prefix) throws Missing, IOException {
        return first(lib, prefix).getFileName().toString().substring(prefix.length()).replace(".jar", "");
    }

    static int compare(String a, String b) {
        Matcher x = VERSION.matcher(a), y = VERSION.matcher(b);
        if (!x.find() || !y.find()) return -1;
        for (int i = 1; i <= 3; i++) {
            int p = x.group(i) == null ? 0 : Integer.parseInt(x.group(i)), q = y.group(i) == null ? 0 : Integer.parseInt(y.group(i));
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static Path onPath(Map<String, String> host, String name) {
        for (String entry : host.getOrDefault(host.containsKey("PATH") ? "PATH" : "Path", "").split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try { Path candidate = Path.of(entry.strip(), name); if (Files.isRegularFile(candidate)) return candidate; } catch (InvalidPathException ignored) { }
        }
        return null;
    }

    static boolean windows() { return System.getProperty("os.name", "").startsWith("Windows"); }
}
