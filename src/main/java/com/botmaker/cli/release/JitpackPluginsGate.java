package com.botmaker.cli.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Every module JitPack builds must pin only Maven plugins JitPack's Maven can execute —
 * {@code release.sh}'s {@code check_jitpack_plugins}.
 *
 * <p><b>Every JitPack build runs Apache Maven {@value GatePlan#JITPACK_MAVEN} ({@link JitpackScriptGate}), and
 * that is a hard ceiling.</b> Until 2026-10-12 it was JitPack's own 3.6.1, which is where the history below
 * comes from.
 * Maven refuses to <i>execute</i> a plugin whose {@code <prerequisites><maven>} exceeds its own version —
 * before the plugin's configuration is read and before anything is published — so the tag is pushed,
 * permanent, and resolves to nothing. **It is invisible locally, because a developer's Maven is newer.**
 * This project has burned three release chains on it, on three different plugins:
 * {@code flatten-maven-plugin} 1.6.0, {@code maven-compiler-plugin} 3.12.0+ and {@code maven-shade-plugin}
 * 3.5.2+ — the last caught by this gate rather than by a tag.
 *
 * <p><b>An unknown warns; it never refuses.</b> A plugin whose prerequisite cannot be read — no network, or
 * a property inherited from a parent pom nobody publishes — is reported and counted. A gate must not stop a
 * release over what it cannot read.
 */
public final class JitpackPluginsGate {

    private JitpackPluginsGate() {
    }

    /**
     * @param ceiling the Maven the module's JitPack build runs — {@link JitpackScriptGate#ceiling}, which is
     *                JitPack's own when the module's script did not pass
     */
    public static GateVerdict check(Path umbrella, Module module, boolean force, String ceiling) {
        return check(umbrella, module, force, ceiling, MavenPrerequisite.localRepository());
    }

    static GateVerdict check(Path umbrella, Module module, boolean force, String ceiling, Path localRepository) {
        // Studio and remote-server ship from their own CI and the two apps are APKs: JitPack builds none
        // of them, so their plugin pins are bounded by nothing but their own CI.
        if (!module.onJitpack()) {
            return GateVerdict.ok("");
        }
        Path pom = umbrella.resolve(module.directory()).resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return GateVerdict.ok("");
        }
        String text;
        try {
            text = Files.readString(pom);
        } catch (IOException e) {
            return GateVerdict.skipped("  " + module.shortName()
                    + ": pom.xml could not be read — JitPack plugin gate skipped");
        }
        return verdict(module, MavenPrerequisite.read(text, localRepository), force, ceiling);
    }

    static GateVerdict verdict(Module module, List<MavenPrerequisite.Pin> pins, boolean force) {
        return verdict(module, pins, force, GatePlan.JITPACK_MAVEN);
    }

    /** The rule over the pins already read — pure, so the FAIL/UNKNOWN arms need no network. */
    static GateVerdict verdict(Module module, List<MavenPrerequisite.Pin> pins, boolean force, String ceiling) {
        List<MavenPrerequisite.Pin> bad = pins.stream()
                .filter(pin -> pin.needs().isPresent())
                .filter(pin -> MavenPrerequisite.exceeds(pin.needs().get(), ceiling))
                .toList();
        List<MavenPrerequisite.Pin> unknown = pins.stream()
                .filter(pin -> !pin.unknown().isEmpty())
                .toList();

        if (bad.isEmpty()) {
            String ok = "  " + module.shortName() + ": every pinned Maven plugin runs on JitPack's Maven "
                    + ceiling + " — ok";
            return unknown.isEmpty()
                    ? GateVerdict.ok(ok)
                    // The script prints the unknown count as its own line before the verdict; here it leads
                    // the same line, because the count is only ever read beside the verdict it qualifies.
                    : GateVerdict.ok("  " + module.shortName() + ": " + unknown.size()
                            + " plugin(s) with no readable prerequisite — not checked\n" + ok);
        }
        if (force) {
            return GateVerdict.forced("  " + module.shortName()
                    + ": plugin too new for JitPack's Maven — FORCED");
        }
        StringBuilder refusal = new StringBuilder(module.directory()
                + ": a pinned Maven plugin cannot run on JitPack's Maven " + ceiling + ".\n\n");
        for (MavenPrerequisite.Pin pin : bad) {
            refusal.append("     ").append(pin.failure()).append('\n');
        }
        refusal.append("""

                     JitPack refuses to execute a plugin whose <prerequisites><maven> exceeds its own \
                version, and it
                     refuses BEFORE publishing anything — so the tag would resolve to nothing and a pushed \
                tag cannot be
                     edited. It builds locally because your Maven is newer.
                     Pin an older release of that plugin (botmaker-shared and botmaker-sdk hold the working \
                versions:
                     maven-compiler-plugin 3.11.0, flatten-maven-plugin 1.4.1), or --force.""");
        return GateVerdict.refused(refusal.toString());
    }
}
