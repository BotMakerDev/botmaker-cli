package com.botmaker.cli.validate;

import java.util.List;
import java.util.Objects;

/**
 * What one {@link Check} answered, and why.
 *
 * <p>{@code detail} is a list rather than a string because the interesting failures are plural — three
 * catalog problems, two colliding value type ids — and a caller that joins them with a newline has thrown
 * away the structure a CI annotation wants. A passing result may carry detail too: "1 plugin, 4 facades" is
 * worth printing, and it is the only evidence that the check looked at anything at all.
 */
public record CheckResult(Check check, Status status, List<String> detail) {

    public CheckResult {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(status, "status");
        detail = List.copyOf(detail);
    }

    public static CheckResult pass(Check check, String... detail) {
        return new CheckResult(check, Status.PASS, List.of(detail));
    }

    public static CheckResult fail(Check check, List<String> detail) {
        return new CheckResult(check, Status.FAIL, detail);
    }

    public static CheckResult fail(Check check, String detail) {
        return new CheckResult(check, Status.FAIL, List.of(detail));
    }

    /**
     * The check could not run here, with the reason. A skip is not a pass and not a failure: it says the
     * question was not asked, so a report that hides skips is lying about its own coverage.
     */
    public static CheckResult skip(Check check, String why) {
        return new CheckResult(check, Status.SKIP, List.of(why));
    }

    /** The check passed with something worth fixing that no host refuses. */
    public static CheckResult warn(Check check, List<String> detail) {
        return new CheckResult(check, Status.WARN, detail);
    }

    public boolean failed() {
        return status == Status.FAIL;
    }

    /** {@code [FAIL] palette — …}, the one line a terminal and a CI log both get. */
    @Override
    public String toString() {
        return "[" + status + "] " + check.id() + " — " + check.title();
    }
}
