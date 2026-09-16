package com.botmaker.cli.gallery;

/**
 * How much a gallery listing has been looked at.
 *
 * <p>A closed set with a stable wire id and a display name, for the reason {@code PlatformId} gives: the id is
 * persisted into {@code catalog.json}, which Studio reads, so it must never be respelled — and every reader
 * wanting a label must get the same one. {@link #fromId} is total: an id a newer builder invents reads as
 * {@link #COMMUNITY}, the tier that promises least.
 *
 * <p><b>Neither tier is a security claim.</b> Vetted means a maintainer looked at one release and chose to
 * list it; it is not a review of anybody's code, which the gallery's README says in as many words.
 */
public enum Tier {

    /** A maintainer pinned a release of it — see {@link VettedRecord}. */
    VETTED("vetted", "Vetted"),

    /** Listed automatically once the gallery's checks passed. */
    COMMUNITY("community", "Community");

    private final String id;
    private final String displayName;

    Tier(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The tier with this wire id, or {@link #COMMUNITY} for anything else. Never throws. */
    public static Tier fromId(String id) {
        for (Tier tier : values()) {
            if (tier.id.equalsIgnoreCase(id == null ? "" : id.trim())) {
                return tier;
            }
        }
        return COMMUNITY;
    }
}
