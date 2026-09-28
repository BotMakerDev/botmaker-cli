package com.botmaker.cli;

import com.botmaker.cli.validate.Check;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.cli.validate.Status;

import java.util.Arrays;
import java.util.List;

/**
 * Everything this program prints.
 *
 * <p>Diagnostics go to {@code stderr} and results go to {@code stdout}, which is not decoration: a plugin
 * author pipes {@code botmaker plugin publish --dry-run} into a file, and a progress line landing in that file
 * makes it not-JSON.
 */
public final class Console {

    /** The escape, written as an octal escape rather than a literal byte so the source stays greppable. */
    private static final String ESC = "\033";

    private final boolean quiet;
    private final boolean colour;

    public Console(boolean quiet) {
        this.quiet = quiet;
        // No colour when stdout is redirected. System.console() is null under a pipe and under CI, which is
        // exactly where an escape sequence turns a readable log into a smear of brackets.
        this.colour = System.console() != null && !"dumb".equals(System.getenv("TERM"));
    }

    /** Progress — suppressed by {@code --quiet}, and never on stdout. */
    public void step(String message) {
        if (!quiet) {
            System.err.println(message);
        }
    }

    /** The thing the user asked for. */
    public void out(String message) {
        System.out.println(message);
    }

    public void error(String message) {
        System.err.println(paint("31", "error: ") + message);
    }

    public void warn(String message) {
        System.err.println(paint("33", "warning: ") + message);
    }

    /**
     * The validation report: one line per check, then every detail indented under it.
     *
     * <p>All seven are always printed, passes included. A report that listed only failures would leave an
     * author unable to tell a check that passed from one that never ran, and {@link Status#SKIP} is a real
     * outcome here — the editors check skips without JavaFX, and the registry-collision half of two checks
     * cannot be asked at all outside the registry's own CI.
     */
    public void report(List<CheckResult> results) {
        report(results, System.out);
    }

    /**
     * The same report, on {@code stderr}.
     *
     * <p>For {@code botmaker plugin publish}, whose <em>result</em> is the entry JSON: a report sharing that stream
     * makes {@code --dry-run > entry.json} produce something no parser will read, which is exactly the
     * failure the stdout/stderr split above exists to prevent. Validation is a diagnostic there and the
     * entry is the answer.
     */
    public void reportAside(List<CheckResult> results) {
        report(results, System.err);
    }

    private void report(List<CheckResult> results, java.io.PrintStream stream) {
        for (CheckResult result : results) {
            stream.println(badge(result.status()) + " " + pad(result.check().id()) + result.check().title());
            for (String detail : result.detail()) {
                stream.println("         " + detail);
            }
        }
    }

    private String badge(Status status) {
        return switch (status) {
            case PASS -> paint("32", "  ok  ");
            case FAIL -> paint("31", " FAIL ");
            case SKIP -> paint("33", " skip ");
        };
    }

    /** One past the longest check id, so a check added with a longer id keeps the column. */
    private static final int ID_COLUMN = Arrays.stream(Check.values())
            .mapToInt(check -> check.id().length()).max().orElse(13) + 1;

    private static String pad(String id) {
        return id.length() >= ID_COLUMN ? id + " " : id + " ".repeat(ID_COLUMN - id.length());
    }

    private String paint(String code, String text) {
        return colour ? ESC + "[" + code + "m" + text + ESC + "[0m" : text;
    }
}
