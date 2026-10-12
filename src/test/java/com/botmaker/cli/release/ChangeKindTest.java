package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeKindTest {

    @Test
    void markdownIsIrrelevantAtEveryDepthIncludingUnderDotGithub() {
        assertTrue(Relevance.irrelevant("CHANGELOG.md"));
        assertTrue(Relevance.irrelevant("docs/refactor/21-api-compat.md"));
        assertTrue(Relevance.irrelevant(".github/README.md"));
        // The scripts and workflows beside it build the artifact, so they are not.
        assertFalse(Relevance.irrelevant(".github/workflows/ci.yml"));
        assertFalse(Relevance.irrelevant("tools/changelog-section.sh"));
    }

    @Test
    void theDenyListIsWhatTheScriptListsAndNothingMore() {
        assertTrue(Relevance.irrelevant(".editorconfig"));
        assertTrue(Relevance.irrelevant(".gitignore"));
        assertTrue(Relevance.irrelevant(".gitattributes"));
        assertTrue(Relevance.irrelevant(".idea/inspectionProfiles/Project_Default.xml"));
        // Unclassified is RELEASE-RELEVANT, deliberately: the harmless direction to be wrong in is a
        // duplicate artifact, never a release that omits the change it was cut for.
        assertFalse(Relevance.irrelevant("pom.xml"));
        assertFalse(Relevance.irrelevant("jitpack.yml"));
        assertFalse(Relevance.irrelevant("src/main/java/com/botmaker/cli/Main.java"));
        assertFalse(Relevance.irrelevant(".deps.env"));
    }

    @Test
    void oneRelevantFileAmongDocsIsStillARealChange() {
        assertEquals(ChangeKind.REAL,
                ChangeKind.classify(List.of("CHANGELOG.md", "README.md", "src/main/java/A.java")));
        assertEquals(ChangeKind.DOCS, ChangeKind.classify(List.of("CHANGELOG.md", "ROADMAP.md")));
        assertEquals(ChangeKind.NONE, ChangeKind.classify(List.of()));
    }

    /**
     * Dashboard v0.3.1 pinned shared v0.2.4, a tag JitPack never built; after shared v0.2.5 its only commit was a
     * changelog, and it was skipped as identical (2026-10-12). Its next tag pins v0.2.5: another artifact.
     */
    @Test
    void aPinThatWouldMoveIsARealChangeWhateverTheCommitsAre() {
        Map<Module, Optional<Version>> tagged = Map.of(
                Module.SHARED, Version.parse("v0.2.4"), Module.CLI, Version.parse("v0.3.3"));
        assertTrue(ChangeKind.pinsMove(tagged, Map.of(
                Module.SHARED, Version.parse("v0.2.5"), Module.CLI, Version.parse("0.3.3"))));
        assertFalse(ChangeKind.pinsMove(tagged, Map.of(
                Module.SHARED, Version.parse("0.2.4"), Module.CLI, Version.parse("v0.3.3"))),
                "the same release spelled with or without its v is the same pin");
        assertFalse(ChangeKind.pinsMove(Map.of(Module.SHARED, Optional.empty()),
                Map.of(Module.SHARED, Version.parse("v0.2.5"))), "a pin that is no version moves nothing");
        assertFalse(ChangeKind.pinsMove(tagged, Map.of()), "an upstream never released moves nothing");
    }

    @Test
    void theSdksChangelogIsDocsEvenThoughItsJarCarriesIt() {
        // botmaker-sdk/pom.xml copies the whole CHANGELOG.md into the jar as
        // META-INF/botmaker/whats-new.md, so editing it does change the artifact. It stays excluded: a
        // release authors its own section, so counting it would make every release justify itself.
        assertEquals(ChangeKind.DOCS, ChangeKind.classify(List.of("CHANGELOG.md")));
    }
}
