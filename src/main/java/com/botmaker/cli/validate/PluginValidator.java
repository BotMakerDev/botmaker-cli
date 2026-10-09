package com.botmaker.cli.validate;

import com.botmaker.cli.project.Poms;
import com.botmaker.plugin.api.slot.SlotEditor;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.catalog.FacadeEntry;
import com.botmaker.plugin.api.catalog.MemberEntry;
import com.botmaker.plugin.api.catalog.MemberId;
import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.EditableType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.ContractLinks;
import com.botmaker.plugin.host.Palettes;
import com.botmaker.plugin.host.PluginLoader;
import com.botmaker.plugin.host.Recordings;

import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public static final String CONTRACT_GROUP = "com.github.BotMakerDev";
    public static final String CONTRACT_ARTIFACT = "botmaker-studio-api";
    public static final String TOOLKIT_ARTIFACT = "botmaker-plugin-toolkit";

    /**
     * The groupId the contract and the toolkit were published under until 2026-10-05. Never required, never
     * written; recognised wherever a coordinate must <i>not</i> appear, because Maven cannot tell the two
     * spellings are one artifact and a second copy of the contract is exactly what those refusals prevent.
     */
    public static final String RETIRED_CONTRACT_GROUP = "com.github.LiQiyeDev";

    /** Whether {@code groupId} is the contract's and the toolkit's, today's spelling or the retired one. */
    public static boolean contractGroup(String groupId) {
        return CONTRACT_GROUP.equals(groupId) || RETIRED_CONTRACT_GROUP.equals(groupId);
    }

    private PluginValidator() {
    }

    /**
     * Runs every check and reports each one, in {@link Check} order and always all of them.
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
            for (Check check : List.of(Check.CONTRACT_LINKS, Check.LOADS, Check.ID, Check.PALETTE, Check.RECORDS,
                    Check.TYPES, Check.MANAGED, Check.EDITORS, Check.PICKERS)) {
                results.add(CheckResult.skip(check, "the classpath did not resolve"));
            }
            results.add(checkPomScopes(subject));
            results.add(checkPluginDeps(subject));
            return List.copyOf(results);
        }
        results.add(checkContractLinks(subject));

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
                for (Check check : List.of(Check.ID, Check.PALETTE, Check.RECORDS, Check.TYPES, Check.MANAGED,
                        Check.EDITORS, Check.PICKERS)) {
                    results.add(CheckResult.skip(check, "nothing loaded"));
                }
            } else {
                results.add(CheckResult.pass(Check.LOADS, plugins.size() + " plugin(s): " + ids(plugins)));
                results.add(checkIds(plugins, subject));
                results.add(checkPalette(plugins));
                results.add(checkRecords(plugins, subject));
                results.add(checkTypes(plugins, subject));
                results.add(checkManaged(plugins, subject));
                results.add(checkEditors(plugins));
                results.add(checkPickers(plugins, subject));
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
    // 2 — contract links
    // -------------------------------------------------------------------------------------------------

    /**
     * Each entry declaring a plugin, read against the contract this build carries — the same reading
     * {@code PluginLoader} makes before it constructs one, so the refusal is seen here first.
     */
    private static CheckResult checkContractLinks(PluginSubject subject) {
        ClassLoader contract = StudioPlugin.class.getClassLoader();
        List<String> problems = new ArrayList<>();
        int plugins = 0;
        for (var entry : subject.classpath()) {
            if (!ContractLinks.declaresPlugin(entry)) continue;
            plugins++;
            List<ContractLinks.Link> missing = ContractLinks.missing(entry, contract);
            if (!missing.isEmpty()) {
                problems.add(entry.getFileName() + ": " + ContractLinks.mismatch(entry, missing).getMessage());
            }
        }
        return problems.isEmpty()
                ? CheckResult.pass(Check.CONTRACT_LINKS, plugins == 0
                        ? "no entry declares a plugin" : plugins + " plugin entr" + (plugins == 1 ? "y" : "ies") + " checked")
                : CheckResult.fail(Check.CONTRACT_LINKS, problems);
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

    private static CheckResult checkPalette(List<StudioPlugin> plugins) {
        List<String> problems = new ArrayList<>();
        int facades = 0;
        int members = 0;
        for (StudioPlugin plugin : plugins) {
            PaletteCatalog catalog;
            try {
                catalog = plugin.catalog();
            } catch (RuntimeException | LinkageError e) {
                problems.add(safeId(plugin) + "#catalog() threw " + e);
                continue;
            }
            if (catalog == null) {
                problems.add(safeId(plugin) + "#catalog() returned null; return PaletteCatalog.empty()"
                        + " to let the host discover the palette from @Palette");
                continue;
            }
            // Empty is the default, and means the host discovers the palette from the plugin's own jar —
            // exactly as Studio does, so the validator judges the palette a user would see.
            if (Palettes.isDefault(catalog)) catalog = Palettes.discover(plugin.getClass());
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

    /**
     * Every {@code @Records} method of the judged plugins is {@code public static} and has no parameter the host
     * cannot fill — asked through {@link Recordings}, the code Studio records with, so a method this passes is
     * one Studio can write. Another plugin on the classpath may answer a parameter type, so all are asked.
     *
     * <p>Skipped on a host with no JavaFX when a plugin's types do not link: whether a type has components or a
     * fresh value is part of the answer, and it cannot be read here.
     */
    private static CheckResult checkRecords(List<StudioPlugin> plugins, PluginSubject subject) {
        for (StudioPlugin plugin : plugins) {
            try {
                plugin.types();
                plugin.componentTypes();
            } catch (LinkageError e) {
                return CheckResult.skip(Check.RECORDS, "no JavaFX on this classpath, and " + safeId(plugin)
                        + "'s types could not be linked without it");
            } catch (RuntimeException e) {
                // A throwing surface is TYPES' finding; here it only means that plugin fills nothing.
            }
        }
        List<String> problems = new ArrayList<>();
        int writers = 0;
        for (StudioPlugin plugin : plugins) {
            if (!subject.judges(safeId(plugin))) continue;
            problems.addAll(Recordings.problems(plugin, plugins).stream()
                    .map(problem -> safeId(plugin) + ": " + problem).toList());
            writers += Recordings.of(List.of(plugin)).size();
        }
        return problems.isEmpty()
                ? CheckResult.pass(Check.RECORDS, writers == 0 ? "none declared" : writers + " method(s)")
                : CheckResult.fail(Check.RECORDS, problems);
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
    // 5 — types
    // -------------------------------------------------------------------------------------------------

    /**
     * What a plugin says about its values holds: {@code types()} and {@code componentTypes()} answer, every
     * {@code PluginType} names a class and a fresh value of it, no class is declared twice in this build,
     * and a {@code ComponentType} takes its fresh value apart and puts it back.
     *
     * <p><b>The clash rule is the host's and no stricter.</b> {@code PluginHost.compose} leaves out a plugin
     * whose {@code types()} names a class another plugin already declared, and says so in Manage Plugins;
     * this is the same rule asked before anyone installs the pair. It replaced a registry-wide check on
     * string ids, which existed because an id was chosen by its author and written into project files. A
     * class is named by its package, which is its author's, so two plugins meet over one only when they
     * are loaded together — and a plugin brought by the one submitted is loaded here, beside it.
     *
     * <p><b>The round trip compares parts, never values.</b> {@code components(build(components(fresh())))}
     * must equal {@code components(fresh())}. Most classes a plugin writes have no {@code equals}, so asking
     * for equal values would refuse sound types; the parts are what the host writes, so parts that survive
     * the trip are the property the host relies on. A component type with no declared type of the same
     * class — the SDK's flow parts, which are never picked — has no fresh value to take apart and is only
     * checked for answering its component list.
     *
     * <p>Soundness is asked only of the plugin being judged: a plugin the classpath carries because the
     * submitted one depends on it answers for its own types under its own entry. The clash is asked of
     * everyone, because it is a fact about the two together.
     */
    private static CheckResult checkTypes(List<StudioPlugin> plugins, PluginSubject subject) {
        List<String> problems = new ArrayList<>();
        List<String> declared = new ArrayList<>();
        List<String> unlinked = new ArrayList<>();
        Map<String, String> owners = new HashMap<>();
        List<Class<?>> known = declaredClasses(plugins);
        int roundTrips = 0;
        for (StudioPlugin plugin : plugins) {
            String id = safeId(plugin);
            boolean judged = subject.judges(id);
            List<PluginType<?>> types;
            List<ComponentType<?>> components;
            try {
                types = plugin.types();
                components = plugin.componentTypes();
            } catch (LinkageError e) {
                // PluginType.editor returns a javafx Node, so a plugin's types can fail to link on a host
                // with no JavaFX — which this CLI is. That is the host's limit, not the plugin's fault.
                if (!javafx()) {
                    unlinked.add(id);
                } else {
                    problems.add(id + "#types() threw " + e);
                }
                continue;
            } catch (RuntimeException e) {
                problems.add(id + "#types() or #componentTypes() threw " + e);
                continue;
            }
            if (types == null) {
                problems.add(id + "#types() returned null; return List.of()");
                types = List.of();
            }
            if (components == null) {
                problems.add(id + "#componentTypes() returned null; return List.of()");
                components = List.of();
            }

            Map<String, Object> fresh = new HashMap<>();
            List<ComponentType<?>> shapes = new ArrayList<>(components);
            for (int i = 0; i < types.size(); i++) {
                PluginType<?> type = types.get(i);
                if (type == null) {
                    problems.add(id + ": types()[" + i + "] is null");
                    continue;
                }
                Class<?> cls;
                try {
                    cls = type.type();
                } catch (RuntimeException | LinkageError e) {
                    problems.add(id + ": types()[" + i + "].type() threw " + e);
                    continue;
                }
                if (cls == null) {
                    problems.add(id + ": types()[" + i + "].type() returned null");
                    continue;
                }
                String name = cls.getName();
                declared.add(cls.getSimpleName());
                String owner = owners.putIfAbsent(name, id);
                if (owner != null) {
                    problems.add(owner.equals(id)
                            ? id + " declares " + name + " twice; list each type once in types()"
                            : id + " declares " + name + ", which " + owner + " already declares. A host loads"
                                    + " the first and leaves the other plugin out");
                }
                if (type instanceof ComponentType<?> shape && !shapes.contains(shape)) {
                    shapes.add(shape);
                }
                if (!judged) {
                    continue;
                }
                Object value;
                try {
                    value = type.fresh();
                } catch (RuntimeException | LinkageError e) {
                    problems.add(id + ": " + name + " fresh() threw " + e);
                    continue;
                }
                if (value == null) {
                    // The contract's one exception: a type whose fresh form is a call the bot evaluates
                    // (the SDK's MatchResult starts as Vision.lastMatch()) answers freshCall() instead.
                    Method call;
                    try {
                        call = type.freshCall();
                    } catch (RuntimeException | LinkageError e) {
                        problems.add(id + ": " + name + " freshCall() threw " + e);
                        continue;
                    }
                    if (call == null) {
                        problems.add(id + ": " + name + " answers neither fresh() nor freshCall(); a new"
                                + " value has to start as something, and the host writes it into the bot's"
                                + " source");
                    } else {
                        String why = freshCallProblem(call, name);
                        if (why != null) problems.add(id + ": " + name + " freshCall() " + call + " " + why);
                    }
                } else if (!boxed(cls).isInstance(value)) {
                    problems.add(id + ": " + name + " fresh() returned a " + value.getClass().getName()
                            + ", which is not a " + name);
                } else {
                    fresh.put(name, value);
                }
            }

            if (!judged) {
                continue;
            }
            for (ComponentType<?> shape : shapes) {
                List<String> found = shapeProblems(id, shape, fresh, known);
                if (found == null) {
                    roundTrips++;
                } else {
                    problems.addAll(found);
                }
            }
        }
        if (!problems.isEmpty()) {
            return CheckResult.fail(Check.TYPES, problems);
        }
        if (!unlinked.isEmpty()) {
            return CheckResult.skip(Check.TYPES, "no JavaFX on this classpath, and the declared types of "
                    + String.join(", ", unlinked) + " could not be linked without it. Put javafx-controls on"
                    + " the classpath to check them here");
        }
        return CheckResult.pass(Check.TYPES, declared.isEmpty() ? "none declared"
                : String.join(", ", declared) + (roundTrips == 0 ? "" : "; " + roundTrips + " round-tripped"));
    }

    /**
     * Why the host could not write {@code call} as a fresh {@code typeName}, or {@code null} when it can: the
     * contract's shape is a public static method with no parameters returning the type by name. A host skips
     * one of any other shape, which leaves the type declarable with nothing to start as.
     */
    static String freshCallProblem(Method call, String typeName) {
        if (!Modifier.isPublic(call.getModifiers()) || !Modifier.isStatic(call.getModifiers())) {
            return "is not public static; the host writes Owner." + call.getName() + "() with no instance";
        }
        if (call.getParameterCount() != 0) {
            return "takes parameters; the host writes the call with no arguments";
        }
        if (!call.getReturnType().getName().equals(typeName)) {
            return "returns " + call.getReturnType().getName() + ", not " + typeName;
        }
        return null;
    }

    /**
     * Why the host could not write or read a value through {@code factory}, or {@code null} when it can.
     *
     * <p>A constructor must be {@code type}'s own. A method must return {@code type} or a supertype of it —
     * {@code Source.current()} returns the interface its value implements. An instance method is a chain on
     * part 0, so part 0 must be of the class that declares it, and the parameters are the parts after it. A
     * varargs parameter stands for the last declared part repeated.
     */
    static String factoryProblem(Executable factory, Class<?> type, List<Class<?>> parts) {
        if (!Modifier.isPublic(factory.getModifiers())) return "is not public; a bot calls it";
        List<Class<?>> arguments = parts;
        if (factory instanceof Method method && !Modifier.isStatic(method.getModifiers())) {
            if (parts.isEmpty() || !method.getDeclaringClass().isAssignableFrom(parts.getFirst())) {
                return "is an instance method of " + method.getDeclaringClass().getName()
                        + ", so part 0 is its receiver, and part 0 is "
                        + (parts.isEmpty() ? "missing" : "a " + parts.getFirst().getName());
            }
            arguments = parts.subList(1, parts.size());
        }
        List<Class<?>> declared = List.of(factory.getParameterTypes());
        if (!fits(declared, arguments, factory.isVarArgs())) {
            return "takes " + names(declared) + ", where componentTypes() declares " + names(arguments);
        }
        if (factory instanceof Constructor<?> constructor) {
            return constructor.getDeclaringClass() == type ? null
                    : "constructs a " + constructor.getDeclaringClass().getName() + ", not a " + type.getName();
        }
        Class<?> returned = ((Method) factory).getReturnType();
        return returned.isAssignableFrom(type) ? null
                : "returns " + returned.getName() + ", which a " + type.getName() + " is not";
    }

    private static boolean fits(List<Class<?>> declared, List<Class<?>> parts, boolean varargs) {
        if (declared.size() != parts.size()) return false;
        for (int i = 0; i < declared.size(); i++) {
            Class<?> want = declared.get(i);
            if (varargs && i == declared.size() - 1) want = want.getComponentType();
            if (want != parts.get(i)) return false;
        }
        return true;
    }

    private static String names(List<Class<?>> types) {
        return types.stream().map(Class::getName).toList().toString();
    }

    /**
     * What is wrong with one component type, or {@code null} when it took a fresh value apart and put it
     * back. An empty list means it answered and there was no value to try it on.
     */
    private static List<String> shapeProblems(String id, ComponentType<?> shape, Map<String, Object> fresh,
                                              List<Class<?>> known) {
        List<String> problems = new ArrayList<>();
        Class<?> cls;
        List<Class<?>> kinds;
        try {
            cls = shape.type();
            kinds = shape.componentTypes();
        } catch (RuntimeException | LinkageError e) {
            problems.add(id + ": a component type threw answering type() or componentTypes(): " + e);
            return problems;
        }
        if (cls == null || kinds == null) {
            problems.add(id + ": a component type answered null from "
                    + (cls == null ? "type()" : cls.getName() + ".componentTypes()"));
            return problems;
        }
        String name = cls.getName();
        Executable factory;
        try {
            factory = shape.factory();
        } catch (RuntimeException | LinkageError e) {
            problems.add(id + ": " + name + " factory() threw " + e);
            return problems;
        }
        if (factory == null) {
            problems.add(id + ": " + name + " factory() returned null; the host has nothing to write it with");
            return problems;
        }
        String why = factoryProblem(factory, cls, kinds);
        if (why != null) {
            problems.add(id + ": " + name + " factory() " + factory + " " + why);
            return problems;
        }
        for (String unwritable : known == null ? List.<String>of() : unwritableParts(factory, known)) {
            problems.add(id + ": " + name + " " + unwritable + ", so a host cannot write a " + cls.getSimpleName()
                    + " holding one: declare it, or write the part as a type a host writes");
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        Object value = fresh.get(name);
        if (value == null) {
            return problems;
        }
        try {
            List<Object> parts = shape.componentsOf(value);
            if (parts == null) {
                problems.add(id + ": " + name + " components(fresh()) returned null");
                return problems;
            }
            String partsWhy = partsProblem(factory, kinds, parts);
            if (partsWhy != null) {
                problems.add(id + ": " + name + " " + partsWhy);
                return problems;
            }
            Object rebuilt = shape.build(parts);
            if (rebuilt == null) {
                problems.add(id + ": " + name + " build(components(fresh())) returned null");
                return problems;
            }
            List<Object> again = shape.componentsOf(rebuilt);
            if (!parts.equals(again)) {
                problems.add(id + ": " + name + " does not survive build(components(…)): " + parts
                        + " came back as " + again + ". The host writes the parts and reads them back"
                        + " through build(), so a value that changes on the way is rewritten on every save");
            }
        } catch (RuntimeException | LinkageError e) {
            problems.add(id + ": " + name + " threw taking its fresh value apart or putting it back: " + e);
        }
        return problems.isEmpty() ? null : problems;
    }

    /**
     * Why {@code parts}, a fresh value taken apart, is not what {@code kinds} declares, or {@code null} when it
     * is: one part per declared kind, each an instance of it — except that a varargs factory's last kind
     * repeats, any number of times (none included), as the host's grammar reads the call.
     */
    static String partsProblem(Executable factory, List<Class<?>> kinds, List<Object> parts) {
        boolean varargs = factory.isVarArgs() && !kinds.isEmpty();
        boolean counts = varargs ? parts.size() >= kinds.size() - 1 : parts.size() == kinds.size();
        if (!counts) {
            return "components(fresh()) answered " + parts.size() + " part(s), where componentTypes() declares "
                    + (varargs ? "at least " + (kinds.size() - 1) : kinds.size());
        }
        for (int i = 0; i < parts.size(); i++) {
            Object part = parts.get(i);
            Class<?> kind = kinds.get(Math.min(i, kinds.size() - 1));
            if (part != null && !boxed(kind).isInstance(part)) {
                return "part " + i + " is a " + part.getClass().getName()
                        + ", where componentTypes() declares " + kind.getName();
            }
        }
        return null;
    }

    /**
     * The containers a host writes by itself — {@code List.of(…)}, {@code Map.ofEntries(…)} and the rest — as
     * Studio's grammar does (its {@code ValueContainer.ALL}); their type arguments are what must be writable.
     */
    private static final List<Class<?>> HOST_CONTAINERS = List.of(List.class, Map.class, Set.class,
            Deque.class, Map.Entry.class);

    /**
     * Every class some loaded plugin declares, as a type or as the parts of one — what a host writes a part
     * through. Every plugin, judged or not: a part may be a type a dependency declares.
     *
     * <p>{@code null} when some plugin's declarations could not be linked here (no JavaFX): what it declares is
     * then unknown, and a part it would have answered for must not fail as declared by nobody, so the walk is
     * not run. A declaration that throws otherwise adds nothing, and {@link #checkTypes} reports it.
     */
    private static List<Class<?>> declaredClasses(List<StudioPlugin> plugins) {
        List<Class<?>> known = new ArrayList<>();
        for (StudioPlugin plugin : plugins) {
            try {
                for (PluginType<?> type : nonNull(plugin.types())) if (type != null) known.add(type.type());
            } catch (LinkageError e) {
                return null;
            } catch (RuntimeException e) {
                // the types check reports it
            }
            try {
                for (ComponentType<?> shape : nonNull(plugin.componentTypes())) if (shape != null) known.add(shape.type());
            } catch (LinkageError e) {
                return null;
            } catch (RuntimeException e) {
                // the types check reports it
            }
        }
        known.removeIf(Objects::isNull);
        return List.copyOf(known);
    }

    /**
     * One sentence per part of {@code factory} a host could not write, naming the path to it — {@code part 1
     * (java.util.List<p.Step>) holds p.Step, which no loaded plugin declares}. Empty when every part is
     * writable (2026-10-05).
     *
     * <p>The fresh-value round trip above proves only the value a type starts as, and a fresh value's lists
     * are usually empty: a {@code Flow} with no activities round-trips while its {@code Activity} is declared by
     * nobody, and the first one the user draws is a value the host cannot write. So this walks the factory's
     * <em>declared</em> parameter types instead — a receiver, each parameter, a varargs element — through the
     * host's containers to every leaf, and asks of each leaf what the host's grammar asks: a JDK literal, an
     * enum, {@code Object}, or a class some loaded plugin declares (or one such class's supertype, written
     * through the declaration of the value's own class).
     */
    static List<String> unwritableParts(Executable factory, List<Class<?>> known) {
        List<Type> parts = new ArrayList<>();
        if (factory instanceof Method method && !Modifier.isStatic(method.getModifiers())) {
            parts.add(method.getDeclaringClass());
        }
        Type[] parameters = factory.getGenericParameterTypes();
        if (parameters.length != factory.getParameterCount()) {
            // An inner class's constructor answers its generic types without the outer instance; read the raw ones.
            parameters = factory.getParameterTypes();
        }
        for (int i = 0; i < parameters.length; i++) {
            Type part = parameters[i];
            if (factory.isVarArgs() && i == parameters.length - 1) part = elementOf(part);
            parts.add(part);
        }
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String leaf = unwritable(parts.get(i), known, new HashSet<>());
            if (leaf != null) {
                problems.add("part " + i + " (" + parts.get(i).getTypeName() + ") " + leaf);
            }
        }
        return problems;
    }

    /** Why a host could not write a value of {@code type}, or {@code null} when it can. */
    private static String unwritable(Type type, List<Class<?>> known,
                                     Set<Type> seen) {
        if (!seen.add(type)) return null;
        return switch (type) {
            case Class<?> cls when cls.isArray() -> "is an array, which no host writes outside a varargs tail";
            case Class<?> cls -> writableClass(cls, known) ? null
                    : "holds " + cls.getName() + ", which no loaded plugin declares";
            case ParameterizedType generic -> {
                Class<?> raw = (Class<?>) generic.getRawType();
                boolean container = HOST_CONTAINERS.contains(raw);
                if (!container && !writableClass(raw, known)) {
                    yield "holds " + raw.getName() + ", which no loaded plugin declares";
                }
                // A method reference writes a functional interface whole: its type arguments are never values.
                if (!container && functional(raw) && known.stream().noneMatch(raw::isAssignableFrom)) yield null;
                for (Type argument : generic.getActualTypeArguments()) {
                    String why = unwritable(argument, known, seen);
                    if (why != null) yield why;
                }
                yield null;
            }
            case WildcardType wildcard -> unwritable(wildcard.getUpperBounds()[0], known, seen);
            case TypeVariable<?> variable -> unwritable(variable.getBounds()[0], known, seen);
            case GenericArrayType ignored ->
                    "is an array, which no host writes outside a varargs tail";
            default -> "is a " + type.getTypeName() + ", which no host writes";
        };
    }

    private static boolean writableClass(Class<?> cls, List<Class<?>> known) {
        Class<?> boxed = boxed(cls);
        if (cls == Object.class || cls.isEnum() || cls == String.class || boxed == Boolean.class
                || boxed == Character.class || Number.class.isAssignableFrom(boxed) && boxed.getName().startsWith("java.lang.")
                || HOST_CONTAINERS.contains(cls)) {
            return true;
        }
        for (Class<?> declared : known) if (cls.isAssignableFrom(declared)) return true;
        return functional(cls);
    }

    /**
     * Whether {@code cls} is a functional interface, whose only Java is a method reference — the SDK's
     * {@code ActivityBody}, written {@code Collect::body}. The host writes such a part through its source-leaf
     * path, from the text the declaration's {@code components} hands over (`two-strings.md`), so no plugin
     * declares it and none need to.
     */
    private static boolean functional(Class<?> cls) {
        if (!cls.isInterface() || cls.isAnnotation()) return false;
        int abstracts = 0;
        for (Method method : cls.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || objectMethod(method)) continue;
            abstracts++;
        }
        return abstracts == 1;
    }

    /** Whether {@code method} restates one of {@code Object}'s public methods, which no lambda implements. */
    private static boolean objectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** A varargs parameter's element type. */
    private static Type elementOf(Type array) {
        if (array instanceof Class<?> cls && cls.isArray()) return cls.getComponentType();
        if (array instanceof GenericArrayType generic) return generic.getGenericComponentType();
        return array;
    }

    /** {@code int.class} as {@code Integer.class}: a value handed around as an {@code Object} is boxed. */
    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }

    private static boolean javafx() {
        try {
            Class.forName("javafx.scene.Node");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // 5b — managed values
    // -------------------------------------------------------------------------------------------------

    /**
     * Every value a judged plugin declares in {@code managedValues()} is one a host can write — the only method
     * it ever writes being {@code public static T id() { return <expression>; }} (2026-09-27).
     *
     * <p>No host grammar runs here, so "can write" is asked the way the host would find out: the value type is
     * a class some loaded plugin declares (a type, or the parts of one) or one the host writes itself (a JDK
     * literal, an enum), and the first value — {@code initial}, or the declared type's {@code fresh()} — is
     * taken apart by its declaration and put back unchanged. A type declared only by its parts has no fresh
     * value, so it owes an {@code initial}. An open set (the plugin's marker on a class) is written as an empty
     * class, and asks for its holder and a host-writable element type.
     */
    private static CheckResult checkManaged(List<StudioPlugin> plugins, PluginSubject subject) {
        Map<String, ComponentType<?>> shapes = new HashMap<>();
        Map<String, PluginType<?>> types = new HashMap<>();
        for (StudioPlugin plugin : plugins) {
            try {
                for (PluginType<?> type : nonNull(plugin.types())) types.putIfAbsent(type.type().getName(), type);
                for (ComponentType<?> shape : nonNull(plugin.componentTypes())) {
                    shapes.putIfAbsent(shape.type().getName(), shape);
                }
            } catch (LinkageError e) {
                if (!javafx()) {
                    return CheckResult.skip(Check.MANAGED, "no JavaFX on this classpath, so the declared types"
                            + " could not be linked to check the values against");
                }
            } catch (RuntimeException ignored) {
                // Reported by the types check; a managed value is judged against whatever did load.
            }
        }
        List<String> problems = new ArrayList<>();
        int sound = 0;
        for (StudioPlugin plugin : plugins) {
            String id = safeId(plugin);
            if (!subject.judges(id)) continue;
            List<ManagedValue<?>> values;
            try {
                values = plugin.managedValues();
            } catch (RuntimeException | LinkageError e) {
                problems.add(id + "#managedValues() threw " + e);
                continue;
            }
            if (values == null) {
                problems.add(id + "#managedValues() returned null; return List.of()");
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (ManagedValue<?> value : values) {
                List<String> found = managedProblems(id, value, seen, types, shapes);
                if (found.isEmpty()) sound++;
                problems.addAll(found);
            }
        }
        if (!problems.isEmpty()) return CheckResult.fail(Check.MANAGED, problems);
        return CheckResult.pass(Check.MANAGED, sound == 0 ? "declares no managed values"
                : sound + " managed value(s), each a method or a set of constants a host can write");
    }

    private static List<String> managedProblems(String plugin, ManagedValue<?> value,
                                                Set<String> seen, Map<String, PluginType<?>> types,
                                                Map<String, ComponentType<?>> shapes) {
        if (value == null) return List.of(plugin + ": managedValues() holds a null");
        String id = value.id();
        String at = plugin + ": managed value \"" + id + "\"";
        List<String> problems = new ArrayList<>();
        // The id is an enum constant the contract checked when it was declared, so it has no spelling to judge.
        if (!seen.add(id)) {
            problems.add(at + " is declared twice; the bot's annotation can only mean one of them");
        }
        if (value.reason() == null || value.reason().isBlank()) {
            problems.add(at + " has no reason; it is the sentence the canvas shows when it refuses an edit there");
        }
        String holder = value.holder();
        if (holder != null && !javaIdentifier(holder)) {
            problems.add(at + " names holder \"" + holder + "\", which is not a simple class name");
        }
        if (value.isOpenSet()) {
            // Each constant's initialiser is written as a value of the element type, so a host must write one.
            Class<?> element = value.type();
            List<Class<?>> known = new ArrayList<>();
            types.values().forEach(type -> known.add(type.type()));
            shapes.values().forEach(shape -> known.add(shape.type()));
            if (element.isArray() || !writableClass(element, known)) {
                problems.add(at + " holds constants of " + element.getName() + ", which no loaded plugin"
                        + " declares, so no host can write one");
            }
            return problems;
        }
        if (holder == null) {
            // Opened, never created: nothing here is written by a host, so there is nothing more to prove.
            return problems;
        }
        Class<?> raw = value.type();
        boolean hostWrites = raw.isPrimitive() || raw.isEnum() || raw == String.class
                || Number.class.isAssignableFrom(boxed(raw)) || boxed(raw) == Boolean.class;
        PluginType<?> declared = types.get(raw.getName());
        ComponentType<?> shape = shapes.get(raw.getName());
        if (!hostWrites && declared == null && shape == null) {
            problems.add(at + " returns " + raw.getName() + ", which no loaded plugin declares, so no host can"
                    + " write the `public static " + raw.getSimpleName() + "` method holding it");
            return problems;
        }
        Object first = value.initial();
        if (first == null && declared != null) {
            try {
                first = declared.fresh();
            } catch (RuntimeException | LinkageError e) {
                problems.add(at + ": " + raw.getName() + " fresh() threw " + e);
                return problems;
            }
        }
        if (first == null) {
            if (!hostWrites) {
                problems.add(at + " has no initial value, and " + raw.getName() + " has no fresh() to start"
                        + " from; give the ManagedValue an initial");
            }
            return problems;
        }
        if (!boxed(raw).isInstance(first)) {
            problems.add(at + "'s first value is a " + first.getClass().getName() + ", not a " + raw.getName());
            return problems;
        }
        if (shape != null) {
            Object back;
            boolean same;
            try {
                back = rebuild(shape, first);
                // By parts as well as by equals, as the types check compares: most classes a plugin writes
                // have no equals, and such a value is still one the host writes back unchanged.
                same = first.equals(back) || (back != null && shape.componentsOf(first).equals(shape.componentsOf(back)));
            } catch (RuntimeException | LinkageError e) {
                problems.add(at + ": taking its first value apart and back threw " + e);
                return problems;
            }
            if (!same) {
                problems.add(at + ": " + shape.type().getSimpleName() + " builds " + back + " from the parts of "
                        + first + ", so the method a host writes would not return the value declared");
            }
        }
        return problems;
    }

    @SuppressWarnings("unchecked")
    private static <T> Object rebuild(ComponentType<T> shape, Object value) {
        return shape.build(shape.components((T) value));
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean javaIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
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
            editor.matches(StubContexts.slot(String.class, 0, "\"x\""));
        } catch (RuntimeException | LinkageError e) {
            problems.add(safeId(plugin) + ": slotEditors()[" + index + "].matches threw on a slot: " + e);
        }
        try {
            editor.matches(StubContexts.row(String.class, "x"));
        } catch (RuntimeException | LinkageError e) {
            problems.add(safeId(plugin) + ": slotEditors()[" + index + "].matches threw on a Parameters row"
                    + " (a row has no call behind it, so it has no enclosingExecutable()/argIndex()): " + e);
        }
        return problems;
    }

    // -------------------------------------------------------------------------------------------------
    // 7 — pickers
    // -------------------------------------------------------------------------------------------------

    /**
     * Every type a judged plugin declares has a picker: its own ({@code EditableType}), or another plugin's
     * {@code SlotEditor} that claims a row of that type — an alternative the user settles with <i>Edit
     * with</i>. Asked with {@code matches} alone, so no JavaFX toolkit is started; a predicate that throws is not
     * a picker, and {@link Check#EDITORS} reports the throw.
     */
    private static CheckResult checkPickers(List<StudioPlugin> plugins, PluginSubject subject) {
        if (!javafx()) {
            return CheckResult.skip(Check.PICKERS, "no JavaFX on this classpath, so neither a declared type nor a"
                    + " slot editor can be linked. Put javafx-controls on the classpath to check that every type"
                    + " has a picker");
        }
        Map<SlotEditor, String> drawers = new LinkedHashMap<>();
        for (StudioPlugin plugin : plugins) {
            try {
                List<SlotEditor> offered = plugin.slotEditors();
                if (offered == null) continue;
                for (SlotEditor editor : offered) if (editor != null) drawers.put(editor, safeId(plugin));
            } catch (RuntimeException | LinkageError e) {
                // EDITORS reports it; an editor list that cannot be built draws nothing here.
            }
        }
        List<String> problems = new ArrayList<>();
        List<String> borrowed = new ArrayList<>();
        int own = 0;
        for (StudioPlugin plugin : plugins) {
            String id = safeId(plugin);
            if (!subject.judges(id)) continue;
            List<PluginType<?>> types;
            try {
                types = plugin.types();
            } catch (RuntimeException | LinkageError e) {
                continue;   // TYPES reports it
            }
            if (types == null) continue;
            for (PluginType<?> type : types) {
                if (type == null) continue;
                if (type instanceof EditableType<?>) {
                    own++;
                    continue;
                }
                Class<?> cls;
                try {
                    cls = type.type();
                } catch (RuntimeException | LinkageError e) {
                    continue;   // TYPES reports it
                }
                if (cls == null) continue;
                String drawer = drawerOf(cls, drawers);
                if (drawer != null) {
                    borrowed.add(cls.getSimpleName() + " (drawn by " + drawer + ")");
                } else {
                    problems.add(id + ": " + cls.getName()
                            + " has no picker: implement EditableType, or depend on a plugin that draws it");
                }
            }
        }
        if (!problems.isEmpty()) return CheckResult.fail(Check.PICKERS, problems);
        return CheckResult.pass(Check.PICKERS, own + " drawn by their owner"
                + (borrowed.isEmpty() ? "" : "; " + String.join(", ", borrowed)));
    }

    /** The id of the first plugin whose slot editor claims a row of {@code cls}, or {@code null}. */
    private static String drawerOf(Class<?> cls, Map<SlotEditor, String> drawers) {
        for (Map.Entry<SlotEditor, String> each : drawers.entrySet()) {
            try {
                if (each.getKey().matches(StubContexts.row(cls, ""))) return each.getValue();
            } catch (RuntimeException | LinkageError e) {
                // not a picker; EDITORS reports the throw
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------------
    // 8 — pom scopes
    // -------------------------------------------------------------------------------------------------

    /**
     * The contract is on the compile classpath and the toolkit is not {@code provided}.
     *
     * <p><b>A {@code compile} contract is accepted since 2026-09-23.</b> It was refused because a second
     * copy of the boundary types would be two {@code Class} objects with one name — but
     * {@code PluginLoader} is parent-first for {@code com.botmaker.plugin.api.**}, so the copy on a plugin's
     * classpath is never the one loaded, and the refusal guarded against something the loader already makes
     * impossible. It started to matter when {@code @Param} and {@code @Managed} moved into the contract: a
     * bot writes them on its own fields, so a plugin whose jar a bot compiles against — the SDK — has to
     * hand the contract on, and {@code provided} is not transitive. Every other scope still fails, since
     * nothing but those two puts the contract where a plugin's source can name it.
     *
     * <p>A {@code provided}-scoped toolkit is simply absent at load time, because the host does not have one
     * to provide: {@code botmaker-studio} must never depend on the toolkit, or two plugins could not hold two
     * versions of it.
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
        if (contract == null && Poms.find(declared, RETIRED_CONTRACT_GROUP, CONTRACT_ARTIFACT).isPresent()) {
            problems.add("the contract is declared as " + RETIRED_CONTRACT_GROUP + ":" + CONTRACT_ARTIFACT
                    + ", its groupId before 2026-10-05; declare " + CONTRACT_GROUP + ":" + CONTRACT_ARTIFACT
                    + " instead (and the toolkit likewise). Maven treats the two as different artifacts, so a"
                    + " host would load two contracts");
        } else if (contract == null) {
            problems.add("no dependency on " + CONTRACT_GROUP + ":" + CONTRACT_ARTIFACT
                    + "; a plugin implements the contract, so it must declare it");
        } else if (!"provided".equals(scope(contract)) && !"compile".equals(scope(contract))) {
            problems.add(CONTRACT_ARTIFACT + " is declared at scope '" + scope(contract)
                    + "'; it must be `provided` or `compile`, the two scopes a plugin's own source compiles"
                    + " against. `provided` for a plugin nothing else builds on, `compile` for one a bot"
                    + " compiles against, since a bot writes the contract's @Param and the plugin's managed"
                    + " marker");
        }
        Poms.Dependency toolkit = Poms.find(declared, CONTRACT_GROUP, TOOLKIT_ARTIFACT).orElse(null);
        if (toolkit != null && "provided".equals(toolkit.scope())) {
            problems.add(TOOLKIT_ARTIFACT + " is declared `provided`; it must not be. The toolkit is resolved"
                    + " onto the PLUGIN's classloader — the host does not have one to provide, because"
                    + " botmaker-studio must never depend on it");
        }
        return problems.isEmpty()
                ? CheckResult.pass(Check.POM_SCOPES, "contract " + scope(contract)
                        + (toolkit == null ? ", no toolkit" : ", toolkit " + scope(toolkit)))
                : CheckResult.fail(Check.POM_SCOPES, problems);
    }

    /** A dependency's scope as Maven reads it: an absent {@code <scope>} is {@code compile}. */
    private static String scope(Poms.Dependency dependency) {
        return dependency.scope().isEmpty() ? "compile" : dependency.scope();
    }

    // -------------------------------------------------------------------------------------------------
    // 9 — plugin deps
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
