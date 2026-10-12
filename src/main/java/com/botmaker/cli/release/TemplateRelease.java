package com.botmaker.cli.release;

import java.util.List;

/**
 * A template's tag made a GitHub Release, which is what New Project copies.
 *
 * <p><b>A bot is its releases, and a template is a bot.</b> Studio installs a gallery bot from its repository's
 * {@code /releases/latest}, and so does New Project from a template; the gallery gate refuses a repository with
 * no release. A tag is not a release: on 2026-10-12 {@code botmaker-gamebot} had tags up to v0.2.7 and its
 * newest GitHub Release was v0.1.1, so New Project copied v0.1.1 and its pom's SDK 1.2.3. The template has no
 * workflow to turn a tag into a release, as the dashboard's {@code ci.yml} does, so the release library does
 * it right after the push.
 *
 * <p><b>A failure is said, not thrown.</b> The tag is on origin by then and the template is tagged last, so
 * nothing after it is waiting; the line names the command that finishes the job.
 */
public final class TemplateRelease {

    private TemplateRelease() {
    }

    /** {@code gh release create} for the pushed tag; {@code --verify-tag} refuses one origin lacks. */
    static List<String> command(Module module, Version version) {
        return List.of("gh", "release", "create", version.tag(),
                "--repo", CleanRoom.OWNER + "/" + module.directory(),
                "--verify-tag",
                "--title", version.tag(),
                "--notes", "The " + module.shortName() + " template at " + version.tag() + ". New Project copies"
                        + " a template's newest release; its pom pins the plugins it was released against.");
    }

    public static void publish(Runner runner, Module module, Version version) {
        List<String> argv = command(module, version);
        Proc.Result made = runner.run(argv);
        if (!made.ok()) {
            runner.say("warning: " + module.directory() + " " + version.tag() + " is tagged but no GitHub Release"
                    + " was made, so New Project still copies the previous one. Run: "
                    + String.join(" ", argv.subList(0, 7)) + " --notes ''");
        }
    }
}
