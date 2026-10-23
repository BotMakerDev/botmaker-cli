package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginChecksTest {

    private static final String PATH = "plugins/com.example.thing.json";

    @Test
    void the_jar_holds_the_plugin_the_entry_names() {
        assertEquals(List.of(), PluginChecks.idFindings(PATH, "com.example.thing",
                List.of("com.example.thing", "com.botmaker.basics")));
    }

    @Test
    void a_jar_answering_to_another_id_is_refused() {
        List<Finding> findings = PluginChecks.idFindings(PATH, "com.example.thing", List.of("com.example.other"));
        assertTrue(Finding.refused(findings));
        assertTrue(findings.getFirst().message().contains("jar's plugin is com.example.other"), findings.toString());
    }

    @Test
    void what_jitpack_does_not_serve_is_refused_before_anything_downloads() throws IOException {
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "", null);
        List<Finding> findings = PluginChecks.check(THING, THING.newest(), PATH, facts, null, Set.of());
        assertTrue(findings.getFirst().message().contains("JitPack does not serve com.github.somedev:thing:v1"),
                findings.toString());
    }

    @Test
    void a_jar_or_pom_that_changed_after_it_was_pinned_is_refused_before_anything_runs() throws IOException {
        FakeFacts facts = new FakeFacts().repo("somedev/thing", 2, "", null);
        facts.jitpack.add("com.github.somedev:thing:v1");
        assertEquals(List.of(), PluginChecks.pinFindings(PATH, "com.github.somedev:thing", THING.newest(), facts));

        facts.hashes.put("com.github.somedev:thing:jar:v1", FakeFacts.shaOf("rebuilt"));
        List<Finding> findings = PluginChecks.check(THING, THING.newest(), PATH, facts, null, Set.of());
        assertTrue(Finding.refused(findings));
        assertTrue(findings.getFirst().message().contains("the jar JitPack serves for com.github.somedev:thing:v1"
                + " hashes to " + FakeFacts.shaOf("rebuilt")), findings.toString());
    }

    @Test
    void the_jar_maven_resolved_is_the_pinned_one(@TempDir Path m2) throws IOException {
        Path jar = Files.writeString(m2.resolve("thing-v1.jar"), "the jar");
        Files.writeString(m2.resolve("thing-v1.pom"), "the pom");
        ApprovedRelease pinned = new ApprovedRelease("v1", FakeFacts.commitOf("v1"), FakeFacts.shaOf("the jar"),
                FakeFacts.shaOf("the pom"));
        assertEquals(List.of(), PluginChecks.resolvedFindings(PATH, "com.github.somedev:thing", pinned, List.of(jar)));

        Files.writeString(jar, "another jar");
        List<Finding> findings = PluginChecks.resolvedFindings(PATH, "com.github.somedev:thing", pinned, List.of(jar));
        assertTrue(findings.getFirst().message().contains("the jar Maven resolved"), findings.toString());
        assertTrue(Finding.refused(PluginChecks.resolvedFindings(PATH, "com.github.somedev:thing", pinned, List.of())));
    }

    private static final PluginEntry THING = new PluginEntry("com.example.thing", "somedev/thing",
            List.of(FakeFacts.pluginRelease("v1")));
}
