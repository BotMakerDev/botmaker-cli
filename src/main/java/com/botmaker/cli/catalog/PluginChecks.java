package com.botmaker.cli.catalog;

import com.botmaker.cli.Subjects;
import com.botmaker.cli.validate.CheckResult;
import com.botmaker.cli.validate.PluginSubject;
import com.botmaker.cli.validate.PluginValidator;
import com.botmaker.plugin.host.PluginCoordinate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * A plugin's own rules, on what JitPack serves at the approved release's tag — what Studio will resolve:
 *
 * <ol start="6">
 *   <li>{@link PluginValidator}'s checks pass (pom scopes, the contract, construction headless, declarations), and
 *       the jar's pom declares the plugin with the entry's coordinate, {@code com.github.<owner>:<repo>};</li>
 *   <li>JitPack serves the coordinate at {@code tag}, and its jar and pom hash to the release's pins.</li>
 * </ol>
 *
 * <p>The plugin's constructor runs here, acceptable only because validate.yml holds no secret.
 */
public final class PluginChecks {

    private PluginChecks() {}

    /** @param release the release the pull request approves */
    public static List<Finding> check(PluginEntry entry, ApprovedRelease release, String path, EntryFacts facts,
                                      Subjects subjects) throws IOException {
        Optional<EntryFacts.Repo> repo = facts.repo(entry.repo());
        if (repo.isEmpty()) {
            return List.of(Finding.error(path, entry.repo() + " is not a public repository on GitHub."));
        }
        String coordinate = repo.get().coordinate();
        String tag = release.tag();
        if (!facts.jitpackServes(coordinate, tag)) {
            return List.of(Finding.error(path, "JitPack does not serve " + coordinate + ":" + tag
                    + ", which is what Studio resolves (or JitPack did not answer). Open https://jitpack.io/#"
                    + entry.repo() + " to see its build log, then re-run the check."));
        }
        List<Finding> pins = pinFindings(path, coordinate, release, facts);
        if (!pins.isEmpty()) return pins;
        PluginSubject subject = subjects.fromCoordinate(coordinate + ":" + tag)
                .about(PluginCoordinate.parse(coordinate));
        // Maven downloads on its own, so the bytes about to run are hashed too: the pins hold for what is checked.
        List<Finding> resolved = resolvedFindings(path, coordinate, release, subject.classpath());
        if (!resolved.isEmpty()) return resolved;
        // DECLARED, told the entry's coordinate, refuses a jar whose pom declares another plugin.
        List<CheckResult> results = PluginValidator.validate(subject);
        if (!PluginValidator.passed(results)) {
            return List.of(Finding.error(path, "the plugin checks fail: " + String.join(", ", results.stream()
                    .filter(CheckResult::failed).map(r -> r.check().id() + " (" + String.join("; ", r.detail()) + ")").toList())));
        }
        return List.of();
    }

    /** Rule 7's second half: the jar and pom JitPack serves now are the bytes the release pins. */
    static List<Finding> pinFindings(String path, String coordinate, ApprovedRelease release, EntryFacts facts) {
        List<Finding> findings = new ArrayList<>();
        for (EntryFacts.Artifact artifact : EntryFacts.Artifact.values()) {
            String pinned = artifact == EntryFacts.Artifact.JAR ? release.jarSha256() : release.pomSha256();
            Optional<String> served = facts.jitpackSha256(coordinate, release.tag(), artifact);
            if (served.isEmpty()) {
                findings.add(Finding.error(path, "JitPack served no " + artifact.extension() + " for " + coordinate
                        + ":" + release.tag() + " (or did not answer); re-run the check."));
            } else if (!served.get().equals(pinned)) {
                findings.add(Finding.error(path, "the " + artifact.extension() + " JitPack serves for " + coordinate
                        + ":" + release.tag() + " hashes to " + served.get() + ", not the pinned " + pinned
                        + ": it changed after it was pinned. Pin the release again (`botmaker plugin publish`)."));
            }
        }
        return findings;
    }

    /**
     * The jar Maven resolved for the coordinate, and the pom beside it, hash to the pins. {@code classpath} is what
     * the validator loads; the plugin's own jar is {@code <artifact>-<tag>.jar} in it.
     */
    static List<Finding> resolvedFindings(String path, String coordinate, ApprovedRelease release,
                                          List<Path> classpath) throws IOException {
        String artifact = coordinate.substring(coordinate.indexOf(':') + 1);
        String stem = artifact + "-" + release.tag();
        Optional<Path> jar = classpath.stream().filter(p -> p.getFileName().toString().equals(stem + ".jar"))
                .findFirst();
        if (jar.isEmpty()) {
            return List.of(Finding.error(path, "Maven resolved no " + stem + ".jar for " + coordinate + ":"
                    + release.tag() + ", so the pinned jar cannot be the one checked."));
        }
        List<Finding> findings = new ArrayList<>();
        for (EntryFacts.Artifact kind : EntryFacts.Artifact.values()) {
            Path file = jar.get().resolveSibling(stem + "." + kind.extension());
            String pinned = kind == EntryFacts.Artifact.JAR ? release.jarSha256() : release.pomSha256();
            String actual = Files.isRegularFile(file) ? sha256(file) : "nothing";
            if (!actual.equals(pinned)) {
                findings.add(Finding.error(path, "the " + kind.extension() + " Maven resolved for " + coordinate + ":"
                        + release.tag() + " hashes to " + actual + ", not the pinned " + pinned + "."));
            }
        }
        return findings;
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256 in this JDK", e);
        }
    }
}
