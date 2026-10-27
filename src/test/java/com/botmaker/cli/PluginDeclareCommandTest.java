package com.botmaker.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code botmaker plugin declare}: which class it names, and the line it adds to a pom that lacks one. */
class PluginDeclareCommandTest {

    @Test
    void the_one_candidate_is_chosen() {
        assertEquals(new PluginDeclareCommand.Choice.Chosen("a.Plugin"),
                PluginDeclareCommand.choose(List.of("a.Plugin"), null));
    }

    @Test
    void several_candidates_need_one_named() {
        var refused = assertInstanceOf(PluginDeclareCommand.Choice.Refused.class,
                PluginDeclareCommand.choose(List.of("a.One", "a.Two"), null));
        assertTrue(refused.why().contains("a.One, a.Two"), refused.why());
        assertEquals(new PluginDeclareCommand.Choice.Chosen("a.Two"),
                PluginDeclareCommand.choose(List.of("a.One", "a.Two"), "a.Two"));
    }

    @Test
    void a_named_class_that_is_no_candidate_is_refused() {
        var refused = assertInstanceOf(PluginDeclareCommand.Choice.Refused.class,
                PluginDeclareCommand.choose(List.of("a.Plugin"), "a.Other"));
        assertTrue(refused.why().contains("these are: a.Plugin"), refused.why());
        assertInstanceOf(PluginDeclareCommand.Choice.Refused.class, PluginDeclareCommand.choose(List.of(), null));
    }

    @Test
    void the_property_goes_first_in_the_properties_block() {
        String pom = """
                <project>
                    <!-- keeps <properties> -->
                    <properties>
                        <java.version>25</java.version>
                    </properties>
                </project>
                """;
        assertEquals("""
                <project>
                    <!-- keeps <properties> -->
                    <properties>
                        <botmaker.plugin>a.Plugin</botmaker.plugin>
                        <java.version>25</java.version>
                    </properties>
                </project>
                """, PluginDeclareCommand.inserted(pom, "a.Plugin"));
    }

    /** A profile's properties apply only while it is active, so the project's own block is made instead. */
    @Test
    void a_profile_is_never_where_the_property_goes() {
        String pom = """
                <project>
                    <dependencies>
                    </dependencies>
                    <profiles>
                        <profile>
                            <properties>
                            </properties>
                        </profile>
                    </profiles>
                </project>
                """;
        String written = PluginDeclareCommand.inserted(pom, "a.Plugin");
        assertTrue(written.indexOf("<botmaker.plugin>") < written.indexOf("<dependencies>"), written);
        assertThrows(IllegalStateException.class, () -> PluginDeclareCommand.inserted(
                "<project>\n    <properties/>\n    <dependencies>\n    </dependencies>\n</project>\n", "a.Plugin"));
    }

    @Test
    void a_pom_without_properties_gets_a_block() {
        String pom = """
                <project>
                    <artifactId>x</artifactId>

                    <dependencies>
                    </dependencies>
                </project>
                """;
        assertEquals("""
                <project>
                    <artifactId>x</artifactId>

                    <properties>
                        <botmaker.plugin>a.Plugin</botmaker.plugin>
                    </properties>

                    <dependencies>
                    </dependencies>
                </project>
                """, PluginDeclareCommand.inserted(pom, "a.Plugin"));
    }
}
