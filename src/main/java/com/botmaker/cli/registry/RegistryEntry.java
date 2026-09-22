package com.botmaker.cli.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * One plugin's row in the registry's {@code index.json}.
 *
 * <p><b>The registry is a curated index, not a security boundary, and this record is where that sentence has
 * to be true.</b> Every field here is either what a coordinate resolves to or what the validator observed;
 * none of them is a claim about what the plugin <em>does</em> once loaded, because a plugin runs arbitrary
 * code in Studio's process and no amount of loading it proves anything about that. {@code verifiedAt} says
 * the checks ran on a date, and nothing more.
 *
 * <p>Field order is fixed by {@link JsonPropertyOrder} so that a pull request adding an entry is a diff of
 * one block rather than a reshuffle. Nulls are omitted for the same reason: an entry that leaves out
 * {@code tags} should not carry a line saying so.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({"id", "name", "coordinate", "repo", "description", "tags", "minContractVersion",
        "editorDependencies", "verifiedVersion", "verifiedAt"})
public record RegistryEntry(
        /** The plugin's own {@code StudioPlugin.id()} — the registry's primary key, and unrenameable. */
        String id,
        /** {@code displayName()} — what a user reads in Manage Plugins. */
        String name,
        /** {@code groupId:artifactId}, with no version: the index names a plugin, not a release. */
        String coordinate,
        /** {@code owner/repo} on GitHub, so the dialog can link to something a human wrote. */
        String repo,
        String description,
        List<String> tags,
        /**
         * The oldest {@code botmaker-studio-api} this plugin's own pom is content with, as it declares it.
         * Read from the pom rather than asserted, because a plugin cannot know what a future Studio does.
         */
        String minContractVersion,
        // valueTypeIds stood here until 2026-09-23: every ValueType id the plugin registered, so the gate
        // could refuse a second plugin claiming one. A type is a class now, named by its own package, and an
        // older entry's field is ignored on read (Registry disables FAIL_ON_UNKNOWN_PROPERTIES).
        /**
         * {@code groupId:artifactId:version} of everything this plugin needs on the <b>editor's</b> classpath
         * and does not bring itself — read from its pom as the dependencies it declares {@code optional}.
         *
         * <p><b>It is here because it is the one thing about a plugin that resolving the plugin cannot
         * tell you.</b> {@code optional} means not transitive, so a host that adds this coordinate to a
         * project's pom gets the jar and none of these; the SDK's plugin half needs a web server and a QR
         * encoder marked exactly that way, so a project that installed it got a toolbar button that failed
         * with a missing class. Studio held the list in its own source until 2026-09-06, as an
         * {@code if (isSdk(…))} — a privilege plugin #1 had and a second plugin could not ask for.
         *
         * <p>Empty is the ordinary case, and the honest one: a plugin whose dependencies are ordinary needs
         * no list, because they are transitive. Two kinds of coordinate are refused rather than listed —
         * {@code org.openjfx:*}, which {@code PluginLoader} resolves parent-first so the host's own copy is
         * what every plugin links, and the contract and the toolkit, which a bot's pom must never declare.
         */
        List<String> editorDependencies,
        /**
         * The version the checks last ran against, and the version the registry's gate resolves.
         *
         * <p>{@link #coordinate} deliberately carries none — the index names a plugin, not a release, and
         * Studio resolves the version to install the same way it resolves the SDK's. But a gate has to
         * download <em>something</em>, and {@link #verifiedAt} without this would be a date attached to no
         * artifact: "the checks ran" is only a fact if it says what they ran on.
         */
        String verifiedVersion,
        /** The date the checks last ran against this coordinate. A fact about a run, not a warranty. */
        String verifiedAt) {

    public RegistryEntry {
        tags = tags == null ? List.of() : List.copyOf(tags);
        editorDependencies = editorDependencies == null ? List.of() : List.copyOf(editorDependencies);
    }
}
