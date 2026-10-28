package com.botmaker.cli.release;

/**
 * Whether a changed file can change what a release publishes — {@code release.sh}'s
 * {@code is_release_irrelevant}.
 *
 * <p><b>The question a tag asks is not "did any byte move" but "does this change what a consumer
 * downloads".</b> Those are different, and the difference bit: the check was one
 * {@code git diff --quiet <tag> HEAD} until 2026-08-24, so a typo fix in {@code CLAUDE.md} made a module
 * "changed" and {@code --all} cut a tag whose published jar was byte-identical to the previous one. Two of
 * them nearly went out whose entire diff since their tags was the {@code CHANGELOG.md} the changelog gate
 * itself had asked for.
 *
 * <p><b>It is a deny-list and must stay one.</b> A file nobody has classified counts as a change and gets
 * released, which is the harmless direction to be wrong in: the cost of a needless tag is a duplicate
 * artifact, and the cost of a missed one is a release that silently omits the change it was cut for.
 *
 * <p>Markdown is never release-relevant, anywhere in the tree — including under {@code .github/}, whose
 * <i>scripts and workflows</i> build the artifact and therefore do count.
 *
 * <p><b>The edge worth knowing is every plugin's</b>: {@code botmaker-plugin-parent} (and the SDK's own pom) copies
 * the whole {@code CHANGELOG.md} into the jar as {@code META-INF/botmaker/changelog.md} ({@code whats-new.md}, SDK
 * only, until 2026-10-28), so editing it really does change that artifact. It is still
 * excluded here, because a release <i>authors</i> its own section — counting it would mean every release
 * trivially justifies itself — and because {@code check_changelog} already refuses a release with no
 * section.
 */
public final class Relevance {

    private Relevance() {
    }

    /**
     * True for a file that cannot change what is published.
     *
     * <p>The path is repository-relative and matched exactly as the script's {@code case} matches it:
     * {@code *.md} anywhere, three dotfiles at the root, and anything under {@code .idea/}.
     */
    public static boolean irrelevant(String path) {
        // CHANGELOG, ROADMAP, CLAUDE, README, docs/ — every one of them, at every depth.
        if (path.endsWith(".md")) {
            return true;
        }
        // Root-level only, exactly as the script's unanchored-but-slashless patterns are.
        if (path.equals(".editorconfig") || path.equals(".gitignore") || path.equals(".gitattributes")) {
            return true;
        }
        // src/, pom.xml, jitpack.yml, packaging/, .github/… all fall through to relevant.
        return path.startsWith(".idea/");
    }
}
