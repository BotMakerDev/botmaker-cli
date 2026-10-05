package com.botmaker.cli.release;

import com.botmaker.cli.registry.Registry;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The plugin registry's {@code verifiedVersion} for a plugin this release cut, moved by a pull request.
 *
 * <p>Studio installs a ticked plugin at its entry's {@code verifiedVersion} and names that version in New
 * Project. Only {@code botmaker plugin publish} wrote it, so a release left it behind: on 2026-10-05 the
 * entries still said SDK v1.1.7 and basics v0.0.1 with v1.2.3 and v0.1.1 released. This is
 * {@link TemplatePin}'s door for the registry: the release opens the pull request and the registry's own CI
 * runs its gate over the new tag, so the version is verified before anyone merges it.
 *
 * <p><b>Never fatal.</b> It runs after every tag is pushed, so a failure here warns and names the file to
 * edit by hand rather than calling a finished release failed.
 */
public final class RegistryPin {

    static final String REPO = "BotMakerDev/botmaker-plugin-registry";

    /** Module to the registry id its entry is filed under. */
    static final Map<Module, String> ENTRIES = new EnumMap<>(Map.of(
            Module.SDK, "com.botmaker.sdk",
            Module.PLUGIN_BASICS, "com.botmaker.basics"));

    private RegistryPin() {
    }

    /** {@code "verifiedVersion" : "v1.1.7"} — the field, with the value as nothing but the gap between groups. */
    static Pattern field(String name) {
        return Pattern.compile("(\"" + Pattern.quote(name) + "\"\\s*:\\s*\")[^\"]*(\")");
    }

    /**
     * Opens one pull request per registered plugin in {@code tagged}.
     *
     * @param work an empty directory's parent; each entry is cloned under it
     * @return whether every pull request was opened
     */
    public static boolean bump(Runner runner, Path work, Map<Module, Version> tagged, LocalDate today) {
        boolean ok = true;
        for (Map.Entry<Module, String> entry : ENTRIES.entrySet()) {
            Version version = tagged.get(entry.getKey());
            if (version != null) {
                ok &= open(runner, work.resolve("registry-" + entry.getValue()), entry.getValue(), version,
                        today);
            }
        }
        return ok;
    }

    private static boolean open(Runner runner, Path clone, String id, Version version, LocalDate today) {
        String file = Registry.ENTRIES_DIRECTORY + "/" + id + ".json";
        runner.say("Registry: " + id + " verified at " + version.tag());
        String branch = "verify-" + id.replace('.', '-') + "-" + version.tag();
        try {
            if (!runner.run("gh", "repo", "clone", REPO, clone.toString(), "--", "--depth=1").ok()) {
                return failed(runner, id, file, "could not clone " + REPO);
            }
            runner.git(clone, "checkout", "-b", branch);
            Path json = clone.resolve(file);
            runner.replace(json, field("verifiedVersion"), "$1" + version.tag() + "$2");
            runner.replace(json, field("verifiedAt"), "$1" + today + "$2");
            String message = "registry: " + id + " verified at " + version.tag();
            if (!runner.git(clone, "commit", "-am", message).ok()
                    || !runner.git(clone, "push", "-u", "origin", branch).ok()) {
                return failed(runner, id, file, "could not commit or push " + branch);
            }
            boolean opened = runner.run("gh", "pr", "create", "--repo", REPO, "--head", branch,
                    "--title", message, "--body", "Moves `" + file + "` to the tag this release cut. The"
                            + " registry's gate runs over `" + version.tag() + "` on this pull request.").ok();
            return opened || failed(runner, id, file, "could not open the pull request for " + branch);
        } catch (ReleaseRefusal e) {
            return failed(runner, id, file, e.getMessage());
        }
    }

    private static boolean failed(Runner runner, String id, String file, String why) {
        runner.say("  warning: " + id + "'s registry entry was not moved (" + why + "). Edit " + file
                + " in " + REPO + " by hand.");
        return false;
    }
}
