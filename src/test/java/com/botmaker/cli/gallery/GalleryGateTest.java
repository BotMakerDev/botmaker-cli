package com.botmaker.cli.gallery;

import com.botmaker.cli.registry.RegistryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate, rule by rule. Since auto-merge nothing reads a gallery pull request after this does, so each
 * rule here is a thing a maintainer's eye used to catch.
 */
class GalleryGateTest {

    @TempDir
    Path head;

    @TempDir
    Path base;

    /** A network where every repository has release v1 and it downloads, unless told otherwise. */
    private static final class Fake implements GalleryFacts {
        Optional<String> tag = Optional.of("v1");
        boolean archive = true;
        Optional<List<RegistryEntry>> registry = Optional.of(List.of(new RegistryEntry("com.botmaker.sdk", "SDK",
                "com.github.LiQiyeDev:botmaker-sdk", "LiQiyeDev/botmaker-sdk", "", List.of(), "", List.of(),
                List.of(), "v1.1.7", "")));
        boolean offline;

        @Override
        public Optional<String> latestReleaseTag(String owner, String repo) throws IOException {
            if (offline) {
                throw new IOException("offline");
            }
            return tag;
        }

        @Override
        public boolean archiveExists(String owner, String repo, String tag) {
            return archive;
        }

        @Override
        public Optional<List<RegistryEntry>> registry() {
            return registry;
        }

        @Override
        public Optional<GalleryEntry> listedEntry(String path) {
            return Optional.empty();
        }
    }

    private final Fake facts = new Fake();

    private static void write(Path root, String path, String json) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private static String entry(String owner, String repo) {
        return """
                {"schemaVersion": 2, "name": "Bot", "owner": "%s", "repo": "%s"}""".formatted(owner, repo);
    }

    private List<GalleryGate.Finding> check(String author, boolean maintainer, String... changed) {
        return GalleryGate.check(new GalleryGate.Request(head, Optional.of(base), List.of(changed), author,
                maintainer), facts);
    }

    private static long errors(List<GalleryGate.Finding> findings) {
        return findings.stream().filter(f -> f.severity() == GalleryGate.Severity.ERROR).count();
    }

    private static long warnings(List<GalleryGate.Finding> findings) {
        return findings.stream().filter(f -> f.severity() == GalleryGate.Severity.WARNING).count();
    }

    @Test
    void an_owners_new_entry_with_a_downloadable_release_passes_clean() throws Exception {
        write(head, "bots/alice-farm.json", entry("alice", "farm"));
        assertEquals(List.of(), check("Alice", false, "bots/alice-farm.json"));
    }

    @Test
    void a_version_one_entry_from_a_shipped_studio_still_passes() throws Exception {
        write(head, "bots/alice-farm.json", """
                {"name": "farm", "owner": "alice", "repo": "farm", "description": "", "tags": []}""");
        assertEquals(List.of(), check("alice", false, "bots/alice-farm.json"));
    }

    @Test
    void the_generated_files_are_never_edited() {
        assertEquals(1, errors(check("alice", false, "index.json")));
        assertEquals(1, errors(check("LiQiyeDev", true, "catalog.json")), "not even by a maintainer");
    }

    @Test
    void only_a_maintainer_changes_vetted() throws Exception {
        write(head, "bots/alice-farm.json", entry("alice", "farm"));
        write(head, "vetted/alice-farm.json", """
                {"owner": "alice", "repo": "farm", "vettedVersion": "v1"}""");

        assertEquals(1, errors(check("alice", false, "vetted/alice-farm.json")), "an author cannot vet themselves");
        assertEquals(0, errors(check("LiQiyeDev", true, "vetted/alice-farm.json")));
    }

    @Test
    void a_vetted_record_names_an_existing_bot_and_a_release_that_downloads() throws Exception {
        write(head, "vetted/alice-farm.json", """
                {"owner": "alice", "repo": "farm", "vettedVersion": "v9"}""");
        facts.archive = false;
        assertEquals(2, errors(check("LiQiyeDev", true, "vetted/alice-farm.json")));
    }

    @Test
    void the_filename_is_the_owner_repo() throws Exception {
        write(head, "bots/alice-other.json", entry("alice", "farm"));
        assertEquals(1, errors(check("alice", false, "bots/alice-other.json")));
    }

    @Test
    void a_schema_newer_than_the_gate_is_refused_rather_than_read_lossily() throws Exception {
        write(head, "bots/alice-farm.json", """
                {"schemaVersion": 99, "name": "Bot", "owner": "alice", "repo": "farm"}""");
        assertEquals(1, errors(check("alice", false, "bots/alice-farm.json")));
    }

    /**
     * {@code a-b-c.json} is both {@code a-b/c} and {@code a/b-c}: the head copy says whatever the pull request
     * wants, so ownership of an existing listing is read from the base.
     */
    @Test
    void an_existing_listing_is_changed_only_by_the_owner_the_base_names() throws Exception {
        write(base, "bots/a-b-c.json", entry("a-b", "c"));
        write(head, "bots/a-b-c.json", entry("a", "b-c"));

        assertEquals(1, errors(check("a", false, "bots/a-b-c.json")));
        assertEquals(0, errors(check("a-b", false, "bots/a-b-c.json")), "the real owner may re-publish");
    }

    @Test
    void a_delisting_is_the_owners() throws Exception {
        write(base, "bots/alice-farm.json", entry("alice", "farm"));

        assertEquals(1, errors(check("mallory", false, "bots/alice-farm.json")));
        assertEquals(0, errors(check("alice", false, "bots/alice-farm.json")));
        assertEquals(0, errors(check("LiQiyeDev", true, "bots/alice-farm.json")));
    }

    @Test
    void a_new_entry_for_a_repository_the_author_is_not_is_a_warning_for_a_maintainer() throws Exception {
        write(head, "bots/acme-farm.json", entry("acme", "farm"));
        List<GalleryGate.Finding> findings = check("alice", false, "bots/acme-farm.json");
        assertEquals(0, errors(findings));
        assertEquals(1, warnings(findings));
    }

    @Test
    void no_release_or_an_archive_that_does_not_download_is_refused() throws Exception {
        write(head, "bots/alice-farm.json", entry("alice", "farm"));

        facts.tag = Optional.empty();
        assertEquals(1, errors(check("alice", false, "bots/alice-farm.json")));
        facts.tag = Optional.of("v1");
        facts.archive = false;
        assertEquals(1, errors(check("alice", false, "bots/alice-farm.json")));
        facts.archive = true;
        facts.offline = true;
        assertEquals(1, errors(check("alice", false, "bots/alice-farm.json")), "unreachable is not a pass");
    }

    @Test
    void requiring_an_unregistered_plugin_is_a_warning_and_an_unreadable_registry_says_nothing() throws Exception {
        write(head, "bots/alice-farm.json", """
                {"schemaVersion": 2, "name": "Bot", "owner": "alice", "repo": "farm",
                 "requires": [{"id": "com.botmaker.sdk", "version": "v1"}, {"id": "com.nobody.plugin"}]}""");
        List<GalleryGate.Finding> findings = check("alice", false, "bots/alice-farm.json");
        assertEquals(0, errors(findings));
        assertEquals(1, warnings(findings));

        facts.registry = Optional.empty();
        assertEquals(List.of(), check("alice", false, "bots/alice-farm.json"));
    }

    @Test
    void without_a_base_tree_an_author_cannot_be_told_from_a_hijacker() throws Exception {
        write(head, "bots/alice-farm.json", entry("alice", "farm"));
        List<GalleryGate.Finding> findings = GalleryGate.check(new GalleryGate.Request(head, Optional.empty(),
                List.of("bots/alice-farm.json"), "alice", false), facts);
        assertEquals(1, errors(findings));
    }

    @Test
    void other_files_are_not_the_gates_business() {
        assertEquals(List.of(), check("alice", false, "README.md"));
    }

    @Test
    void the_command_line_reads_paths_from_a_file_and_exits_with_the_verdict() throws Exception {
        write(head, "bots/alice-farm.json", entry("alice", "farm"));
        Path list = Files.createTempFile("changed", ".txt");
        Files.writeString(list, "bots/alice-farm.json\n\n");

        assertEquals(0, GalleryGate.run(new String[]{head.toString(), "--base", base.toString(), "--author", "alice",
                "@" + list}, facts));
        assertEquals(1, GalleryGate.run(new String[]{head.toString(), "--base", base.toString(), "--author",
                "mallory", "index.json"}, facts));
        assertEquals(2, GalleryGate.run(new String[]{head.toString()}, facts));
    }

    @Test
    void ownership_ignores_case_and_refuses_a_blank_author() {
        assertTrue(GalleryGate.ownedBy("LIQIYEDEV", "LiQiyeDev"));
        assertFalse(GalleryGate.ownedBy("", ""));
    }
}
