package com.botmaker.cli;

import com.botmaker.cli.project.Poms;
import com.botmaker.cli.project.Tags;
import com.botmaker.cli.registry.Registry;
import com.botmaker.cli.registry.RegistryEntry;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.cli.validate.PluginSubject;
import com.botmaker.cli.validate.PluginValidator;
import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.host.PluginLoader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code botmaker plugin publish} — validate, then compose the registry entry and open the pull request.
 *
 * <p><b>The author never hand-edits anything in the registry.</b> Almost every field in an entry is something the
 * plugin already says about itself — its id, its display name, the value type ids it registers, the contract
 * version its pom declares — and a human retyping those is a human introducing a typo into the one file that
 * is the registry's primary key. What is genuinely the author's, and only that, is asked for: the
 * description, the tags and the repository.
 *
 * <p>Validation runs first and a failure stops it. That is the whole point of {@code validate} being a
 * library rather than a command: the check that refuses the pull request is the check that refused to open
 * it.
 *
 * <p><b>Nothing is published that nobody has followed.</b> An entry is a set of pointers — a coordinate at a
 * version, and a repository — and every one of them is checked here before the pull request is opened, for
 * the same reason validation runs here at all: a submission that fails in the registry's CI for a reason its
 * author could not have seen coming is the experience this command exists to prevent. So the version is a
 * <b>git tag</b> rather than the pom's {@code <version>} (see {@link #publishedVersion}), a
 * {@code -SNAPSHOT} is refused by name, the coordinate is resolved before the pull request is opened, and
 * {@code --repo} is confirmed to exist.
 *
 * <p><b>One file, {@code plugins/&lt;plugin-id&gt;.json}, and never the index.</b> The index is generated
 * from those files by the registry's own CI. That is what makes two authors publishing on the same day two
 * pull requests with no line in common — an entry appended to a shared array conflicts with every other
 * submission, and is a read-modify-write race against a base SHA somebody else may have moved.
 */
@Command(name = "publish",
        header = "Validate, then open the registry pull request.",
        description = "Almost every field of an entry is something the plugin already says about itself; "
                + "what is genuinely yours — the repository, the description, the tags — is asked for here.",
        mixinStandardHelpOptions = true)
final class PluginPublishCommand implements Callable<Integer> {

    private static final String REGISTRY_REPO = "BotMakerDev/botmaker-plugin-registry";

    @ParentCommand
    private PluginCommand parent;

    @Option(names = "--dir", defaultValue = ".",
            description = "The plugin project. Default: ${DEFAULT-VALUE}")
    private String directory;

    @Option(names = "--repo", paramLabel = "<owner/name>",
            description = "Where the plugin's source lives. Required for a real run; without it this is a "
                    + "dry run, because an entry needs somewhere for a reader to go and look.")
    private String repo;

    @Option(names = "--name", description = "Overrides the plugin's own displayName.")
    private String displayName;

    @Option(names = "--description", defaultValue = "", description = "One sentence, for the index.")
    private String description;

    @Option(names = "--tags", defaultValue = "", paramLabel = "<a,b,c>", description = "Comma separated.")
    private String tags;

    /**
     * <b>{@code --tag}, not {@code --version}, and the name is forced rather than chosen.</b>
     * {@code mixinStandardHelpOptions} already declares {@code -V, --version} on every command here, so a
     * second one would be a duplicate picocli refuses at construction. It reads better anyway: what JitPack
     * serves an artifact under is a git tag.
     */
    @Option(names = "--tag", paramLabel = "<tag>",
            description = "The published version the registry's gate will resolve. Default: the newest git "
                    + "tag on this working copy, or the pom's own <version> when there is not one.")
    private String tag;

    @Option(names = "--min-contract-version", paramLabel = "<version>",
            description = "Default: the botmaker-studio-api version this pom declares, with its own "
                    + "<properties> substituted in. Required when that is a property this pom does not "
                    + "define, or a -SNAPSHOT.")
    private String minContractVersion;

    @Option(names = "--dry-run", description = "Print the index entry and open nothing.")
    private boolean dryRun;

    @Option(names = "--no-build", description = "Trust the existing target/classes.")
    private boolean noBuild;

    @Override
    public Integer call() throws IOException {
        Console console = parent.main().console();
        Path dir = Path.of(directory).toAbsolutePath().normalize();
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IOException(
                    "no pom.xml in " + dir + " — point `botmaker plugin publish` at a plugin project");
        }

        // Before the compile, because both of these are refusals and a refusal the author waits a minute for
        // is a refusal that reads as a fault in the tool.
        String published = publishedVersion(dir, pom);
        String snapshot = snapshotRefusal(published);
        if (snapshot != null) {
            console.error(snapshot);
            return 1;
        }
        String contractVersion = minContractVersion != null && !minContractVersion.isBlank()
                ? minContractVersion.trim()
                : declaredContractVersion(pom);
        String contract = contractVersionRefusal(contractVersion);
        if (contract != null) {
            console.error(contract);
            return 1;
        }
        if (repo != null && !dryRun && !repositoryExists(repo)) {
            console.error("no repository " + repo + " that `gh` can see. An entry's repo is the one field"
                    + " whose whole job is to give a reader somewhere to go.");
            return 1;
        }

        PluginSubject subject = parent.main().subjects().fromDirectory(dir, !noBuild);

        // Aside, on stderr: this command's stdout is the entry, and `--dry-run > entry.json` has to be a
        // file a parser will read.
        List<CheckResult> results = PluginValidator.validate(subject);
        console.reportAside(results);
        if (!PluginValidator.passed(results)) {
            console.error("not publishing: the plugin does not pass its own checks, and the registry runs"
                    + " these same checks on the pull request");
            return 1;
        }

        RegistryEntry entry = compose(subject, published, contractVersion);
        String json = Registry.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(entry);

        if (dryRun || repo == null) {
            if (repo == null) {
                console.warn("--repo <owner/name> was not given, so this is a dry run: the registry entry"
                        + " needs somewhere for a reader to go and look at the source.");
            }
            // Nothing but the entry on stdout: `botmaker plugin publish --dry-run > plugins/<id>.json` is the
            // by-hand path when `gh` is unavailable, and a blank line makes that file not-JSON.
            console.out(json);
            return 0;
        }
        if (!resolves(entry)) {
            return 1;
        }
        return openPullRequest(entry, json);
    }

    /**
     * The version the entry publishes, which is the version the registry's gate resolves from JitPack.
     *
     * <p><b>The newest git tag, not the pom's {@code <version>}, and that is a correction rather than a
     * preference.</b> JitPack builds a tag on demand and serves the result under that tag whatever the pom
     * says — this project's own poms carry a cosmetic {@code 0.0.0-SNAPSHOT} for exactly that reason — so a
     * pom version resolves only where it happens to equal the tag. {@code --tag} overrides both, for the
     * author whose newest tag is not what they publish under.
     */
    private String publishedVersion(Path dir, Path pom) throws IOException {
        if (tag != null && !tag.isBlank()) {
            return tag.trim();
        }
        Optional<String> tag = Tags.newest(dir);
        if (tag.isPresent()) {
            return tag.get();
        }
        return Poms.coordinate(pom).version();
    }

    /**
     * A snapshot is refused here rather than left to the gate, because the point is that the author sees it.
     *
     * <p>{@code botmaker plugin new} generates {@code 0.1.0-SNAPSHOT}, so publishing straight after generating is
     * the very first thing a new author does and produced an entry whose gate failed in the <em>registry's</em>
     * CI. Returns the refusal, or {@code null} when there is nothing to refuse.
     */
    static String snapshotRefusal(String published) {
        if (published == null || published.isBlank()) {
            return "no version to publish: this working copy has no git tag and its pom declares no"
                    + " <version>. Tag a release, or pass --tag <tag>.";
        }
        if (published.endsWith("-SNAPSHOT")) {
            return "the version to publish is " + published + ", and JitPack cannot resolve a snapshot:"
                    + " the registry's gate would download nothing. Tag a release (`git tag v0.1.0 && git"
                    + " push --tags`) and re-run, or pass --tag <tag>.";
        }
        return null;
    }

    /**
     * {@code gh repo view}, which is the cheapest possible statement of "this pointer points at something".
     *
     * <p>Only on a real run: {@code --dry-run} is the by-hand path taken when {@code gh} is unavailable, and
     * a check that needs {@code gh} cannot be the thing that stops it.
     */
    private boolean repositoryExists(String ownerAndName) throws IOException {
        Path here = Path.of(".").toAbsolutePath().normalize();
        return gh(here, "repo", "view", ownerAndName, "--json", "name") == 0;
    }

    /**
     * The gate's own first step, run locally.
     *
     * <p>{@link Subjects#fromCoordinate} is literally what {@code RegistryGate} calls, so a coordinate that
     * will not resolve here is a pull request that cannot pass — and finding that out costs seconds where
     * finding it out in CI costs a round trip. Not the full validation again: the checks have already run
     * against this working copy, and what is being asked is only whether the world can download what the
     * entry names.
     */
    private boolean resolves(RegistryEntry entry) {
        Console console = parent.main().console();
        String coordinate = entry.coordinate() + ":" + entry.verifiedVersion();
        try {
            parent.main().subjects().fromCoordinate(coordinate, Set.of());
            return true;
        } catch (IOException e) {
            console.error("nobody can download " + coordinate + " yet, so the registry's gate could not"
                    + " check it either:\n" + e.getMessage()
                    + "\nPush the tag and let JitPack build it, then re-run.");
            return false;
        }
    }

    /**
     * The {@code botmaker-studio-api} version this pom declares, with its {@code <properties>} substituted
     * in.
     *
     * <p>The substitution is not tidiness. Every module in this project pins its BotMaker upstreams through
     * a property so that {@code jitpack.yml} can inject the released tag at build time, so the pom of a
     * plugin written the way this project writes one says {@code ${botmaker.studioapi.version}} — and that
     * string, copied verbatim into a registry entry, is a compatibility floor no reader can compare
     * anything against. {@link Poms#dependencies} deliberately keeps saying what the pom says, because a
     * scope check must; this asks the other question, for the one field that leaves the machine.
     */
    private String declaredContractVersion(Path pom) throws IOException {
        String declared = Poms.find(Poms.dependencies(pom),
                        PluginValidator.CONTRACT_GROUP, PluginValidator.CONTRACT_ARTIFACT)
                .map(Poms.Dependency::version)
                .orElse("");
        return Poms.interpolate(declared, Poms.properties(pom)).trim();
    }

    /**
     * Refuses a contract version a stranger cannot act on. Returns the refusal, or {@code null}.
     *
     * <p>Three ways it is unusable, and the third is the one this project hits every time. An unresolved
     * {@code ${…}} means the property lives somewhere this reader cannot see — a parent pom, the settings —
     * and would be published literally. A blank means the plugin declares no contract at all, which
     * {@code pom-scopes} refuses anyway. And a {@code -SNAPSHOT} is what every BotMaker module's own
     * committed pom carries, because JitPack overrides it with the tag: it is the cosmetic version, never
     * the one the plugin was built against. In all three the author knows the answer and the tool does not,
     * so it asks rather than guessing — {@code --min-contract-version} is the flag, and for a module of this
     * project the value is the {@code STUDIO_API_TAG} in its own {@code .deps.env}.
     */
    static String contractVersionRefusal(String contractVersion) {
        if (contractVersion == null || contractVersion.isBlank()) {
            return "this pom declares no com.github.LiQiyeDev:botmaker-studio-api dependency, so there is"
                    + " no contract version to publish. Declare it at `provided` scope, or pass"
                    + " --min-contract-version <version>.";
        }
        if (contractVersion.contains("${")) {
            return "the contract version reads " + contractVersion + ", a property this pom does not"
                    + " define — a reader of the registry cannot compare anything against it. Pass"
                    + " --min-contract-version <version> with the botmaker-studio-api version this plugin"
                    + " was actually built against.";
        }
        if (contractVersion.endsWith("-SNAPSHOT")) {
            return "the contract version reads " + contractVersion + ", which is a snapshot: JitPack"
                    + " overrides it with the tag, so it is not a version anyone can resolve. Pass"
                    + " --min-contract-version <version> — for a module of this project that is the"
                    + " STUDIO_API_TAG in its own .deps.env.";
        }
        return null;
    }

    /**
     * The entry's {@code editorDependencies}: every {@code optional} dependency this pom declares, minus the
     * ones a host must not be asked for.
     *
     * <p><b>Read rather than asked for, because the pom already says it.</b> {@code optional} means <i>not
     * transitive</i>, so these are exactly the dependencies a host adding this plugin's coordinate to a
     * project does <em>not</em> get — the list is the definition of what has to be written down, and
     * anything else in the pom needs no entry because resolving the plugin already brings it.
     *
     * <p>Two exclusions, both platform rules rather than preferences. <b>{@code org.openjfx}</b> is
     * parent-first in {@code PluginLoader}, so the host's own JavaFX is what every plugin links whatever a
     * project's pom says; asking a bot to resolve a JavaFX distribution for its platform is a resolve that
     * fails on a headless machine for a jar nothing would have loaded from. <b>The contract and the
     * toolkit</b> are refused because a bot's pom must never declare either — the host has one copy of the
     * contract by construction, and the toolkit is the plugin's own dependency at {@code compile} scope,
     * which {@code pom-scopes} and {@code plugin-deps} already enforce from the plugin's side.
     *
     * <p>A version that is a property this pom does not resolve is skipped with a warning rather than
     * written: the registry's readers can do nothing with {@code ${javalin.version}}, and a silently
     * malformed line is worse than an absent one. Same judgement as
     * {@link #contractVersionRefusal(String)}, one severity down — a contract version nobody can compare is
     * the whole entry's problem, and one unusable companion is one plugin feature's.
     */
    static List<String> editorDependencies(Console console, List<Poms.Dependency> declared) {
        List<String> companions = new ArrayList<>();
        for (Poms.Dependency dependency : declared) {
            if (!dependency.optional()) continue;
            if ("org.openjfx".equals(dependency.groupId())) continue;
            if (PluginValidator.CONTRACT_GROUP.equals(dependency.groupId())
                    && (PluginValidator.CONTRACT_ARTIFACT.equals(dependency.artifactId())
                    || PluginValidator.TOOLKIT_ARTIFACT.equals(dependency.artifactId()))) {
                continue;
            }
            String version = dependency.version();
            if (version == null || version.isBlank() || version.contains("${")) {
                console.warn(dependency.coordinate() + " is optional but its version reads "
                        + (version == null || version.isBlank() ? "nothing" : version)
                        + ", which nobody can resolve — it is left out of editorDependencies, so a project"
                        + " installing this plugin will not get it.");
                continue;
            }
            companions.add(dependency.coordinate() + ":" + version);
        }
        return companions;
    }

    private RegistryEntry compose(PluginSubject subject, String published, String contractVersion)
            throws IOException {
        Console console = parent.main().console();
        String id = "";
        String name = "";
        try (PluginLoader loaded =
                     PluginLoader.open(subject.classpath().stream().map(Object::toString).toList())) {
            // The validator has already refused an empty load, so there is at least one plugin here. The
            // FIRST is the entry's subject: a jar contributing two is legal and rare, and picking silently
            // would be worse than the warning.
            List<StudioPlugin> plugins = loaded == null ? List.of() : loaded.plugins();
            if (plugins.size() > 1) {
                console.warn("this jar contributes " + plugins.size() + " plugins; the entry describes"
                        + " the first, " + plugins.getFirst().id());
            }
            StudioPlugin plugin = plugins.getFirst();
            id = plugin.id();
            name = plugin.displayName();
        }

        Poms.Dependency self = Poms.coordinate(subject.pom());

        return new RegistryEntry(
                id,
                displayName != null ? displayName : name,
                self.groupId() + ":" + self.artifactId(),
                repo,
                description,
                List.of(tags.split("\\s*,\\s*")).stream().filter(t -> !t.isBlank()).toList(),
                contractVersion,
                editorDependencies(console, Poms.dependencies(subject.pom())),
                // The tag the world can download, which is the version the registry's gate will resolve and
                // run the same checks against. A verifiedAt with no version beside it is a date attached to
                // no artifact — and the pom's own <version>, which stood here until 2026-09-04, is a date
                // attached to an artifact only JitPack's tag happens to agree with.
                published,
                LocalDate.now().toString());
    }

    /**
     * Forks, branches, writes the entry file and opens the PR — all through {@code gh}.
     *
     * <p>{@code gh} rather than the REST API directly, because it already holds the user's credential and
     * because a tool that asked a plugin author for a GitHub token would be asking for the one thing this
     * program has no business holding.
     */
    private int openPullRequest(RegistryEntry entry, String json) throws IOException {
        Console console = parent.main().console();
        Path work = Files.createTempDirectory("botmaker-publish");
        Path clone = work.resolve("registry");
        // A fork only when a fork is needed: GitHub does not fork a repository into the account that owns
        // it, so the registry's own maintainer would be refused here. And no --remote flag — `gh repo fork`
        // rejects it outright when it is given a repository argument, which is how `bot publish` failed on
        // its first real run.
        boolean push = Shell.capture(work, "gh", "api", "repos/" + REGISTRY_REPO, "--jq",
                ".permissions.push").orElse("").trim().equals("true");
        int fetched = push
                ? gh(work, "repo", "clone", REGISTRY_REPO, "botmaker-plugin-registry")
                : gh(work, "repo", "fork", REGISTRY_REPO, "--clone=true",
                        "--fork-name=botmaker-plugin-registry");
        if (fetched != 0) {
            console.error("could not " + (push ? "clone " : "fork ") + REGISTRY_REPO
                    + ". Is `gh` installed and authenticated?");
            return 1;
        }
        // `gh repo fork --clone` clones into the current directory under the repository's own name.
        Path cloned = work.resolve("botmaker-plugin-registry");
        if (Files.isDirectory(cloned)) {
            clone = cloned;
        }
        Path entries = clone.resolve(Registry.ENTRIES_DIRECTORY);
        if (!Files.isDirectory(entries)) {
            console.error("no " + Registry.ENTRIES_DIRECTORY + "/ in " + REGISTRY_REPO + " — the registry is"
                    + " not published yet. Re-run with --dry-run and open the pull request by hand.");
            return 1;
        }
        String branch = "add-" + entry.id().replaceAll("[^a-zA-Z0-9]+", "-");
        run(clone, "git", "checkout", "-b", branch);
        // The filename is the id, so an update rewrites this author's own file and a new plugin adds one:
        // either way the diff touches nothing anybody else's submission touches.
        String file = Registry.ENTRIES_DIRECTORY + "/" + entry.id() + ".json";
        Files.writeString(entries.resolve(entry.id() + ".json"), json + "\n");
        run(clone, "git", "add", file);
        run(clone, "git", "commit", "-m", "registry: add " + entry.id());
        if (run(clone, "git", "push", "-u", "origin", branch) != 0) {
            console.error("could not push the branch");
            return 1;
        }
        int opened = gh(clone, "pr", "create", "--repo", REGISTRY_REPO, "--title",
                "Add " + entry.name() + " (" + entry.id() + ")", "--body",
                "Adds `" + Registry.ENTRIES_DIRECTORY + "/" + entry.id() + ".json` — `"
                        + entry.coordinate() + ":" + entry.verifiedVersion() + "`.\n\n"
                        + "Composed by `botmaker plugin publish`, which runs the same checks this repository's CI"
                        + " runs.\n\n```json\n" + json + "\n```");
        return opened == 0 ? 0 : 1;
    }

    private int gh(Path dir, String... args) throws IOException {
        return Shell.gh(parent.main().console(), dir, args);
    }

    private int run(Path dir, String... argv) throws IOException {
        return Shell.run(parent.main().console(), dir, argv);
    }
}
