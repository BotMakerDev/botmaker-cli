package com.botmaker.cli.validate;

import com.botmaker.plugin.api.value.ComponentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code managed-history}'s reading of a value's parts: a held value whose declaration is unchanged is still
 * unreadable when the factory it is written with changed — the SDK's {@code Sdk.flow()} from v1.4.2, whose
 * {@code Flow.activity(Activity, …)} became {@code Flow.activity(Class, …)}.
 */
class PartChangesTest {

    public record Card(String name, int size) {

        public static Card of(String name) {
            return new Card(name, 1);
        }

        public static Card sized(String name, int size) {
            return new Card(name, size);
        }

        public Card larger(int size) {
            return new Card(name, size);
        }
    }

    private static final ComponentType<Card> BY_NAME = ComponentType.part(Card.class)
            .writtenAs(Card::of, Card::name);

    private static final ComponentType<Card> BY_NAME_AND_SIZE = ComponentType.part(Card.class)
            .writtenAs(Card::sized, Card::name, Card::size);

    private static final ComponentType<Card> BY_NAME_LARGER = ComponentType.part(Card.class)
            .writtenAs(Card::of, Card::name)
            .with(Card::larger, Card::size);

    @Test
    void theSameFactoryReadsTheSame() {
        assertEquals(List.of(), changes(BY_NAME, BY_NAME));
    }

    @Test
    void aChangedFactoryIsAChangedPart() {
        List<String> changed = changes(BY_NAME, BY_NAME_AND_SIZE);
        assertEquals(1, changed.size(), changed::toString);
        assertTrue(changed.getFirst().contains("PartChangesTest.Card.of(String) → PartChangesTest.Card.sized(String, int)"),
                changed::toString);
    }

    /** No value the older release wrote calls a wither it did not have. */
    @Test
    void anAddedWitherStillReads() {
        assertEquals(List.of(), changes(BY_NAME, BY_NAME_LARGER));
    }

    @Test
    void aRemovedWitherIsAChangedPart() {
        assertEquals(1, changes(BY_NAME_LARGER, BY_NAME).size());
    }

    @Test
    void aTypeNoLongerDeclaredIsAChangedPart() {
        List<String> changed = PluginValidator.partChanges(Card.class, Map.of(Card.class.getName(), BY_NAME),
                Card.class, Map.of());
        assertTrue(changed.getFirst().contains("is no longer declared"), changed::toString);
    }

    /** The bot's Java reads the same whatever a type variable is called, or whether a list is widened. */
    @Test
    void aTypeVariableIsItsBound() throws NoSuchMethodException {
        assertEquals(PluginValidator.written(Generic.class.getMethod("named", Class.class).getGenericParameterTypes()[0]),
                PluginValidator.written(Generic.class.getMethod("renamed", Class.class).getGenericParameterTypes()[0]));
        assertEquals("java.lang.Class<java.lang.Enum>",
                PluginValidator.written(Generic.class.getMethod("selfBound", Class.class).getGenericParameterTypes()[0]));
    }

    public interface Generic {
        <O extends CharSequence> void named(Class<O> type);

        void renamed(Class<? extends CharSequence> type);

        <E extends Enum<E>> void selfBound(Class<E> type);
    }

    @Test
    void namesPrintWithoutPackages() {
        assertEquals("List<Flow.Step>", PluginValidator.shortTypes("java.util.List<com.botmaker.sdk.api.flow.Flow$Step>"));
        assertEquals("SdkValue.Id.FLOW", PluginValidator.simple("com.botmaker.sdk.api.bot.SdkValue$Id.FLOW"));
    }

    private static List<String> changes(ComponentType<Card> was, ComponentType<Card> is) {
        return PluginValidator.partChanges(Card.class, Map.of(Card.class.getName(), was),
                Card.class, Map.of(Card.class.getName(), is));
    }
}
