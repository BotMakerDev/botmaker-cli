package com.botmaker.cli.validate;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.plugin.host.grammar.SourceParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeLiteral;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * {@link Check#MIGRATIONS} (2026-11-01): each element of the plugin's own classes marked
 * {@code @Deprecated(forRemoval = true)} has a {@code @Migrate} rule in the plugin's sources, or says
 * {@link Migrate.Dropped} that none replaces it. Only ever a {@link Status#WARN}: a bot whose element is removed with
 * neither still gets Studio's default repair.
 *
 * <p>A rule is matched by what its own signature says, read without compiling: the owner's simple name, the
 * element's name and its parameters' simple erased types ({@code @Migrate.Self} left out). That is as much as the
 * sources say without a classpath, and a warning is all a near miss costs.
 *
 * <p>The sources are the working copy's {@code src} beside the pom ({@code src/test} left out), else the
 * {@code -sources.jar} beside the plugin's jar; with neither, the check is a skip when anything is deprecated.
 */
final class MigrationCheck {

    private static final String DROPPED = Migrate.Dropped.class.getName();

    /** What a rule names: its kind, the owner's simple name, the element's name and simple parameter types. */
    record Named(Migrate.Kind kind, String owner, String name, List<String> params) {}

    private MigrationCheck() {}

    static CheckResult run(PluginSubject subject) {
        Path own = subject.classpath().getFirst();
        // Open while the answer is built: a Class read after its loader closed may still need to load another.
        try (URLClassLoader loader = new URLClassLoader(urls(subject.classpath()),
                MigrationCheck.class.getClassLoader())) {
            return run(subject, own, loader);
        } catch (IOException e) {
            return CheckResult.skip(Check.MIGRATIONS, "cannot read " + own + ": " + e.getMessage());
        }
    }

    private static CheckResult run(PluginSubject subject, Path own, ClassLoader loader) throws IOException {
        List<AnnotatedElement> deprecated = deprecated(own, loader);
        if (deprecated.isEmpty()) return CheckResult.pass(Check.MIGRATIONS, "nothing is deprecated for removal");
        List<String> sources;
        try {
            sources = sources(subject.pom(), own);
        } catch (IOException e) {
            return CheckResult.skip(Check.MIGRATIONS, "cannot read the plugin's sources: " + e.getMessage());
        }
        if (sources == null) {
            return CheckResult.skip(Check.MIGRATIONS, deprecated.size() + " element(s) deprecated for removal, and no"
                    + " sources beside the pom or the jar to read their @Migrate rules from");
        }
        Set<Named> rules = new HashSet<>();
        for (String source : sources) rules.addAll(rules(source));
        List<String> open = new ArrayList<>();
        for (AnnotatedElement element : deprecated) {
            if (!covered(element, rules)) {
                open.add(display(element) + " is deprecated for removal with no @Migrate rule: write one beside it,"
                        + " or mark it @Migrate.Dropped(\"why\") when nothing replaces it");
            }
        }
        if (open.isEmpty()) {
            return CheckResult.pass(Check.MIGRATIONS, deprecated.size() + " element(s) deprecated for removal, each"
                    + " with a rule or dropped");
        }
        return CheckResult.warn(Check.MIGRATIONS, open);
    }

    // ---------------------------------------------------------------------------------------------
    // the deprecated elements, by reflection
    // ---------------------------------------------------------------------------------------------

    /**
     * Every type, constructor, method and field of {@code entry} a bot in another package can name — public, or a
     * protected member — deprecated for removal.
     */
    static List<AnnotatedElement> deprecated(Path entry, ClassLoader loader) throws IOException {
        List<AnnotatedElement> found = new ArrayList<>();
        for (String name : classNames(entry)) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                if (type.isAnonymousClass() || type.isLocalClass() || type.isSynthetic()
                        || !Modifier.isPublic(type.getModifiers())) {
                    continue;
                }
                if (forRemoval(type)) found.add(type);
                List<Member> members = new ArrayList<>();
                Collections.addAll(members, type.getDeclaredConstructors());
                Collections.addAll(members, type.getDeclaredMethods());
                Collections.addAll(members, type.getDeclaredFields());
                for (Member member : members) {
                    int modifiers = member.getModifiers();
                    if (member.isSynthetic() || !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) {
                        continue;
                    }
                    if (forRemoval((AnnotatedElement) member)) found.add((AnnotatedElement) member);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
                // A class this cannot load, or whose annotations name what is not there, is LOADS's to report.
            }
        }
        return found;
    }

    private static boolean forRemoval(AnnotatedElement element) {
        Deprecated mark = element.getAnnotation(Deprecated.class);
        return mark != null && mark.forRemoval();
    }

    private static boolean dropped(AnnotatedElement element) {
        for (java.lang.annotation.Annotation mark : element.getAnnotations()) {
            if (mark.annotationType().getName().equals(DROPPED)) return true;
        }
        return false;
    }

    /**
     * Whether a rule or {@link Migrate.Dropped} covers {@code element}, or covers a type it is in. A rule names its
     * owner by simple name, so two nested types of one name share their rules.
     */
    static boolean covered(AnnotatedElement element, Set<Named> rules) {
        if (dropped(element)) return true;
        Class<?> outer = element instanceof Class<?> type ? type.getEnclosingClass()
                : ((Member) element).getDeclaringClass();
        for (Class<?> around = outer; around != null; around = around.getEnclosingClass()) {
            if (forRemoval(around) && covered(around, rules)) return true;
        }
        if (element instanceof Class<?> type) {
            return rules.stream().anyMatch(r -> r.kind() == Migrate.Kind.TYPE && r.owner().equals(type.getSimpleName()));
        }
        Member member = (Member) element;
        Class<?> owner = member.getDeclaringClass();
        Named wanted = switch (member) {
            case Constructor<?> constructor -> new Named(Migrate.Kind.CONSTRUCTOR, owner.getSimpleName(), "",
                    params(constructor));
            case Method method -> new Named(Migrate.Kind.METHOD, owner.getSimpleName(), method.getName(),
                    params(method));
            case Field field -> new Named(Migrate.Kind.FIELD, owner.getSimpleName(), field.getName(), List.of());
            default -> null;
        };
        return wanted != null && rules.contains(wanted);
    }

    /**
     * The parameters as a rule's source writes them: a type variable by its name ({@code T}, not its erasure), a
     * generic type by its raw class. An inner class's constructor leaves out the enclosing instance javac adds.
     */
    private static List<String> params(Executable executable) {
        java.lang.reflect.Type[] generic = executable.getGenericParameterTypes();
        Class<?>[] raw = executable.getParameterTypes();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) {
            // Without a Signature attribute the generic list is the raw one, enclosing instance and all.
            out.add(simple(generic.length == raw.length ? generic[i] : raw[i]));
        }
        Class<?> owner = executable.getDeclaringClass();
        if (executable instanceof Constructor<?> && owner.isMemberClass() && !Modifier.isStatic(owner.getModifiers())
                && raw.length > 0 && raw[0] == owner.getEnclosingClass() && generic.length == raw.length) {
            out.removeFirst();
        }
        return out;
    }

    // Qualified: JDT's Type and ParameterizedType are imported for the rules' side.
    private static String simple(java.lang.reflect.Type type) {
        return switch (type) {
            case Class<?> plain -> plain.getSimpleName();
            case TypeVariable<?> variable -> variable.getName();
            case java.lang.reflect.ParameterizedType parameterized -> simple(parameterized.getRawType());
            case GenericArrayType array -> simple(array.getGenericComponentType()) + "[]";
            default -> type.getTypeName();
        };
    }

    private static String display(AnnotatedElement element) {
        return switch (element) {
            case Class<?> type -> type.getName();
            case Constructor<?> constructor -> "new " + constructor.getDeclaringClass().getName() + "("
                    + String.join(", ", params(constructor)) + ")";
            case Method method -> method.getDeclaringClass().getName() + "#" + method.getName() + "("
                    + String.join(", ", params(method)) + ")";
            case Field field -> field.getDeclaringClass().getName() + "#" + field.getName();
            default -> element.toString();
        };
    }

    // ---------------------------------------------------------------------------------------------
    // the rules, from the sources
    // ---------------------------------------------------------------------------------------------

    /** Each {@code @Migrate} rule in {@code source}, as its signature names its element. */
    static List<Named> rules(String source) {
        if (!source.contains("Migrate")) return List.of();
        List<Named> found = new ArrayList<>();
        parse(source).accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                for (Object modifier : method.modifiers()) {
                    if (modifier instanceof Annotation mark && isMigrate(mark.getTypeName())) {
                        Named named = named(method, mark);
                        if (named != null) found.add(named);
                    }
                }
                return false;
            }
        });
        return found;
    }

    private static boolean isMigrate(Name name) {
        String text = name.getFullyQualifiedName();
        return text.equals("Migrate") || text.endsWith(".meta.Migrate");
    }

    private static Named named(MethodDeclaration method, Annotation mark) {
        String owner = null;
        Migrate.Kind kind = Migrate.Kind.METHOD;
        if (mark instanceof SingleMemberAnnotation single) owner = owner(single.getValue());
        if (mark instanceof NormalAnnotation normal) {
            for (Object each : normal.values()) {
                MemberValuePair pair = (MemberValuePair) each;
                Expression value = pair.getValue();
                switch (pair.getName().getIdentifier()) {
                    case "value" -> owner = owner(value);
                    case "name" -> {
                        if (value instanceof StringLiteral literal) owner = simple(literal.getLiteralValue());
                    }
                    case "kind" -> {
                        if (value instanceof Name name) {
                            String last = simple(name.getFullyQualifiedName());
                            for (Migrate.Kind each2 : Migrate.Kind.values()) if (each2.name().equals(last)) kind = each2;
                        }
                    }
                    default -> { }
                }
            }
        }
        if (owner == null) return null;
        List<String> params = new ArrayList<>();
        for (Object each : method.parameters()) {
            SingleVariableDeclaration param = (SingleVariableDeclaration) each;
            if (isSelf(param)) continue;
            String type = simple(param.getType());
            params.add(param.isVarargs() ? type + "[]" : type);
        }
        return switch (kind) {
            case TYPE -> new Named(kind, owner, "", List.of());
            case FIELD -> new Named(kind, owner, method.getName().getIdentifier(), List.of());
            case CONSTRUCTOR -> new Named(kind, owner, "", params);
            case METHOD -> new Named(kind, owner, method.getName().getIdentifier(), params);
        };
    }

    private static boolean isSelf(SingleVariableDeclaration param) {
        for (Object modifier : param.modifiers()) {
            if (modifier instanceof Annotation mark) {
                String text = mark.getTypeName().getFullyQualifiedName();
                if (text.equals("Self") || text.endsWith("Migrate.Self")) return true;
            }
        }
        return false;
    }

    private static String owner(Expression value) {
        return value instanceof TypeLiteral literal ? simple(literal.getType()) : null;
    }

    /** {@code java.util.List<String>} → {@code List}, {@code a.Outer.Inner[]} → {@code Inner[]}. */
    private static String simple(Type type) {
        return switch (type) {
            case ParameterizedType parameterized -> simple(parameterized.getType());
            case ArrayType array -> simple(array.getElementType()) + "[]".repeat(array.getDimensions());
            default -> simple(type.toString().replaceAll("@\\S+\\s+", "").replaceAll("<.*>", ""));
        };
    }

    private static String simple(String qualified) {
        int cut = Math.max(qualified.lastIndexOf('.'), qualified.lastIndexOf('$'));
        return qualified.substring(cut + 1).strip();
    }

    private static CompilationUnit parse(String source) {
        return SourceParser.parse(source);
    }

    // ---------------------------------------------------------------------------------------------
    // files
    // ---------------------------------------------------------------------------------------------

    /**
     * The text of every {@code .java} file of the plugin: under {@code src} beside {@code pom} less
     * {@code src/test}, else in the {@code -sources.jar} beside {@code own}; {@code null} when there is neither.
     */
    static List<String> sources(Path pom, Path own) throws IOException {
        Path src = pom == null || pom.getParent() == null ? null : pom.getParent().resolve("src");
        if (src != null && Files.isDirectory(src)) {
            Path tests = src.resolve("test");
            List<String> texts = new ArrayList<>();
            try (Stream<Path> files = Files.walk(src)) {
                for (Path file : files.filter(f -> !f.startsWith(tests) && f.toString().endsWith(".java")).toList()) {
                    texts.add(Files.readString(file));
                }
            }
            return texts;
        }
        String name = own.getFileName().toString();
        if (!name.endsWith(".jar")) return null;
        Path jar = own.resolveSibling(name.substring(0, name.length() - ".jar".length()) + "-sources.jar");
        if (!Files.isRegularFile(jar)) return null;
        List<String> texts = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : Collections.list(file.entries())) {
                if (entry.isDirectory() || !entry.getName().endsWith(".java")) continue;
                try (InputStream in = file.getInputStream(entry)) {
                    texts.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return texts;
    }

    private static List<String> classNames(Path entry) throws IOException {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(entry)) {
            try (Stream<Path> files = Files.walk(entry)) {
                files.map(entry::relativize).map(Path::toString).filter(MigrationCheck::isClass)
                        .forEach(file -> names.add(className(file.replace('\\', '/'))));
            }
            return names;
        }
        try (JarFile jar = new JarFile(entry.toFile())) {
            for (JarEntry each : Collections.list(jar.entries())) {
                if (isClass(each.getName())) names.add(className(each.getName()));
            }
        }
        return names;
    }

    private static boolean isClass(String file) {
        return file.endsWith(".class") && !file.endsWith("module-info.class") && !file.endsWith("package-info.class");
    }

    private static String className(String file) {
        return file.substring(0, file.length() - ".class".length()).replace('/', '.');
    }

    private static URL[] urls(List<Path> classpath) throws MalformedURLException {
        URL[] urls = new URL[classpath.size()];
        for (int i = 0; i < urls.length; i++) urls[i] = classpath.get(i).toUri().toURL();
        return urls;
    }
}
