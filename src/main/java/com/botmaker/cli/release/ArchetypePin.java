package com.botmaker.cli.release;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The BotMaker versions a freshly generated plugin pins, and the archetype release's bump of them —
 * {@link TemplatePin}'s argument applied to the archetype's descriptor.
 *
 * <p><b>The defaults were {@code main-SNAPSHOT} until 2026-09-29</b>, chosen because it "never goes stale".
 * It went stale in the other direction: the tip of the contract's {@code main} is routinely ahead of every
 * released Studio, and a plugin compiled against a member only {@code main} has is refused at load
 * ({@code ContractLinks}, "built for a newer Studio"). A generated plugin that no released Studio loads is the
 * worst first five minutes the archetype could give. So the defaults are tags, and this moves them.
 *
 * <p><b>Each pin is what {@link DepTag} answers</b>: the version this run cuts when it cuts the upstream,
 * otherwise that upstream's newest tag. So every archetype release writes the newest pair, whether or not
 * the contract moved in the same run, and a skeleton generated from it is reproducible. The archetype is
 * still forced by nothing: a contract release without it leaves the previous pair, which still loads.
 *
 * <p>An anchored regex over the descriptor's text, for {@code TemplatePin}'s reason: the anchor is the
 * property's key, never the version, and {@link Runner#replace} throws when nothing matches.
 */
public final class ArchetypePin {

    /** Where the defaults live, relative to the archetype's directory. */
    static final String DESCRIPTOR = "src/main/resources/META-INF/maven/archetype-metadata.xml";

    /** Upstream module to the {@code requiredProperty} key whose default pins it. */
    static final Map<Module, String> PINS = new EnumMap<>(Map.of(
            Module.STUDIO_API, "studioApiVersion",
            Module.PLUGIN_TOOLKIT, "toolkitVersion"));

    private ArchetypePin() {
    }

    /**
     * {@code <requiredProperty key="studioApiVersion"> <defaultValue>v0.3.0</defaultValue>} — the property,
     * then its default, with the version between the two groups.
     */
    static Pattern declaration(String key) {
        return Pattern.compile("(<requiredProperty key=\"" + Pattern.quote(key)
                + "\">\\s*<defaultValue>)[^<]*(</defaultValue>)");
    }

    /** Rewrites both defaults. Called from the archetype's own release and only from there. */
    public static void bump(Runner runner, Path umbrella, Map<Module, Version> releasing) {
        Path descriptor = umbrella.resolve(Module.PLUGIN_ARCHETYPE.directory()).resolve(DESCRIPTOR);
        PINS.forEach((pinned, key) -> {
            String ref = DepTag.of(umbrella, pinned, Optional.ofNullable(releasing.get(pinned)));
            runner.replace(descriptor, declaration(key), "$1" + Matcher.quoteReplacement(ref) + "$2");
        });
    }
}
