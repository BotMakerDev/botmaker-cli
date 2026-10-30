package com.botmaker.cli.repair;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The members of one Java file, however damaged, as far as they can be trusted.
 *
 * <p>JDT recovers from anything and answers with confidence, so nothing it recovered is taken at its word. A syntax
 * error is <i>confined</i> when blanking the members holding the errors leaves a file with none: each member still
 * standing then parsed cleanly, and the blanked ones are unsure. Otherwise a member counts only if it ends before the
 * first error, and all that follows is unsure: unmatched, never matched. Even that member may have ended early, on a
 * bracket its body lost ({@code if (a) b(); }}), so {@link Damage} also takes it as whole only if it is unchanged.
 */
public final class Members {

    private static final ASTMatcher MATCHER = new ASTMatcher(false);

    private Members() {}

    /**
     * A member: {@code Type#name(int, String)}, {@code Type.field}, {@code Type.Nested}, {@code Enum.CONSTANT}.
     *
     * @param name what a reader would search the text for; empty for an initializer block
     */
    public record Member(String key, String name, int startLine, int endLine, ASTNode node) {

        boolean sameAs(Member other) {
            return node.subtreeMatch(MATCHER, other.node);
        }

        boolean isType() {
            return node instanceof AbstractTypeDeclaration;
        }

        /** The simple name of the type it is declared in; empty for a top-level type. */
        String owner() {
            int hash = key.indexOf('#');
            String path = hash >= 0 ? key.substring(0, hash)
                    : key.lastIndexOf('.') >= 0 ? key.substring(0, key.lastIndexOf('.')) : "";
            return path.substring(path.lastIndexOf('.') + 1);
        }
    }

    /**
     * What a file holds.
     *
     * @param unreadable     not text, blank, or nothing recognisable as a type
     * @param members        the ones to trust, by key
     * @param bad            the blanked ones when {@code confined}, by what recovery called them
     * @param unsure         the text of whatever is not trusted
     * @param firstErrorLine the first syntax error's line, or 0 with none
     * @param confined       whether the errors stay inside {@code bad}; true with none
     */
    public record Reading(boolean unreadable, Map<String, Member> members, List<Member> bad, String unsure,
                          int firstErrorLine, boolean confined) {

        boolean hasSyntaxError() {
            return firstErrorLine > 0;
        }

        /** Whether the unsure text could hold a member of that name. */
        boolean unsureMentions(String name) {
            return !name.isEmpty() && mentions(unsure, name);
        }

        /** The innermost trusted member over a line, or null. */
        Member at(int line) {
            Member found = null;
            for (Member m : members.values()) {
                if (m.startLine() <= line && line <= m.endLine()
                        && (found == null || m.endLine() - m.startLine() <= found.endLine() - found.startLine())) {
                    found = m;
                }
            }
            return found;
        }
    }

    private static boolean mentions(String text, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text).find();
    }

    /** Reads a file's bytes. Text that is not UTF-8, or has a NUL in it, is garbage. */
    public static Reading read(byte[] bytes) {
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return unreadable();
        }
        return source.isBlank() || source.indexOf('\0') >= 0 ? unreadable() : read(source);
    }

    /** Reads source text. */
    public static Reading read(String source) {
        CompilationUnit cu = parse(source);
        List<IProblem> errors = errors(cu);
        if (cu.types().isEmpty()) return unreadable();
        Map<String, Member> all = members(cu);
        if (errors.isEmpty()) return new Reading(false, all, List.of(), "", 0, true);

        int firstError = errors.stream().mapToInt(IProblem::getSourceStart).min().orElseThrow();
        int firstErrorLine = Math.max(1, cu.getLineNumber(firstError));
        Reading confined = confined(source, cu, errors, all, firstErrorLine);
        if (confined != null) return confined;

        Map<String, Member> before = new LinkedHashMap<>();
        for (Member m : all.values()) {
            boolean read = m.isType() ? m.node().getStartPosition() < firstError
                    : m.node().getStartPosition() + m.node().getLength() <= firstError;
            if (read) before.put(m.key(), m);
        }
        return new Reading(false, before, List.of(), source.substring(Math.max(0, firstError)), firstErrorLine, false);
    }

    private static Reading unreadable() {
        return new Reading(true, Map.of(), List.of(), "", 0, false);
    }

    /** The reading with every error inside a member, or null when blanking those leaves any error behind. */
    private static Reading confined(String source, CompilationUnit cu, List<IProblem> errors,
                                    Map<String, Member> all, int firstErrorLine) {
        Map<String, Member> holding = new LinkedHashMap<>();
        for (IProblem error : errors) {
            Member m = holding(all, error.getSourceStart());
            // An initializer holding an error is recovery's guess at statements left outside a method.
            if (m == null || m.node() instanceof Initializer) return null;
            holding.put(m.key(), m);
        }
        StringBuilder blanked = new StringBuilder(source);
        StringBuilder unsure = new StringBuilder();
        for (Member m : holding.values()) {
            int start = m.node().getStartPosition();
            int end = Math.min(source.length(), start + m.node().getLength());
            unsure.append(source, start, end).append('\n');
            for (int i = start; i < end; i++) {
                if (source.charAt(i) != '\n' && source.charAt(i) != '\r') blanked.setCharAt(i, ' ');
            }
        }
        CompilationUnit again = parse(blanked.toString());
        if (!errors(again).isEmpty() || again.types().isEmpty()) return null;
        return new Reading(false, members(again), List.copyOf(holding.values()), unsure.toString(), firstErrorLine,
                true);
    }

    /** The smallest member, not a type, over an offset; null when the offset lies between members. */
    private static Member holding(Map<String, Member> all, int offset) {
        Member found = null;
        for (Member m : all.values()) {
            int start = m.node().getStartPosition();
            if (m.isType() || offset < start || offset >= start + m.node().getLength()) continue;
            if (found == null || m.node().getLength() < found.node().getLength()) found = m;
        }
        return found;
    }

    private static CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        // ASTParser's default level is 1.3, where a tree read off today's Java is wrong without a word.
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        parser.setCompilerOptions(options);
        return (CompilationUnit) parser.createAST(null);
    }

    private static List<IProblem> errors(CompilationUnit cu) {
        List<IProblem> errors = new ArrayList<>();
        for (IProblem problem : cu.getProblems()) {
            if (problem.isError()) errors.add(problem);
        }
        return errors;
    }

    private static Map<String, Member> members(CompilationUnit cu) {
        Map<String, Member> out = new LinkedHashMap<>();
        for (Object t : cu.types()) {
            if (t instanceof AbstractTypeDeclaration type) collect(cu, type, "", out);
        }
        return out;
    }

    private static void collect(CompilationUnit cu, AbstractTypeDeclaration type, String outer,
                                Map<String, Member> out) {
        String name = type.getName().getIdentifier();
        String path = outer.isEmpty() ? name : outer + "." + name;
        add(cu, out, path, name, type);
        if (type instanceof EnumDeclaration e) {
            for (Object c : e.enumConstants()) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) c;
                String id = constant.getName().getIdentifier();
                add(cu, out, path + "." + id, id, constant);
            }
        }
        int initializers = 0;
        for (Object member : type.bodyDeclarations()) {
            switch (member) {
                case MethodDeclaration m -> add(cu, out, signature(path, m), m.getName().getIdentifier(), m);
                case AnnotationTypeMemberDeclaration m ->
                        add(cu, out, path + "#" + m.getName().getIdentifier() + "()", m.getName().getIdentifier(), m);
                case FieldDeclaration f -> {
                    for (Object o : f.fragments()) {
                        String id = ((VariableDeclarationFragment) o).getName().getIdentifier();
                        add(cu, out, path + "." + id, id, f);
                    }
                }
                case Initializer i -> add(cu, out, path + "#{" + initializers++ + "}", "", i);
                case AbstractTypeDeclaration nested -> collect(cu, nested, path, out);
                default -> { }
            }
        }
    }

    private static void add(CompilationUnit cu, Map<String, Member> out, String key, String name, ASTNode node) {
        int start = node.getStartPosition();
        out.putIfAbsent(key, new Member(key, name, cu.getLineNumber(start),
                cu.getLineNumber(start + Math.max(0, node.getLength() - 1)), node));
    }

    /** {@code Outer.Inner#name(int, String...)}: what makes two declarations the same function. */
    public static String signature(String typePath, MethodDeclaration m) {
        List<String> params = new ArrayList<>();
        for (Object p : m.parameters()) {
            SingleVariableDeclaration v = (SingleVariableDeclaration) p;
            params.add(v.getType() + (v.isVarargs() ? "..." : ""));
        }
        return typePath + "#" + m.getName().getIdentifier() + "(" + String.join(", ", params) + ")";
    }

    /** A key's first ancestor that is also in {@code keys}, dropped: a type gone says its members are. */
    static List<String> outermost(List<String> keys) {
        return keys.stream().filter(key -> keys.stream()
                .noneMatch(other -> key.startsWith(other + "#") || key.startsWith(other + "."))).toList();
    }
}
