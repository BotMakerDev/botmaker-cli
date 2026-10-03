package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackVersionsGateTest {

    /**
     * The shape a constant actually has in MavenService.java, javadoc and all.
     *
     * <p>It keeps a {@code TOOLKIT_FALLBACK_VERSION} that Studio deleted on 2026-09-06, deliberately: this
     * is a fixture for the <i>reader</i>, and a second constant whose name ends in the first's is what
     * exercises the pattern below. The tripwire at the bottom is the test that reads the real file.
     */
    private static final String SOURCE = """
            public class MavenService {
                /** Version used for the SDK when none is supplied. */
                public static final String SDK_FALLBACK_VERSION = "1.1.6";
                public static final String TOOLKIT_FALLBACK_VERSION = "0.0.5";
            }
            """;

    @Test
    void bothConstantsAreReadOffTheirDeclarations() {
        assertEquals(Optional.of("1.1.6"), FallbackVersionsGate.read(SOURCE, "SDK_FALLBACK_VERSION"));
        assertEquals(Optional.of("0.0.5"), FallbackVersionsGate.read(SOURCE, "TOOLKIT_FALLBACK_VERSION"));
    }

    @Test
    void aConstantThatIsNotThereIsEmptyRatherThanAGuess() {
        // Phase 2 deletes TOOLKIT_FALLBACK_VERSION outright, and an absent constant is an ordinary state:
        // the gate has nothing to check, not something to refuse.
        assertEquals(Optional.empty(), FallbackVersionsGate.read(SOURCE, "SESSION_FALLBACK_VERSION"));
    }

    @Test
    void theLongerNameDoesNotMatchTheShorterOne() {
        // SDK_FALLBACK_VERSION is a suffix of nothing here, but TOOLKIT_FALLBACK_VERSION would match a
        // sloppy `.*FALLBACK_VERSION` pattern against the SDK's line and read the wrong version.
        assertEquals(Optional.of("0.0.5"), FallbackVersionsGate.read(SOURCE, "TOOLKIT_FALLBACK_VERSION"));
    }

    @Test
    void theGateOnlyRunsForAStudioRelease() {
        // The constants live in Studio's source, so only a Studio release publishes a change to them.
        assertTrue(GatePlan.fallbackVersions(java.util.Set.of(Module.STUDIO)));
        assertTrue(!GatePlan.fallbackVersions(java.util.Set.of(Module.SDK)));
    }

    @Test
    void studiosOwnSourceStillDeclaresWhatTheGateReads() {
        // The gate finds the constants by name in a real file; a rename in Studio makes it read nothing and
        // pass silently, which is the failure mode it exists to prevent. This is the tripwire for that.
        java.nio.file.Path source = java.nio.file.Path.of("..")
                .resolve(Module.STUDIO.directory()).resolve(FallbackVersionsGate.SOURCE);
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(source),
                "not run from the umbrella checkout");
        String text = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> java.nio.file.Files.readString(source));

        for (String constant : Fallback.CONSTANTS.values()) {
            assertTrue(FallbackVersionsGate.read(text, constant).isPresent(),
                    "MavenService no longer declares " + constant + " the way the gate reads it");
        }
    }
}
