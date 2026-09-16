package com.botmaker.cli.release;

import java.nio.file.Path;

/**
 * A module being cut must not be red on {@code main} — the newest completed {@code ci.yml} run there.
 *
 * <p><b>Added 2026-09-16, for the release that tagged {@code botmaker-plugin-host v0.1.0}</b> while
 * {@code PluginLoaderTest.a_broken_plugin_does_not_cost_the_others} had been failing on {@code main} for ten
 * days. The tag's own CI failed on the same test, so the tag publishes no GitHub Release, and a pushed tag
 * cannot be edited. Every other gate here reads the checkout; this is the one that asks what the module's
 * own CI already said about the commit being tagged, or the one before it.
 *
 * <p><b>Only a finished red run refuses.</b> A run still going is a warning, because the answer is minutes
 * away and the operator can wait for it. No {@code gh}, no {@code ci.yml} run, or a {@code gh} that cannot
 * reach the repository is {@link GateVerdict.Status#SKIPPED}: a gate must not stop a release over what it
 * cannot read. {@code --force} overrides a red run, and says so.
 */
public final class CiGate {

    private CiGate() {
    }

    public static GateVerdict check(Module module, boolean force) {
        if (!Proc.onPath("gh")) {
            return GateVerdict.skipped("  " + module.shortName() + ": no gh on PATH — CI on main not checked");
        }
        Proc.Result run = Proc.run(Path.of("."), "gh", "run", "list",
                "--repo", CleanRoom.OWNER + "/" + module.directory(),
                "--workflow", "ci.yml", "--branch", "main", "--limit", "5",
                "--json", "status,conclusion,url,headSha",
                "--jq", ".[] | [.status, .conclusion, .url, .headSha] | @tsv");
        if (!run.ok()) {
            return GateVerdict.skipped("  " + module.shortName() + ": could not list CI runs on main — skipped");
        }
        return verdict(module, run.out(), force);
    }

    /**
     * The rule over {@code gh}'s tab-separated runs, newest first — pure, so every arm is testable offline.
     */
    static GateVerdict verdict(Module module, String tsv, boolean force) {
        String name = module.shortName();
        boolean running = false;
        for (String line : tsv.lines().filter(l -> !l.isBlank()).toList()) {
            String[] cells = line.split("\t", -1);
            String status = cells[0].strip();
            if (!"completed".equals(status)) {
                running = true;
                continue;
            }
            String conclusion = cells.length > 1 ? cells[1].strip() : "";
            String url = cells.length > 2 ? cells[2].strip() : "";
            String sha = cells.length > 3 ? cells[3].strip() : "";
            String commit = sha.length() > 7 ? sha.substring(0, 7) : sha;
            String also = running ? " (a newer run is still going)" : "";
            if ("success".equals(conclusion) || "skipped".equals(conclusion)) {
                return GateVerdict.ok("  " + name + ": CI on main is green at " + commit + also + " — ok");
            }
            if (force) {
                return GateVerdict.forced("  " + name + ": CI on main is " + conclusion + " at " + commit
                        + " — FORCED (" + url + ")");
            }
            return GateVerdict.refused(module.directory() + ": the newest finished CI run on main is "
                    + conclusion + " (" + commit + ")" + also + ".\n"
                    + "     " + url + "\n"
                    + "     Its tag would run the same build and fail the same way, and a pushed tag cannot be"
                    + " edited.\n"
                    + "     Fix main first, or wait for a newer run. --force overrides.");
        }
        if (running) {
            return GateVerdict.skipped("  " + name + ": CI on main is still running — not checked");
        }
        return GateVerdict.skipped("  " + name + ": no CI run on main — not checked");
    }
}
