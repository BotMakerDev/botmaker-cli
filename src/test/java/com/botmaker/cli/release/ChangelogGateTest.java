package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The gate runs a module's own {@code tools/changelog-section.sh}, and the interesting property is that it
 * runs it <em>from the module's directory</em>.
 *
 * <p>That is what made a relative umbrella refuse a changelog that was present: the script path was passed
 * as {@code ./botmaker-plugin-basics/tools/changelog-section.sh} into a process whose working directory was
 * already {@code botmaker-plugin-basics}. {@code release.sh} cannot reproduce it — its {@code ROOT} is
 * computed absolute — so this was a divergence between two implementations whose whole verification is a
 * diff of their output.
 *
 * <p>The fixture is a real extractor copied from a real module rather than a stub, for the same reason the
 * gate shells to it at all: a second implementation of that script is exactly what having one file exists
 * to prevent.
 */
class ChangelogGateTest {

    private static final String CHANGELOG = """
            # Changelog

            ## [Unreleased]

            ### Added

            - Something worth releasing.
            """;

    /** A fake umbrella holding one module: the extractor, and a changelog with an [Unreleased] section. */
    private static Path fakeUmbrella(Path root) throws IOException {
        Path extractor = Path.of("..").resolve("botmaker-plugin-toolkit/tools/changelog-section.sh");
        assumeTrue(Files.isExecutable(extractor),
                "built outside the umbrella, so there is no real extractor to copy");

        Path module = root.resolve(Module.PLUGIN_TOOLKIT.directory());
        Files.createDirectories(module.resolve("tools"));
        Path copy = module.resolve("tools/changelog-section.sh");
        Files.copy(extractor, copy, StandardCopyOption.REPLACE_EXISTING);
        copy.toFile().setExecutable(true);
        Files.writeString(module.resolve("CHANGELOG.md"), CHANGELOG);
        return root;
    }

    @Test
    void anUnreleasedSectionSatisfiesTheGate(@TempDir Path tmp) throws IOException {
        GateVerdict verdict = ChangelogGate.check(
                fakeUmbrella(tmp), Module.PLUGIN_TOOLKIT, new Version(0, 0, 6), false);
        assertEquals(GateVerdict.Status.OK, verdict.status(), verdict.line());
    }

    @Test
    void aRelativeUmbrellaFindsTheSameExtractor(@TempDir Path tmp) throws IOException {
        Path absolute = fakeUmbrella(tmp);
        // A path with no root, the shape `--umbrella .` produces once the temp directory is expressed from
        // the working directory. The gate must answer identically.
        Path relative = Path.of("").toAbsolutePath().relativize(absolute);
        assertTrue(!relative.isAbsolute(), "the fixture is meant to be relative: " + relative);

        GateVerdict verdict = ChangelogGate.check(
                relative, Module.PLUGIN_TOOLKIT, new Version(0, 0, 6), false);
        assertEquals(GateVerdict.Status.OK, verdict.status(), verdict.line());
    }

    @Test
    void aChangelogWithNothingInItIsRefused(@TempDir Path tmp) throws IOException {
        Path root = fakeUmbrella(tmp);
        // A heading is not a description: the extractor exits non-zero on an empty body, and the gate reads
        // that as the refusal.
        Files.writeString(root.resolve(Module.PLUGIN_TOOLKIT.directory()).resolve("CHANGELOG.md"),
                "# Changelog\n\n## [Unreleased]\n");

        GateVerdict verdict = ChangelogGate.check(root, Module.PLUGIN_TOOLKIT, new Version(0, 0, 6), false);
        assertEquals(GateVerdict.Status.REFUSED, verdict.status(), verdict.refusal());
    }
}
