package com.botmaker.cli.catalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * How far Studio trusts a listing, decided by who owns its repository ({@link Publishers}), never by the listing.
 * The maintainer vets a person once; every bot and plugin that person owns inherits it, every tag.
 */
public enum Rank {
    /** The maintainer's own accounts. */
    ADMIN("admin", "Official"),
    /** Developers the maintainer vetted. */
    TRUSTED("trusted", "Trusted"),
    /** Everyone else whose entry passed the check. */
    COMMUNITY("community", "Community");

    private final String id;
    private final String displayName;

    Rank(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** The id {@code catalog.json} carries. */
    @JsonValue
    public String id() {
        return id;
    }

    /** The badge Studio shows. */
    public String displayName() {
        return displayName;
    }

    /** The rank an id names; anything unknown is {@link #COMMUNITY}, the least trusted. */
    @JsonCreator
    public static Rank fromId(String id) {
        if (id != null) {
            String wanted = id.toLowerCase(Locale.ROOT);
            for (Rank rank : values()) {
                if (rank.id.equals(wanted)) return rank;
            }
        }
        return COMMUNITY;
    }
}
