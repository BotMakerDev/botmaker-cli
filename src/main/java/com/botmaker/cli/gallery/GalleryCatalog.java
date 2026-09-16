package com.botmaker.cli.gallery;

import com.botmaker.cli.registry.Registry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The whole gallery as its two source directories hold it, and the two files generated from it.
 *
 * <h2>Two generated files, for two generations of reader</h2>
 *
 * <ul>
 *   <li><b>{@value #CATALOG}</b> — every listing, with its {@link Tier}. What a Studio from 2026-09-16 on
 *       reads. It is an object with a {@code schemaVersion} rather than a bare array, so the next change of
 *       shape is announced by the file instead of being discovered by a parser.</li>
 *   <li><b>{@value #INDEX}</b> — the {@link Tier#VETTED} listings only, as the array every earlier Studio
 *       has compiled in the URL of. Those readers know nothing of tiers and would show a Community bot
 *       exactly as they show a vetted one, so what they are given is the set somebody looked at. Only the
 *       fields they knew are written.</li>
 * </ul>
 *
 * <p><b>Nothing is rewritten on disk to migrate.</b> A version-1 entry is read as it stands and stamped as
 * the current schema in memory ({@link GalleryEntry#migrated()}); the author's file keeps whatever it said
 * until the author publishes again. A migration that rewrote every entry would be a pull request touching
 * every author's file at once, which is the shared-array diff the per-entry layout was built to end.
 *
 * <p>Order is by filename, sorted, for the reason {@code build-index.sh} gave: a regenerated file that
 * reorders itself produces a diff nobody can read.
 */
public final class GalleryCatalog {

    /** The generated file every Studio from 2026-09-16 reads. */
    public static final String CATALOG = "catalog.json";

    /** The generated legacy file. Its path is compiled into every earlier Studio and must never move. */
    public static final String INDEX = "index.json";

    public static final int CATALOG_SCHEMA = 2;

    /** One bot as the generated files describe it. */
    public record Listing(GalleryEntry entry, Tier tier, Optional<VettedRecord> vetted) {
    }

    private final List<Listing> listings;
    private final List<String> problems;

    private GalleryCatalog(List<Listing> listings, List<String> problems) {
        this.listings = List.copyOf(listings);
        this.problems = List.copyOf(problems);
    }

    /**
     * Reads {@code bots/} and {@code vetted/} under {@code root}. A file that does not parse is an
     * {@link IOException} naming it: the builder runs after a merge, and an index silently missing one bot is
     * a delisting nobody asked for.
     */
    public static GalleryCatalog read(Path root) throws IOException {
        ObjectMapper mapper = Registry.mapper();
        Map<String, VettedRecord> vetted = new LinkedHashMap<>();
        for (Path file : jsonFiles(root.resolve(VettedRecord.DIRECTORY))) {
            VettedRecord record = parse(mapper, file, VettedRecord.class);
            vetted.put(key(record.owner(), record.repo()), record);
        }

        List<Listing> listings = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path file : jsonFiles(root.resolve(GalleryEntry.ENTRIES_DIRECTORY))) {
            GalleryEntry entry = parse(mapper, file, GalleryEntry.class).migrated();
            VettedRecord record = vetted.remove(key(entry.owner(), entry.repo()));
            listings.add(new Listing(entry, record == null ? Tier.COMMUNITY : Tier.VETTED,
                    Optional.ofNullable(record)));
        }
        // A vetted record whose bot was delisted is harmless — it lists nothing — but it is a file that says
        // something false, so it is reported rather than silently kept.
        for (VettedRecord orphan : vetted.values()) {
            problems.add(orphan.path() + " vets " + orphan.slug() + ", which has no entry in "
                    + GalleryEntry.ENTRIES_DIRECTORY + "/");
        }
        return new GalleryCatalog(listings, problems);
    }

    public List<Listing> listings() {
        return listings;
    }

    /** What is true but not fatal — an orphaned vetted record. */
    public List<String> problems() {
        return problems;
    }

    /** {@value #CATALOG}: {@code {schemaVersion, bots: [entry + tier (+ vettedVersion, vettedAt)]}}. */
    public String catalogJson() throws IOException {
        ObjectMapper mapper = Registry.mapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", CATALOG_SCHEMA);
        ArrayNode bots = root.putArray("bots");
        for (Listing listing : listings) {
            ObjectNode bot = mapper.valueToTree(listing.entry());
            // The file carries one schema number, at the top; one per bot would be a second statement of it.
            bot.remove("schemaVersion");
            bot.put("tier", listing.tier().id());
            listing.vetted().ifPresent(record -> {
                bot.put("vettedVersion", record.vettedVersion());
                if (!record.vettedAt().isEmpty()) {
                    bot.put("vettedAt", record.vettedAt());
                }
            });
            bots.add(bot);
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }

    /**
     * {@value #INDEX}: the vetted listings as the bare array an earlier Studio parses, with the fields it knew.
     *
     * <p>{@code description} and {@code tags} are always written — every entry the old layout held carried
     * them, empty or not — and {@code launchTargets} only when declared, which is how an older Studio already
     * reads its absence.
     */
    public String legacyIndexJson() throws IOException {
        ObjectMapper mapper = Registry.mapper();
        ArrayNode array = mapper.createArrayNode();
        for (Listing listing : listings) {
            if (listing.tier() != Tier.VETTED) {
                continue;
            }
            GalleryEntry entry = listing.entry();
            ObjectNode bot = array.addObject();
            bot.put("name", entry.name());
            bot.put("owner", entry.owner());
            bot.put("repo", entry.repo());
            bot.put("description", entry.description());
            ArrayNode tags = bot.putArray("tags");
            entry.tags().forEach(tags::add);
            if (!entry.launchTargets().isEmpty()) {
                ArrayNode targets = bot.putArray("launchTargets");
                entry.launchTargets().forEach(targets::add);
            }
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(array) + "\n";
    }

    /** The listing for {@code owner/repo}, compared the way GitHub compares them — ignoring case. */
    public Optional<Listing> find(String owner, String repo) {
        String wanted = key(owner, repo);
        return listings.stream().filter(l -> key(l.entry().owner(), l.entry().repo()).equals(wanted)).findFirst();
    }

    private static String key(String owner, String repo) {
        return (owner + "/" + repo).toLowerCase(Locale.ROOT);
    }

    private static List<Path> jsonFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static <T> T parse(ObjectMapper mapper, Path file, Class<T> type) throws IOException {
        try {
            return mapper.readValue(Files.readString(file), type);
        } catch (IOException e) {
            throw new IOException(file.getParent().getFileName() + "/" + file.getFileName()
                    + " is not a valid " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
