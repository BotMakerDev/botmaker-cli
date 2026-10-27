package com.botmaker.cli.publish;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files a plugin builds on JitPack with (2026-10-27): its {@code jitpack.yml} and BotMaker's own
 * {@code .jitpack/build.sh} with its {@code statx.c}, as this jar bundles them. The script is this module's
 * {@code .jitpack} copy of the umbrella's {@code tools/jitpack/build.sh}, put in the jar at its build like the
 * archetype's template zip; the {@code jitpack.yml} is the one a plugin needs, which runs it.
 *
 * <p>The cli owns them, not the plugin: {@code plugin new} writes them, and {@code plugin publish} writes them again
 * before it tags, so a plugin builds with the script of the {@code botmaker} that published it. Without them JitPack
 * builds on its default JDK and fails {@code release 25}.
 */
public final class JitpackFiles {

    /** Each file's path in the plugin's folder, in the order they are written. */
    public static final List<String> PATHS = List.of("jitpack.yml", ".jitpack/build.sh", ".jitpack/statx.c");

    private static final String RESOURCE = "/com/botmaker/cli/jitpack/";

    private JitpackFiles() {}

    /** Every file, by its path in the plugin's folder, as this jar bundles it. */
    public static Map<String, String> bundled() {
        Map<String, String> files = new LinkedHashMap<>();
        for (String path : PATHS) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            try (InputStream in = JitpackFiles.class.getResourceAsStream(RESOURCE + name)) {
                if (in == null) throw new IllegalStateException("this botmaker was built without " + RESOURCE + name);
                files.put(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    /** The files {@code dir} lacks or holds otherwise than {@link #bundled}, by path. */
    public static Map<String, String> stale(Path dir) {
        Map<String, String> stale = new LinkedHashMap<>();
        bundled().forEach((path, text) -> {
            Path file = dir.resolve(path);
            try {
                if (!Files.isRegularFile(file) || !Files.readString(file).equals(text)) stale.put(path, text);
            } catch (IOException unreadable) {
                stale.put(path, text);
            }
        });
        return stale;
    }

    /** {@code files} written into {@code dir}. */
    public static void write(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> each : files.entrySet()) {
            Path file = dir.resolve(each.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, each.getValue());
        }
    }
}
