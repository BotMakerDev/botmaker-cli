package com.botmaker.cli;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The example plugin {@code botmaker plugin new} copies, and the renaming that makes it somebody's plugin.
 *
 * <p><b>The skeleton is a project that compiled.</b> {@code botmaker-plugin-archetype} is the example plugin
 * itself, built and tested by its own CI, and its build zips its sources as the {@code template} artifact this
 * jar bundles ({@link #RESOURCE}). It was a {@code maven-archetype} until 2026-10-19: Velocity templates no
 * build compiled, which drifted from the toolkit twice before a test here caught it, ate every {@code ##}
 * heading and could not ship a {@code .gitignore}.
 *
 * <p>What a copy changes is the whole of what makes the example the author's, each found by an anchor the
 * example keeps, and a template without one is refused rather than copied half-renamed:
 * <ul>
 *   <li>the package {@value #PACKAGE}, in every source and in the pom's {@code botmaker.plugin} class, and the
 *       directories under {@code src/main/java} and {@code src/test/java};</li>
 *   <li>the name a user reads, the literal {@value #NAME} in the sources and the pom's {@code <name>};</li>
 *   <li>the pom's region marked {@code coordinates}, replaced, and its region marked {@code drop} (the build
 *       of this zip), removed; the contract and toolkit pins only when the author names other ones;</li>
 *   <li>{@code gitignore}, stored without its dot because a jar's default excludes drop a {@code .gitignore}.</li>
 * </ul>
 * There is no plugin id to rename (2026-10-27): the pom's {@code groupId:artifactId} is the plugin's identity.
 */
final class PluginTemplate {

    /** Where the build puts the archetype's {@code template} zip in this jar. */
    static final String RESOURCE = "/com/botmaker/cli/plugin-template.zip";

    /** The example's package. */
    static final String PACKAGE = "com.example.greetings";

    /** The example's display name, as the literal it is spelled with. */
    static final String NAME = "\"Greeter\"";

    private static final String PACKAGE_PATH = PACKAGE.replace('.', '/') + "/";
    private static final String GITIGNORE = "gitignore";

    /** A marked region of the pom, with the indentation before it and the line break after it. */
    private static final Pattern REGION = Pattern.compile(
            "[ \\t]*<!-- botmaker plugin new: (\\w+) -->.*?<!-- /botmaker plugin new -->\\R", Pattern.DOTALL);

    /** The pom property naming the plugin class ({@code PluginPoms.PROPERTY}), with the class between. */
    private static final Pattern PLUGIN_CLASS = Pattern.compile(
            "(<botmaker\\.plugin>)\\s*([^<]*?)\\s*(</botmaker\\.plugin>)");

    /**
     * What the new plugin is called. An empty pin keeps the one the template carries, which is the release
     * this {@code botmaker} was built with.
     */
    record Answers(String groupId, String artifactId, String version, String javaPackage,
                   String pluginName, Optional<String> studioApiVersion, Optional<String> toolkitVersion) {
        Answers {
            if (!SourceVersion.isName(javaPackage)) {
                throw new IllegalArgumentException("not a Java package name: " + javaPackage);
            }
            // Written into string literals and a Javadoc comment, where a line break or `*/` breaks the compile.
            if (pluginName.chars().anyMatch(Character::isISOControl) || pluginName.contains("*/")) {
                throw new IllegalArgumentException("a plugin name holds no line break and no */: " + pluginName);
            }
        }
    }

    /** The contract and toolkit versions the new plugin's pom pins. */
    record Made(String studioApiVersion, String toolkitVersion) {
    }

    private PluginTemplate() {
    }

    /** The template this jar was built with. */
    static InputStream bundled() {
        InputStream zip = PluginTemplate.class.getResourceAsStream(RESOURCE);
        if (zip == null) {
            throw new IllegalStateException("this botmaker was built without " + RESOURCE);
        }
        return zip;
    }

    /** Writes the template into {@code target}, renamed by {@code answers}. */
    static Made write(InputStream template, Path target, Answers answers) throws IOException {
        String packagePath = answers.javaPackage().replace('.', '/') + "/";
        Made made = null;
        try (ZipInputStream zip = new ZipInputStream(template)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                if (name.equals(GITIGNORE)) {
                    name = "." + GITIGNORE;
                } else if (name.equals("pom.xml")) {
                    text = pom(text, answers);
                    made = new Made(pin(text, "studioapi"), pin(text, "plugintoolkit"));
                } else if (name.endsWith(".java")) {
                    text = text.replace(PACKAGE, answers.javaPackage())
                            .replace(NAME, javaString(answers.pluginName()));
                    name = name.replace(PACKAGE_PATH, packagePath);
                }
                Path file = target.resolve(name).normalize();
                if (!file.startsWith(target)) {
                    throw new IllegalStateException("the template names a file outside the project: " + name);
                }
                Files.createDirectories(file.getParent());
                Files.writeString(file, text);
            }
        }
        if (made == null) throw new IllegalStateException("not the example plugin's template: no pom.xml");
        return made;
    }

    /** The template's pom with the new coordinates, without the template's own build, and with any new pins. */
    static String pom(String pom, Answers answers) {
        StringBuilder out = new StringBuilder();
        Matcher region = REGION.matcher(pom);
        boolean coordinates = false;
        boolean drop = false;
        while (region.find()) {
            String replacement = switch (region.group(1)) {
                case "coordinates" -> {
                    coordinates = true;
                    yield "    <groupId>" + xml(answers.groupId()) + "</groupId>\n"
                            + "    <artifactId>" + xml(answers.artifactId()) + "</artifactId>\n"
                            + "    <version>" + xml(answers.version()) + "</version>\n\n"
                            + "    <name>" + xml(answers.pluginName()) + "</name>\n";
                }
                case "drop" -> {
                    drop = true;
                    yield "";
                }
                default -> throw new IllegalStateException("the template's pom marks an unknown region: "
                        + region.group(1));
            };
            region.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        region.appendTail(out);
        if (!coordinates || !drop) {
            throw new IllegalStateException("the template's pom has no region marked "
                    + (coordinates ? "drop" : "coordinates"));
        }
        String copied = pluginClass(out.toString(), answers.javaPackage());
        String api = answers.studioApiVersion().map(v -> withPin(copied, "studioapi", v)).orElse(copied);
        return answers.toolkitVersion().map(v -> withPin(api, "plugintoolkit", v)).orElse(api);
    }

    private static Pattern property(String key) {
        return Pattern.compile("(<botmaker\\." + key + "\\.version>)([^<]*)(</botmaker\\." + key + "\\.version>)");
    }

    private static String pin(String pom, String key) {
        Matcher match = property(key).matcher(pom);
        if (!match.find()) {
            throw new IllegalStateException("the template's pom has no botmaker." + key + ".version");
        }
        return match.group(2);
    }

    private static String withPin(String pom, String key, String version) {
        pin(pom, key);
        return property(key).matcher(pom).replaceFirst("$1" + Matcher.quoteReplacement(xml(version)) + "$3");
    }

    /**
     * The pom with its one {@code botmaker.plugin} class moved into {@code javaPackage}, as the sources are: the
     * class a host constructs is the one the copy holds. The example's must be in {@value #PACKAGE}, or the copy
     * would name a class it does not have.
     */
    private static String pluginClass(String pom, String javaPackage) {
        Matcher match = PLUGIN_CLASS.matcher(pom);
        if (!match.find()) throw new IllegalStateException("the template's pom sets no botmaker.plugin");
        String example = match.group(2);
        if (!example.startsWith(PACKAGE + ".")) {
            throw new IllegalStateException("the template's botmaker.plugin, " + example + ", is not in " + PACKAGE);
        }
        String replaced = pom.substring(0, match.start()) + match.group(1)
                + javaPackage + example.substring(PACKAGE.length()) + match.group(3) + pom.substring(match.end());
        if (match.find()) throw new IllegalStateException("the template's pom sets botmaker.plugin twice");
        return replaced;
    }

    private static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
