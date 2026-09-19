package com.botmaker.cli.release;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsTest {

    private static final Version V = new Version(1, 1, 7);

    @Test
    void theVerdictIsTheWorstOfEveryRunOnTheTag() {
        // Several workflows fire on one tag — studio's package matrix, its pages deploy and its JReleaser
        // step are three — and one failure is a release whose notes may not exist.
        String tsv = """
                package\tcompleted\tsuccess\thttps://example.invalid/1
                release\tcompleted\tfailure\thttps://example.invalid/2
                pages\tcompleted\tsuccess\thttps://example.invalid/3
                """;

        Actions.Poll poll = Actions.verdict(tsv, V);

        assertEquals("FAILED — release", poll.verdict());
        assertEquals("release: failure — https://example.invalid/2", poll.error());
    }

    @Test
    void aRunStillGoingIsCountedRatherThanCalled() {
        String tsv = """
                package\tin_progress\t\thttps://example.invalid/1
                release\tcompleted\tsuccess\thttps://example.invalid/2
                """;

        assertEquals("running (1 of 2)", Actions.verdict(tsv, V).verdict());
    }

    @Test
    void aSkippedJobIsNotAFailure() {
        // A job that correctly did not apply to this tag has not gone wrong.
        String tsv = "pages\tcompleted\tskipped\thttps://example.invalid/1\n";

        assertEquals("success (1)", Actions.verdict(tsv, V).verdict());
    }

    @Test
    void noRunAtAllIsAFindingBecauseATagIsFinished() {
        // Deliberately unlike a pull request with no check run yet: nothing more will fire on a tag, so
        // this is a verdict rather than a state on the way to one.
        assertEquals("no run on v1.1.7", Actions.verdict("", V).verdict());
        assertTrue(Actions.verdict("", V).error().isEmpty());
    }

    @Test
    void theExcerptOfAFailedTestRunIsMavensReasonWithoutItsAdvice() {
        // Trimmed from botmaker-plugin-host v0.1.0's CI on 2026-09-16, tabs and timestamps as gh prints them.
        String log = """
                build\tUNKNOWN STEP\t﻿2026-09-16T10:25:31.6610499Z Current runner version: '2.337.0'
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1542359Z [ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.653 s <<< FAILURE! -- in com.botmaker.plugin.host.PluginLoaderTest
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1707360Z [ERROR] Failures:\s
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1708402Z [ERROR]   PluginLoaderTest.a_broken_plugin_does_not_cost_the_others:171 a plugin — p/Helper ==> expected: <true> but was: <false>
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1754386Z [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project botmaker-plugin-host: There are test failures.
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1755560Z [ERROR]\s
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1756469Z [ERROR] Please refer to /home/runner/work/botmaker-plugin-host/botmaker-plugin-host/target/surefire-reports for the individual test results.
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1759184Z [ERROR] -> [Help 1]
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1762139Z [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
                build\tUNKNOWN STEP\t2026-09-16T10:25:56.1927731Z ##[error]Process completed with exit code 1.
                """;

        assertEquals("""
                build: Tests run: 13, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.653 s <<< FAILURE! -- in com.botmaker.plugin.host.PluginLoaderTest
                build: Failures:
                build: PluginLoaderTest.a_broken_plugin_does_not_cost_the_others:171 a plugin — p/Helper ==> expected: <true> but was: <false>
                build: Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project botmaker-plugin-host: There are test failures.
                build: Process completed with exit code 1.""", Actions.excerpt(log));
    }

    @Test
    void aRunnerErrorKeepsTheCommandThatCausedItAndAMatrixSaysItOnce() {
        // Trimmed from botmaker-studio v1.1.0's package matrix: the ##[error] line alone does not say which
        // ref could not be fetched; the [command] before it does.
        String job = "package (ubuntu-latest, linux-x64, linux-x86_64)";
        String log = job + "\tUNKNOWN STEP\t2026-09-16T10:24:19.2623226Z [command]/usr/bin/git -c protocol.version=2"
                + " fetch --depth=1 origin +refs/tags/v0.1.0*:refs/tags/v0.1.0*\n"
                + job + "\tUNKNOWN STEP\t2026-09-16T10:24:19.4458121Z ##[error]The process '/usr/bin/git' failed"
                + " with exit code 1\n"
                + job + "\tUNKNOWN STEP\t2026-09-16T10:24:19.4458121Z ##[error]The process '/usr/bin/git' failed"
                + " with exit code 1\n";

        assertEquals(job + ": $ /usr/bin/git -c protocol.version=2 fetch --depth=1 origin"
                        + " +refs/tags/v0.1.0*:refs/tags/v0.1.0*\n"
                        + job + ": The process '/usr/bin/git' failed with exit code 1",
                Actions.excerpt(log));
    }

    @Test
    void aJavaExceptionIsTheReasonAndACommandFromAnEarlierActionIsNot() {
        // Trimmed from botmaker-session v0.0.15's release job (run 35347410408, attempt 1). JReleaser's own
        // action downloads the tool and died on a 504. The release record quoted the `tar` a PREVIOUS action
        // ran — same job, same step column, 40 lines up — and never the exception, which matched no prefix.
        String p = "release\tPublish the release\t2026-09-18T14:55:0";
        String log = p + "1.1Z [command]/usr/bin/tar xz --warning=no-unknown-keyword --overwrite -C /tmp/a -f /tmp/b\n"
                + p + "2.1Z ##[start-action display=Download JReleaser;id=__jreleaser_release-action.__run]\n"
                + p + "2.2Z ##[group]📦 Download JReleaser\n"
                + p + "3.1Z ☠️  JReleaser 1.25.0 could not be downloaded/copied\n"
                + p + "3.2Z java.io.IOException: Server returned HTTP response code: 504 for URL:"
                + " https://github.com/jreleaser/jreleaser/releases/download/v1.25.0/jreleaser-tool-provider-1.25.0.jar\n"
                + p + "3.3Z \tat java.base/sun.net.www.protocol.http.HttpURLConnection.getInputStream0(HttpURLConnection.java:2024)\n"
                + p + "3.4Z \tat get_jreleaser.main(get_jreleaser.java:87)\n"
                + p + "4.1Z ##[error]Process completed with exit code 1.\n";

        assertEquals("release: java.io.IOException: Server returned HTTP response code: 504 for URL:"
                        + " https://github.com/jreleaser/jreleaser/releases/download/v1.25.0/jreleaser-tool-provider-1.25.0.jar\n"
                        + "release: Process completed with exit code 1.",
                Actions.excerpt(log));
    }

    @Test
    void aCausedByLineIsKeptAndAMavenLineIsNotReadAsAnException() {
        String log = "build\ttest\t2026-09-18T10:00:00.0Z Exception in thread \"main\" java.lang.IllegalStateException: no config\n"
                + "build\ttest\t2026-09-18T10:00:00.1Z Caused by: java.nio.file.NoSuchFileException: /etc/botmaker.json\n"
                + "build\ttest\t2026-09-18T10:00:00.2Z [ERROR] java.lang.AssertionError: expected 1\n";

        assertEquals("""
                build: java.lang.IllegalStateException: no config
                build: Caused by: java.nio.file.NoSuchFileException: /etc/botmaker.json
                build: java.lang.AssertionError: expected 1""", Actions.excerpt(log));
    }

    @Test
    void aNodeActionsErrorLineIsKeptAndItsStackIsNot() {
        // botmaker-remote v0.0.1: android-actions/setup-android died inside its own dist/index.js. Neither
        // [ERROR] nor ##[error] appears; the one line that says what failed starts with "Error: ".
        String job = "apk";
        String log = job + "\tRun android-actions/setup-android@v3\t2026-09-17T15:12:45.8Z Warning: Failed to"
                + " find package 'tools'\n"
                + job + "\tRun android-actions/setup-android@v3\t2026-09-17T15:12:45.8Z Error: The process"
                + " '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager' failed with exit code 1\n"
                + job + "\tRun android-actions/setup-android@v3\t2026-09-17T15:12:45.8Z     at"
                + " ExecState._setResult (/home/runner/work/_actions/android-actions/setup-android/v3/dist/index.js:1823:25)\n";

        assertEquals(job + ": The process '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager'"
                + " failed with exit code 1", Actions.excerpt(log));
    }

    @Test
    void aTagWhoseRunHasNotAppearedYetIsWaitedForRatherThanCalledFailed() {
        // The chain polls seconds after the push. botmaker-remote-server v0.0.2 and v0.0.3 both read
        // "no run on <tag>" — broken, with no error text — while their workflow was still being registered.
        java.util.List<java.time.Duration> waits = new java.util.ArrayList<>();
        java.util.Iterator<String> answers = java.util.List.of("", "",
                "release\tin_progress\t\thttps://example.invalid/1\t9").iterator();

        Actions.Poll poll = Actions.poll(Module.REMOTE_SERVER, V, answers::next, waits::add);

        assertEquals("running (1 of 1)", poll.verdict());
        assertEquals(java.util.List.of(Actions.APPEAR_INTERVAL, Actions.APPEAR_INTERVAL), waits);
    }

    @Test
    void aRunThatIsAlreadyThereIsNotWaitedOn() {
        java.util.List<java.time.Duration> waits = new java.util.ArrayList<>();

        Actions.Poll poll = Actions.poll(Module.REMOTE_SERVER, V,
                () -> "release\tcompleted\tsuccess\thttps://example.invalid/1\t9", waits::add);

        assertEquals("success (1)", poll.verdict());
        assertTrue(waits.isEmpty());
    }

    @Test
    void theWindowEndsAndNoRunAtAllIsStillAVerdict() {
        // A tag that fires nothing is the failure this column was added to catch; the wait only makes the
        // sentence true. The window is asked for exactly as many intervals as it holds.
        java.util.List<java.time.Duration> waits = new java.util.ArrayList<>();

        Actions.Poll poll = Actions.poll(Module.REMOTE_SERVER, V, () -> "", waits::add);

        assertEquals("no run on v1.1.7", poll.verdict());
        assertEquals(Actions.APPEAR_WINDOW.dividedBy(Actions.APPEAR_INTERVAL), waits.size());
    }

    @Test
    void theExcerptIsCapped() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            log.append("build\tstep\t2026-09-16T10:00:00.0Z [ERROR] failure ").append(i).append('\n');
        }

        assertEquals(Actions.EXCERPT_LINES, Actions.excerpt(log.toString()).lines().count());
    }

    @Test
    void onlyFinishedRunsThatDidNotSucceedAreFetched() {
        String tsv = """
                CI\tcompleted\tfailure\thttps://x/1\t35084897733
                CI\tcompleted\tsuccess\thttps://x/2\t2
                CI\tin_progress\t\thttps://x/3\t3
                pages\tcompleted\tskipped\thttps://x/4\t4
                """;

        assertEquals(java.util.List.of("35084897733"), Actions.failedRunIds(tsv));
    }

    @Test
    void aFailureNamesEveryFailingRunAndItsUrl() {
        String tsv = """
                ci\tcompleted\ttimed_out\thttps://example.invalid/1
                release\tcompleted\tcancelled\thttps://example.invalid/2
                """;

        Actions.Poll poll = Actions.verdict(tsv, V);

        assertEquals("FAILED — ci, release", poll.verdict());
        assertEquals("""
                ci: timed_out — https://example.invalid/1
                release: cancelled — https://example.invalid/2""", poll.error());
    }

    // -------------------------------------------------------------------------
    // The one run worth a click
    // -------------------------------------------------------------------------

    /**
     * The verdict is the worst of several runs, and so is the link: an operator opening the page is opening
     * it to read the failure, not the matrix job beside it that passed.
     */
    @Test
    void theRunToOpenIsTheFailingOneWhenThereIsOne() {
        String tsv = """
                package\tcompleted\tsuccess\thttps://example.invalid/1
                release\tcompleted\tfailure\thttps://example.invalid/2
                """;

        assertEquals("https://example.invalid/2", Actions.verdict(tsv, V).url());
    }

    @Test
    void whileOneRunIsStillGoingThatIsTheOneToOpen() {
        String tsv = """
                package\tcompleted\tsuccess\thttps://example.invalid/1
                release\tin_progress\t\thttps://example.invalid/2
                """;

        assertEquals("https://example.invalid/2", Actions.verdict(tsv, V).url());
    }

    /** Everything passed: the newest run, which is the first line {@code gh run list} returns. */
    @Test
    void aTagWhereEverythingPassedOpensTheNewestRun() {
        String tsv = """
                package\tcompleted\tsuccess\thttps://example.invalid/1
                release\tcompleted\tskipped\thttps://example.invalid/2
                """;

        assertEquals("https://example.invalid/1", Actions.verdict(tsv, V).url());
    }

    /** No run at all: no link, and the caller falls back to the repository's filtered list. */
    @Test
    void aTagWithNoRunHasNothingToOpen() {
        Actions.Poll poll = Actions.verdict("", V);

        assertEquals("no run on v1.1.7", poll.verdict());
        assertEquals("", poll.url());
    }
}
