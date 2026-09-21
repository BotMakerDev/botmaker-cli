package com.botmaker.cli.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The pass after the chain that fills each row's JitPack and Actions cells — run for several rows at once.
 *
 * <p><b>It was a {@code for} loop, and the loop was the only thing making it serial.</b> Every tag is pushed
 * before it starts, the log is written once after it ends, and a row's two questions ({@link CleanRoom},
 * {@link Actions}) depend on no other row. Nine clean-room resolves at ~40 s each, plus up to a minute each of
 * waiting for a run to appear, were paid one after the other.
 *
 * <p><b>A bounded pool, not a parallel stream</b>: each resolve forks a real {@code mvn} into its own
 * temporary repository and downloads ~120 MB, so nine at once is nine Mavens competing for one link and one
 * disk. {@link #PARALLEL} is the cap.
 *
 * <p><b>Output stays in row order and grouped by module.</b> Each task narrates into its own {@link Runner}
 * over a buffer, and the buffers are flushed in row order as each head-of-line task finishes — so the
 * operator still sees progress, and never four modules' lines interleaved.
 *
 * <p><b>Nothing in the pass may stop a release</b>: a task that throws becomes that row's Actions cell. The
 * tags are already pushed; the log is what is left to write, and it must be written.
 */
public final class VerifyPass {

    /** How many rows are verified at once — each is one forked {@code mvn} and ~120 MB of downloads. */
    static final int PARALLEL = 4;

    /** One row's verification; it narrates through the runner it is handed and nothing else. */
    @FunctionalInterface
    interface Check {
        ReleaseLog.Row verify(Runner runner, ReleaseLog.Row row);
    }

    private VerifyPass() {
    }

    /** The real check: a clean-room resolve for a Maven artifact, then the tag's Actions runs. */
    static ReleaseLog.Row verify(Runner runner, ReleaseLog.Row row) {
        ReleaseLog.Row done = row;
        if (ReleaseLog.onJitpack(row.module())) {
            Optional<String> broken = CleanRoom.resolve(runner, row.module(), row.version());
            done = broken.isPresent()
                    ? done.withJitpack("BROKEN", broken.get())
                    : done.withJitpack("ok (resolves clean)", "");
        }
        if (row.module().template()) {
            // A template repository has no .github/workflows, so `no run on <tag>` — the failure this column
            // exists to catch — is the answer it would always give, and it would always be wrong.
            return done;
        }
        Actions.Poll actions = Actions.poll(row.module(), row.version());
        return done.withActions(actions.verdict(), actions.error(), actions.url());
    }

    /**
     * Runs {@code check} over every row, at most {@link #PARALLEL} at a time.
     *
     * @return the checked rows, in the order they were given, whatever order they finished in
     */
    static List<ReleaseLog.Row> run(Runner runner, List<ReleaseLog.Row> rows, Check check) {
        record Task(List<String> said, Future<ReleaseLog.Row> row) {
        }
        List<ReleaseLog.Row> done = new ArrayList<>(rows.size());
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL)) {
            List<Task> tasks = new ArrayList<>(rows.size());
            for (ReleaseLog.Row row : rows) {
                List<String> said = java.util.Collections.synchronizedList(new ArrayList<>());
                Runner own = new Runner(runner.dryRun(), said::add);
                tasks.add(new Task(said, pool.submit(() -> {
                    try {
                        return check.verify(own, row);
                    } catch (RuntimeException e) {
                        return row.withActions("unknown (the check failed)",
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                })));
            }
            for (int i = 0; i < tasks.size(); i++) {
                Task task = tasks.get(i);
                ReleaseLog.Row result;
                try {
                    result = task.row().get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result = rows.get(i);
                } catch (ExecutionException e) {
                    // Unreachable — the task catches — but a row is owed an answer either way.
                    result = rows.get(i);
                }
                // Copied under the list's own lock: the task is finished, but the list is still shared.
                List<String> said;
                synchronized (task.said()) {
                    said = List.copyOf(task.said());
                }
                said.forEach(runner::say);
                done.add(result);
            }
        }
        return List.copyOf(done);
    }
}
