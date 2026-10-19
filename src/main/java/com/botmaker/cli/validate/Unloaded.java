package com.botmaker.cli.validate;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader.PluginFailure;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Why no plugin loaded, said as the line to fix (2026-10-19, `docs/refactor/46-plugin-target.md` F4): each provider
 * that failed, with its own cause, or, when the plugin's own entry has no services file, the class to name in it.
 * A plugin that compiles and packages but is listed nowhere contributes nothing, and nothing else says so.
 */
final class Unloaded {

    static final String SERVICES = "META-INF/services/" + StudioPlugin.class.getName();

    private Unloaded() {}

    /** The lines of a failed LOADS check; {@code classpath}'s first entry is the plugin's own. */
    static List<String> why(List<Path> classpath, List<PluginFailure> failures) {
        List<String> lines = new ArrayList<>();
        for (PluginFailure failure : failures) lines.add(failure.describe());
        if (!lines.isEmpty() || classpath.isEmpty()) return lines.isEmpty() ? List.of("nothing to load") : lines;

        Path own = classpath.getFirst();
        if (hasServices(own)) {
            return List.of(own.getFileName() + "'s " + SERVICES + " names no class: write your plugin's fully"
                    + " qualified class on a line of its own");
        }
        List<String> plugins = pluginClasses(own, classpath);
        if (plugins.isEmpty()) {
            return List.of("no class in " + own.getFileName() + " implements StudioPlugin: a plugin is a class"
                    + " extending DeclaredPlugin, with a public no-argument constructor");
        }
        lines.add(own.getFileName() + " has no " + SERVICES + ", so no host loads "
                + String.join(", ", plugins));
        lines.add("write src/main/resources/" + SERVICES + " with the line: " + String.join(" / ", plugins));
        return lines;
    }

    private static boolean hasServices(Path entry) {
        if (Files.isDirectory(entry)) return Files.isRegularFile(entry.resolve(SERVICES));
        try (JarFile jar = new JarFile(entry.toFile())) {
            return jar.getEntry(SERVICES) != null;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** The concrete top-level {@link StudioPlugin} classes in {@code entry}, loaded over the whole classpath. */
    private static List<String> pluginClasses(Path entry, List<Path> classpath) {
        List<String> found = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(urls(classpath), Unloaded.class.getClassLoader())) {
            for (String name : classNames(entry)) {
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    if (StudioPlugin.class.isAssignableFrom(type) && !type.isInterface()
                            && !Modifier.isAbstract(type.getModifiers())) {
                        found.add(name);
                    }
                } catch (ReflectiveOperationException | LinkageError unloadable) {
                    // Not a plugin this check can name; LOADS says what it can.
                }
            }
        } catch (IOException unreadable) {
            return found;
        }
        return found;
    }

    private static List<String> classNames(Path entry) throws IOException {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(entry)) {
            try (Stream<Path> files = Files.walk(entry)) {
                files.map(entry::relativize).map(Path::toString).filter(Unloaded::isTopLevelClass)
                        .forEach(file -> names.add(className(file.replace('\\', '/'))));
            }
            return names;
        }
        try (JarFile jar = new JarFile(entry.toFile())) {
            for (Enumeration<JarEntry> each = jar.entries(); each.hasMoreElements(); ) {
                String file = each.nextElement().getName();
                if (isTopLevelClass(file)) names.add(className(file));
            }
        }
        return names;
    }

    private static boolean isTopLevelClass(String file) {
        return file.endsWith(".class") && !file.contains("$") && !file.endsWith("module-info.class");
    }

    private static String className(String file) {
        return file.substring(0, file.length() - ".class".length()).replace('/', '.');
    }

    private static URL[] urls(List<Path> classpath) throws MalformedURLException {
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = classpath.get(i).toUri().toURL();
        return urls;
    }
}
