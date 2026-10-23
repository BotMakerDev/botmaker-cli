package com.botmaker.cli.catalog;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-23T18:00:00Z");
    private static final ListingPolicy.Change NEW_BOT = new ListingPolicy.Change("bots/somedev__firestone.json", "added");

    @Test
    void a_green_single_entry_merges() {
        assertEquals(ListingPolicy.Kind.MERGE, decide(true, List.of(NEW_BOT), List.of()).decision());
        assertEquals(ListingPolicy.Kind.MERGE, decide(true,
                List.of(new ListingPolicy.Change("plugins/com.botmaker.sdk.json", "removed")), List.of()).decision());
    }

    @Test
    void a_red_check_holds() {
        assertEquals(ListingPolicy.Kind.HOLD, decide(false, List.of(NEW_BOT), List.of()).decision());
    }

    @Test
    void anything_but_one_entry_path_holds() {
        assertEquals(ListingPolicy.Kind.HOLD, decide(true, List.of(NEW_BOT,
                new ListingPolicy.Change("bots/somedev__other.json", "added")), List.of()).decision());
        assertEquals(ListingPolicy.Kind.HOLD, decide(true,
                List.of(new ListingPolicy.Change(".github/workflows/index.yml", "modified")), List.of()).decision());
        assertEquals(ListingPolicy.Kind.HOLD, decide(true,
                List.of(new ListingPolicy.Change("bots/somedev__firestone.json", "renamed")), List.of()).decision());
    }

    @Test
    void a_fourth_new_entry_in_a_day_waits_until_the_oldest_leaves_the_window() {
        Instant oldest = NOW.minus(Duration.ofHours(20));
        ListingPolicy.Decision decision = decide(true, List.of(NEW_BOT),
                List.of(NOW.minus(Duration.ofHours(2)), oldest, NOW.minus(Duration.ofHours(5))));
        assertEquals(ListingPolicy.Kind.WAIT, decision.decision());
        assertEquals(Optional.of(oldest.plus(ListingPolicy.WINDOW)), decision.retryAfter());

        assertEquals(ListingPolicy.Kind.MERGE, decide(true, List.of(NEW_BOT),
                List.of(NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(2)))).decision());
    }

    @Test
    void a_copied_entry_counts_as_new() {
        assertEquals(ListingPolicy.Kind.WAIT, decide(true,
                List.of(new ListingPolicy.Change("bots/somedev__firestone.json", "copied")),
                List.of(NOW, NOW, NOW)).decision());
    }

    @Test
    void an_update_is_never_limited() {
        assertEquals(ListingPolicy.Kind.MERGE, decide(true,
                List.of(new ListingPolicy.Change("bots/somedev__firestone.json", "modified")),
                List.of(NOW, NOW, NOW)).decision());
    }

    @Test
    void the_facts_file_reads_and_the_decision_prints_as_the_workflow_expects() throws IOException {
        ListingPolicy.Facts facts = ListingPolicy.Facts.read("""
                {"author":"somedev","checksPassed":true,"now":"2026-10-23T18:00:00Z",
                 "changes":[{"path":"bots/somedev__firestone.json","status":"added","additions":1}],
                 "recentNewEntries":[]}
                """);
        String json = ListingPolicy.decide(facts).toJson();
        assertTrue(json.startsWith("{\"decision\":\"merge\""), json);
    }

    private static ListingPolicy.Decision decide(boolean green, List<ListingPolicy.Change> changes,
                                                 List<Instant> recent) {
        return ListingPolicy.decide(new ListingPolicy.Facts("somedev", green, NOW, changes, recent));
    }
}
