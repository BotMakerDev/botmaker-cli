package com.botmaker.cli.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishersCheckTest {

    private static final long ADMIN = 298085676;
    private static final String BASE = """
            {"admin":[{"id":298085676,"login":"LiQiyeDev"}],"trusted":[]}""";

    @TempDir
    Path head;
    @TempDir
    Path base;

    private final FakeFacts facts = new FakeFacts();

    @BeforeEach
    void catalog() throws IOException {
        Files.writeString(base.resolve(Publishers.FILE), BASE);
    }

    @Test
    void onlyAnAdminChangesPublishers() throws IOException {
        Files.writeString(head.resolve(Publishers.FILE), """
                {"admin":[{"id":298085676,"login":"LiQiyeDev"}],"trusted":[{"id":789,"login":"somedev"}]}""");
        assertEquals(List.of(), check(ADMIN, Publishers.FILE));
        assertRefused(check(789, Publishers.FILE), "only an admin");
        assertRefused(check(0, Publishers.FILE), "only an admin");
    }

    @Test
    void theLastAdminCannotBeRemoved() throws IOException {
        Files.writeString(head.resolve(Publishers.FILE), """
                {"admin":[],"trusted":[{"id":298085676,"login":"LiQiyeDev"}]}""");
        assertRefused(check(ADMIN, Publishers.FILE), "at least one admin");
        Files.delete(head.resolve(Publishers.FILE));
        assertRefused(check(ADMIN, Publishers.FILE), "cannot be removed");
    }

    @Test
    void anIdTwiceIsRefused() throws IOException {
        Files.writeString(head.resolve(Publishers.FILE), """
                {"admin":[{"id":298085676,"login":"LiQiyeDev"}],"trusted":[{"id":298085676,"login":"again"}]}""");
        assertRefused(check(ADMIN, Publishers.FILE), "more than once");
        Files.writeString(head.resolve(Publishers.FILE), """
                {"admin":[{"id":298085676,"login":"LiQiyeDev"}],"trusted":[{"id":0,"login":"nobody"}]}""");
        assertRefused(check(ADMIN, Publishers.FILE), "account id");
        Files.writeString(head.resolve(Publishers.FILE), """
                {"admin":[{"id":298085676,"login":"LiQiyeDev","rank":"x"}],"trusted":[]}""");
        assertRefused(check(ADMIN, Publishers.FILE), "schema");
    }

    @Test
    void publishersBesideAnEntryIsRefused() throws IOException {
        Files.writeString(head.resolve(Publishers.FILE), BASE);
        assertRefused(check(ADMIN, Publishers.FILE, "bots/somedev__firestone.json"), "exactly one");
    }

    @Test
    void theKindStepHasNothingToDoForPublishers() throws IOException {
        Files.writeString(head.resolve(Publishers.FILE), BASE);
        assertEquals(0, EntryGate.run(new String[]{head.toString(), "--author", "LiQiyeDev", "--author-id",
                String.valueOf(ADMIN), "--base", base.toString(), "--part", "kind", "--", Publishers.FILE}, facts));
        assertEquals(0, EntryGate.run(new String[]{head.toString(), "--author", "LiQiyeDev", "--author-id",
                String.valueOf(ADMIN), "--base", base.toString(), "--", Publishers.FILE}, facts));
        assertEquals(2, EntryGate.run(new String[]{head.toString(), "--author", "LiQiyeDev", "--author-id",
                "twelve", "--base", base.toString(), "--", Publishers.FILE}, facts), "an id that is no number");
    }

    private List<Finding> check(long authorId, String... changed) {
        return EntryGate.check(new EntryGate.Request(head, Optional.of(base), List.of(changed), "someone", authorId),
                facts);
    }

    private static void assertRefused(List<Finding> findings, String expected) {
        assertTrue(Finding.refused(findings), "refused: " + findings);
        assertTrue(findings.stream().anyMatch(f -> f.message().contains(expected)),
                "a finding saying \"" + expected + "\": " + findings);
    }
}
