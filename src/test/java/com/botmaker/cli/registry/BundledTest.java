package com.botmaker.cli.registry;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of the bundled-id reservation that needs no network: how the two sources of a claimed id are
 * combined.
 *
 * <p>Resolving a coordinate is covered end to end rather than here — a unit test of it would be a test of
 * Maven. What matters at this level is that an entry's own id is excluded from the <em>registry's</em>
 * claimed set (re-submitting your own plugin is an update) while a <b>bundled</b> id is never excluded,
 * because taking the host's own plugin id is exactly what must be refused.
 */
class BundledTest {

    @Test
    void a_union_holds_both_sources_and_deduplicates() {
        Set<String> union = Bundled.union(Set.of("a", "shared"), Set.of("shared", "b"));

        assertEquals(Set.of("a", "b", "shared"), union);
    }

    @Test
    void an_empty_side_changes_nothing() {
        assertEquals(Set.of("a"), Bundled.union(Set.of("a"), Set.of()));
        assertEquals(Set.of("a"), Bundled.union(Set.of(), Set.of("a")));
        assertTrue(Bundled.union(Set.of(), Set.of()).isEmpty());
    }

    @Test
    void nothing_is_reserved_when_no_coordinates_are_named() {
        assertTrue(Bundled.none().pluginIds().isEmpty());
    }
}
