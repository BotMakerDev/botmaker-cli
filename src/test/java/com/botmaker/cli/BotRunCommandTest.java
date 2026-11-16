// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code botmaker bot run}'s flags for several runs, read as picocli reads them; none here reaches a core. */
class BotRunCommandTest {

    private static CommandLine.ParseResult run(String... argv) {
        CommandLine.ParseResult top = new CommandLine(new Main()).parseArgs(argv);
        return top.subcommand().subcommand();
    }

    @Test
    void severalRunsReadTheirCountPinsAndHow() {
        CommandLine.ParseResult parsed = run("bot", "run", "farm", "--runs", "3", "--dry-run", "--all-or-none", "-y",
                "--pin", "Farm#account=com.example.Account.MAIN", "--pin", "2:INSTANCE=Instance.named(\"ld-2\")");
        assertEquals("run", parsed.commandSpec().name());
        assertEquals(3, (int) parsed.matchedOptionValue("--runs", 0));
        assertTrue(parsed.matchedOptionValue("--dry-run", false));
        assertTrue(parsed.matchedOptionValue("--all-or-none", false));
        assertTrue(parsed.matchedOptionValue("--yes", false));
        assertEquals(List.of("Farm#account=com.example.Account.MAIN", "2:INSTANCE=Instance.named(\"ld-2\")"),
                parsed.matchedOptionValue("--pin", List.of()));
    }

    @Test
    void theFlagsOfSeveralRunsWithoutRunsOrWithAnEntryAreRefusedBeforeAnyCore() {
        for (String[] argv : List.of(new String[]{"bot", "run", "--dry-run"},
                new String[]{"bot", "run", "--pin", "a=b"}, new String[]{"bot", "run", "--all-or-none"},
                new String[]{"bot", "run", "--runs", "2", "--entry", "Explore map"})) {
            assertEquals(2, new CommandLine(new Main()).execute(argv), String.join(" ", argv));
        }
    }
}
