package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Whether a catalog pull request whose check has run is merged now or later (automerge.yml).
 *
 * <p><b>Pure, and handed its facts.</b> The workflow collects them through the API — the author, the changed
 * files, when the author's recent new entries merged — and never checks the pull request's code out, which is what
 * makes running with a write token safe. There is no manual path: whoever controls a repository is proven by the
 * check ({@link EntryGate}), so nothing is left for a person to judge.
 *
 * <ol>
 *   <li>The check has not passed: {@link Kind#HOLD}.</li>
 *   <li>It modifies {@code publishers.json} alone, which the check passes only for an admin
 *       ({@link PublishersCheck}): {@link Kind#MERGE}, never limited, while {@code main}'s copy is still the one
 *       the branch was made on; otherwise {@link Kind#HOLD}.</li>
 *   <li>An entry checked against other ranks than main's, by an author who is no admin on main now:
 *       {@link Kind#HOLD}, since the check may have passed it as a demoted admin's.</li>
 *   <li>The pull request is not exactly one entry path, added, modified or removed (a rename is two): {@link Kind#HOLD},
 *       since it can never pass the check either.</li>
 *   <li>A new entry over {@value #NEW_ENTRIES_PER_DAY} in the author's last 24 hours: {@link Kind#WAIT} until the
 *       oldest falls out of the window.</li>
 *   <li>Otherwise {@link Kind#MERGE}. Only additions count: updating your own entry is never limited.</li>
 * </ol>
 */
public final class ListingPolicy {

    /** New entries one author may have merged in any rolling 24 hours. */
    public static final int NEW_ENTRIES_PER_DAY = 3;

    public static final Duration WINDOW = Duration.ofHours(24);

    public enum Kind { MERGE, WAIT, HOLD }

    /**
     * What to do, why (a sentence the workflow posts verbatim), and for a wait when to look again. {@code announce}
     * marks a hold the author cannot see from the check runs, which the workflow posts as its comment.
     */
    public record Decision(Kind decision, String reason, Optional<Instant> retryAfter, boolean announce) {
        static Decision of(Kind kind, String reason) {
            return new Decision(kind, reason, Optional.empty(), false);
        }

        /**
         * {@code {"decision":"merge","reason":"…","retryAfter":"…","announce":true}}: what the workflow reads with
         * jq; {@code retryAfter} and {@code announce} only when set.
         */
        public String toJson() throws IOException {
            ObjectMapper mapper = CatalogJson.strict().disable(SerializationFeature.INDENT_OUTPUT);
            ObjectNode node = mapper.createObjectNode();
            node.put("decision", decision.name().toLowerCase(Locale.ROOT));
            node.put("reason", reason);
            retryAfter.ifPresent(t -> node.put("retryAfter", t.toString()));
            if (announce) node.put("announce", true);
            return mapper.writeValueAsString(node);
        }
    }

    /** The hold for a pull request checked against a {@code publishers.json} that main no longer has. */
    private static final Decision STALE_PUBLISHERS = new Decision(Kind.HOLD, "publishers.json changed on main after"
            + " this pull request's branch was made, so the check read other ranks than main's. Update the branch"
            + " so the check runs again.", Optional.empty(), true);

    /** One changed file, as GitHub's pull request files API reports it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(String path, String status) {
        /** Only a change to an entry that exists is unlimited; every other status (added, copied, …) is new. */
        boolean added() {
            return !"modified".equalsIgnoreCase(status) && !"removed".equalsIgnoreCase(status);
        }
    }

    /**
     * Everything the decision reads. Instants are ISO-8601 text on the wire, what {@code gh} prints.
     *
     * @param publishersCurrent {@code publishers.json} on {@code main} is the copy at the merge base of the pull
     *                          request and {@code main}: then a merge leaves the file the check passed, and the
     *                          ranks the check read are main's. Missing on the wire is {@code false}
     * @param authorAdmin       the author's account id is an admin in {@code main}'s {@code publishers.json} now;
     *                          missing on the wire is {@code false}
     */
    public record Facts(String author, boolean checksPassed, Instant now, List<Change> changes,
                        List<Instant> recentNewEntries, boolean publishersCurrent, boolean authorAdmin) {
        public Facts {
            author = author == null ? "" : author.trim();
            changes = changes == null ? List.of() : List.copyOf(changes);
            recentNewEntries = recentNewEntries == null ? List.of() : List.copyOf(recentNewEntries);
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Wire(String author, boolean checksPassed, String now, List<Change> changes,
                            List<String> recentNewEntries, boolean publishersCurrent, boolean authorAdmin) {}

        /** Parses the facts file the workflow writes. A missing {@code now} is the current instant. */
        public static Facts read(String json) throws IOException {
            Wire wire = CatalogJson.lenient().readValue(json, Wire.class);
            try {
                return new Facts(wire.author(), wire.checksPassed(),
                        wire.now() == null || wire.now().isBlank() ? Instant.now() : Instant.parse(wire.now()),
                        wire.changes(), wire.recentNewEntries() == null ? List.of()
                        : wire.recentNewEntries().stream().map(Instant::parse).toList(), wire.publishersCurrent(),
                        wire.authorAdmin());
            } catch (DateTimeParseException e) {
                throw new IOException("a timestamp in the facts is not ISO-8601: " + e.getMessage(), e);
            }
        }
    }

    private ListingPolicy() {}

    /** {@code ListingPolicy <facts.json>}: prints the decision as JSON. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ListingPolicy <facts.json>");
            System.exit(2);
        }
        System.out.println(decide(Facts.read(Files.readString(Path.of(args[0])))).toJson());
    }

    public static Decision decide(Facts facts) {
        if (!facts.checksPassed()) {
            return Decision.of(Kind.HOLD, "the catalog's check has not passed.");
        }
        if (facts.changes().size() != 1) {
            return Decision.of(Kind.HOLD, "a pull request changes exactly one entry file; this one changes "
                    + facts.changes().size() + ".");
        }
        Change change = facts.changes().getFirst();
        if (Publishers.FILE.equals(change.path()) && "modified".equalsIgnoreCase(change.status())) {
            // Two rank changes on different lines merge cleanly into a file neither check saw: no admin left, an
            // id twice, or an admin demoted since. So only one made on main's own copy, by an admin of main's own
            // copy, merges: the check read the ranks main had when it ran, which need not be either.
            return facts.publishersCurrent() && facts.authorAdmin()
                    ? Decision.of(Kind.MERGE, "changes the ranks; the check passed it as an admin's.")
                    : STALE_PUBLISHERS;
        }
        if (EntryPath.parse(change.path()).isEmpty() || "renamed".equalsIgnoreCase(change.status())) {
            return Decision.of(Kind.HOLD, change.path() + " is not one entry file added, changed or removed.");
        }
        // The check may have passed it as an admin's, by ranks main has since changed: an admin demoted after
        // the check would still merge. One whose author is an admin on main now is sound either way.
        if (!facts.publishersCurrent() && !facts.authorAdmin()) {
            return STALE_PUBLISHERS;
        }
        if (!change.added()) {
            return Decision.of(Kind.MERGE, "changes or removes a listed entry.");
        }
        Instant windowStart = facts.now().minus(WINDOW);
        List<Instant> recent = facts.recentNewEntries().stream().filter(t -> t.isAfter(windowStart)).sorted().toList();
        int over = recent.size() + 1 - NEW_ENTRIES_PER_DAY;
        if (over > 0) {
            Instant retry = recent.get(over - 1).plus(WINDOW);
            return new Decision(Kind.WAIT, facts.author() + " has had " + recent.size() + " new entries listed in"
                    + " the last 24 hours, and " + NEW_ENTRIES_PER_DAY + " is the limit. This pull request merges"
                    + " automatically after " + retry + ".", Optional.of(retry), false);
        }
        return Decision.of(Kind.MERGE, "passes, within the limit.");
    }
}
