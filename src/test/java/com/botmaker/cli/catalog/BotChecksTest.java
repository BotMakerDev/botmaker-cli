package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotChecksTest {

    private static final String PATH = "bots/somedev__firestone.json";
    private static final Map<String, String> CATALOG =
            Map.of(PomFacts.key("com.github.BotMakerDev", "botmaker-sdk"), "com.botmaker.sdk");

    @TempDir
    Path dir;

    @Test
    void a_pom_pinning_a_catalog_plugin_at_a_release_passes() throws IOException {
        assertEquals(List.of(), BotChecks.checkPom(pom("${sdk.version}", "<sdk.version>2.1.0</sdk.version>"),
                PATH, CATALOG));
    }

    @Test
    void a_pom_with_no_catalog_plugin_is_refused() throws IOException {
        List<Finding> findings = BotChecks.checkPom(pom("2.1.0", ""), PATH, Map.of());
        assertTrue(Finding.refused(findings));
        assertTrue(findings.getFirst().message().contains("no plugin the catalog lists"), findings.toString());
    }

    @Test
    void a_snapshot_or_an_unresolved_pin_is_refused() throws IOException {
        for (String version : List.of("2.2.0-SNAPSHOT", "${nowhere}")) {
            List<Finding> findings = BotChecks.checkPom(pom(version, ""), PATH, CATALOG);
            assertTrue(Finding.refused(findings), version);
            assertTrue(findings.getFirst().message().contains("not a released version"), findings.toString());
        }
    }

    @Test
    void no_pom_is_refused() throws IOException {
        assertTrue(Finding.refused(BotChecks.checkPom(dir.resolve("pom.xml"), PATH, CATALOG)));
    }

    @Test
    void the_classes_must_hold_a_main() throws IOException {
        Path classes = dir.resolve("classes");
        compile(classes, "Helper", "public class Helper { static void main(String[] args) {} }");
        List<Finding> findings = BotChecks.checkClasses(classes, PATH);
        assertTrue(Finding.refused(findings), "a main that is not public is no entry point");

        compile(classes, "MyBot", "public class MyBot { public static void main(String[] args) {} }");
        assertEquals(List.of(), BotChecks.checkClasses(classes, PATH));
    }

    private Path pom(String version, String properties) throws IOException {
        return Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.github.somedev</groupId><artifactId>firestone</artifactId><version>1.2.0</version>
                  <properties>%s</properties>
                  <dependencies>
                    <dependency><groupId>com.github.BotMakerDev</groupId><artifactId>botmaker-sdk</artifactId>
                      <version>%s</version></dependency>
                  </dependencies>
                </project>
                """.formatted(properties, version));
    }

    private static void compile(Path out, String name, String source) throws IOException {
        Path src = Files.createTempDirectory("bot-src").resolve(name + ".java");
        Files.writeString(src, source);
        Files.createDirectories(out);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(),
                src.toString()));
    }
}
