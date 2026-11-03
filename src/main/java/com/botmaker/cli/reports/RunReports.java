package com.botmaker.cli.reports;

import com.botmaker.runlog.RunLog;
import com.botmaker.runlog.RunLogs;
import com.botmaker.runlog.api.Report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The problems of a bot's run logs on this computer, read through {@link RunLogs} whatever their format. */
public final class RunReports {

    /** A run's log in its run folder. */
    public static final String LOG = RunLog.FILE;

    private RunReports() {
    }

    /**
     * The problems of {@code path}: a {@code run.jsonl}, a run's folder, or a folder of run folders
     * ({@code target/botmaker-runs}) — every run in it.
     */
    public static Reports read(Path path) {
        List<String> notes = new ArrayList<>();
        Report report = Report.EMPTY;
        List<Path> logs = logs(path);
        if (logs.isEmpty()) notes.add("No run log (" + LOG + ") in " + path + ".");
        for (Path log : logs) {
            switch (RunLogs.read(log)) {
                case RunLogs.Readable read -> report = report.plus(read.report());
                case RunLogs.Unreadable why -> notes.add(log + ": " + why.reason());
            }
        }
        return new Reports(path.toString(), report, Map.of(), notes);
    }

    private static List<Path> logs(Path path) {
        if (Files.isRegularFile(path)) return List.of(path);
        if (Files.isRegularFile(path.resolve(LOG))) return List.of(path.resolve(LOG));
        if (!Files.isDirectory(path)) return List.of();
        try (Stream<Path> runs = Files.list(path)) {
            return runs.map(run -> run.resolve(LOG)).filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
