// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.cli;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

/**
 * {@code botmaker completion} — prints the shell completion script on stdout.
 *
 * <p><b>Generated from the same annotated fields that parse, which is the whole reason it is a command
 * rather than a checked-in script.</b> A hand-written completion file is the third statement of one fact —
 * beside the parser and the usage text — and it is the one nothing tests: it does not fail, it just stops
 * offering an option somebody added, or keeps offering one they removed. That is the argument this program
 * already made for taking picocli at all (see {@link Main}), applied to the shell.
 *
 * <p><b>bash and zsh from one script.</b> picocli emits bash, and zsh runs it through its own
 * {@code bashcompinit}, which is what the two install lines below do. No fish or PowerShell: picocli
 * generates neither, and a hand-written one for those would be exactly the drifting file this avoids.
 *
 * <p><b>stdout is the script and nothing else</b>, the same rule {@code publish --dry-run} follows, so
 * {@code botmaker completion > /path} is a working install and the packages can generate the file by
 * running the command rather than by carrying a copy of it.
 */
@Command(
        name = "completion",
        header = "Print the shell completion script.",
        description = {
                "Tab-completes verbs, options and their enum values. Generated from the same annotations "
                        + "that parse the command line, so it cannot fall behind them.",
                "",
                "The rpm and the deb install this already. For a jar, or to try it in this shell only:",
                "",
                "  source <(botmaker completion)                     # bash, this shell only",
                "  botmaker completion > ~/.botmaker-completion.bash # …and source it from ~/.bashrc",
                "",
                "zsh runs the same script through bashcompinit:",
                "",
                "  autoload -U +X compinit bashcompinit && compinit && bashcompinit",
                "  source <(botmaker completion)"},
        mixinStandardHelpOptions = true)
final class CompletionCommand implements Callable<Integer> {

    @ParentCommand
    private Main parent;

    @Override
    public Integer call() {
        // The parent's own CommandLine, so every subcommand registered on it is in the script — including
        // any added after this file was written, which is the property a checked-in script cannot have.
        CommandLine root = new CommandLine(new Main());
        parent.console().out(AutoComplete.bash(root.getCommandName(), root));
        return 0;
    }
}
