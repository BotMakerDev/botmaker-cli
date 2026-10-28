package com.botmaker.cli;

import com.botmaker.cli.validate.PluginValidator;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
 *   <li>the package {@value #PACKAGE}, in every source and page and in the pom's {@code botmaker.plugin} class,
 *       and the directories under {@code src/main/java} and {@code src/test/java};</li>
 *   <li>the classes named {@value #CLASS_PREFIX}<i>Something</i> — {@code ExamplePlugin}, {@code ExampleTypes}, … —
 *       named after the plugin instead (2026-10-28), in the sources, the pages, the file names and the pom's
 *       {@code botmaker.plugin};</li>
 *   <li>the name a user reads, the literal {@value #NAME} in the sources and the pom's {@code <name>};</li>
 *   <li>the pom's region marked {@code coordinates}, replaced, and its region marked {@code drop} (the build
 *       of this zip), removed; its {@code <parent>}, {@code botmaker-plugin-parent}, found from the
 *       repository rather than beside the example in the umbrella, at another version only when the author
 *       names one;</li>
 *   <li>{@code gitignore}, stored without its dot because a jar's default excludes drop a {@code .gitignore}.</li>
 * </ul>
 * There is no plugin id to rename (2026-10-27): the pom's {@code groupId:artifactId} is the plugin's identity.
 *
 * <p>Entries under {@value #MINIMAL} are the {@code --minimal} variant: a plugin class that declares nothing, its
 * one test and a README. A minimal copy writes them in place of the example's {@code src/} and README, and keeps the
 * rest (the pom, the changelog, the icon, {@code DECLARE.md}); a full copy skips them.
 */
final class PluginTemplate {

    /** Where the build puts the archetype's {@code template} zip in this jar. */
    static final String RESOURCE = "/com/botmaker/cli/plugin-template.zip";

    /** The example's package. */
    static final String PACKAGE = "com.example.greetings";

    /** What the example's class names start with, each renamed to the plugin's own prefix. */
    static final String CLASS_PREFIX = "Example";

    /** The example's display name, as the literal it is spelled with. */
    static final String NAME = "\"Greeter\"";

    /** The zip's folder holding the {@code --minimal} variant's files, at the paths they take in the copy. */
    static final String MINIMAL = "minimal/";

    private static final String PACKAGE_PATH = PACKAGE.replace('.', '/') + "/";
    private static final String GITIGNORE = "gitignore";

    /** {@code ExampleTypes}, never the word {@code Example} in a sentence: the prefix followed by a capital. */
    private static final Pattern CLASS_NAME = Pattern.compile("\\b" + CLASS_PREFIX + "(?=[A-Z])");

    /** A marked region of the pom, with the indentation before it and the line break after it. */
    private static final Pattern REGION = Pattern.compile(
            "[ \\t]*<!-- botmaker plugin new: (\\w+) -->.*?<!-- /botmaker plugin new -->\\R", Pattern.DOTALL);

    /** The parent every plugin names, which pins the contract and the toolkit together. */
    static final String PARENT = PluginValidator.PARENT_ARTIFACT;

    /** The pom's {@code <parent>} element. */
    private static final Pattern PARENT_ELEMENT = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);
    private static final Pattern PARENT_VERSION = Pattern.compile("(<version>)\\s*([^<]*?)\\s*(</version>)");
    private static final Pattern RELATIVE_PATH = Pattern.compile("<relativePath>[^<]*</relativePath>");

    /** The pom property naming the plugin class ({@code PluginPoms.PROPERTY}), with the class between. */
    private static final Pattern PLUGIN_CLASS = Pattern.compile(
            "(<botmaker\\.plugin>)\\s*([^<]*?)\\s*(</botmaker\\.plugin>)");

    /**
     * What the new plugin is called. An empty parent version keeps the one the template carries, which is the
     * release this {@code botmaker} was built with. {@code classPrefix} replaces {@value #CLASS_PREFIX} in every
     * class name ({@code DiscordNotifier} makes {@code DiscordNotifierPlugin}).
     */
    record Answers(String groupId, String artifactId, String version, String javaPackage,
                   String pluginName, String classPrefix, boolean minimal, Optional<String> parentVersion) {
        Answers {
            if (!SourceVersion.isName(javaPackage)) {
                throw new IllegalArgumentException("not a Java package name: " + javaPackage);
            }
            // Written into string literals and a Javadoc comment, where a line break or `*/` breaks the compile.
            if (pluginName.chars().anyMatch(Character::isISOControl) || pluginName.contains("*/")) {
                throw new IllegalArgumentException("a plugin name holds no line break and no */: " + pluginName);
            }
            if (classPrefix.isEmpty() || !Character.isUpperCase(classPrefix.charAt(0))
                    || !SourceVersion.isIdentifier(classPrefix) || SourceVersion.isKeyword(classPrefix)) {
                throw new IllegalArgumentException("not a class name prefix (a Java name starting with a capital): "
                        + classPrefix);
            }
        }
    }

    /** The parent version the new plugin's pom names, and the plugin class it declares. */
    record Made(String parentVersion, String pluginClass) {
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
        for (Map.Entry<String, byte[]> entry : chosen(template, answers.minimal()).entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (name.equals(GITIGNORE)) {
                name = "." + GITIGNORE;
            } else if (name.equals("pom.xml")) {
                String pom = pom(new String(bytes, StandardCharsets.UTF_8), answers);
                bytes = pom.getBytes(StandardCharsets.UTF_8);
                made = new Made(parentVersion(pom), pluginClassOf(pom));
            } else if (name.endsWith(".java") || name.endsWith(".md")) {
                String text = new String(bytes, StandardCharsets.UTF_8).replace(PACKAGE, answers.javaPackage())
                        .replace(NAME, javaString(answers.pluginName()));
                bytes = classNames(text, answers.classPrefix()).getBytes(StandardCharsets.UTF_8);
                name = classNames(name.replace(PACKAGE_PATH, packagePath), answers.classPrefix());
            }
            Path file = target.resolve(name).normalize();
            if (!file.startsWith(target)) {
                throw new IllegalStateException("the template names a file outside the project: " + name);
            }
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        }
        if (made == null) throw new IllegalStateException("not the example plugin's template: no pom.xml");
        return made;
    }

    /**
     * The zip's files the copy writes, by the path they take: for a full copy every file outside {@value #MINIMAL};
     * for a minimal one each {@value #MINIMAL} file in place of the example's {@code src/} and of the file at its
     * path, beside the rest. Bytes, not text: the icon is a PNG.
     */
    private static Map<String, byte[]> chosen(InputStream template, boolean minimal) throws IOException {
        Map<String, byte[]> full = new LinkedHashMap<>();
        Map<String, byte[]> variant = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(template)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.startsWith(MINIMAL)) {
                    variant.put(name.substring(MINIMAL.length()), zip.readAllBytes());
                } else {
                    full.put(name, zip.readAllBytes());
                }
            }
        }
        if (!minimal) return full;
        if (variant.isEmpty()) throw new IllegalStateException("the template has no " + MINIMAL + " variant");
        Map<String, byte[]> out = new LinkedHashMap<>();
        full.forEach((name, bytes) -> {
            if (!name.startsWith("src/") && !variant.containsKey(name)) out.put(name, bytes);
        });
        out.putAll(variant);
        return out;
    }

    /** {@code text} with every {@code ExampleSomething} class name made {@code <prefix>Something}. */
    private static String classNames(String text, String prefix) {
        return CLASS_NAME.matcher(text).replaceAll(Matcher.quoteReplacement(prefix));
    }

    /** The template's pom with the new coordinates, without the template's own build, and on its parent. */
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
        return parent(pluginClass(out.toString(), answers), answers.parentVersion());
    }

    /**
     * The pom with its {@code <parent>} found from the repository — the example's {@code relativePath} names the
     * umbrella's sibling, which a new plugin has not got — and at {@code version} when one is given.
     */
    private static String parent(String pom, Optional<String> version) {
        Matcher element = parentElement(pom);
        String parent = RELATIVE_PATH.matcher(element.group()).replaceFirst("<relativePath/>");
        if (version.isPresent()) {
            parent = PARENT_VERSION.matcher(parent)
                    .replaceFirst("$1" + Matcher.quoteReplacement(xml(version.get())) + "$3");
        }
        return pom.substring(0, element.start()) + parent + pom.substring(element.end());
    }

    private static String parentVersion(String pom) {
        Matcher version = PARENT_VERSION.matcher(parentElement(pom).group());
        if (!version.find()) throw new IllegalStateException("the template's <parent> names no version");
        return version.group(2);
    }

    /** The {@code <parent>} naming {@value #PARENT}; a template on any other is refused. */
    private static Matcher parentElement(String pom) {
        Matcher element = PARENT_ELEMENT.matcher(pom);
        if (!element.find() || !element.group().matches("(?s).*<artifactId>\\s*" + PARENT + "\\s*</artifactId>.*")) {
            throw new IllegalStateException("the template's pom names no " + PARENT + " <parent>");
        }
        return element;
    }

    /**
     * The pom with its one {@code botmaker.plugin} class moved into the new package and renamed by the new prefix,
     * as the sources are: the class a host constructs is the one the copy holds. The example's must be in
     * {@value #PACKAGE}, or the copy would name a class it does not have.
     */
    private static String pluginClass(String pom, Answers answers) {
        Matcher match = PLUGIN_CLASS.matcher(pom);
        if (!match.find()) throw new IllegalStateException("the template's pom sets no botmaker.plugin");
        String example = match.group(2);
        if (!example.startsWith(PACKAGE + ".")) {
            throw new IllegalStateException("the template's botmaker.plugin, " + example + ", is not in " + PACKAGE);
        }
        String renamed = answers.javaPackage() + classNames(example.substring(PACKAGE.length()), answers.classPrefix());
        String replaced = pom.substring(0, match.start()) + match.group(1) + renamed + match.group(3)
                + pom.substring(match.end());
        if (match.find()) throw new IllegalStateException("the template's pom sets botmaker.plugin twice");
        return replaced;
    }

    private static String pluginClassOf(String pom) {
        Matcher match = PLUGIN_CLASS.matcher(pom);
        if (!match.find()) throw new IllegalStateException("the template's pom sets no botmaker.plugin");
        return match.group(2);
    }

    private static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
