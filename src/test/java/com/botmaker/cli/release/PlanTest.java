package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTest {

    @Test
    void everyModuleHasALabelToPrintItselfWith() {
        // The plan block is `LABEL.get(module)`, so a module added to the enum and not to the map prints
        // `null: 0.3.0 -> v0.3.0` — which is what a module added on 2026-09-21 did.
        assertEquals(EnumSet.allOf(Module.class), EnumSet.copyOf(Plan.LABEL.keySet()));
        for (Module module : Module.values()) {
            String label = Plan.LABEL.get(module);
            assertFalse(label.isBlank(), module + " has a blank label");
            // A label is the short name or a shortening of it — `plugin-toolkit` is printed `toolkit`,
            // because the block reads down a column and the prefix is the same for four of them.
            assertTrue(module.shortName().endsWith(label.strip()),
                    module + " is labelled " + label.strip());
        }
    }

    @Test
    void theLabelsInOneDecideGroupAreOneColumnWide() {
        // The padding is what aligns the colons in the printed block; it is per group of neighbours in
        // Order.DECIDE rather than global.
        assertEquals(Plan.LABEL.get(Module.DASHBOARD).length(), Plan.LABEL.get(Module.REMOTE).length());
        assertEquals(Plan.LABEL.get(Module.REMOTE_SERVER).length(), Plan.LABEL.get(Module.REMOTE).length());
    }
}
