package com.botmaker.cli.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code --status [file]}: re-poll a release log that already exists.
 *
 * <p><b>Both columns, and both through the release's own readers.</b> JitPack goes through the very same
 * {@link CleanRoom} the release used — so the two can never disagree about what <i>ok</i> means, which is
 * the same reason the changelog has one extractor and two callers — and Actions through {@link Actions}.
 *
 * <p><b>Idempotent by construction</b>: the file is rewritten from scratch, so running it a week later
 * records what CI looks like a week later, as a reviewable diff. That is the point of committing the log at
 * all — a verdict that only ever existed in a terminal is a verdict nobody read.
 *
 * <p>Whether a {@code BROKEN} verdict should also make the command exit non-zero is <b>still open</b>, and
 * the argument against is real: no exit code can recall a pushed tag, and a red command that cannot be acted
 * on trains people to ignore it. Until that is decided this reports and exits 0, exactly as the script does.
 */
public final class ReleaseStatus {

    private ReleaseStatus() {
    }

    /**
     * @param file the log to re-poll, or empty for the newest in {@code releases/}
     * @return the rewritten rows, so a caller can print a summary without re-reading the file
     */
    public static List<ReleaseLog.Row> repoll(Runner runner, Path umbrella, Optional<Path> file) {
        Path log = file.orElseGet(() -> ReleaseLog.newest(umbrella));
        if (!Files.isRegularFile(log)) {
            throw new ReleaseRefusal(log + ": no such release log.");
        }
        runner.say("Re-polling " + log.getFileName());

        List<ReleaseLog.Row> rows = new ArrayList<>();
        for (ReleaseLog.Row row : ReleaseLog.read(log)) {
            ReleaseLog.Row polled = row;
            if (!row.stage().tagged()) {
                // A row the release never tagged keeps its stage and its failure: there is no tag to ask
                // JitPack or Actions about, and the reason it stopped is not something a poll can re-derive.
                runner.say("    " + row.module().directory() + " " + row.version().tag()
                        + " — " + row.stage().cell() + ", not polled");
                rows.add(row);
                continue;
            }

            if (ReleaseLog.onJitpack(row.module())) {
                Optional<String> broken = CleanRoom.resolve(runner, row.module(), row.version());
                polled = broken.isPresent()
                        ? polled.withJitpack("BROKEN", broken.get())
                        : polled.withJitpack("ok (resolves clean)", "");
            }

            Actions.Poll actions = Actions.poll(row.module(), row.version());
            polled = polled.withActions(actions.verdict(), actions.error());

            runner.say("    " + row.module().directory() + " " + row.version().tag()
                    + " — jitpack: " + polled.jitpackCell() + " · actions: " + polled.actionsCell());
            rows.add(polled);
        }

        // Rewritten whole, never patched in place: a half-updated table is the one output worse than a
        // stale one, because it looks current.
        runner.write(log, ReleaseLog.render(stampOf(log), rows));
        return List.copyOf(rows);
    }

    /**
     * The heading keeps the moment the release was cut, not the moment it was re-polled.
     *
     * <p>Read back from the file name rather than from the heading text: the name is what makes the log
     * sort, and a heading somebody edited by hand must not move the file's identity.
     */
    static LocalDateTime stampOf(Path log) {
        String name = log.getFileName().toString().replace(".md", "");
        try {
            return LocalDateTime.parse(name,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ReleaseRefusal(log + ": not a release log name (want YYYY-MM-DD-HHMM.md).");
        }
    }
}
