package com.botmaker.cli.repair;

import com.botmaker.cli.repair.Compile.Problem;
import com.botmaker.cli.repair.Members.Member;
import com.botmaker.cli.repair.Members.Reading;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A project on disk against its repair point: one row per damaged thing, the pom first, as nothing compiles without it
 * (the data-safety spec, §4). Pure in that it only reads its two sides and changes nothing. Studio's Repair pane and
 * {@code botmaker check} both show this report.
 *
 * <p>A member the project no longer has is a row only: it comes back without a tick only when a compile error asks
 * for it, as a member deleted on purpose must not return.
 */
public final class Damage {

    public static final String POM = "pom.xml";

    private static final Comparator<Row> ORDER = Comparator.<Row, Boolean>comparing(row -> !POM.equals(row.path()))
            .thenComparing(Row::kind).thenComparing(Row::path);

    /** What is wrong with one thing. */
    public enum Kind {
        NO_POM("no-pom", "No pom"),
        NO_GIT("no-git", "No history"),
        NOTHING_TO_COMPARE("nothing-to-compare", "Nothing to compare with"),
        UNREADABLE_FILE("unreadable-file", "Unreadable file"),
        BAD_MEMBER("bad-member", "Member that does not compile"),
        BLURRED("blurred", "Members run together"),
        FILES_GONE("files-gone", "Files gone"),
        MEMBERS_GONE("members-gone", "Members gone");

        private final String id;
        private final String displayName;

        Kind(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** What <i>Repair all</i> does for a row. */
    public enum Repair {
        PUT_BACK_MEMBERS,
        WHOLE_FILE,
        PUT_BACK_FILES,
        /** With no repair point to take a pom from, Studio writes one. */
        NEW_POM,
        RELINK,
        /** Listed only: a tick puts it back. */
        ONLY_BY_TICK,
        /** Nothing to put back from. */
        NOTHING
    }

    /**
     * One damaged thing.
     *
     * @param path    the file; empty for a row about the whole project or several files
     * @param line    where the damage starts, or 0
     * @param members the keys {@link Members} gives
     * @param files   the paths gone, for {@link Kind#FILES_GONE}
     */
    public record Row(Kind kind, Repair repair, String path, int line, List<String> members, List<String> files) {

        public Row {
            members = List.copyOf(members);
            files = List.copyOf(files);
        }
    }

    /**
     * The comparison.
     *
     * @param elsewhere compile errors no row accounts for: in a member just like the repair point's, so caused from
     *                  outside it (a dependency, the pom)
     * @param compile   what the compile said; a build can fail with no error any row or line could name
     */
    public record Report(List<Row> rows, List<Problem> elsewhere, Compile.State compile) {

        public Report {
            rows = rows.stream().sorted(ORDER).toList();
            elsewhere = List.copyOf(elsewhere);
        }

        public boolean clean() {
            return rows.isEmpty() && elsewhere.isEmpty() && compile != Compile.State.FAILED;
        }
    }

    private Damage() {}

    /** The folder has no {@code .git}: relinking is offered, and only the compile's errors can be told. */
    public static Report noRepository(ProjectSide disk, Compile compile) throws IOException {
        return alone(Kind.NO_GIT, Repair.RELINK, disk, compile);
    }

    /** A repository with no record and no commit that compiles, or a folder that cannot be relinked. */
    public static Report noRepairPoint(ProjectSide disk, Compile compile) throws IOException {
        return alone(Kind.NOTHING_TO_COMPARE, Repair.NOTHING, disk, compile);
    }

    private static Report alone(Kind kind, Repair repair, ProjectSide disk, Compile compile) throws IOException {
        List<Row> rows = new ArrayList<>(List.of(new Row(kind, repair, "", 0, List.of(), List.of())));
        pomRow(disk, disk.paths(), Repair.NEW_POM, Repair.NOTHING, rows);
        return new Report(rows, compile.problems(), compile.state());
    }

    /** Compares the project on disk with a repair point. */
    public static Report compare(ProjectSide point, ProjectSide disk, Compile compile) throws IOException {
        return new Comparison(point, disk, compile).report();
    }

    private static final class Comparison {
        private final ProjectSide point;
        private final ProjectSide disk;
        private final Compile compile;
        private final Map<String, List<Problem>> errors = new LinkedHashMap<>();
        private final List<Row> rows = new ArrayList<>();
        private final List<Problem> elsewhere = new ArrayList<>();
        private final Set<String> onDisk = new HashSet<>();
        private final Map<String, Reading> readings = new LinkedHashMap<>();
        /** The repair point's reading of each file on disk that differs from it. */
        private final Map<String, Reading> differing = new LinkedHashMap<>();
        /** Members a file has that its repair point lacks: only there can a member gone from a file have moved to. */
        private final Set<String> gained = new HashSet<>();
        private final List<String> filesGone = new ArrayList<>();
        /** The names of members coming back because an error asks: their errors are accounted for. */
        private final Set<String> asked = new HashSet<>();

        Comparison(ProjectSide point, ProjectSide disk, Compile compile) {
            this.point = point;
            this.disk = disk;
            this.compile = compile;
            for (Problem p : compile.problems()) errors.computeIfAbsent(p.path(), x -> new ArrayList<>()).add(p);
        }

        Report report() throws IOException {
            onDisk.addAll(disk.paths());
            Set<String> inPoint = new TreeSet<>(point.paths());
            pomRow(disk, onDisk, inPoint.contains(POM) ? Repair.PUT_BACK_FILES : Repair.NEW_POM, Repair.WHOLE_FILE,
                    rows);
            readDisk(inPoint);
            for (String path : inPoint) {
                if (path.equals(POM)) continue;
                if (holdsMembers(path)) javaFile(path);
                else otherFile(path);
            }
            for (String path : new TreeSet<>(readings.keySet())) {
                if (!inPoint.contains(path)) changed(path, null, readings.get(path));
            }
            if (!filesGone.isEmpty()) {
                rows.add(new Row(Kind.FILES_GONE, Repair.PUT_BACK_FILES, "", 0, List.of(), filesGone));
            }
            errors.values().forEach(elsewhere::addAll);
            elsewhere.removeIf(p -> asked.contains(p.symbol()));
            return new Report(rows, elsewhere, compile.state());
        }

        /** A package's or a module's info file declares no type, so it is compared as a plain file. */
        private static boolean holdsMembers(String path) {
            return path.endsWith(".java") && !path.endsWith("package-info.java") && !path.endsWith("module-info.java");
        }

        private void readDisk(Set<String> inPoint) throws IOException {
            for (String path : onDisk) {
                if (!holdsMembers(path)) continue;
                byte[] now = bytesOnDisk(path);
                Reading reading = Members.read(now);
                readings.put(path, reading);
                Set<String> added = new HashSet<>(reading.members().keySet());
                if (inPoint.contains(path)) {
                    byte[] then = point.read(path);
                    if (Arrays.equals(then, now)) continue;
                    Reading was = Members.read(then);
                    differing.put(path, was);
                    added.removeAll(was.members().keySet());
                }
                gained.addAll(added);
            }
        }

        private void otherFile(String path) throws IOException {
            if (!onDisk.contains(path)) {
                filesGone.add(path);
            } else if (point.size(path) > 0 && sizeOnDisk(path) == 0) {
                rows.add(new Row(Kind.UNREADABLE_FILE, Repair.WHOLE_FILE, path, 0, List.of(), List.of()));
            }
        }

        private long sizeOnDisk(String path) {
            try {
                return disk.size(path);
            } catch (IOException e) {
                return 0;
            }
        }

        private void javaFile(String path) throws IOException {
            if (!onDisk.contains(path)) {
                fileGone(path, Members.read(point.read(path)));
            } else if (differing.containsKey(path)) {
                changed(path, differing.get(path), readings.get(path));
            }
        }

        private byte[] bytesOnDisk(String path) {
            try {
                return disk.read(path);
            } catch (IOException e) {
                return new byte[0];
            }
        }

        /** Gone, members moved to another file aside. It comes back whole only if a compile error asks for it. */
        private void fileGone(String path, Reading was) {
            List<String> gone = new ArrayList<>(was.members().keySet());
            gone.removeAll(gained);
            if (gone.isEmpty()) {
                // Either all of it moved, or it had no member to name and so is listed as a file.
                if (was.members().isEmpty()) filesGone.add(path);
                return;
            }
            List<String> wanted = gone.stream().filter(key -> asksFor(was.members().get(key))).toList();
            if (wanted.isEmpty()) {
                rows.add(new Row(Kind.MEMBERS_GONE, Repair.ONLY_BY_TICK, path, 0, Members.outermost(gone),
                        List.of()));
            } else {
                filesGone.add(path);
                wanted.forEach(key -> asked.add(was.members().get(key).name()));
            }
        }

        /** Compares one file that differs; {@code was} is null when the repair point has no such file. */
        private void changed(String path, Reading was, Reading now) {
            List<Problem> here = errors.getOrDefault(path, List.of());
            Map<String, Member> before = was == null ? Map.of() : was.members();
            if (now.unreadable()) {
                if (!before.isEmpty() || was == null) {
                    errors.remove(path);
                    rows.add(new Row(Kind.UNREADABLE_FILE, was == null ? Repair.NOTHING : Repair.WHOLE_FILE, path, 0,
                            Members.outermost(List.copyOf(before.keySet())), List.of()));
                }
                return;
            }
            List<String> unmatched = new ArrayList<>();
            List<String> gone = new ArrayList<>();
            for (String key : before.keySet()) {
                Member read = now.members().get(key);
                if (read != null && !now.confined() && !read.isType() && !read.sameAs(before.get(key))) {
                    unmatched.add(key); // It reads as a member, past damage nothing bounds: unsure, not matched.
                } else if (read == null && !gained.contains(key)) {
                    (now.unsureMentions(before.get(key).name()) ? unmatched : gone).add(key);
                }
            }
            Set<String> badNames = new HashSet<>();
            now.bad().forEach(m -> badNames.add(m.name()));
            boolean blurred = now.hasSyntaxError() && (!now.confined()
                    || unmatched.stream().anyMatch(key -> !badNames.contains(before.get(key).name())));

            List<String> bad = new ArrayList<>();
            int line = now.firstErrorLine();
            if (blurred) {
                rows.add(new Row(Kind.BLURRED, was == null ? Repair.NOTHING : Repair.PUT_BACK_MEMBERS, path,
                        now.firstErrorLine(), Members.outermost(unmatched), List.of()));
            } else if (now.hasSyntaxError()) {
                now.bad().forEach(m -> bad.add(m.key()));
                unmatched.stream().filter(key -> !bad.contains(key)).forEach(bad::add);
            }
            for (Problem problem : here) {
                Member member = now.at(problem.line());
                Member old = member == null ? null : before.get(member.key());
                if (member == null || member.isType() || (old != null && old.sameAs(member))) {
                    elsewhere.add(problem);
                } else if (!bad.contains(member.key())) {
                    bad.add(member.key());
                    if (line == 0 || problem.line() < line) line = problem.line();
                }
            }
            errors.remove(path);

            List<String> stillGone = new ArrayList<>();
            for (String key : gone) {
                if (asksFor(before.get(key))) {
                    bad.add(key);
                    asked.add(before.get(key).name());
                } else {
                    stillGone.add(key);
                }
            }
            if (!bad.isEmpty()) {
                boolean hasCopy = bad.stream().anyMatch(before::containsKey);
                rows.add(new Row(Kind.BAD_MEMBER, hasCopy ? Repair.PUT_BACK_MEMBERS : Repair.NOTHING, path, line,
                        Members.outermost(bad), List.of()));
            }
            if (!stillGone.isEmpty()) {
                rows.add(new Row(Kind.MEMBERS_GONE, Repair.ONLY_BY_TICK, path, 0, Members.outermost(stillGone),
                        List.of()));
            }
        }

        private boolean asksFor(Member member) {
            return compile.problems().stream().anyMatch(p -> p.asksFor(member.name(), member.owner(), member.isType()));
        }
    }

    private static void pomRow(ProjectSide disk, Set<String> paths, Repair whenGone, Repair whenBad, List<Row> rows) {
        if (!paths.contains(POM)) {
            rows.add(new Row(Kind.NO_POM, whenGone, POM, 0, List.of(), List.of()));
        } else if (!readablePom(disk)) {
            rows.add(new Row(Kind.UNREADABLE_FILE, whenBad, POM, 0, List.of(), List.of()));
        }
    }

    private static boolean readablePom(ProjectSide disk) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null); // Its default prints to stderr; the row says it.
            return "project".equals(builder.parse(new ByteArrayInputStream(disk.read(POM))).getDocumentElement()
                    .getNodeName());
        } catch (Exception e) {
            return false;
        }
    }
}
