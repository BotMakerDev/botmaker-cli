package com.botmaker.cli.validate;

import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;
import com.botmaker.plugin.api.slot.SlotContext;
import com.botmaker.plugin.api.slot.TypeRef;
import com.botmaker.plugin.api.slot.ValueContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The contexts {@link PluginValidator} offers a plugin's editor predicates.
 *
 * <p><b>Hand-written here rather than taken from {@code botmaker-plugin-toolkit.testing.TestContexts},
 * which does the same job.</b> The toolkit is a <em>plugin's</em> dependency, resolved onto the plugin's own
 * classloader so that two plugins may hold two versions of it; a host that resolves one version onto its own
 * classpath takes that away. The same rule that forbids {@code botmaker-studio} from listing the toolkit
 * forbids it here, and the cost of keeping it is forty lines.
 *
 * <p>Every service method throws. A predicate is asked <em>which slot is this</em>, and the answer is in the
 * type and the call site it is handed; one that reaches for the theme or a dialog to decide is doing
 * something a headless host cannot support and Studio should not be asked to. The exception message says so,
 * and {@link PluginValidator} reports it as the editor's failure rather than its own.
 */
final class StubContexts {

    private StubContexts() {
    }

    /**
     * A slot: a value of {@code typeName} sitting at argument {@code argIndex} of
     * {@code enclosingClass#enclosingMethod}. This is the shape a call-site-matched editor is looking for,
     * and the shape the archetype's own example uses.
     */
    static SlotContext slot(String typeName, String enclosingClass, String enclosingMethod, int argIndex,
                            String currentSource) {
        return new SlotContext() {
            @Override
            public TypeRef type() {
                return typeRef(typeName);
            }

            @Override
            public <T> Optional<T> value(Class<T> type) {
                return Optional.empty();
            }

            @Override
            public void set(Object value) {
            }

            @Override
            public String source() {
                return currentSource == null ? "" : currentSource;
            }

            @Override
            public void setSource(String javaExpression, Class<?>... imports) {
            }

            @Override
            public StudioServices services() {
                return SERVICES;
            }

            @Override
            public Optional<String> enclosingClassName() {
                return Optional.ofNullable(enclosingClass);
            }

            @Override
            public Optional<String> enclosingMethodName() {
                return Optional.ofNullable(enclosingMethod);
            }

            @Override
            public int argIndex() {
                return argIndex;
            }

            @Override
            public Optional<String> enclosingCall() {
                return Optional.empty();
            }
        };
    }

    /**
     * A value of {@code typeName} with no call behind it — a Parameters row, or a {@code @Managed} method.
     * An editor chosen by the call must decline this one, which is the property the archetype's generated
     * test holds and the reason the validator asks both shapes rather than only the slot.
     */
    static ValueContext row(String typeName, String source) {
        return new ValueContext() {
            @Override
            public TypeRef type() {
                return typeRef(typeName);
            }

            @Override
            public <T> Optional<T> value(Class<T> type) {
                return Optional.empty();
            }

            @Override
            public void set(Object value) {
            }

            @Override
            public String source() {
                return source == null ? "" : source;
            }

            @Override
            public void setSource(String javaExpression, Class<?>... imports) {
            }

            @Override
            public StudioServices services() {
                return SERVICES;
            }
        };
    }

    // Not named `type`: a call to it sits inside an anonymous class that already declares `type()`, and
    // Java resolves a method call against the innermost enclosing declaration holding that NAME — arity
    // does not widen the search, so `type(name)` there would not compile.
    private static TypeRef typeRef(String name) {
        return new TypeRef() {
            @Override
            public String simpleName() {
                int dot = name.lastIndexOf('.');
                return dot < 0 ? name : name.substring(dot + 1);
            }

            @Override
            public String qualifiedName() {
                return name;
            }
        };
    }

    private static final StudioServices SERVICES = new StudioServices() {
        @Override
        public Path projectDir() {
            throw unsupported("projectDir()");
        }

        @Override
        public Path resourcesDir() {
            throw unsupported("resourcesDir()");
        }

        @Override
        public Theme theme() {
            throw unsupported("theme()");
        }

        // capture() was stubbed here until 2026-09-01. StudioServices.capture() and the whole Capture
        // interface were deleted from the contract on 2026-08-27, in the reversal that also took Assets and
        // SourceChoice: they were the host carrying the SDK's own vocabulary — what a capture source is
        // belongs to whoever owns CaptureSource, and botmaker-shared is published, so a plugin that wants
        // pixels asks it directly. A stub of a member the contract no longer declares is dead weight.

        @Override
        public Dialogs dialogs() {
            throw unsupported("dialogs()");
        }

        private UnsupportedOperationException unsupported(String member) {
            return new UnsupportedOperationException("a slot editor's predicate called StudioServices."
                    + member + "; deciding whether an editor applies must use the slot's type and call site"
                    + " alone, because a host has no project open when it asks");
        }
    };
}
