package com.botmaker.cli.gallery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unpacking somebody else's published bot, and the one thing that changes on the way in.
 *
 * <p>Nothing here reaches the network: what is worth holding is the <em>rewrite</em> — that a template
 * arrives as its author shipped it except for its package — plus the zip-slip guard, which is the one place
 * a downloaded archive gets to choose a path on this machine.
 */
class TemplatesTest {

    @TempDir
    Path root;

    // ---- the rewrite -----------------------------------------------------------------------------------

    @Test
    void only_the_package_changes_and_every_type_keeps_its_name() throws Exception {
        Path project = template("com.botmaker.gamebot");

        Templates.repackage(project, "com.myfarmer");

        Path moved = project.resolve("src/main/java/com/myfarmer/GameBot.java");
        assertTrue(Files.isRegularFile(moved), "the sources move with the package");
        String source = Files.readString(moved);
        assertTrue(source.startsWith("package com.myfarmer;"), source);
        assertTrue(source.contains("class GameBot"), "the author's type name survives:\n" + source);
        assertTrue(source.contains("new Helper()"), source);
        assertTrue(Files.isRegularFile(project.resolve("src/main/java/com/myfarmer/Helper.java")));
        assertFalse(Files.exists(project.resolve("src/main/java/com/botmaker")),
                "the empty shell directories are pruned");
    }

    /** An older release's declaration file is read by nothing, and the copy does not keep it. */
    @Test
    void an_old_declaration_file_is_ignored_and_removed() throws Exception {
        Path project = template("com.botmaker.gamebot");
        Files.writeString(project.resolve("botmaker-template.properties"), "package=com.somewhere.else\n");

        assertEquals("com.botmaker.gamebot", Templates.packageOf(project));
        Templates.repackage(project, "com.myfarmer");

        assertFalse(Files.exists(project.resolve("botmaker-template.properties")));
    }

    /** The prefix is replaced in every text file, the pom included — that is what keeps the build valid. */
    @Test
    void the_prefix_is_replaced_in_every_text_file() throws Exception {
        Path project = template("com.botmaker.gamebot");

        Templates.repackage(project, "com.myfarmer");

        assertTrue(Files.readString(project.resolve("pom.xml")).contains("<groupId>com.myfarmer</groupId>"));
    }

    /** A main whose package line disagrees with its directory names a package with no sources in it. */
    @Test
    void a_template_whose_main_names_a_package_it_does_not_have_is_refused() throws Exception {
        Path project = template("com.botmaker.gamebot");
        Path entry = project.resolve("src/main/java/com/botmaker/gamebot/GameBot.java");
        Files.writeString(entry, Files.readString(entry).replace("package com.botmaker.gamebot;",
                "package com.somewhere.else;"));

        IOException refused =
                assertThrows(IOException.class, () -> Templates.repackage(project, "com.myfarmer"));
        assertTrue(refused.getMessage().contains("com.somewhere.else"), refused.getMessage());
    }

    @Test
    void a_template_with_no_main_is_refused() throws Exception {
        Path project = template("com.botmaker.gamebot");
        Files.delete(project.resolve("src/main/java/com/botmaker/gamebot/GameBot.java"));

        IOException refused =
                assertThrows(IOException.class, () -> Templates.repackage(project, "com.myfarmer"));
        assertTrue(refused.getMessage().contains("main method"), refused.getMessage());
    }

    // ---- the archive -----------------------------------------------------------------------------------

    @Test
    void the_single_top_level_directory_github_wraps_everything_in_is_stripped() throws Exception {
        byte[] zip = zip("LiQiyeDev-gamebot-9a1b2c3/pom.xml", "<project/>",
                "LiQiyeDev-gamebot-9a1b2c3/src/main/java/A.java", "class A {}");
        Path dest = root.resolve("unpacked");

        Templates.unzipStrippingTopDirectory(zip, dest);

        assertEquals("<project/>", Files.readString(dest.resolve("pom.xml")));
        assertTrue(Files.isRegularFile(dest.resolve("src/main/java/A.java")));
    }

    /** An archive is the one input here that gets to name a path on this machine. */
    @Test
    void an_entry_escaping_the_destination_is_refused() throws Exception {
        byte[] zip = zip("repo-sha/../../evil.txt", "owned");

        assertThrows(IOException.class,
                () -> Templates.unzipStrippingTopDirectory(zip, root.resolve("unpacked")));
    }

    /**
     * The project's coordinate takes the new name; a dependency that happens to share the old one does not.
     * Before this, `bot new farm --from …` produced a project called farm that built base-0.0.1-SNAPSHOT.jar
     * and collided in ~/.m2 with every other copy of the same template.
     */
    @Test
    void the_project_is_renamed_and_a_dependency_of_the_same_name_is_not() throws Exception {
        Path project = template("com.author.gamebot");

        Templates.repackage(project, "com.myfarmer", "farm");

        String pom = Files.readString(project.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>farm</artifactId>"), pom);
        assertEquals(1, pom.split("<artifactId>base</artifactId>", -1).length - 1,
                "the dependency keeps its own artifactId:\n" + pom);
        assertTrue(pom.contains("<groupId>com.myfarmer</groupId>"), pom);
        assertTrue(pom.contains("<groupId>com.elsewhere</groupId>"), pom);
    }

    /** No new name, no rename — the two-argument form is unchanged for every caller that had it. */
    @Test
    void the_coordinate_is_left_alone_when_no_name_is_given() throws Exception {
        Path project = template("com.author.gamebot");

        Templates.repackage(project, "com.myfarmer");

        assertTrue(Files.readString(project.resolve("pom.xml")).contains("<artifactId>base</artifactId>"));
    }

    // ---- fixtures --------------------------------------------------------------------------------------

    /** A template as its author shipped it: two classes in one package, one of them with main, and a pom. */
    private Path template(String packageName) throws IOException {
        Path project = root.resolve("template");
        Path sources = project.resolve("src/main/java").resolve(packageName.replace('.', '/'));
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("GameBot.java"), """
                package %s;

                public class GameBot {
                    public static void main(String[] args) {
                        new GameBot().go();
                    }

                    void go() {
                        new Helper();
                    }
                }
                """.formatted(packageName));
        Files.writeString(sources.resolve("Helper.java"),
                "package " + packageName + ";\n\nclass Helper {\n}\n");
        // A dependency whose artifactId is the project's own name, deliberately: the rename must move the
        // project's coordinate and leave the dependency alone.
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                    <groupId>%s</groupId>
                    <artifactId>base</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.elsewhere</groupId>
                            <artifactId>base</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(packageName));
        return project;
    }

    private static byte[] zip(String... pathsAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < pathsAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(pathsAndContents[i]));
                out.write(pathsAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
