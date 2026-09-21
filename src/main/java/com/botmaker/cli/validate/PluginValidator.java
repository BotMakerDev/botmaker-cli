package com.botmaker.cli.validate;

import com.botmaker.cli.project.Poms;
import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.MemberEntry;
import com.botmaker.plugin.api.catalog.MemberId;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.plugin.host.PluginLoader;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The eight checks, run once, over a {@link PluginSubject}.
 *
 * <p><b>This class is the reason {@code botmaker-cli}'s main artifact is a library.</b> It has two callers
 * in two repositories — the author's {@code botmaker validate} and the plugin registry's CI on a pull
 * request — and they must reach the same verdict, because a submission that fails for a reason its author
 * could not have seen coming is exactly the experience the gate exists to prevent. So: no printing, no
 * {@code System.exit}, no process spawned, no network. Everything that resolves a coordinate or reads a
 * command line lives in {@code com.botmaker.cli} and hands the resolved facts in.
 *
 * <p><b>What a pass is not.</b> Every check here asks whether a plugin <em>works</em> — loads, offers a
 * clean catalog, collides with nobody. None of them asks whether it is <em>safe</em>: a plugin runs
 * arbitrary code in the host's process, and no amount of loading it proves anything about what it then does.
 * The registry is a curated index with a working gate, never a security boundary, and its README says so in
 * those words.
 */
public final class PluginValidator {

    /**
     * A plugin id is lower case, starts and ends with a letter or digit, and is separated by dots, dashes
     * or underscores. Not a style rule: the id keys the host's merge and is written into a project file, so
     * two ids differing only in case are two plugins on a case-insensitive filesystem and one everywhere
     * else.
     */
    private static final Pattern ID = Pattern.compile("[a-z0-9]([a-z0-9._-]*[a-z0-9])?");

    /**
     * The two coordinates the platform's own rules are about.
     *
     * <p>Public because the checks are not their only reader: {@code plugin publish} composes a registry
     * entry that must not name either, and the registry's gate refuses one that does. Three spellings of
     * {@code botmaker-plugin-toolkit} in one module is how a rule comes to be enforced in two places and one
     * of them means something slightly different.
     */
    public static final String CONTRACT_GROUP = "com.github.LiQiyeDev";
    public static final String CONTRACT_ARTIFACT = "botmaker-studio-api";
    public static final String TOOLKIT_ARTIFACT = "botmaker-plugin-toolkit";

    private PluginValidator() {
    }

    /**
     * Runs every check and reports each one, in {@link Check} order and always all eight.
     *
     * <p>A check whose predecessor made it unanswerable is a {@link Status#SKIP} with the reason, never a
     * second failure and never silence: a report that shrinks when things go wrong is a report that hides
     * how much it did not look at.
     */
    public static List<CheckResult> validate(PluginSubject subject) {
        List<CheckResult> results = new ArrayList<>();

        CheckResult classpath = checkClasspath(subject);
        results.add(classpath);
        if (classpath.failed()) {
            for (Check check : List.of(Check.LOADS, Check.ID, Check.PALETTE, Check.VALUE_TYPES, Check.EDITORS)) {
                results.add(CheckResult.skip(check, "the classpath did not resolve"));
            }
            results.add(checkPomScopes(subject));
            results.add(checkPluginDeps(subject));
            return List.copyOf(results);
        }

        // One loader for every check that needs a loaded plugin. Opening it five times would be five
        // URLClassLoaders holding the same jars — and on Windows a held jar cannot be replaced, which is
        // the same reason PluginLoader is Closeable in the first place.
        try (PluginLoader loaded = PluginLoader.open(subject.classpath().stream().map(Object::toString).toList())) {
            List<StudioPlugin> plugins = loaded == null ? List.of() : List.copyOf(loaded.plugins());
            if (plugins.isEmpty()) {
                results.add(CheckResult.fail(Check.LOADS, List.of(
                        "no StudioPlugin was found on the classpath",
                        "PluginLoader answers `null` for all of: nothing to load, no"
                                + " META-INF/services/com.botmaker.plugin.api.StudioPlugin, a services file naming"
                                + " a class that is not there, and a plugin whose own dependency is missing",
                        "check that src/main/resources/META-INF/services/com.botmaker.plugin.api.StudioPlugin"
                                + " exists and names your plugin's fully qualified class")));
                for (Check check : List.of(Check.ID, Check.PALETTE, Check.VALUE_TYPES, Check.EDITORS)) {
                    results.add(CheckResult.skip(check, "nothing loaded"));
                }
            } else {
                results.add(CheckResult.pass(Check.LOADS, plugins.size() + " plugin(s): " + ids(plugins)));
                results.add(checkIds(plugins, subject));
                results.add(checkPalette(plugins, subject));
                results.add(checkValueTypes(plugins, subject));
                results.add(checkEditors(plugins));
            }
        }

        results.add(checkPomScopes(subject));
        results.add(checkPluginDeps(subject));
        return List.copyOf(results);
    }

    /** Whether every check either passed or was skipped for a reason that is not a failure. */
    public static boolean passed(List<CheckResult> results) {
        return results.stream().noneMatch(CheckResult::failed);
    }

    // -------------------------------------------------------------------------------------------------
    // 1 — classpath
    // -------------------------------------------------------------------------------------------------

    private static CheckResult checkClasspath(PluginSubject subject) {
        if (subject.classpath().isEmpty()) {
            return CheckResult.fail(Check.CLASSPATH, "the classpath is empty");
        }
        List<String> missing = subject.classpath().stream()
                .filter(path -> !Files.exists(path))
                .map(Object::toString)
                .toList();
        return missing.isEmpty()
                ? CheckResult.pass(Check.CLASSPATH, subject.classpath().size() + " entries")
                : CheckResult.fail(Check.CLASSPATH,
                        missing.stream().map(path -> "no such classpath entry: " + path).toList());
    }

    // -------------------------------------------------------------------------------------------------
    // 3 — ids
    // -------------------------------------------------------------------------------------------------

    private static CheckResult checkIds(List<StudioPlugin> plugins, PluginSubject subject) {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StudioPlugin plugin : plugins) {
            String id;
            try {
                id = plugin.id();
            } catch (RuntimeException e) {
                problems.add(plugin.getClass().getName() + "#id() threw " + e);
                continue;
            }
            if (id == null || id.isBlank()) {
                problems.add(plugin.getClass().getName() + " has a blank id");
            } else if (!ID.matcher(id).matches()) {
                problems.add("'" + id + "' is not a well-formed id; expected lower case, starting and ending"
                        + " with a letter or digit, separated by . - or _");
            } else if (!seen.add(id)) {
                problems.add("'" + id + "' is claimed twice inside this build");
            } else if (subject.judges(id) && subject.claimedPluginIds().contains(id)) {
                problems.add("'" + id + "' is already registered by another plugin; pick an id nobody else"
                        + " could reasonably want, and prefix it with something that is yours");
            }
        }
        return problems.isEmpty() ? CheckResult.pass(Check.ID, String.join(", ", seen))
                : CheckResult.fail(Check.ID, problems);
    }

    // -------------------------------------------------------------------------------------------------
    // 4 — palette
    // -------------------------------------------------------------------------------------------------

    private static CheckResult checkPalette(List<StudioPlugin> plugins, PluginSubject subject) {
        List<String> problems = new ArrayList<>();
        int facades = 0;
        int members = 0;
        for (StudioPlugin plugin : plugins) {
            PaletteCatalog catalog;
            try {
                catalog = plugin.catalog(subject.pinnedVersion());
            } catch (RuntimeException | LinkageError e) {
                problems.add(safeId(plugin) + "#catalog() threw " + e);
                continue;
            }
            if (catalog == null) {
                problems.add(safeId(plugin) + "#catalog() returned null; return PaletteCatalog.empty()"
                        + " to offer everything the jar contains");
                continue;
            }
            catalog.problems().forEach(problem -> problems.add(safeId(plugin) + ": " + problem));
            for (FacadeEntry facade : catalog.facades()) {
                facades++;
                for (MemberEntry member : facade.members()) {
                    members++;
                    // A catalog entry naming a member that no longer exists is the failure the deleted
                    // annotation processor made a javac error. It is checked here rather than assumed
                    // because nothing has made it a compile error since 2026-08-27: members are
                    // DISCOVERED by reflection, so a catalog is only ever as true as the jar it was built
                    // from — and this subject's jar is the one being submitted.
                    if (!resolves(member.id())) {
                        problems.add(safeId(plugin) + ": " + member.id()
                                + " does not resolve to a public member of its facade");
                    }
                }
            }
        }
        return problems.isEmpty()
                ? CheckResult.pass(Check.PALETTE, facades + " facade(s), " + members + " member(s)")
                : CheckResult.fail(Check.PALETTE, problems);
    }

    private static boolean resolves(MemberId id) {
        try {
            for (Method method : id.declaringClass().getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && MemberId.of(method).equals(id)) {
                    return true;
                }
            }
            return false;
        } catch (LinkageError e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 5 — value types
    // -------------------------------------------------------------------------------------------------

    private static CheckResult checkValueTypes(List<StudioPlugin> plugins, PluginSubject subject) {
        List<String> problems = new ArrayList<>();
        List<String> registered = new ArrayList<>();
        // Merged as the host merges, so a clash between two plugins in ONE build is caught here rather
        // than at the registry — clashesWith is the contract's own answer to this question and is reused
        // rather than reimplemented.
        ValueCatalog accumulated = ValueCatalog.empty();
        for (StudioPlugin plugin : plugins) {
            ValueCatalog catalog;
            try {
                catalog = plugin.valueTypes();
            } catch (RuntimeException | LinkageError e) {
                problems.add(safeId(plugin) + "#valueTypes() threw " + e);
                continue;
            }
            if (catalog == null) {
                problems.add(safeId(plugin) + "#valueTypes() returned null; return ValueCatalog.empty()");
                continue;
            }
            accumulated.clashesWith(catalog).forEach(id ->
                    problems.add(safeId(plugin) + ": value type id '" + id
                            + "' is already registered by another plugin in this build"));
            for (ValueType type : catalog.types()) {
                String id = type.id();
                if (id == null || id.isBlank()) {
                    problems.add(safeId(plugin) + " registers a value type with a blank id");
                    continue;
                }
                registered.add(id);
                // A plugin this classpath carries only because the submitted plugin depends on it
                // registers its own ids, under its own entry. Judging them here would refuse the SDK for
                // plugin-basics' nine, and the advice in the message would be to rename them.
                if (subject.judges(safeId(plugin)) && subject.claimedValueTypeIds().contains(id)) {
                    problems.add(safeId(plugin) + ": value type id '" + id
                            + "' is already registered by another plugin — a registry entry, or one the"
                            + " host itself ships; prefix yours");
                }
                // NOT checked here: whether the id is prefixed. The contract asks an author to "prefix an
                // id that is not obviously yours" and that is advice, not a rule anything can enforce —
                // the SDK's own seventeen ids are bare (TEXT, NUMBER, …) because they are the old enum
                // constant names and every project ever written holds them. A gate that refused a bare id
                // would refuse the plugin the platform was built around, and one that carved out an
                // exception for all-caps would be guessing. The collision it guards against is caught for
                // real, by name, two branches up.
            }
            accumulated = accumulated.merge(catalog);
        }
        return problems.isEmpty()
                ? CheckResult.pass(Check.VALUE_TYPES, registered.isEmpty() ? "none registered"
                        : String.join(", ", registered))
                : CheckResult.fail(Check.VALUE_TYPES, problems);
    }

    // -------------------------------------------------------------------------------------------------
    // 6 — editors
    // -------------------------------------------------------------------------------------------------

    /**
     * {@code slotEditors()} builds, and every predicate answers both shapes without throwing.
     *
     * <p><b>The predicate is checked and the node is not, which is narrower than it looks and is a real
     * limitation.</b> Building a node needs a live JavaFX toolkit, and this CLI ships as one jar for every
     * OS precisely by not carrying JavaFX — so where JavaFX is absent this check does what it can and says
     * what it did not do. Even reaching {@code slotEditors()} needs {@code javafx.scene.Node} on the
     * classpath, because an editor written as a lambda links its {@code (ValueContext)Node} method type when
     * the list is built; without it the whole check skips.
     *
     * <p>The half that is checked is the half that decides <em>which</em> editor a slot gets, and a
     * predicate that throws takes down every editor after it in the merge. The half that is not is seen the
     * first time anybody clicks the slot — which is what {@code botmaker run} is for.
     */
    private static CheckResult checkEditors(List<StudioPlugin> plugins) {
        try {
            Class.forName("javafx.scene.Node");
        } catch (ClassNotFoundException | LinkageError e) {
            return CheckResult.skip(Check.EDITORS, "no JavaFX on this classpath, so a plugin's editor list"
                    + " cannot even be linked. Run `botmaker run` to see the editors draw, or put"
                    + " javafx-controls on the classpath to check the predicates here");
        }
        List<String> problems = new ArrayList<>();
        int editors = 0;
        for (StudioPlugin plugin : plugins) {
            List<SlotEditor> list;
            try {
                list = plugin.slotEditors();
            } catch (RuntimeException | LinkageError e) {
                problems.add(safeId(plugin) + "#slotEditors() threw " + e);
                continue;
            }
            if (list == null) {
                problems.add(safeId(plugin) + "#slotEditors() returned null; return List.of()");
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                SlotEditor editor = list.get(i);
                if (editor == null) {
                    problems.add(safeId(plugin) + ": slotEditors()[" + i + "] is null");
                    continue;
                }
                editors++;
                // Both shapes, because an editor chosen by the CALL must decline the Parameters row and an
                // editor chosen by the TYPE must claim it: whichever this one is, one of these two asks it
                // the question it was not written for, and that is where a predicate throws.
                problems.addAll(answers(plugin, i, editor));
            }
        }
        return problems.isEmpty() ? CheckResult.pass(Check.EDITORS, editors + " editor(s)")
                : CheckResult.fail(Check.EDITORS, problems);
    }

    private static List<String> answers(StudioPlugin plugin, int index, SlotEditor editor) {
        List<String> problems = new ArrayList<>();
        try {
            editor.matches(StubContexts.slot("java.lang.String", "Example", "example", 0, "\"x\""));
        } catch (RuntimeException | LinkageError e) {
            problems.add(safeId(plugin) + ": slotEditors()[" + index + "].matches threw on a slot: " + e);
        }
        try {
            editor.matches(StubContexts.row("java.lang.String", "x"));
        } catch (RuntimeException | LinkageError e) {
            problems.add(safeId(plugin) + ": slotEditors()[" + index + "].matches threw on a Parameters row"
                    + " (a row has no call behind it, so enclosingClass()/argIndex() are not a slot's): " + e);
        }
        return problems;
    }

    // -------------------------------------------------------------------------------------------------
    // 7 — pom scopes
    // -------------------------------------------------------------------------------------------------

    /**
     * The contract is {@code provided} and the toolkit is not.
     *
     * <p>Both mistakes compile, both produce a jar, and both fail only once a host loads the result. A
     * {@code compile}-scoped contract ships a second copy of the boundary types, which the loader's
     * parent-first arm exists to make impossible — the symptom is a {@code ClassCastException} between two
     * classes with identical names. A {@code provided}-scoped toolkit is simply absent at load time, because
     * the host does not have one to provide: {@code botmaker-studio} must never depend on the toolkit, or
     * two plugins could not hold two versions of it.
     */
    private static CheckResult checkPomScopes(PluginSubject subject) {
        if (subject.pom() == null) {
            return CheckResult.skip(Check.POM_SCOPES, "no pom.xml to read");
        }
        List<Poms.Dependency> declared;
        try {
            declared = Poms.dependencies(subject.pom());
        } catch (Exception e) {
            return CheckResult.fail(Check.POM_SCOPES, "cannot read " + subject.pom() + ": " + e.getMessage());
        }
        List<String> problems = new ArrayList<>();
        Poms.Dependency contract = Poms.find(declared, CONTRACT_GROUP, CONTRACT_ARTIFACT).orElse(null);
        if (contract == null) {
            problems.add("no dependency on " + CONTRACT_GROUP + ":" + CONTRACT_ARTIFACT
                    + "; a plugin implements the contract, so it must declare it");
        } else if (!"provided".equals(contract.scope())) {
            problems.add(CONTRACT_ARTIFACT + " is declared at scope '"
                    + (contract.scope().isEmpty() ? "compile" : contract.scope())
                    + "'; it must be `provided`. The host has the contract already, and a second copy makes"
                    + " a contract class two different Class objects");
        }
        Poms.Dependency toolkit = Poms.find(declared, CONTRACT_GROUP, TOOLKIT_ARTIFACT).orElse(null);
        if (toolkit != null && "provided".equals(toolkit.scope())) {
            problems.add(TOOLKIT_ARTIFACT + " is declared `provided`; it must not be. The toolkit is resolved"
                    + " onto the PLUGIN's classloader — the host does not have one to provide, because"
                    + " botmaker-studio must never depend on it");
        }
        return problems.isEmpty()
                ? CheckResult.pass(Check.POM_SCOPES, toolkit == null ? "contract provided, no toolkit"
                        : "contract provided, toolkit "
                                + (toolkit.scope().isEmpty() ? "compile" : toolkit.scope()))
                : CheckResult.fail(Check.POM_SCOPES, problems);
    }

    // -------------------------------------------------------------------------------------------------
    // 8 — plugin deps
    // -------------------------------------------------------------------------------------------------

    /**
     * The toolkit is not {@code optional}.
     *
     * <p><b>{@code optional} means <i>not transitive</i>, and this project has shipped that mistake three
     * times.</b> On 2026-08-28 the SDK's {@code optional} toolkit meant Studio's classpath had none, so
     * {@code ServiceLoader} could not resolve {@code SdkPlugin}'s own superclass and Studio ran with an
     * empty palette and one line on stderr. On 2026-09-04 the same dependency was found {@code optional}
     * again. On 2026-09-05 it was {@code javafx-controls}, linked from {@code SdkPlugin}'s <i>constructor</i>,
     * so {@code v1.1.5} could not be instantiated by any host without JavaFX — which every headless host is.
     *
     * <p><b>It is invisible in the module that has the bug</b>, which is the whole reason it is a check
     * here. An {@code optional} dependency <i>is</i> on its own project's classpath: every test passes, the
     * jar builds, and {@code botmaker plugin validate} over a working copy passes too. Only a consumer
     * resolving the published artifact sees it — and the first consumer is a host loading the plugin.
     *
     * <p><b>Why the toolkit by name, rather than every {@code optional} dependency.</b> A plugin is nobody's
     * dependency, so {@code optional} never buys one anything — but the SDK is a library <em>and</em> a
     * plugin in one jar, and it marks the pilot's server and QR encoder {@code optional} precisely so a
     * headless bot links neither. A blanket refusal would refuse the plugin this platform was built around.
     * The toolkit is different: nothing but plugin code can name a toolkit type, so an {@code optional} one
     * is a dependency the plugin's own classes link and no consumer resolves. That is a fact about the
     * graph, not a judgement.
     */
    private static CheckResult checkPluginDeps(PluginSubject subject) {
        if (subject.pom() == null) {
            return CheckResult.skip(Check.PLUGIN_DEPS, "no pom.xml to read");
        }
        List<Poms.Dependency> declared;
        try {
            declared = Poms.dependencies(subject.pom());
        } catch (Exception e) {
            return CheckResult.fail(Check.PLUGIN_DEPS, "cannot read " + subject.pom() + ": " + e.getMessage());
        }
        Poms.Dependency toolkit = Poms.find(declared, CONTRACT_GROUP, TOOLKIT_ARTIFACT).orElse(null);
        if (toolkit == null) {
            return CheckResult.pass(Check.PLUGIN_DEPS, "no toolkit to be optional");
        }
        if (toolkit.optional()) {
            return CheckResult.fail(Check.PLUGIN_DEPS, List.of(
                    TOOLKIT_ARTIFACT + " is declared `optional`; it must not be",
                    "`optional` means NOT TRANSITIVE: the toolkit is on this build's own classpath, so"
                            + " everything here compiles and passes, and it is absent from the classpath a"
                            + " host resolves this plugin onto",
                    "the host cannot supply one either — botmaker-studio must never depend on the toolkit —"
                            + " so the plugin fails to load and the symptom is an empty palette",
                    "remove <optional>true</optional>; a plugin is nobody's dependency, so it buys nothing"));
        }
        return CheckResult.pass(Check.PLUGIN_DEPS, "toolkit is transitive");
    }

    // -------------------------------------------------------------------------------------------------

    private static String ids(List<StudioPlugin> plugins) {
        return String.join(", ", plugins.stream().map(PluginValidator::safeId).toList());
    }

    /** A plugin whose {@code id()} throws still has to be nameable in the report about it. */
    private static String safeId(StudioPlugin plugin) {
        try {
            String id = plugin.id();
            return id == null || id.isBlank() ? plugin.getClass().getName() : id;
        } catch (RuntimeException | LinkageError e) {
            return plugin.getClass().getName();
        }
    }
}
