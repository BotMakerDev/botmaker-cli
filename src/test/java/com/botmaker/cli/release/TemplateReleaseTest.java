package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A template's pushed tag becomes the GitHub Release New Project copies. */
class TemplateReleaseTest {

    @Test
    void theReleaseIsTheTagOnTheTemplatesOwnRepository() {
        List<String> argv = TemplateRelease.command(Module.GAMEBOT, new Version(0, 2, 8));

        assertEquals(List.of("gh", "release", "create", "v0.2.8", "--repo", "BotMakerDev/botmaker-gamebot",
                "--verify-tag", "--title", "v0.2.8"), argv.subList(0, 9));
    }

    @Test
    void aDryRunEchoesTheCommandAndWarnsNothing() {
        List<String> said = new ArrayList<>();
        TemplateRelease.publish(new Runner(true, said::add), Module.GAMEBOT, new Version(0, 2, 8));

        assertEquals(1, said.size(), said.toString());
        assertTrue(said.getFirst().startsWith("    $ gh release create v0.2.8 --repo BotMakerDev/botmaker-gamebot"),
                said.getFirst());
    }
}
