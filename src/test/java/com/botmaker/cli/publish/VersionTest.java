package com.botmaker.cli.publish;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {

    @Test
    void bumpZeroesWhatIsBelowTheLevel() {
        Version v = new Version(1, 2, 3);
        assertEquals("2.0.0", v.bump(Level.MAJOR).toString());
        assertEquals("1.3.0", v.bump(Level.MINOR).toString());
        assertEquals("1.2.4", v.bump(Level.PATCH).toString());
    }

    @Test
    void anEntryWithNoTagBumpsFromZero() {
        assertEquals("0.0.1", Version.ZERO.bump(Level.PATCH).toString());
        assertEquals("0.1.0", Version.ZERO.bump(Level.MINOR).toString());
    }

    @Test
    void orderingIsByNumberAndNotText() {
        // 1.10.0 is above 1.9.0; every string comparison puts it below, and a bump off the wrong base is a tag.
        assertTrue(Version.parse("1.10.0").orElseThrow()
                .compareTo(Version.parse("1.9.0").orElseThrow()) > 0);
    }

    @Test
    void aLeadingVIsStrippedAndAnythingElseIsNotAVersion() {
        assertEquals(Optional.of(new Version(1, 0, 25)), Version.parse("v1.0.25"));
        assertTrue(Version.parse("1.2").isEmpty());
        assertTrue(Version.parse("1.2.0-rc1").isEmpty());
        assertTrue(Version.parse("demo-2026").isEmpty());
        assertTrue(Version.parse(null).isEmpty());
    }

    @Test
    void theTagCarriesTheVAndTheVersionDoesNot() {
        assertEquals("1.2.0", new Version(1, 2, 0).toString());
        assertEquals("v1.2.0", new Version(1, 2, 0).tag());
    }
}
