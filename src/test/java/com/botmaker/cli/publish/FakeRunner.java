package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.FakeFacts;
import com.botmaker.cli.validate.CheckResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link StepRunner} over a real git working copy and a pretend GitHub: {@code git} runs for real (its pushes are
 * only recorded), {@code gh} is answered here, and every {@link #change} is recorded in order. A release
 * {@code gh} creates becomes one {@link FakeFacts} reports, made by {@link #login}.
 */
final class FakeRunner implements StepRunner {

    static final String PR_URL = "https://github.com/BotMakerDev/botmaker-catalog/pull/7";

    final List<List<String>> changes = new ArrayList<>();
    /** {@code gh repo view} answers for these repositories. */
    final Set<String> existingRepos = new HashSet<>();
    /** A change whose command starts with one of these fails. */
    final List<List<String>> failing = new ArrayList<>();
    private final FakeFacts facts;
    String login = "somedev";
    /** The signed-in account's id, which {@code publishers.json} ranks. */
    long accountId = 7;

    FakeRunner(FakeFacts facts) {
        this.facts = facts;
    }

    /** The commands changed, each joined by spaces, in order. */
    List<String> commands() {
        return changes.stream().map(c -> String.join(" ", c)).toList();
    }

    @Override
    public Optional<String> read(Path dir, List<String> argv) {
        if (argv.getFirst().equals("git")) return system(dir, argv);
        if (argv.equals(List.of("gh", "api", "user", "--jq", ".login"))) return Optional.ofNullable(login);
        if (argv.equals(List.of("gh", "api", "user", "--jq", ".id"))) return Optional.of(String.valueOf(accountId));
        if (argv.subList(0, 3).equals(List.of("gh", "repo", "view"))) {
            return existingRepos.contains(argv.get(3)) ? Optional.of("{}") : Optional.empty();
        }
        if (argv.subList(0, 3).equals(List.of("gh", "release", "view"))) {
            return facts.release(argv.get(5), argv.get(3)).map(r -> "{}");
        }
        if (argv.subList(0, 2).equals(List.of("gh", "api"))) return Optional.of("false");
        return Optional.of("");
    }

    @Override
    public Ran change(Path dir, List<String> argv) {
        changes.add(argv);
        for (List<String> prefix : failing) {
            if (argv.size() >= prefix.size() && argv.subList(0, prefix.size()).equals(prefix)) {
                return new Ran(1, "refused");
            }
        }
        if (argv.subList(0, 3).equals(List.of("gh", "repo", "clone"))) {
            try {
                Files.createDirectories(dir.resolve(argv.get(4)).resolve("plugins"));
                Files.createDirectories(dir.resolve(argv.get(4)).resolve("bots"));
            } catch (IOException e) {
                return new Ran(1, e.getMessage());
            }
        }
        if (argv.subList(0, 3).equals(List.of("gh", "release", "create"))) {
            facts.release(argv.get(argv.indexOf("--repo") + 1), argv.get(3), login);
        }
        if (argv.subList(0, 3).equals(List.of("gh", "pr", "create"))) return new Ran(0, PR_URL + "\n");
        boolean local = argv.getFirst().equals("git") && !argv.get(1).equals("push")
                && !dir.endsWith("botmaker-catalog");
        if (local) {
            Optional<String> out = system(dir, argv);
            return new Ran(out.isPresent() ? 0 : 1, out.orElse(""));
        }
        return new Ran(0, "");
    }

    @Override
    public void write(Path file, String content) throws IOException {
        Files.writeString(file, content);
    }

    private static Optional<String> system(Path dir, List<String> argv) {
        try {
            ProcessBuilder builder = new ProcessBuilder(argv).directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            // An identity for a repository the code under test initialises itself.
            for (String who : List.of("AUTHOR", "COMMITTER")) {
                builder.environment().put("GIT_" + who + "_NAME", "test");
                builder.environment().put("GIT_" + who + "_EMAIL", "test@example.invalid");
            }
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes()).strip();
            return process.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** A git repository in {@code dir} with one commit, an identity, and {@code origin} (when not null). */
    static Path repository(Path dir, String origin) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>\n");
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.email", "test@example.invalid");
        git(dir, "config", "user.name", "test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "tag.gpgsign", "false");
        git(dir, "add", "-A");
        git(dir, "commit", "-qm", "one");
        if (origin != null) git(dir, "remote", "add", "origin", origin);
        return dir;
    }

    /** A new commit on {@code dir}'s branch. */
    static void commit(Path dir, String message) throws IOException {
        Files.writeString(dir.resolve(message + ".txt"), message);
        git(dir, "add", "-A");
        git(dir, "commit", "-qm", message);
    }

    static void git(Path dir, String... args) throws IOException {
        List<String> argv = new ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        if (system(dir, argv).isEmpty()) throw new IOException("git " + String.join(" ", args) + " failed");
    }

    /** Hears nothing. */
    static final Listener QUIET = new Listener() {
        @Override
        public void step(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void checks(List<CheckResult> results) {}
    };
}
