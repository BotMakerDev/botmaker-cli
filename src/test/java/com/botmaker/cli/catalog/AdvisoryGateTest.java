// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.botmaker.cli.catalog.FakeFacts.botRelease;
import static com.botmaker.cli.catalog.FakeFacts.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog's check on a pull request filing or changing one advisory (spec 2026-11-21 § 5). */
class AdvisoryGateTest {

    private static final long ADMIN = 298085676;
    private static final String FIRST = Advisory.path("BMA-2026-0001");

    @TempDir
    Path head;
    @TempDir
    Path base;

    private final FakeFacts facts = new FakeFacts();

    @BeforeEach
    void catalog() throws IOException {
        for (Path root : List.of(head, base)) {
            Files.writeString(root.resolve(Publishers.FILE), """
                    {"admin":[{"id":298085676,"login":"LiQiyeDev"}],"trusted":[{"id":789,"login":"somedev"}]}""");
            Files.createDirectories(root.resolve("bots"));
            Files.writeString(root.resolve("bots/somedev__firestone.json"), json(new BotEntry("somedev/firestone",
                    List.of(botRelease("v1.0.0"), botRelease("v1.1.0"), botRelease("v1.2.0")), false,
                    List.of("olddev/firestone"))));
            Files.createDirectories(root.resolve(Advisory.DIRECTORY));
        }
    }

    private static Advisory advisory(String id, String repo, List<String> releases) {
        return new Advisory(id, repo, releases, Advisory.Severity.HIGH, "Sends the account's token to a third party.",
                "https://example.org/bma", "2026-11-21", null);
    }

    private void put(Path root, Advisory advisory) throws IOException {
        Files.writeString(root.resolve(Advisory.path(advisory.id())), advisory.toJson());
    }

    private List<Finding> check(long author, String... changed) {
        return EntryGate.check(new EntryGate.Request(head, Optional.of(base), List.of(changed), "someone", author),
                facts);
    }

    private static void assertRefused(List<Finding> findings, String expected) {
        assertTrue(Finding.refused(findings), "refused: " + findings);
        assertTrue(findings.stream().anyMatch(f -> f.message().contains(expected)),
                "a finding saying \"" + expected + "\": " + findings);
    }

    @Test
    void adminCreates() throws IOException {
        put(head, advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.1.0")));
        assertEquals(List.of(), check(ADMIN, FIRST));
    }

    @Test
    void nonAdminRefused() throws IOException {
        put(head, advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.1.0")));
        assertRefused(check(789, FIRST), "only an admin");
    }

    @Test
    void idMatchesFile() throws IOException {
        Files.writeString(head.resolve(FIRST), advisory("BMA-2026-0002", "somedev/firestone", List.of("v1.1.0"))
                .toJson());
        assertRefused(check(ADMIN, FIRST), "file name is the advisory's id");
    }

    @Test
    void idSequential() throws IOException {
        put(head, advisory("BMA-2026-0002", "somedev/firestone", List.of("v1.1.0")));
        assertRefused(check(ADMIN, Advisory.path("BMA-2026-0002")), "BMA-2026-0001");
        // A withdrawn advisory still holds its number.
        Advisory first = advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.0.0")).withdraw("a mistake");
        put(base, first);
        put(head, first);
        assertEquals(List.of(), check(ADMIN, Advisory.path("BMA-2026-0002")));
        Files.writeString(head.resolve(Advisory.path("BMA-2025-0001")), new Advisory("BMA-2025-0001",
                "somedev/firestone", List.of("v1.0.0"), Advisory.Severity.LOW, "x", null, "2026-11-21", null).toJson());
        assertRefused(check(ADMIN, Advisory.path("BMA-2025-0001")), "year it was published");
    }

    @Test
    void unknownRepoRefused() throws IOException {
        put(head, advisory("BMA-2026-0001", "nobody/nothing", List.of("v1.1.0")));
        assertRefused(check(ADMIN, FIRST), "no entry the catalog lists");
    }

    @Test
    void formerSlugAccepted() throws IOException {
        put(head, advisory("BMA-2026-0001", "OldDev/Firestone", List.of("v1.1.0")));
        assertEquals(List.of(), check(ADMIN, FIRST));
    }

    @Test
    void unknownTagRefused() throws IOException {
        put(head, advisory("BMA-2026-0001", "somedev/firestone", List.of("v9.0.0")));
        assertRefused(check(ADMIN, FIRST), "no approved release v9.0.0");
    }

    @Test
    void extendReleases() throws IOException {
        Advisory held = advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.0.0"));
        put(base, held);
        put(head, held.extend(List.of("v1.1.0")));
        assertEquals(List.of(), check(ADMIN, FIRST));
        put(head, held.extend(List.of("v7.0.0")));
        assertRefused(check(ADMIN, FIRST), "no approved release v7.0.0");
        put(head, new Advisory(held.id(), held.repo(), List.of("v1.1.0"), held.severity(), held.summary(),
                held.url(), held.published(), null));
        assertRefused(check(ADMIN, FIRST), "appends releases");
    }

    @Test
    void editSummaryRefused() throws IOException {
        Advisory held = advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.0.0"));
        put(base, held);
        put(head, new Advisory(held.id(), held.repo(), held.releases(), Advisory.Severity.LOW, "Something else.",
                held.url(), held.published(), null));
        assertRefused(check(ADMIN, FIRST), "nothing else of it is ever edited");
        put(head, held);
        assertRefused(check(ADMIN, FIRST), "changes nothing");
    }

    @Test
    void withdraw() throws IOException {
        Advisory held = advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.0.0"));
        put(base, held);
        put(head, held.withdraw("The token was never sent; a misread log."));
        assertEquals(List.of(), check(ADMIN, FIRST));
        put(base, held.withdraw("gone"));
        put(head, held.withdraw("gone").extend(List.of("v1.1.0")));
        assertRefused(check(ADMIN, FIRST), "is withdrawn");
    }

    @Test
    void deleteRefused() throws IOException {
        put(base, advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.0.0")));
        assertRefused(check(ADMIN, FIRST), "never deleted");
    }

    @Test
    void twoFilesRefused() throws IOException {
        put(head, advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.0.0")));
        put(head, advisory("BMA-2026-0002", "somedev/firestone", List.of("v1.1.0")));
        assertRefused(check(ADMIN, FIRST, Advisory.path("BMA-2026-0002")), "exactly one");
    }

    @Test
    void theKindStepHasNothingToDo() throws IOException {
        put(head, advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.1.0")));
        assertEquals(0, EntryGate.run(new String[]{head.toString(), "--author", "LiQiyeDev", "--author-id",
                String.valueOf(ADMIN), "--base", base.toString(), "--part", "kind", "--", FIRST}, facts));
    }

    @Test
    void badFieldsRefused() throws IOException {
        put(head, new Advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.1.0"), Advisory.Severity.HIGH,
                "x", "http://plain.example", "2026-11-21", null));
        assertRefused(check(ADMIN, FIRST), "https");
        Files.writeString(head.resolve(FIRST), advisory("BMA-2026-0001", "somedev/firestone", List.of("v1.1.0"))
                .toJson().replace("\"high\"", "\"dire\""));
        assertRefused(check(ADMIN, FIRST), "severity");
    }
}
