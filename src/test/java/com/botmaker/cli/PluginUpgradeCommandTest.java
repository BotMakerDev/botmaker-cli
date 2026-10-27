package com.botmaker.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code botmaker plugin upgrade}'s refusals, its no-op and the move, with {@code --to} so GitHub is not asked.
 */
class PluginUpgradeCommandTest {

    @TempDir
    Path dir;

    private int upgrade(String... args) {
        String[] argv = new String[args.length + 4];
        argv[0] = "plugin";
        argv[1] = "upgrade";
        argv[2] = "--dir";
        argv[3] = dir.toString();
        System.arraycopy(args, 0, argv, 4, args.length);
        return Main.run(argv);
    }

    @Test
    void a_pom_on_another_parent_is_refused() throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project><parent><groupId>org.apache</groupId>"
                + "<artifactId>apache</artifactId><version>30</version></parent><artifactId>x</artifactId></project>");
        assertEquals(1, upgrade("--to", "v0.2.0"));
    }

    @Test
    void no_pom_is_refused() {
        assertEquals(1, upgrade("--to", "v0.2.0"));
    }

    @Test
    void the_parent_already_named_changes_nothing() throws IOException {
        String pom = "<project><parent><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-plugin-parent</artifactId><version>v0.2.0</version></parent>"
                + "<artifactId>x</artifactId></project>";
        Files.writeString(dir.resolve("pom.xml"), pom);
        assertEquals(0, upgrade("--to", "v0.2.0"));
        assertEquals(pom, Files.readString(dir.resolve("pom.xml")));
    }

    @Test
    void a_snapshot_parent_no_repository_serves_moves_to_the_tag() throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project><parent><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-plugin-parent</artifactId>\n  <version>0.1.0-SNAPSHOT</version>"
                + "<relativePath/></parent><artifactId>x</artifactId></project>");
        upgrade("--to", "v0.2.0");   // the check after the move fails: there is no plugin here
        assertEquals("<project><parent><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-plugin-parent</artifactId>\n  <version>v0.2.0</version>"
                + "<relativePath/></parent><artifactId>x</artifactId></project>",
                Files.readString(dir.resolve("pom.xml")));
    }

    @Test
    void a_tag_that_is_not_one_is_refused() throws IOException {
        String pom = "<project><parent><groupId>com.github.BotMakerDev</groupId>"
                + "<artifactId>botmaker-plugin-parent</artifactId><version>v0.1.0</version></parent>"
                + "<artifactId>x</artifactId></project>";
        Files.writeString(dir.resolve("pom.xml"), pom);
        assertEquals(1, upgrade("--to", "v0.2.0</version>"));
        assertEquals(pom, Files.readString(dir.resolve("pom.xml")));
    }
}
