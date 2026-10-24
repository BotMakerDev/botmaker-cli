package com.botmaker.cli.publish;

import com.botmaker.cli.catalog.GitHubFacts;
import com.botmaker.cli.release.CatalogStep;
import com.botmaker.cli.release.CleanRoom;
import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Version;
import com.botmaker.cli.validate.CheckResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The release library's {@link CatalogStep}: {@link Publish#listRelease} for the module's repository,
 * {@code BotMakerDev/<directory>}, its answer turned into the release log's {@code ## Catalog} row.
 */
public final class ReleaseListing implements CatalogStep {

    private final Publish publish;
    private final String command;

    /** @param command what the pull request says opened it: {@code botmaker release}, the dashboard's Release tab */
    public ReleaseListing(Publish publish, String command) {
        this.publish = publish;
        this.command = command;
    }

    /**
     * Wired to GitHub and JitPack.
     *
     * @param checks the host's plugin checks over the catalog it reads ({@link Checks#LEFT_TO_THE_CATALOG} where no
     *               plugin loads)
     * @param stop   the release's Stop, read in the waits
     */
    public static ReleaseListing of(Function<CatalogPr.Reader, Checks> checks, BooleanSupplier stop,
                                    String command) {
        GitHubFacts facts = GitHubFacts.anonymousOrToken();
        CatalogPr.Reader catalog = CatalogPr.Reader.of(facts);
        return new ReleaseListing(new Publish(facts, catalog, checks.apply(catalog), Jitpack.Probe.real(),
                Jitpack.Sleeper.real(stop)), command);
    }

    @Override
    public Optional<Listed> list(Module module, Version version, Path dir, Consumer<String> say) {
        Listener listener = listener(say);
        String repo = CleanRoom.COORDINATE_OWNER + "/" + module.directory();
        return publish.listRelease(dir, repo, version.tag(), command, listener, StepRunner.system(listener))
                .map(outcome -> switch (outcome) {
                    case Publish.Outcome.Opened opened -> new Listed(opened.pullRequest().isPresent()
                            ? "pull request opened" : "listed already", opened.pullRequest().orElse(""), "");
                    case Publish.Outcome.AlreadyListed listed -> new Listed("listed already", "", "");
                    case Publish.Outcome.Refused refused -> Listed.refused(refused.reason());
                    // Never: listRelease is no dry run.
                    case Publish.Outcome.WouldList would -> new Listed("would list", "", "");
                });
    }

    /** Every line said through the run's narration, indented under the module like the verify pass's. */
    private static Listener listener(Consumer<String> say) {
        return new Listener() {
            @Override
            public void step(String message) {
                say.accept("    " + message);
            }

            @Override
            public void warn(String message) {
                say.accept("    warning: " + message);
            }

            @Override
            public void checks(List<CheckResult> results) {
                results.stream().filter(CheckResult::failed).forEach(r -> say.accept("    " + r));
            }
        };
    }
}
