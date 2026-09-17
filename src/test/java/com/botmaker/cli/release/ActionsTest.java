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
}
