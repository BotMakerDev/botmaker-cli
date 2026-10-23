package com.botmaker.cli.catalog;

import java.util.List;

/** One thing the check says about one path. An {@link Severity#ERROR} refuses the pull request. */
public record Finding(Severity severity, String path, String message) {

    public enum Severity { ERROR, WARNING }

    static Finding error(String path, String message) {
        return new Finding(Severity.ERROR, path, message);
    }

    static Finding warning(String path, String message) {
        return new Finding(Severity.WARNING, path, message);
    }

    /** Whether any of {@code findings} refuses. */
    public static boolean refused(List<Finding> findings) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
    }
}
