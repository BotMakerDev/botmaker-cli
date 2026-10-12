package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitpackScriptGateTest {

    private static final String YML = "jdk:\n  - openjdk25\ninstall:\n  - bash .jitpack/build.sh\n";

    private static Path canonical(Path root) throws IOException {
        Path canonical = root.resolve(JitpackScriptGate.CANONICAL);
        Files.createDirectories(canonical);
        Files.writeString(canonical.resolve("build.sh"), "echo build\n");
        Files.writeString(canonical.resolve("statx.c"), "int statx;\n");
        return canonical;
    }

    private static Path module(Path root, String script, String yml) throws IOException {
        Path dir = root.resolve("botmaker-shared");
        Files.createDirectories(dir.resolve(".jitpack"));
        Files.writeString(dir.resolve(".jitpack/build.sh"), script);
        Files.writeString(dir.resolve(".jitpack/statx.c"), "int statx;\n");
        Files.writeString(dir.resolve("jitpack.yml"), yml);
        return dir;
    }

    @Test
    void anIdenticalCommittedCopyThatInstallRunsPasses(@TempDir Path root) throws IOException {
        List<String> problems = JitpackScriptGate.problems(canonical(root), module(root, "echo build\n", YML),
                file -> true);

        assertEquals(List.of(), problems);
        assertEquals(GateVerdict.Status.OK, JitpackScriptGate.verdict(Module.SHARED, problems, false).status());
    }

    @Test
    void aCopyOnlyOnDiskIsRefused(@TempDir Path root) throws IOException {
        // The release commit is `commit -am`: the yml change would ship and the untracked script would not.
        List<String> problems = JitpackScriptGate.problems(canonical(root), module(root, "echo build\n", YML),
                file -> !file.equals(".jitpack/build.sh"));

        assertEquals(List.of(".jitpack/build.sh is not committed, and the release commit adds no new file"), problems);
    }

    @Test
    void aDriftedCopyAMissingShimAndAPlainMvnInstallAreEachNamed(@TempDir Path root) throws IOException {
        // The v0.2.4 shape: a module whose yml runs a Maven fetched from Central with no retry.
        Path dir = module(root, "echo older\n", "install:\n  - mvn install -DskipTests -B\n");
        Files.delete(dir.resolve(".jitpack/statx.c"));

        GateVerdict verdict = JitpackScriptGate.verdict(Module.SHARED,
                JitpackScriptGate.problems(canonical(root), dir, file -> true), false);

        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().startsWith("botmaker-shared: JitPack would not build this tag"));
        assertTrue(verdict.refusal().contains("     .jitpack/build.sh differs from the umbrella's tools/jitpack/build.sh"));
        assertTrue(verdict.refusal().contains("     .jitpack/statx.c is missing"));
        assertTrue(verdict.refusal().contains("     jitpack.yml's install does not start with `bash .jitpack/build.sh`"));
    }

    @Test
    void onlyTheFirstInstallCommandCounts() {
        assertTrue(JitpackScriptGate.installRunsScript(
                "install:\n  # why\n  - bash .jitpack/build.sh -Dbotmaker.japicmp.skip=true\n"));
        // JitPack's own mvn would run first and die on the 429 the script exists for.
        assertFalse(JitpackScriptGate.installRunsScript("install:\n  - mvn install\n  - bash .jitpack/build.sh\n"));
        assertFalse(JitpackScriptGate.installRunsScript(
                "before_install:\n  - bash .jitpack/build.sh\ninstall:\n  - mvn install\n"));
        assertFalse(JitpackScriptGate.installRunsScript("jdk:\n  - openjdk25\n"));
    }

    @Test
    void theCeilingFallsBackToJitpacksOwnMavenUnlessTheScriptPassed() {
        assertEquals("3.9.9", JitpackScriptGate.ceiling(GateVerdict.ok("ok")));
        assertEquals("3.6.1", JitpackScriptGate.ceiling(GateVerdict.forced("forced")));
        assertEquals("3.6.1", JitpackScriptGate.ceiling(GateVerdict.skipped("skipped")));
    }

    @Test
    void forceDowngradesItToALine() {
        GateVerdict verdict = JitpackScriptGate.verdict(Module.SDK, List.of("jitpack.yml does not run it"), true);

        assertEquals(GateVerdict.Status.FORCED, verdict.status());
        assertEquals("  sdk: JitPack build script out of date — FORCED", verdict.line());
    }

    @Test
    void anUmbrellaWithoutTheScriptSkipsAndNeverRefuses(@TempDir Path root) {
        assertEquals(GateVerdict.Status.SKIPPED, JitpackScriptGate.check(root, Module.SHARED, false).status());
    }
}
