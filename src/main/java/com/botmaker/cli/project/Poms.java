package com.botmaker.cli.project;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.XMLConstants;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reading and editing a {@code pom.xml} as XML.
 *
 * <p><b>DOM rather than {@code maven-model}, deliberately.</b> Two of the three things this file is for are
 * questions about what the pom <em>says</em> — which scope a dependency is declared at — and a model reader
 * answers what the pom <em>means</em> after inheritance and interpolation, which is a different question and
 * the wrong one: a toolkit dependency inheriting {@code provided} from a parent is still a broken plugin.
 * The third, adding a dependency, must leave every other line of the file untouched, which a model
 * round-trip cannot promise. {@code MavenService} in Studio uses {@code maven-model} for the opposite reason
 * — it composes a whole pom rather than editing somebody's.
 *
 * <p>This class holds no state, spawns no process and prints nothing, which is why
 * {@code com.botmaker.cli.validate} — the library the registry's CI runs — is allowed to reach it.
 */
public final class Poms {

    private Poms() {
    }

    /**
     * One {@code <dependency>} as the pom declares it. {@code scope} is {@code ""} when it is omitted.
     *
     * <p><b>{@code optional} is read as well as {@code scope}, because they are one mistake seen from two
     * sides.</b> A {@code provided} toolkit is absent from the plugin's own runtime classpath; an
     * {@code optional} one is present there and absent from every consumer's, since {@code optional} means
     * <i>not transitive</i>. Both compile, both produce a jar, and both are found by a host that cannot load
     * the plugin. This project has shipped the second three times.
     */
    public record Dependency(String groupId, String artifactId, String version, String scope,
                             boolean optional) {

        /** A dependency being <i>written</i>, where nothing has ever wanted {@code optional}. */
        public Dependency(String groupId, String artifactId, String version, String scope) {
            this(groupId, artifactId, version, scope, false);
        }

        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    /** Every dependency the file declares, in file order. Dependency management is not consulted. */
    public static List<Dependency> dependencies(Path pom) throws IOException {
        Document doc = parse(pom);
        Element deps = firstChild(doc.getDocumentElement(), "dependencies");
        if (deps == null) {
            return List.of();
        }
        List<Dependency> out = new ArrayList<>();
        for (Element dep : children(deps, "dependency")) {
            out.add(new Dependency(text(dep, "groupId"), text(dep, "artifactId"),
                    text(dep, "version"), text(dep, "scope"), "true".equals(text(dep, "optional"))));
        }
        return List.copyOf(out);
    }

    /** The {@code groupId:artifactId:version} the file declares for itself. */
    public static Dependency coordinate(Path pom) throws IOException {
        Element root = parse(pom).getDocumentElement();
        Element parent = firstChild(root, "parent");
        String group = text(root, "groupId");
        String version = text(root, "version");
        if (parent != null) {
            if (group.isEmpty()) {
                group = text(parent, "groupId");
            }
            if (version.isEmpty()) {
                version = text(parent, "version");
            }
        }
        return new Dependency(group, text(root, "artifactId"), version, "");
    }

    /** The {@code <parent>} the file names, as declared; empty when it names none. */
    public static Optional<Dependency> parent(Path pom) throws IOException {
        Element parent = firstChild(parse(pom).getDocumentElement(), "parent");
        return parent == null ? Optional.empty() : Optional.of(new Dependency(text(parent, "groupId"),
                text(parent, "artifactId"), text(parent, "version"), ""));
    }

    /** The project's own {@code <name>}, as declared; {@code ""} when it has none. */
    public static String name(Path pom) throws IOException {
        return text(parse(pom).getDocumentElement(), "name");
    }

    /**
     * The {@code <properties>} block, as declared. A pom with no block answers an empty map.
     *
     * <p>Deliberately <b>not</b> applied by {@link #dependencies}: the class opens by saying that the
     * question here is what a pom <em>says</em>, and a scope check must keep reading it that way. This is
     * for the one caller that genuinely needs the value rather than the text — {@code publish}, which puts
     * a version into a registry entry that a stranger will read, where {@code ${botmaker.studioapi.version}}
     * is not an answer.
     */
    public static Map<String, String> properties(Path pom) throws IOException {
        Element block = firstChild(parse(pom).getDocumentElement(), "properties");
        if (block == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        NodeList nodes = block.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                out.put(element.getTagName(), element.getTextContent().trim());
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Substitutes {@code ${name}} from {@code properties}, following a property whose own value is another
     * reference, up to a small depth.
     *
     * <p>One level is not enough for a real pom and unbounded recursion is a hang on a pom that references
     * itself, so it stops after ten passes and returns whatever it has — the caller's job is to notice a
     * {@code ${} that survived, not this method's to decide what to do about it. Nothing outside the
     * {@code <properties>} block is consulted: {@code ${project.version}} and the settings' properties
     * belong to a model reader, and a caller meeting one gets an unresolved string and can say so.
     */
    public static String interpolate(String value, Map<String, String> properties) {
        String current = value == null ? "" : value;
        for (int pass = 0; pass < 10 && current.contains("${"); pass++) {
            String before = current;
            for (Map.Entry<String, String> property : properties.entrySet()) {
                current = current.replace("${" + property.getKey() + "}", property.getValue());
            }
            if (current.equals(before)) {
                break;
            }
        }
        return current;
    }

    public static Optional<Dependency> find(List<Dependency> declared, String groupId, String artifactId) {
        return declared.stream()
                .filter(d -> d.groupId().equals(groupId) && d.artifactId().equals(artifactId))
                .findFirst();
    }

    /**
     * Adds or updates one dependency, rewriting the file only when something actually changed.
     *
     * <p>Returns {@code true} when it wrote. Idempotence is not tidiness here: {@code botmaker run} calls
     * this on every launch, and a project whose pom is touched every time is a project whose editor believes
     * it has unsaved changes every time.
     *
     * @return whether the file was rewritten
     */
    public static boolean upsertDependency(Path pom, Dependency wanted) throws IOException {
        Document doc = parse(pom);
        Element root = doc.getDocumentElement();
        Element deps = firstChild(root, "dependencies");
        if (deps == null) {
            deps = doc.createElement("dependencies");
            root.appendChild(deps);
        }
        for (Element dep : children(deps, "dependency")) {
            if (text(dep, "groupId").equals(wanted.groupId())
                    && text(dep, "artifactId").equals(wanted.artifactId())) {
                if (text(dep, "version").equals(wanted.version())) {
                    return false;
                }
                setText(doc, dep, "version", wanted.version());
                write(doc, pom);
                return true;
            }
        }
        Element dep = doc.createElement("dependency");
        setText(doc, dep, "groupId", wanted.groupId());
        setText(doc, dep, "artifactId", wanted.artifactId());
        setText(doc, dep, "version", wanted.version());
        if (!wanted.scope().isEmpty()) {
            setText(doc, dep, "scope", wanted.scope());
        }
        deps.appendChild(dep);
        write(doc, pom);
        return true;
    }

    private static Document parse(Path pom) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // A pom is a file the CLI was pointed at, and a plugin author's working copy is not a trust
            // boundary — but an external entity here would let one read arbitrary files off the CI runner
            // that validates it, which is.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(pom.toFile());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot read " + pom + ": " + e.getMessage(), e);
        }
    }

    private static void write(Document doc, Path pom) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            Files.writeString(pom, out.toString());
        } catch (Exception e) {
            throw new IOException("cannot write " + pom + ": " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && element.getTagName().equals(name)) {
                out.add(element);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static String text(Element parent, String name) {
        Element child = firstChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static void setText(Document doc, Element parent, String name, String value) {
        Element child = firstChild(parent, name);
        if (child == null) {
            child = doc.createElement(name);
            parent.appendChild(child);
        }
        child.setTextContent(value);
    }
}
