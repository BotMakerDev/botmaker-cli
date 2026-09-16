package com.botmaker.cli.gallery;

import com.botmaker.cli.registry.Registry;
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
 * Whether a gallery pull request whose checks have run is merged now, later, or by a person.
 *
 * <p><b>Pure, and handed its facts.</b> The gallery's {@code automerge.yml} collects them with {@code gh api}
 * — the author, what each changed file is and who owns it, and when the author's recent new listings were
 * merged — writes them to a file and calls {@link #main}. It never checks the pull request's code out; every
 * fact is data read through the API, which is what makes running with a write token safe.
 *
 * <h2>The rules, in order</h2>
 * <ol>
 *   <li>Checks not passed: {@link Kind#HOLD} — nothing to do until the author fixes it.</li>
 *   <li>Any path outside {@code bots/<file>.json}, including {@code vetted/}: {@link Kind#MANUAL}. A workflow
 *       file, a README and a vetted record are all a maintainer's to merge; auto-merging only ever touches
 *       authors' own entries.</li>
 *   <li>Any entry the author does not own: {@link Kind#MANUAL}, through {@link GalleryGate#ownedBy} — the
 *       organisation's bot the gate warned about.</li>
 *   <li>A maintainer: {@link Kind#MERGE}, uncounted.</li>
 *   <li>More new entries in one pull request than the daily limit: {@link Kind#MANUAL}. It could never
 *       merge by waiting.</li>
 *   <li>Over {@value #NEW_LISTINGS_PER_DAY} new listings in the last 24 hours, counting this one:
 *       {@link Kind#WAIT}, with the instant enough of the earlier ones fall out of the window.</li>
 *   <li>Otherwise {@link Kind#MERGE}.</li>
 * </ol>
 *
 * <p><b>Only additions count.</b> Re-publishing your own bot is how a description gets fixed, and a limit on
 * that would punish the author for maintaining their listing; the limit is about how fast somebody can fill
 * the gallery.
 */
public final class ListingPolicy {

    /** New listings one author may have merged in any rolling 24 hours. */
    public static final int NEW_LISTINGS_PER_DAY = 3;

    public static final Duration WINDOW = Duration.ofHours(24);

    public enum Kind { MERGE, WAIT, MANUAL, HOLD }

    /** What to do, why (a sentence the workflow posts verbatim), and — for a wait — when to look again. */
    public record Decision(Kind decision, String reason, Optional<Instant> retryAfter) {
        static Decision merge(String reason) {
            return new Decision(Kind.MERGE, reason, Optional.empty());
        }

        static Decision of(Kind kind, String reason) {
            return new Decision(kind, reason, Optional.empty());
        }

        /** {@code {"decision":"merge","reason":"…","retryAfter":"…"}} — what the workflow reads with jq. */
        public String toJson() throws IOException {
            ObjectMapper mapper = Registry.mapper().disable(SerializationFeature.INDENT_OUTPUT);
            ObjectNode node = mapper.createObjectNode();
            node.put("decision", decision.name().toLowerCase(Locale.ROOT));
            node.put("reason", reason);
            retryAfter.ifPresent(t -> node.put("retryAfter", t.toString()));
            return mapper.writeValueAsString(node);
        }
    }

    /** One changed file, as GitHub's pull request files API reports it, plus the owner its entry names. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(String path, String status, String owner) {
        boolean added() {
            return "added".equalsIgnoreCase(status);
        }
    }

    /**
     * Everything the decision reads. Instants are ISO-8601 text on the wire (what {@code gh} prints), parsed
     * by {@link #read}; this module carries no Jackson time module and one record is no reason to add one.
     */
    public record Facts(String author, boolean maintainer, boolean checksPassed, Instant now,
                        List<Change> changes, List<Instant> recentNewListings) {
        public Facts {
            author = author == null ? "" : author.trim();
            changes = changes == null ? List.of() : List.copyOf(changes);
            recentNewListings = recentNewListings == null ? List.of() : List.copyOf(recentNewListings);
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Wire(String author, boolean maintainer, boolean checksPassed, String now,
                            List<Change> changes, List<String> recentNewListings) {
        }

        /** Parses the facts file the workflow writes. A missing {@code now} is the current instant. */
        public static Facts read(String json) throws IOException {
            Wire wire = Registry.mapper().readValue(json, Wire.class);
            try {
                return new Facts(wire.author(), wire.maintainer(), wire.checksPassed(),
                        wire.now() == null || wire.now().isBlank() ? Instant.now() : Instant.parse(wire.now()),
                        wire.changes(),
                        wire.recentNewListings() == null ? List.of()
                                : wire.recentNewListings().stream().map(Instant::parse).toList());
            } catch (DateTimeParseException e) {
                throw new IOException("a timestamp in the facts is not ISO-8601: " + e.getMessage(), e);
            }
        }
    }

    private ListingPolicy() {
    }

    /** {@code ListingPolicy <facts.json>} — prints the decision as JSON on stdout. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ListingPolicy <facts.json>");
            System.exit(2);
        }
        System.out.println(decide(Facts.read(Files.readString(Path.of(args[0])))).toJson());
    }

    public static Decision decide(Facts facts) {
        if (!facts.checksPassed()) {
            return Decision.of(Kind.HOLD, "the gallery's checks have not passed.");
        }
        if (facts.changes().isEmpty()) {
            return Decision.of(Kind.HOLD, "the pull request changes nothing.");
        }
        for (Change change : facts.changes()) {
            if (!isEntry(change.path()) || "renamed".equalsIgnoreCase(change.status())) {
                return Decision.of(Kind.MANUAL, change.path() + " is not an author's own entry, so a maintainer"
                        + " merges this pull request.");
            }
        }
        if (!facts.maintainer()) {
            for (Change change : facts.changes()) {
                if (!GalleryGate.ownedBy(facts.author(), change.owner())) {
                    return Decision.of(Kind.MANUAL, change.path() + " names " + change.owner() + ", which "
                            + facts.author() + " cannot be shown to own. A maintainer lists it.");
                }
            }
        }
        if (facts.maintainer()) {
            return Decision.merge("opened by a maintainer.");
        }

        int added = (int) facts.changes().stream().filter(Change::added).count();
        if (added > NEW_LISTINGS_PER_DAY) {
            return Decision.of(Kind.MANUAL, "adds " + added + " bots at once, more than the "
                    + NEW_LISTINGS_PER_DAY + " a day that are listed automatically.");
        }
        Instant windowStart = facts.now().minus(WINDOW);
        List<Instant> recent = facts.recentNewListings().stream()
                .filter(t -> t.isAfter(windowStart))
                .sorted()
                .toList();
        int over = recent.size() + added - NEW_LISTINGS_PER_DAY;
        if (added > 0 && over > 0) {
            Instant retry = recent.get(over - 1).plus(WINDOW);
            return new Decision(Kind.WAIT, facts.author() + " has had " + recent.size() + " new bot(s) listed in"
                    + " the last 24 hours, and " + NEW_LISTINGS_PER_DAY + " is the limit. This pull request"
                    + " merges automatically after " + retry + ".", Optional.of(retry));
        }
        return Decision.merge(added == 0 ? "updates the author's own listing." : "passes, within the limit.");
    }

    private static boolean isEntry(String path) {
        String prefix = GalleryEntry.ENTRIES_DIRECTORY + "/";
        return path != null && path.startsWith(prefix) && path.endsWith(".json")
                && path.indexOf('/', prefix.length()) < 0;
    }

}
