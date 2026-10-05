package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryPinTest {

    /** The SDK's entry as the registry holds it, spacing included. */
    private static final String ENTRY = """
            {
              "id" : "com.botmaker.sdk",
              "minContractVersion" : "v0.0.4",
              "verifiedVersion" : "v1.1.7",
              "verifiedAt" : "2026-09-17"
            }
            """;

    @Test
    void theFieldPatternRewritesTheValueAndNothingElse() {
        Matcher match = RegistryPin.field("verifiedVersion").matcher(ENTRY);

        String after = match.replaceFirst("$1v1.2.3$2");

        assertTrue(after.contains("\"verifiedVersion\" : \"v1.2.3\""), after);
        assertTrue(after.contains("\"minContractVersion\" : \"v0.0.4\""), after);
        assertTrue(after.contains("\"verifiedAt\" : \"2026-09-17\""), after);
    }

    @Test
    void aDryRunOpensOnePullRequestPerRegisteredPlugin(@TempDir Path work) {
        List<String> out = new ArrayList<>();

        boolean ok = RegistryPin.bump(new Runner(true, out::add), work,
                Map.of(Module.SDK, new Version(2, 0, 0), Module.PLUGIN_BASICS, new Version(0, 1, 2),
                        Module.STUDIO, new Version(1, 0, 40)),
                LocalDate.of(2026, 10, 5));

        assertTrue(ok);
        List<String> prs = out.stream().filter(line -> line.contains("gh pr create")).toList();
        assertEquals(2, prs.size(), String.join("\n", out));
        assertTrue(out.stream().anyMatch(line -> line.contains("verify-com-botmaker-sdk-v2.0.0")));
        assertTrue(out.stream().anyMatch(line -> line.contains("verify-com-botmaker-basics-v0.1.2")));
        assertFalse(out.stream().anyMatch(line -> line.contains("studio")), "Studio has no registry entry");
    }

    @Test
    void aReleaseWithoutAPluginOpensNothing(@TempDir Path work) {
        List<String> out = new ArrayList<>();

        assertTrue(RegistryPin.bump(new Runner(true, out::add), work,
                Map.of(Module.STUDIO, new Version(1, 0, 40)), LocalDate.of(2026, 10, 5)));
        assertTrue(out.isEmpty(), String.join("\n", out));
    }
}
