package com.botmaker.cli.validate;

import com.botmaker.plugin.api.DeclaredPlugin;

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
 * The classes a classpath entry's pom could name as its plugin (2026-10-27): each concrete top-level class extending
 * {@link DeclaredPlugin} directly, which is what {@code StudioPlugin}'s sealing allows. Read for the line that says
 * which {@code botmaker.plugin} to write ({@link Unloaded}) and for {@code botmaker plugin declare}, which writes it.
 */
public final class PluginClasses {

    private PluginClasses() {}

    /** The candidates in {@code entry}, a jar or a class directory, loaded over the whole {@code classpath}. */
    public static List<String> declarable(Path entry, List<Path> classpath) {
        List<String> found = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(urls(classpath), PluginClasses.class.getClassLoader())) {
            for (String name : classNames(entry)) {
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    if (type.getSuperclass() == DeclaredPlugin.class && !Modifier.isAbstract(type.getModifiers())) {
                        found.add(name);
                    }
                } catch (ReflectiveOperationException | LinkageError unloadable) {
                    // Not a class this can name; LOADS says what it can.
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
                files.map(entry::relativize).map(Path::toString).filter(PluginClasses::isTopLevelClass)
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
