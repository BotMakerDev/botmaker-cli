package com.botmaker.cli.gallery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a passing gallery pull request is merged by nobody, later, or by a maintainer. */
class ListingPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    private static ListingPolicy.Change added(String owner) {
        return new ListingPolicy.Change("bots/" + owner + "-bot" + System.nanoTime() + ".json", "added", owner);
    }

    private static ListingPolicy.Facts facts(boolean maintainer, List<ListingPolicy.Change> changes,
                                             Instant... recent) {
        return new ListingPolicy.Facts("alice", maintainer, true, NOW, changes, List.of(recent));
    }

    private static ListingPolicy.Kind decide(ListingPolicy.Facts facts) {
        return ListingPolicy.decide(facts).decision();
    }

    @Test
    void a_passing_new_listing_within_the_limit_merges() {
        assertEquals(ListingPolicy.Kind.MERGE, decide(facts(false, List.of(added("alice")),
                NOW.minusSeconds(3600), NOW.minusSeconds(7200))));
    }

    @Test
    void failed_checks_hold() {
        assertEquals(ListingPolicy.Kind.HOLD, decide(new ListingPolicy.Facts("alice", false, false, NOW,
                List.of(added("alice")), List.of())));
    }

    @Test
    void over_the_limit_waits_until_enough_earlier_listings_leave_the_window() {
        Instant oldest = NOW.minusSeconds(20 * 3600);
        ListingPolicy.Decision decision = ListingPolicy.decide(facts(false, List.of(added("alice")),
                NOW.minusSeconds(3600), oldest, NOW.minusSeconds(10 * 3600)));

        assertEquals(ListingPolicy.Kind.WAIT, decision.decision());
        assertEquals(oldest.plus(ListingPolicy.WINDOW), decision.retryAfter().orElseThrow());
    }

    @Test
    void listings_older_than_a_day_do_not_count() {
        Instant old = NOW.minusSeconds(25 * 3600);
        assertEquals(ListingPolicy.Kind.MERGE, decide(facts(false, List.of(added("alice")), old, old, old, old)));
    }

    @Test
    void updating_your_own_listing_is_never_limited() {
        ListingPolicy.Change update = new ListingPolicy.Change("bots/alice-farm.json", "modified", "alice");
        Instant recent = NOW.minusSeconds(60);
        assertEquals(ListingPolicy.Kind.MERGE, decide(facts(false, List.of(update), recent, recent, recent, recent)));
    }

    @Test
    void more_new_bots_at_once_than_a_day_allows_goes_to_a_maintainer() {
        assertEquals(ListingPolicy.Kind.MANUAL, decide(facts(false,
                List.of(added("alice"), added("alice"), added("alice"), added("alice")))));
    }

    @Test
    void anything_outside_an_authors_entries_goes_to_a_maintainer() {
        for (String path : List.of("vetted/alice-farm.json", "README.md", ".github/workflows/index.yml",
                "bots/nested/alice-farm.json")) {
            assertEquals(ListingPolicy.Kind.MANUAL, decide(facts(false,
                    List.of(new ListingPolicy.Change(path, "modified", "alice")))), path);
        }
    }

    @Test
    void an_entry_the_author_cannot_be_shown_to_own_goes_to_a_maintainer() {
        assertEquals(ListingPolicy.Kind.MANUAL, decide(facts(false, List.of(added("acme")))));
    }

    @Test
    void a_maintainer_merges_uncounted() {
        Instant recent = NOW.minusSeconds(60);
        assertEquals(ListingPolicy.Kind.MERGE, decide(new ListingPolicy.Facts("LiQiyeDev", true, true, NOW,
                List.of(added("someone"), added("someone"), added("someone"), added("someone")),
                List.of(recent, recent, recent))));
    }

    @Test
    void the_workflow_reads_and_writes_plain_json() throws Exception {
        ListingPolicy.Facts facts = ListingPolicy.Facts.read("""
                {"author": "alice", "maintainer": false, "checksPassed": true, "now": "2026-09-16T12:00:00Z",
                 "changes": [{"path": "bots/alice-farm.json", "status": "added", "owner": "alice",
                              "additions": 7}],
                 "recentNewListings": ["2026-09-16T11:00:00Z", "2026-09-16T10:00:00Z", "2026-09-16T09:00:00Z"]}""");
        String json = ListingPolicy.decide(facts).toJson();

        assertTrue(json.contains("\"decision\":\"wait\""), json);
        assertTrue(json.contains("\"retryAfter\":\"2026-09-17T09:00:00Z\""), json);
    }
}
