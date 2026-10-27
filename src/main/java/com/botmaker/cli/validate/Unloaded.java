package com.botmaker.cli.validate;

import com.botmaker.plugin.host.PluginLoader.PluginFailure;
import com.botmaker.plugin.host.PluginPoms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Why no plugin loaded, said as the line to fix (2026-10-19, `docs/refactor/46-plugin-target.md` F4): each plugin
 * that failed, with its own cause, or, when the plugin's own pom names no class, the class to name in it
 * (2026-10-27). A plugin that compiles and packages but is declared nowhere contributes nothing, and nothing else
 * says so. A working copy is also offered {@code botmaker plugin declare}, which writes the property.
 */
final class Unloaded {

    private Unloaded() {}

    /** The lines of a failed LOADS check; {@code classpath}'s first entry is the plugin's own. */
    static List<String> why(List<Path> classpath, List<PluginFailure> failures) {
        List<String> lines = new ArrayList<>();
        for (PluginFailure failure : failures) lines.add(failure.describe());
        if (classpath.isEmpty()) return lines.isEmpty() ? List.of("nothing to load") : lines;

        Path own = classpath.getFirst();
        boolean workingCopy = Files.isDirectory(own);
        List<String> plugins = PluginClasses.declarable(own, classpath);
        if (!lines.isEmpty()) {
            // The pom names a class that is not the plugin: the one class that could be is offered.
            List<String> named = PluginPoms.read(own).stream().map(PluginPoms.PluginPom::pluginClass).toList();
            if (workingCopy && plugins.size() == 1 && !named.contains(plugins.getFirst())) {
                lines.add("`botmaker plugin declare` sets " + PluginPoms.PROPERTY + " to " + plugins.getFirst()
                        + ", the one class here that can be the plugin");
            }
            return lines;
        }
        if (plugins.isEmpty()) {
            return List.of("no class in " + own.getFileName() + " extends DeclaredPlugin: a plugin is a public"
                    + " final class extending DeclaredPlugin, with a public no-argument constructor");
        }
        lines.add("no " + PluginPoms.PROPERTY + " in " + (workingCopy ? "pom.xml" : "the pom in " + own.getFileName())
                + ", so no host loads "
                + String.join(", ", plugins));
        if (!workingCopy) {
            lines.add("a flattened pom keeps it only with <pomElements><properties>keep</properties></pomElements>;"
                    + " botmaker-plugin-parent's flatten does");
        }
        lines.add("add to pom.xml's <properties>: <" + PluginPoms.PROPERTY + ">" + String.join(" / ", plugins)
                + "</" + PluginPoms.PROPERTY + ">");
        if (workingCopy) {
            lines.add(plugins.size() == 1 ? "or run `botmaker plugin declare`, which sets it"
                    : "or run `botmaker plugin declare <class>` naming one of them");
        }
        return lines;
    }
}
