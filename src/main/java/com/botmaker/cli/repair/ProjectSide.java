package com.botmaker.cli.repair;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;

/** One side of a {@link Damage} comparison, a project's files by path with {@code /}: a folder, or a commit. */
public interface ProjectSide {

    Set<String> paths() throws IOException;

    long size(String path) throws IOException;

    byte[] read(String path) throws IOException;

    /** The project on disk, without {@code .git} and the build's {@code target}. */
    static ProjectSide folder(Path dir) {
        return new ProjectSide() {
            @Override
            public Set<String> paths() throws IOException {
                Set<String> paths = new TreeSet<>();
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs) {
                        boolean skipped = folder.equals(dir.resolve(".git")) || folder.equals(dir.resolve("target"));
                        return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        // A .git file: a submodule's or a worktree's link, not part of the project.
                        if (!file.equals(dir.resolve(".git"))) paths.add(path(file));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        // A file that cannot be opened is still there: its row says it cannot be read.
                        paths.add(path(file));
                        return FileVisitResult.CONTINUE;
                    }
                });
                return paths;
            }

            private String path(Path file) {
                return dir.relativize(file).toString().replace('\\', '/');
            }

            @Override
            public long size(String path) throws IOException {
                Path file = dir.resolve(path);
                return Files.isSymbolicLink(file) ? Files.readSymbolicLink(file).toString().length()
                        : Files.size(file);
            }

            @Override
            public byte[] read(String path) throws IOException {
                Path file = dir.resolve(path);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(path + " is gone");
                return Files.readAllBytes(file);
            }
        };
    }
}
