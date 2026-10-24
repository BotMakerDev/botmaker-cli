package com.botmaker.cli.publish;

import com.botmaker.cli.validate.CheckResult;

import java.util.List;

/**
 * What a publish reports while it runs: the cli prints it, Studio shows it in the Publish sheet. A refusal is not
 * reported here; it is the {@link Publish.Outcome} the run returns.
 */
public interface Listener {

    /** Progress: a step begun, a program run and its output. */
    void step(String message);

    /** Something the author should read that stops nothing. */
    void warn(String message);

    /** A plugin's check report: the folder's before the tag, the jar's once JitPack built it. */
    void checks(List<CheckResult> results);
}
