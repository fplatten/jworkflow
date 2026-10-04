package org.jworkflow.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.*;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supported API must not expose unsupported types. Every public type outside {@code org.jworkflow.internal}
 * and {@code org.jworkflow.jdbc.internal} is checked: its supertypes and the types in its public or protected
 * fields, constructors and methods (including generic arguments and declared exceptions) must not be internal.
 */
class ApiBoundaryTest {
    private static final String ROOT = "org/jworkflow";

    @Test
    void supportedApiDoesNotExposeInternalTypes() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : productionClasses()) {
            if (!isSupported(type)) continue;
            for (Type supertype : supertypes(type)) report(violations, type, "extends/implements", supertype);
            for (Field field : type.getDeclaredFields()) {
                if (exposed(field.getModifiers())) report(violations, type, "field " + field.getName(), field.getGenericType());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (!exposed(constructor.getModifiers())) continue;
                for (Type parameter : constructor.getGenericParameterTypes()) report(violations, type, "constructor", parameter);
                for (Type thrown : constructor.getGenericExceptionTypes()) report(violations, type, "constructor throws", thrown);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !exposed(method.getModifiers())) continue;
                report(violations, type, method.getName() + "() returns", method.getGenericReturnType());
                for (Type parameter : method.getGenericParameterTypes()) report(violations, type, method.getName() + "() parameter", parameter);
                for (Type thrown : method.getGenericExceptionTypes()) report(violations, type, method.getName() + "() throws", thrown);
            }
        }
        assertTrue(violations.isEmpty(), "Supported API exposes internal types:\n" + String.join("\n", new TreeSet<>(violations)));
    }

    /** Public top-level types, and public members of supported types, outside internal packages. */
    private static boolean isSupported(Class<?> type) {
        if (internal(type)) return false;
        for (Class<?> c = type; c != null; c = c.getDeclaringClass()) {
            if (!Modifier.isPublic(c.getModifiers())) return false;
        }
        return true;
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static boolean internal(Class<?> type) {
        String name = type.getName();
        return name.startsWith("org.jworkflow.internal.") || name.startsWith("org.jworkflow.jdbc.internal.");
    }

    private static List<Type> supertypes(Class<?> type) {
        List<Type> result = new ArrayList<>(Arrays.asList(type.getGenericInterfaces()));
        if (type.getGenericSuperclass() != null) result.add(type.getGenericSuperclass());
        return result;
    }

    private static void report(List<String> violations, Class<?> owner, String where, Type type) {
        for (Class<?> referenced : classesIn(type)) {
            if (internal(referenced)) violations.add(owner.getName() + " " + where + " " + referenced.getName());
        }
    }

    private static Set<Class<?>> classesIn(Type type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        Deque<Type> pending = new ArrayDeque<>(List.of(type));
        Set<Type> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Type next = pending.pop();
            if (!seen.add(next)) continue;
            if (next instanceof Class<?> c) {
                found.add(c.isArray() ? c.getComponentType() : c);
            } else if (next instanceof ParameterizedType p) {
                pending.push(p.getRawType());
                pending.addAll(Arrays.asList(p.getActualTypeArguments()));
            } else if (next instanceof GenericArrayType g) {
                pending.push(g.getGenericComponentType());
            } else if (next instanceof WildcardType w) {
                pending.addAll(Arrays.asList(w.getUpperBounds()));
                pending.addAll(Arrays.asList(w.getLowerBounds()));
            } else if (next instanceof TypeVariable<?> v) {
                pending.addAll(Arrays.asList(v.getBounds()));
            }
        }
        return found;
    }

    /** All main-code classes under org/jworkflow on the classpath (directories or jars), excluding test classes. */
    private static List<Class<?>> productionClasses() throws IOException, URISyntaxException, ClassNotFoundException {
        Set<String> names = new TreeSet<>();
        Enumeration<URL> roots = ApiBoundaryTest.class.getClassLoader().getResources(ROOT);
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if ("file".equals(root.getProtocol())) {
                Path dir = Path.of(root.toURI());
                if (dir.toString().replace('\\', '/').contains("/test-classes/")) continue;
                Path base = dir.getParent().getParent();
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(f -> f.toString().endsWith(".class")).forEach(f -> names.add(className(base.relativize(f).toString())));
                }
            } else if ("jar".equals(root.getProtocol())) {
                try (JarFile jar = ((JarURLConnection) root.openConnection()).getJarFile()) {
                    jar.stream().map(e -> e.getName()).filter(n -> n.startsWith(ROOT + "/") && n.endsWith(".class"))
                            .forEach(n -> names.add(className(n)));
                }
            }
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            if (name.endsWith("module-info") || name.endsWith("package-info")) continue;
            classes.add(Class.forName(name, false, ApiBoundaryTest.class.getClassLoader()));
        }
        assertTrue(classes.size() > 100, "expected to scan jworkflow-core and jworkflow-jdbc classes, found " + classes.size());
        return classes;
    }

    private static String className(String path) {
        return path.replace('\\', '/').replace('/', '.').replaceAll("\\.class$", "");
    }
}
