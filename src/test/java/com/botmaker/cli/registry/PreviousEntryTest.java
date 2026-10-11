package com.botmaker.cli.registry;

import com.botmaker.cli.release.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The release an updated entry is compared against: the one the registry holds at the pull request's base, read
 * from git, so {@code managed-history} sees what every bot on the registered release holds.
 */
class PreviousEntryTest {

    @Test
    void an_entry_at_the_base_names_its_verified_release(@TempDir Path root) throws IOException {
        String base = commit(root, """
                {"id":"com.example.good","coordinate":"com.example:good","verifiedVersion":"0.1.0","repo":"e/good"}
                """);

        assertEquals("com.example:good:0.1.0",
                RegistryGate.previousCoordinate(root, base, "plugins/com.example.good.json"));
    }

    @Test
    void a_new_entry_has_no_previous_release(@TempDir Path root) throws IOException {
        String base = commit(root, """
                {"id":"com.example.good","coordinate":"com.example:good","verifiedVersion":"0.1.0","repo":"e/good"}
                """);

        assertNull(RegistryGate.previousCoordinate(root, base, "plugins/com.example.other.json"));
    }

    @Test
    void no_base_compares_nothing(@TempDir Path root) {
        assertNull(RegistryGate.previousCoordinate(root, null, "plugins/com.example.good.json"));
        assertNull(RegistryGate.previousCoordinate(root, " ", "plugins/com.example.good.json"));
    }

    /** A repository holding {@code plugins/com.example.good.json} as {@code json}; answers the commit. */
    private static String commit(Path root, String json) throws IOException {
        assumeTrue(Git.run(root, "init", "-q").ok(), "no git");
        Files.createDirectories(root.resolve("plugins"));
        Files.writeString(root.resolve("plugins/com.example.good.json"), json);
        Git.run(root, "add", ".");
        Git.run(root, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "entry");
        return Git.capture(root, "rev-parse", "HEAD").orElseThrow();
    }
}
