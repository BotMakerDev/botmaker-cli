package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateGateTest {

    /** What `mvn -B -q -f <pom> compile` printed on 2026-09-21, when the template was a day behind its pin. */
    private static final String BROKEN = """
            [ERROR] /home/x/botmaker-gamebot/src/main/java/com/example/Parameters.java:[7,33] \
            cannot find symbol
            [ERROR]   symbol:   class Param
            [ERROR] -> [Help 1]
            """;

    @Test
    void bothTemplatesAreCompiledAndOnlyOneOfThemIsAModule() {
        // botmaker-base has no release flag — it names no SDK, so it has no pin to move — and it is still a
        // project a user copies, so it is still compiled. That is why this gate is about directories.
        assertEquals(List.of("botmaker-gamebot", "botmaker-base"), TemplateGate.directories());
    }

    @Test
    void theGateRunsWhenTheSdkIsCutEvenThoughNoTemplateIs() {
        // The whole reason it is not a forcing edge: an SDK patch must not demand a template version, but an
        // SDK release IS the moment an untouched template can stop compiling.
        assertEquals(TemplateGate.directories(), GatePlan.templates(Set.of(Module.SDK)));
        assertEquals(TemplateGate.directories(), GatePlan.templates(Set.of(Module.GAMEBOT)));
        assertEquals(TemplateGate.directories(),
                GatePlan.templates(EnumSet.allOf(Module.class)));
    }

    @Test
    void aReleaseThatTouchesNeitherCompilesNoTemplate() {
        assertTrue(GatePlan.templates(Set.of(Module.STUDIO, Module.CLI)).isEmpty());
        assertTrue(GatePlan.templates(Set.of()).isEmpty());
    }

    @Test
    void aCheckoutWithoutTheSubmoduleIsSkippedRatherThanRefused() {
        // A gate that cannot run must say so. An umbrella cloned without --recursive has no template to
        // compile, which is a fact about the checkout and not about the template.
        GateVerdict verdict = TemplateGate.check(Path.of("/nonexistent-umbrella"), "botmaker-base", false);

        assertEquals(GateVerdict.Status.SKIPPED, verdict.status());
        assertFalse(verdict.stops());
        assertTrue(verdict.line().contains("no pom.xml in this checkout"), verdict.line());
    }

    @Test
    void aFailedCompileRefusesWithMavensOwnErrorsRatherThanAVersion() {
        GateVerdict verdict = TemplateGate.verdict("botmaker-gamebot", new Proc.Result(1, BROKEN), false);

        assertTrue(verdict.stops());
        assertTrue(verdict.refusal().startsWith(
                "botmaker-gamebot: the template does not compile at the version it pins."), verdict.refusal());
        // The message names the file and the symbol, which is what neither of the two 2026-09-21 failures
        // said: one died on `com/botmaker/plugin/api/ValueContext` with no version anywhere in it.
        assertTrue(verdict.refusal().contains("Parameters.java"), verdict.refusal());
        assertTrue(verdict.refusal().contains("symbol:   class Param"), verdict.refusal());
    }

    @Test
    void aCompileWhoseErrorsMavenDidNotPrefixIsStillQuoted() {
        // `mvn -q` hands some javac diagnostics through unprefixed. A refusal quoting nothing reads as the
        // gate being broken rather than the template.
        GateVerdict verdict = TemplateGate.verdict("botmaker-base",
                new Proc.Result(1, "Sdk.java:14: error: cannot find symbol\n"), false);

        assertTrue(verdict.refusal().contains("Sdk.java:14: error: cannot find symbol"), verdict.refusal());
    }

    @Test
    void forceTurnsItIntoAVisibleOverrideAndAPassIsOneLine() {
        assertEquals(GateVerdict.Status.FORCED,
                TemplateGate.verdict("botmaker-gamebot", new Proc.Result(1, BROKEN), true).status());
        assertEquals("  botmaker-gamebot: compiles at its own pin — ok",
                TemplateGate.verdict("botmaker-gamebot", new Proc.Result(0, ""), false).line());
    }

    @Test
    void aDryRunDoesNotCompileTheBumpedPinBecauseItNeverWroteOne(@TempDir Path umbrella) {
        // The pom was not edited, so this would compile the old pin and report on a file that does not
        // exist yet. The decide pass already compiled that pin.
        List<String> said = new ArrayList<>();

        TemplateGate.afterBump(new Runner(true, said::add), umbrella, Module.GAMEBOT, new Version(1, 1, 15));

        assertTrue(said.isEmpty(), said.toString());
    }
}
