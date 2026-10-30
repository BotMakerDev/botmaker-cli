package com.botmaker.cli.repair;

import com.botmaker.cli.repair.Compile.Problem;
import com.botmaker.cli.repair.Damage.Kind;
import com.botmaker.cli.repair.Damage.Report;
import com.botmaker.cli.repair.Damage.Repair;
import com.botmaker.cli.repair.Damage.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One damaged fixture per row of §4 of the data-safety spec, and how far recovery is trusted past a bad bracket. */
class DamageTest {

    private static final String BOT = "src/main/java/com/x/Bot.java";
    private static final String POM_TEXT = "<project><modelVersion>4.0.0</modelVersion></project>";

    private static final String GOOD = """
            package com.x;
            class Bot {
                static int speed = 5;
                void mine() {
                    click(1);
                }
                void home() {
                    log("home");
                }
                void last() {
                    log("last");
                }
                enum Color { RED, BLUE }
            }
            """;

    /** A project in memory. */
    private static ProjectSide side(Map<String, byte[]> files) {
        return new ProjectSide() {
            @Override
            public Set<String> paths() {
                return files.keySet();
            }

            @Override
            public long size(String path) {
                return files.get(path).length;
            }

            @Override
            public byte[] read(String path) {
                return files.get(path);
            }
        };
    }

    private static Map<String, byte[]> project() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(Damage.POM, POM_TEXT.getBytes(StandardCharsets.UTF_8));
        files.put(BOT, GOOD.getBytes(StandardCharsets.UTF_8));
        files.put("images/play.png", new byte[]{1, 2, 3});
        return files;
    }

    private static Report withBot(String source, Compile compile) throws IOException {
        Map<String, byte[]> disk = project();
        disk.put(BOT, source.getBytes(StandardCharsets.UTF_8));
        return Damage.compare(side(project()), side(disk), compile);
    }

    private static Row only(Report report) {
        assertEquals(1, report.rows().size(), () -> "rows: " + report.rows());
        return report.rows().getFirst();
    }

    @Test
    void an_untouched_project_has_no_rows() throws IOException {
        assertTrue(Damage.compare(side(project()), side(project()), Compile.clean()).clean());
    }

    @Test
    void a_reformatted_or_edited_project_that_compiles_is_not_damage() throws IOException {
        String edited = GOOD.replace("click(1);", "click(2);\n        click(3);").replace("\n", "\r\n");
        assertTrue(withBot(edited, Compile.clean()).clean());
    }

    @Test
    void a_syntax_error_inside_a_member_is_that_member() throws IOException {
        Row row = only(withBot(GOOD.replace("click(1);", "click(;"), Compile.failed(List.of())));
        assertEquals(Kind.BAD_MEMBER, row.kind());
        assertEquals(Repair.PUT_BACK_MEMBERS, row.repair());
        assertEquals(List.of("Bot#mine()"), row.members());
        assertEquals(5, row.line());
    }

    @Test
    void two_members_each_with_a_bad_line_stay_two_members() throws IOException {
        String source = GOOD.replace("click(1);", "click(;").replace("log(\"last\");", "log(;");
        Row row = only(withBot(source, Compile.failed(List.of())));
        assertEquals(Kind.BAD_MEMBER, row.kind());
        assertEquals(List.of("Bot#mine()", "Bot#last()"), row.members());
    }

    @Test
    void a_type_error_is_the_member_it_is_in_when_that_member_changed() throws IOException {
        String source = GOOD.replace("click(1);", "click(\"a\");");
        Row row = only(withBot(source, Compile.failed(List.of(new Problem(BOT, 5, "incompatible types")))));
        assertEquals(Kind.BAD_MEMBER, row.kind());
        assertEquals(List.of("Bot#mine()"), row.members());
    }

    @Test
    void an_error_in_an_unchanged_member_is_caused_elsewhere() throws IOException {
        String source = GOOD.replace("click(1);", "click(2);");
        Problem problem = new Problem(BOT, 8, "cannot find symbol method log");
        Report report = withBot(source, Compile.failed(List.of(problem)));
        assertTrue(report.rows().isEmpty(), () -> "rows: " + report.rows());
        assertEquals(List.of(problem), report.elsewhere());
    }

    @Test
    void a_missing_method_bracket_that_leaves_the_others_whole_is_that_method() throws IOException {
        Row closing = only(withBot(GOOD.replace("click(1);\n    }", "click(1);\n"), Compile.failed(List.of())));
        assertEquals(Kind.BAD_MEMBER, closing.kind());
        assertEquals(List.of("Bot#mine()"), closing.members());
        Row opening = only(withBot(GOOD.replace("void home() {", "void home()"), Compile.failed(List.of())));
        assertEquals(Kind.BAD_MEMBER, opening.kind());
        assertEquals(List.of("Bot#home()"), opening.members());
    }

    @Test
    void a_lost_bracket_recovery_misreads_blurs_from_there_and_trusts_only_what_came_before() throws IOException {
        // Without its {, the if's } closes mine() early and the rest of mine() reads as a method c(): both untrue.
        String source = GOOD.replace("static int speed = 5;", "static int speed = 5;\n    static int more = 6;")
                .replace("click(1);", "if (a)\n            b();\n        }\n        c();");
        Row row = only(withBot(source, Compile.failed(List.of())));
        assertEquals(Kind.BLURRED, row.kind());
        assertEquals(Repair.PUT_BACK_MEMBERS, row.repair());
        assertTrue(row.members().containsAll(List.of("Bot#mine()", "Bot#home()", "Bot#last()")), row::toString);
        assertFalse(row.members().contains("Bot.speed"), "came before the damage: " + row);
        assertTrue(row.line() > 0);
    }

    @Test
    void an_unclosed_comment_blurs() throws IOException {
        Row row = only(withBot(GOOD.replace("void home()", "/* void home()"), Compile.failed(List.of())));
        assertEquals(Kind.BLURRED, row.kind());
        assertTrue(row.members().contains("Bot#home()"), row::toString);
    }

    /** Every character removed in turn: a syntax error always has a row, and no damaged member reads as trusted. */
    @Test
    void a_missing_character_anywhere_is_never_matched_as_whole() throws IOException {
        String good = GOOD.replace("click(1);", "if (a) {\n            click(1);\n        }\n        c();");
        Members.Reading whole = Members.read(good);
        Map<String, byte[]> point = project();
        point.put(BOT, good.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < good.length(); i++) {
            if (Character.isWhitespace(good.charAt(i))) continue;
            String source = good.substring(0, i) + good.substring(i + 1);
            Members.Reading reading = Members.read(source);
            if (reading.unreadable() || !reading.hasSyntaxError()) continue;
            String at = "offset " + i + " '" + good.charAt(i) + "': ";
            Map<String, byte[]> disk = project();
            disk.put(BOT, source.getBytes(StandardCharsets.UTF_8));
            List<Row> rows = Damage.compare(side(point), side(disk), Compile.failed(List.of())).rows();
            assertFalse(rows.isEmpty(), at + "a syntax error and no row");
            for (Members.Member m : reading.members().values()) {
                Members.Member was = whole.members().get(m.key());
                if (m.isType() || was == null || was.sameAs(m)) continue;
                assertTrue(rows.stream().anyMatch(row -> row.members().contains(m.key())),
                        at + m.key() + " differs and reads as whole: " + rows);
            }
        }
    }

    @Test
    void half_a_file_erased_still_compiling_is_listed_and_puts_nothing_back() throws IOException {
        String half = GOOD.substring(0, GOOD.indexOf("    void home()")) + "}\n";
        Row row = only(withBot(half, Compile.clean()));
        assertEquals(Kind.MEMBERS_GONE, row.kind());
        assertEquals(Repair.ONLY_BY_TICK, row.repair());
        assertEquals(List.of("Bot#home()", "Bot#last()", "Bot.Color"), row.members());
    }

    @Test
    void half_a_file_cut_mid_member_blurs() throws IOException {
        String half = GOOD.substring(0, GOOD.indexOf("log(\"home\")"));
        Report report = withBot(half, Compile.failed(List.of()));
        Row blurred = report.rows().stream().filter(r -> r.kind() == Kind.BLURRED || r.kind() == Kind.BAD_MEMBER)
                .findFirst().orElseThrow(() -> new AssertionError(report.rows()));
        assertTrue(blurred.members().contains("Bot#home()"), blurred::toString);
    }

    @Test
    void a_gone_member_comes_back_only_when_an_error_asks_for_it() throws IOException {
        String source = GOOD.replace("void home() {\n        log(\"home\");\n    }\n", "")
                .replace("void last() {\n        log(\"last\");\n    }\n", "")
                .replace("click(1);", "home();");
        Problem caller = new Problem(BOT, 5, "cannot find symbol", "home", "Bot");
        Report report = withBot(source, Compile.failed(List.of(caller)));
        Row bad = report.rows().stream().filter(r -> r.kind() == Kind.BAD_MEMBER).findFirst().orElseThrow();
        assertTrue(bad.members().contains("Bot#home()"), bad::toString);
        Row kept = report.rows().stream().filter(r -> r.kind() == Kind.MEMBERS_GONE).findFirst().orElseThrow();
        assertEquals(List.of("Bot#last()"), kept.members());
    }

    @Test
    void only_the_missing_symbol_in_its_own_type_asks_for_a_member() throws IOException {
        String source = GOOD.replace("void home() {\n        log(\"home\");\n    }\n", "");
        for (Problem other : List.of(new Problem(BOT, 5, "cannot find symbol: home"),
                new Problem(BOT, 5, "cannot find symbol", "home", "Other"))) {
            Row row = withBot(source, Compile.failed(List.of(other))).rows().stream()
                    .filter(r -> r.members().contains("Bot#home()")).findFirst().orElseThrow();
            assertEquals(Kind.MEMBERS_GONE, row.kind(), other::toString);
        }
    }

    @Test
    void a_member_lost_while_a_same_named_class_elsewhere_has_it_is_still_lost() throws IOException {
        String other = "src/main/java/com/y/Bot.java";
        Map<String, byte[]> then = project();
        then.put(other, GOOD.getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> disk = new LinkedHashMap<>(then);
        disk.put(BOT, GOOD.replace("void home() {\n        log(\"home\");\n    }\n", "").getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("Bot#home()"), only(Damage.compare(side(then), side(disk), Compile.clean())).members());
        disk.remove(BOT);
        assertEquals(Kind.MEMBERS_GONE, only(Damage.compare(side(then), side(disk), Compile.clean())).kind());
    }

    @Test
    void a_build_that_fails_with_no_line_to_name_is_not_clean(@TempDir Path dir) throws IOException {
        Compile unresolved = Compile.failed("[ERROR] Could not resolve dependencies for project x", dir);
        Report report = Damage.compare(side(project()), side(project()), unresolved);
        assertTrue(report.rows().isEmpty());
        assertFalse(report.clean());
    }

    @Test
    void an_info_file_is_a_plain_file() throws IOException {
        String info = "src/main/java/com/x/package-info.java";
        Map<String, byte[]> then = project();
        then.put(info, "package com.x;".getBytes(StandardCharsets.UTF_8));
        assertTrue(Damage.compare(side(then), side(then), Compile.clean()).clean());
        Map<String, byte[]> gone = new LinkedHashMap<>(then);
        gone.remove(info);
        assertEquals(List.of(info), only(Damage.compare(side(then), side(gone), Compile.clean())).files());
        assertTrue(Damage.compare(side(project()), side(then), Compile.clean()).clean(), "a new one is no damage");
    }

    @Test
    void an_empty_file_is_put_back_whole() throws IOException {
        Row row = only(withBot("  \n", Compile.clean()));
        assertEquals(Kind.UNREADABLE_FILE, row.kind());
        assertEquals(Repair.WHOLE_FILE, row.repair());
    }

    @Test
    void garbage_is_put_back_whole() throws IOException {
        assertEquals(Kind.UNREADABLE_FILE, only(withBot("asdf qwer ;;; 12", Compile.failed(List.of()))).kind());
        Map<String, byte[]> disk = project();
        disk.put(BOT, new byte[]{(byte) 0xff, (byte) 0xfe, 0, 7});
        Row row = only(Damage.compare(side(project()), side(disk), Compile.failed(List.of())));
        assertEquals(Kind.UNREADABLE_FILE, row.kind());
    }

    @Test
    void no_pom_comes_first_and_is_put_back() throws IOException {
        Map<String, byte[]> disk = project();
        disk.remove(Damage.POM);
        disk.remove("images/play.png");
        Report report = Damage.compare(side(project()), side(disk), Compile.notRun());
        assertEquals(Kind.NO_POM, report.rows().getFirst().kind());
        assertEquals(Repair.PUT_BACK_FILES, report.rows().getFirst().repair());
        assertEquals(Kind.FILES_GONE, report.rows().get(1).kind());
    }

    @Test
    void a_broken_pom_comes_first_and_is_put_back_whole() throws IOException {
        Map<String, byte[]> disk = project();
        disk.put(Damage.POM, "<project><oops".getBytes(StandardCharsets.UTF_8));
        Row row = only(Damage.compare(side(project()), side(disk), Compile.notRun()));
        assertEquals(Kind.UNREADABLE_FILE, row.kind());
        assertEquals(Damage.POM, row.path());
        assertEquals(Repair.WHOLE_FILE, row.repair());
    }

    @Test
    void files_gone_are_one_row_and_are_put_back() throws IOException {
        Map<String, byte[]> disk = project();
        disk.remove("images/play.png");
        Row row = only(Damage.compare(side(project()), side(disk), Compile.clean()));
        assertEquals(Kind.FILES_GONE, row.kind());
        assertEquals(Repair.PUT_BACK_FILES, row.repair());
        assertEquals(List.of("images/play.png"), row.files());
    }

    @Test
    void a_picture_emptied_is_put_back_whole() throws IOException {
        Map<String, byte[]> disk = project();
        disk.put("images/play.png", new byte[0]);
        Row row = only(Damage.compare(side(project()), side(disk), Compile.clean()));
        assertEquals(Kind.UNREADABLE_FILE, row.kind());
        assertEquals("images/play.png", row.path());
    }

    @Test
    void a_java_file_deleted_while_compiling_is_listed_not_put_back() throws IOException {
        Map<String, byte[]> disk = project();
        disk.remove(BOT);
        Row row = only(Damage.compare(side(project()), side(disk), Compile.clean()));
        assertEquals(Kind.MEMBERS_GONE, row.kind());
        assertEquals(List.of("Bot"), row.members());
    }

    @Test
    void a_class_moved_to_another_file_is_not_gone() throws IOException {
        Map<String, byte[]> disk = project();
        disk.put("src/main/java/com/y/Bot.java", disk.remove(BOT));
        assertTrue(Damage.compare(side(project()), side(disk), Compile.clean()).clean());
    }

    @Test
    void a_new_file_with_a_bad_line_has_nothing_to_put_back() throws IOException {
        Map<String, byte[]> disk = project();
        disk.put("src/main/java/com/x/New.java", "class New { void a() { b(; } }".getBytes(StandardCharsets.UTF_8));
        Row row = only(Damage.compare(side(project()), side(disk), Compile.failed(List.of())));
        assertEquals(Kind.BAD_MEMBER, row.kind());
        assertEquals(Repair.NOTHING, row.repair());
    }

    @Test
    void no_git_offers_the_relink_and_keeps_the_errors() throws IOException {
        Map<String, byte[]> disk = project();
        disk.remove(Damage.POM);
        Problem problem = new Problem(BOT, 1, "bad");
        Report report = Damage.noRepository(side(disk), Compile.failed(List.of(problem)));
        assertEquals(Kind.NO_POM, report.rows().getFirst().kind());
        assertEquals(Repair.NEW_POM, report.rows().getFirst().repair());
        assertEquals(Kind.NO_GIT, report.rows().get(1).kind());
        assertEquals(Repair.RELINK, report.rows().get(1).repair());
        assertEquals(List.of(problem), report.elsewhere());
    }

    @Test
    void nothing_to_compare_with_says_so_and_puts_nothing_back() throws IOException {
        Row row = only(Damage.noRepairPoint(side(project()), Compile.notRun()));
        assertEquals(Kind.NOTHING_TO_COMPARE, row.kind());
        assertEquals(Repair.NOTHING, row.repair());
    }

    @Test
    void a_folder_side_skips_git_and_target(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve(".git/x"));
        Files.writeString(dir.resolve(".git/x/f"), "x");
        Files.createDirectories(dir.resolve("target"));
        Files.writeString(dir.resolve("target/a.class"), "x");
        Files.createDirectories(dir.resolve("src/target"));
        Files.writeString(dir.resolve("src/target/kept.txt"), "x");
        assertEquals(Set.of("src/target/kept.txt"), ProjectSide.folder(dir).paths());
    }

    @Test
    void a_folder_side_reads_a_link_as_its_target_name_and_refuses_what_is_no_file(@TempDir Path dir)
            throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "never read");
        Files.createSymbolicLink(dir.resolve("L.java"), secret);
        Files.createDirectory(dir.resolve("folder.java"));
        ProjectSide side = ProjectSide.folder(dir);
        assertEquals(secret.toString(), new String(side.read("L.java"), StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> side.read("folder.java"));
    }

    @Test
    void maven_says_whether_a_folder_compiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(Damage.POM), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>x</groupId><artifactId>x</artifactId><version>1</version>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                  <build><plugins><plugin>
                    <artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version>
                  </plugin></plugins></build>
                </project>
                """);
        Path source = dir.resolve("src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A { }");
        assertTrue(Compile.compiles(dir));
        Files.writeString(source, "class A { int x = \"no\"; }");
        assertFalse(Compile.compiles(dir));
    }

    @Test
    void a_compiles_output_reads_in_javac_and_maven_form(@TempDir Path dir) {
        String abs = dir.resolve(BOT).toString();
        Compile javac = Compile.failed(abs + ":5: error: cannot find symbol\n  click();\n  ^\n  symbol:   method click()\n"
                + "  location: class com.x.Bot\n1 error\n", dir);
        assertEquals(List.of(new Problem(BOT, 5, "cannot find symbol", "click", "Bot")), javac.problems());
        Compile onVariable = Compile.failed(abs + ":5: error: cannot find symbol\n  symbol:   method click()\n"
                + "  location: variable b of type Bot<String>\n", dir);
        assertEquals("Bot", onVariable.problems().getFirst().owner());
        String line = "[ERROR] " + abs + ":[5,3] cannot find symbol\n[ERROR]   symbol:   method click()\n"
                + "[ERROR]   location: class com.x.Bot\n";
        assertEquals(javac.problems(), Compile.failed(line + line, dir).problems());
    }
}
