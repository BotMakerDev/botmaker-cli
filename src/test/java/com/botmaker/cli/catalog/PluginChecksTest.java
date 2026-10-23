package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
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
        List<Finding> findings = PluginChecks.check(new PluginEntry("com.example.thing", "somedev/thing", "v1"), PATH,
                facts, null, Set.of());
        assertTrue(findings.getFirst().message().contains("JitPack does not serve com.github.somedev:thing:v1"),
                findings.toString());
    }
}
