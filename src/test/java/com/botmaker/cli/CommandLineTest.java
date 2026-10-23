package com.botmaker.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line, tested for what is <em>ours</em> rather than for what picocli owns.
 *
 * <p>The parser this replaced had eight tests over three option forms — picocli's job, and not something
 * worth re-proving here. What is worth proving is the surface: that the exit-code contract still holds
 * (0 / 1 / 2), that an option a command actually reads is an option the command line accepts, and that an
 * option nobody declared is <b>refused</b>. That last one is the reason the library is here at all: the
 * first real invocation of {@code botmaker plugin new} passed {@code --botmaker-version}, which the
 * hand-rolled parser silently ignored, so the generated project was pinned to something else and nothing
 * said so.
 */
class CommandLineTest {

    @Test
    void an_unknown_option_is_refused_and_named() {
        Captured captured = capture(
                () -> Main.run(new String[]{"plugin", "new", "x", "--botmaker-version", "1.2.3"}));

        assertEquals(CommandLine.ExitCode.USAGE, captured.exitCode);
        assertTrue(captured.err.contains("--botmaker-version"),
                "the refusal must name the option; got:\n" + captured.err);
    }

    /** picocli's suggestion, which is the half that turns a refusal into a fix. */
    @Test
    void a_near_miss_suggests_the_option_that_was_meant() {
        Captured captured = capture(
                () -> Main.run(new String[]{"plugin", "new", "x", "--plugin-versio", "9"}));

        assertTrue(captured.err.contains("--plugin-version"),
                "expected a suggestion naming --plugin-version; got:\n" + captured.err);
    }

    @Test
    void no_verb_prints_the_usage_and_exits_two() {
        Captured captured = capture(() -> Main.run(new String[0]));

        assertEquals(CommandLine.ExitCode.USAGE, captured.exitCode);
        for (String verb : new String[]{"plugin", "bot", "doctor", "completion"}) {
            assertTrue(captured.out.contains(verb), "usage must list " + verb + "; got:\n" + captured.out);
        }
    }

    /**
     * The four verbs that moved say so, and run nothing.
     *
     * <p>Exit 2 rather than 1: a verb that no longer exists is a command line that was wrong, not a plugin
     * that failed to validate. And the message names the verb <em>as typed</em> — all four are aliases of
     * one hidden command, so a reply naming picocli's primary name would tell three of the four users the
     * wrong thing.
     */
    @Test
    void the_moved_verbs_name_their_replacement_and_do_not_run() {
        for (String verb : new String[]{"new", "validate", "run", "publish"}) {
            Captured captured = capture(() -> Main.run(new String[]{verb, "whatever"}));

            assertEquals(CommandLine.ExitCode.USAGE, captured.exitCode, verb);
            assertTrue(captured.err.contains("botmaker plugin " + verb),
                    "expected `" + verb + "` to point at `botmaker plugin " + verb + "`; got:\n"
                            + captured.err);
        }
    }

    /** {@code doctor} parses with no arguments — it asks about the machine, not about a project. */
    @Test
    void doctor_takes_no_arguments() {
        assertEquals("doctor", parse("doctor").subcommand().commandSpec().name());
    }

    @Test
    void help_exits_zero() {
        Captured captured = capture(() -> Main.run(new String[]{"--help"}));

        assertEquals(0, captured.exitCode);
    }

    @Test
    void version_answers_even_with_no_manifest() {
        Captured captured = capture(() -> Main.run(new String[]{"--version"}));

        assertEquals(0, captured.exitCode);
        assertTrue(captured.out.startsWith("botmaker "), captured.out);
    }

    /**
     * The completion script names every verb the command line has. That is the property a checked-in
     * script cannot hold: this test fails the day somebody adds a subcommand, which is exactly when a
     * hand-written script would have quietly stopped being complete.
     *
     * <p>Hidden commands are skipped on both sides — picocli leaves them out of the script, and the moved
     * verbs are hidden precisely so nothing offers them.
     */
    @Test
    void the_completion_script_offers_every_verb_and_nothing_else() {
        Captured captured = capture(() -> Main.run(new String[]{"completion"}));

        assertEquals(0, captured.exitCode);
        assertTrue(captured.out.contains("complete -F _complete_botmaker"), captured.out);
        for (CommandLine sub : new CommandLine(new Main()).getSubcommands().values()) {
            if (sub.getCommandSpec().usageMessage().hidden()) {
                continue;
            }
            assertTrue(captured.out.contains(sub.getCommandName()),
                    "the script does not mention " + sub.getCommandName() + ":\n" + captured.out);
        }
    }

    @Test
    void a_missing_artifact_id_is_a_command_line_error_not_a_failure() {
        Captured captured = capture(() -> Main.run(new String[]{"plugin", "new"}));

        assertEquals(CommandLine.ExitCode.USAGE, captured.exitCode);
    }

    /**
     * The defaults the README and {@code --help} both promise, read off the parse rather than off a run:
     * a generated pom pinned to something other than the template's own pins because a default moved is
     * exactly the class of change that produces no error until much later.
     */
    @Test
    void new_defaults_are_the_documented_ones() {
        ParseResult verb = parse("plugin", "new", "my-plugin").subcommand().subcommand();

        assertEquals("my-plugin", verb.matchedPositionalValue(0, ""));
        // Not matchedOptionValue: an option nobody typed is not "matched", and the question here is what
        // the command will actually USE — which is the default, applied to the field during the parse.
        assertEquals("com.example", applied(verb, "--group"));
        // No default: the template's pom keeps the pins it was released with.
        assertNull(verb.commandSpec().findOption("--studio-api").getValue());
        assertNull(verb.commandSpec().findOption("--toolkit").getValue());
        assertEquals("0.1.0-SNAPSHOT", applied(verb, "--plugin-version"));
    }

    /** {@code validate} takes its directory either way, which is what the old hand-rolled parser did. */
    @Test
    void validate_accepts_the_directory_as_a_position_or_an_option() {
        assertEquals("plugins/mine", parse("plugin", "validate", "plugins/mine")
                .subcommand().subcommand().matchedPositionalValue(0, ""));
        assertEquals("plugins/mine", parse("plugin", "validate", "--dir", "plugins/mine")
                .subcommand().subcommand().matchedOptionValue("--dir", ""));
    }

    /** {@code --quiet} and {@code --debug} are inherited, so they parse after the verb as well as before. */
    @Test
    void the_global_flags_parse_on_either_side_of_the_verb() {
        assertTrue(parse("--quiet", "plugin", "validate").hasMatchedOption("--quiet"));
        assertTrue(parse("plugin", "validate", "--quiet")
                .subcommand().subcommand().hasMatchedOption("--quiet"));
    }

    /**
     * {@code publish --tag} exists and is spelled that way.
     *
     * <p>{@code --version} is taken by {@code mixinStandardHelpOptions} on every command here, so this
     * option cannot be called that; the test is here because the name is forced by something invisible at
     * the call site, and renaming it back would fail at construction rather than at review.
     */
    @Test
    void publish_takes_the_tag_it_will_publish_under() {
        assertEquals("v1.2.0", parse("plugin", "publish", "--tag", "v1.2.0")
                .subcommand().subcommand().matchedOptionValue("--tag", ""));
    }

    /**
     * {@code bot} is a noun with two verbs under it, and both parse two levels deep.
     *
     * <p>{@code bot new} and {@code plugin new} are different commands about different things — a bot and a
     * plugin — which is why both are spelled with their noun since 2026-09-05.
     */
    @Test
    void bot_has_its_own_new_and_publish() {
        ParseResult botNew = parse("bot", "new", "farmbot", "--from", "alice/farmbot").subcommand().subcommand();
        assertEquals("farmbot", botNew.matchedPositionalValue(0, ""));
        assertEquals("alice/farmbot", botNew.matchedOptionValue("--from", ""));
        assertEquals(".", applied(botNew, "--dir"));

        ParseResult botPublish = parse("bot", "publish", "--repo", "me/farmbot").subcommand().subcommand();
        assertEquals("me/farmbot", botPublish.matchedOptionValue("--repo", ""));
        assertEquals("v0.1.0", applied(botPublish, "--tag"));
        assertEquals("me/farmbot", parse("bot", "unpublish", "--repo", "me/farmbot")
                .subcommand().subcommand().matchedOptionValue("--repo", ""));
        assertEquals("list", parse("bot", "list").subcommand().subcommand().commandSpec().name());
    }

    /** The catalog's verbs for a plugin: unpublish names the entry by id, list takes nothing. */
    @Test
    void plugin_has_unpublish_and_list() {
        assertEquals("com.example.thing", parse("plugin", "unpublish", "--id", "com.example.thing")
                .subcommand().subcommand().matchedOptionValue("--id", ""));
        assertEquals("list", parse("plugin", "list").subcommand().subcommand().commandSpec().name());
        assertEquals(CommandLine.ExitCode.USAGE, exit("plugin", "unpublish"), "--id is required");
        assertEquals(CommandLine.ExitCode.USAGE, exit("plugin", "publish", "--min-contract-version", "0.9.0"),
                "the catalog derives the contract version; the option is gone");
        assertEquals(CommandLine.ExitCode.USAGE, exit("bot", "publish", "--repo", "me/farmbot", "--tags", "a"),
                "a bot's topics are its repository's own; the option is gone");
    }

    private static int exit(String... argv) {
        return capture(() -> Main.run(argv)).exitCode;
    }

    /** {@code --from} is a repository, refused in any other shape before git is asked anything. */
    @Test
    void from_wants_owner_and_repo() throws Exception {
        assertEquals("alice/farm-bot", BotNewCommand.slug(" alice/farm-bot "));
        for (String bad : new String[]{"alice-farmbot", "alice/", "/farmbot", "a/b/c", "--upload-pack=x/y",
                "--help/x", "-a/b", "a/-b", "a/.", "a/.."}) {
            assertThrows(IOException.class, () -> BotNewCommand.slug(bad), bad);
        }
    }

    /** A publish pushes only into the repository it names, never into one whose name merely contains it. */
    @Test
    void the_origin_check_compares_the_whole_repository() {
        assertTrue(BotNewCommand.names("https://github.com/Alice/Farmbot.git", "alice/farmbot"));
        assertTrue(BotNewCommand.names("git@github.com:alice/farmbot.git", "alice/farmbot"));
        assertTrue(BotNewCommand.names("https://github.com/alice/farmbot/", "alice/farmbot"));
        assertFalse(BotNewCommand.names("https://github.com/alice/farmbot-fork", "alice/farmbot"));
        assertFalse(BotNewCommand.names("https://github.com/xalice/farmbot", "alice/farmbot"));
    }

    /**
     * The third noun's flags are {@code release.sh}'s, one for one — and each takes an optional value, so
     * {@code --cli} and {@code --cli patch} are the same request, as {@code take_optional} makes them.
     */
    @Test
    void release_mirrors_the_scripts_module_flags() {
        ParseResult bare = parse("release", "--cli", "--shared").subcommand();
        assertEquals("patch", applied(bare, "--cli"));
        assertEquals("patch", applied(bare, "--shared"));

        ParseResult explicit = parse("release", "--sdk", "1.2.0", "--all", "minor").subcommand();
        assertEquals("1.2.0", explicit.matchedOptionValue("--sdk", ""));
        assertEquals("minor", explicit.matchedOptionValue("--all", ""));

        // Every module the script has a flag for, and nothing it does not: --gallery is not a release.
        for (String flag : new String[]{"--studio-api", "--plugin-toolkit", "--plugin-host",
                "--plugin-archetype", "--cli", "--shared", "--session", "--sdk", "--studio", "--pilot"}) {
            assertTrue(parse("release", flag).subcommand().hasMatchedOption(flag), flag);
        }
    }

    /**
     * {@code --why} exists because the cutover diff has to be empty.
     *
     * <p>The reasons a module was forced into a release are the one place the port improves on the script,
     * and printing them by default would make every {@code --dry-run} comparison fail on an addition
     * nobody objects to.
     */
    @Test
    void release_keeps_its_one_addition_opt_in() {
        assertFalse(parse("release", "--all").subcommand().hasMatchedOption("--why"));
        assertTrue(parse("release", "--all", "--why").subcommand().hasMatchedOption("--why"));
    }

    /** The option `botmaker plugin run` passes to Studio as a named JavaFX parameter. */
    @Test
    void run_takes_a_project_name() {
        assertEquals("MyBot", parse("plugin", "run", "--project", "MyBot")
                .subcommand().subcommand().matchedOptionValue("--project", ""));
    }

    /** The value a command will use for an option: what was typed, or the default the parse applied. */
    private static String applied(ParseResult verb, String option) {
        // The Object local is not ceremony: getValue() is generic, so String.valueOf(getValue()) infers
        // char[] and throws a ClassCastException at runtime.
        Object value = verb.commandSpec().findOption(option).getValue();
        return String.valueOf(value);
    }

    private static ParseResult parse(String... argv) {
        return new CommandLine(new Main()).parseArgs(argv);
    }

    private record Captured(int exitCode, String out, String err) {
    }

    private static Captured capture(Supplier<Integer> body) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exitCode = body.get();
            return new Captured(exitCode,
                    out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
