package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The command line the SDK plugin gate runs: {@code --against} the SDK's last tag once it has one. */
class SdkGatesTest {

    private static final Path JAR = Path.of("cli-all.jar");
    private static final Path SDK = Path.of("botmaker-sdk");

    @Test
    void theLastReleaseIsComparedAgainst() {
        assertEquals(List.of("java", "-jar", "cli-all.jar", "plugin", "validate", "botmaker-sdk", "--against", "v1.4.2"),
                SdkGates.validateCommand(JAR, SDK, Optional.of("v1.4.2")));
    }

    @Test
    void aFirstReleaseHasNothingToCompare() {
        assertEquals(List.of("java", "-jar", "cli-all.jar", "plugin", "validate", "botmaker-sdk"),
                SdkGates.validateCommand(JAR, SDK, Optional.empty()));
    }
}
