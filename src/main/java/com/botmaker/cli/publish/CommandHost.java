package com.botmaker.cli.publish;

import com.botmaker.cli.BotExport;
import com.botmaker.cli.bmk.Central;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PublishHost#commands}: {@code git} in the folder and {@code gh} as the account it is signed in to, each
 * through the {@link StepRunner}, so a test answers in their place.
 */
final class CommandHost implements PublishHost {

    private final Path dir;
    private final StepRunner runner;
    private final Listener listener;
    private final BotExport.Jars jars;

    CommandHost(Path dir, StepRunner runner, Listener listener, BotExport.Jars jars) {
        this.dir = dir;
        this.runner = runner;
        this.listener = listener;
        this.jars = jars;
    }

    @Override
    public Optional<Account> account() {
        Optional<String> login = runner.read(Path.of("."), List.of("gh", "api", "user", "--jq", ".login"))
                .filter(s -> !s.isBlank());
        if (login.isEmpty()) return Optional.empty();
        long id = runner.read(Path.of("."), List.of("gh", "api", "user", "--jq", ".id")).map(s -> {
            try {
                return Long.parseLong(s.strip());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }).orElse(-1L);
        return Optional.of(new Account(login.get().strip(), id));
    }

    @Override
    public boolean isRepository() {
        return git("rev-parse", "--git-dir").isPresent();
    }

    @Override
    public Optional<String> remoteUrl() {
        return git("remote", "get-url", "origin").filter(s -> !s.isBlank());
    }

    @Override
    public Optional<String> head() {
        return git("rev-parse", "HEAD");
    }

    @Override
    public List<String> tagsAtHead() {
        return lines(git("tag", "--points-at", "HEAD"));
    }

    @Override
    public Optional<String> nearestTag() {
        return git("describe", "--tags", "--abbrev=0").filter(s -> !s.isBlank());
    }

    @Override
    public List<String> tagsMerged() {
        return lines(git("tag", "--merged", "HEAD"));
    }

    @Override
    public Optional<String> tagCommit(String tag) {
        return git("rev-parse", "-q", "--verify", "refs/tags/" + tag + "^{commit}").filter(s -> !s.isBlank());
    }

    @Override
    public boolean clean() {
        return git("status", "--porcelain").map(String::isBlank).orElse(false);
    }

    @Override
    public boolean repositoryExists(String repo) {
        return runner.read(dir, List.of("gh", "repo", "view", repo, "--json", "name")).isPresent();
    }

    @Override
    public boolean releaseExists(String repo, String tag) {
        return runner.read(dir, List.of("gh", "release", "view", tag, "--repo", repo)).isPresent();
    }

    @Override
    public void firstCommit() throws Refusal {
        // The local check just built target/, and the repository is public: never commit the build.
        Path ignore = dir.resolve(".gitignore");
        if (!Files.exists(ignore)) {
            try {
                runner.write(ignore, "target/\n");
            } catch (IOException e) {
                throw new Refusal("could not write " + ignore + ": " + e.getMessage());
            }
        }
        must("could not make the first commit in " + dir + ".", List.of("git", "init", "-q", "-b", "main"),
                List.of("git", "add", "-A"), List.of("git", "commit", "-qm", "Initial commit"));
    }

    @Override
    public void commitFiles(String message, Map<String, String> files) throws Refusal {
        for (Map.Entry<String, String> each : files.entrySet()) {
            Path file = dir.resolve(each.getKey());
            try {
                Files.createDirectories(file.getParent());
                runner.write(file, each.getValue());
            } catch (IOException e) {
                throw new Refusal("could not write " + file + ": " + e.getMessage());
            }
        }
        if (!isRepository()) return;
        List<String> add = new ArrayList<>(List.of("git", "add", "--"));
        add.addAll(files.keySet());
        must("could not stage " + String.join(", ", files.keySet()) + ".", add);
        // A checkout that turned the files' line breaks into CRLF stages nothing new: then there is nothing to commit.
        List<String> staged = new ArrayList<>(List.of("diff", "--cached", "--name-only", "--"));
        staged.addAll(files.keySet());
        if (lines(git(staged.toArray(String[]::new))).isEmpty()) return;
        List<String> commit = new ArrayList<>(List.of("git", "commit", "-qm", message, "--"));
        commit.addAll(files.keySet());
        must("could not commit " + String.join(", ", files.keySet()) + ".", commit);
    }

    @Override
    public void createRepository(String repo) throws Refusal {
        must("could not create " + repo + ".", List.of("gh", "repo", "create", repo, "--source", ".", "--push",
                "--public"));
    }

    @Override
    public void pushHead() throws Refusal {
        must("could not push HEAD to origin.", List.of("git", "push", "origin", "HEAD"));
    }

    @Override
    public void tag(String tag) throws Refusal {
        if (git("rev-parse", "-q", "--verify", "refs/tags/" + tag).isEmpty()) {
            must("could not tag HEAD " + tag + ".", List.of("git", "tag", tag));
        }
        must("could not push the tag " + tag + ".", List.of("git", "push", "origin", "refs/tags/" + tag));
    }

    @Override
    public void release(String repo, String tag) throws Refusal {
        must("could not create the release " + tag + " on " + repo + ".", List.of("gh", "release", "create", tag,
                "--repo", repo, "--title", tag, "--notes", repo + " " + tag + ".", "--verify-tag"));
    }

    /**
     * {@link BotExport}, outside the {@link StepRunner}: it only reads the folder and writes into {@code outDir}, as
     * the folder's check builds it; the upload that publishes the file goes through the runner.
     */
    @Override
    public Path bmk(String repo, String tag, Path outDir) throws Refusal {
        try {
            return BotExport.write(dir, tag, repo, outDir, jars.of(dir), Central.maven());
        } catch (IOException e) {
            throw new Refusal("could not write the bot's .bmk: " + e.getMessage());
        }
    }

    @Override
    public void uploadAsset(String repo, String tag, Path file) throws Refusal {
        must("could not attach " + file.getFileName() + " to the release " + tag + ".", List.of("gh", "release",
                "upload", tag, file.toString(), "--repo", repo, "--clobber"));
    }

    @Override
    public Optional<String> pullRequest(CatalogPr.Change change, Account account, Listener listener) throws Refusal {
        return CatalogPr.open(change, account.login(), runner, listener);
    }

    private Optional<String> git(String... argv) {
        String[] command = new String[argv.length + 1];
        command[0] = "git";
        System.arraycopy(argv, 0, command, 1, argv.length);
        return runner.read(dir, List.of(command));
    }

    @SafeVarargs
    private void must(String failure, List<String>... commands) throws Refusal {
        for (List<String> command : commands) {
            if (!runner.change(dir, command).ok()) throw new Refusal(failure + " Its output is above.");
        }
    }

    private static List<String> lines(Optional<String> out) {
        return out.map(text -> text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList()).orElse(List.of());
    }
}
