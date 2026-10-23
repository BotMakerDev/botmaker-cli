package com.botmaker.cli.catalog;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * The two mappers the catalog reads and writes with.
 *
 * <p>{@link #strict()} for what a person or another job wrote (entry files, {@code publishers.json},
 * {@code facts.json}): an unknown field is a refusal, because the check's rule is "exactly this schema".
 * {@link #lenient()} only for reading back the catalog's own generated {@code catalog.json}.
 */
public final class CatalogJson {

    private CatalogJson() {}

    public static ObjectMapper strict() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static ObjectMapper lenient() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
