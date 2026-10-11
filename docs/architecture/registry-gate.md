# The registry's gate is in this module too

`com.botmaker.cli.registry.RegistryGate` is what `botmaker-plugin-registry`'s CI runs on a pull request. It
lives here for the same reason the validator is a library: **it must be the code the author already ran.**
The registry's workflow resolves this module's *main* artifact and calls the gate; everything the gate adds
on top of `botmaker plugin validate` is what only the registry knows — the plugin ids the **host's own
bundled plugins** own (`Bundled`), which entry the pull request is about, and the rule that `index.json` is
generated. A check belongs in `PluginValidator`, never here.

**Value type ids left on 2026-09-23** — `Registry.claimedValueTypeIds`, `Bundled`'s type half,
`PluginSubject`'s parameter for them and `RegistryEntry.valueTypeIds`. A type is identified by its Java
class now, which carries its own package, so two plugins can only collide by declaring *the same class*,
and that is only detectable with both loaded: `checkTypes` refuses it within one classpath, the same rule
`PluginHost.compose` applies. An old entry's `valueTypeIds` is read and ignored, and dropped from the
generated index; no converter was written. The paragraphs below describe the id era where they say *value
type ids*.

**An entry is about one plugin, and the gate has to say which** (`PluginSubject.about`/`judges`, 2026-09-17).
A plugin may depend on a plugin — the SDK on `botmaker-plugin-basics`, an ordinary Maven dependency — so
resolving one coordinate loads two, and the ids the second registers belong to *its* entry. Before this the
gate refused the SDK for plugin-basics' nine value type ids and advised renaming them. The filename is the
id claim, so it is the answer. A local `botmaker plugin validate` names no entry and so judges every plugin
it finds, which is what it has always done.

**`Bundled` closes a hole the per-entry layout cannot close by itself.** `plugins/<id>.json` makes
entry-vs-entry uniqueness a property of git, but a plugin the host *ships* has no entry file — so
`com.botmaker.sdk` and the SDK's seventeen value type ids were claimed by nobody, and a submission taking one
passed every check and then lost silently in `ValueCatalog.merge`. The gate resolves the coordinates named by
`BOTMAKER_BUNDLED_PLUGINS` and asks the plugins themselves; a hand-kept list of ids here would be a second
answer to a question the SDK already answers, and would drift the first time a type was added. Two details
that are not decoration: the coordinates are resolved onto **one** classpath (`Subjects.fromCoordinates`),
because a bundled plugin's own dependency may be `optional` and so not transitive — the SDK's toolkit was,
until SDK v1.1.5, and resolving `botmaker-sdk` alone gave a classpath `SdkPlugin` could not be constructed
from; and a bundled id is **never** excluded by the submitting entry's own id, where a registry id is —
re-submitting your own plugin is an update, taking the host's is not.

**And the set is empty as of 2026-09-05, which is the truthful value rather than a disabled check.**
`botmaker-studio` has bundled no plugin since 2026-09-02: every plugin, the SDK included, is loaded off the
open project's own resolved classpath. So no id is reserved outside the index, and `com.botmaker.sdk` is
claimed the way every other id is — by `plugins/com.botmaker.sdk.json` existing, which git enforces. The
gate distinguishes the two states rather than treating them alike: **unset** warns (nobody said, and the
hole is open), **set and empty** is silent (the host bundles nothing, and says so).

It parses two positional arguments by reading an array, and that is not laziness: picocli is `optional`
precisely so a library consumer resolves no parser, and a gate that needed one would undo it. Changed paths
arrive as `@file` because a pull request chooses its own filenames — a path interpolated into a workflow's
`run:` line is a command injection.

`Subjects` and `Mvn` are public for this one caller, and only `fromCoordinate`/`fromCoordinates` are: a
working copy is the author's question, and the registry has none. `Console.reportAside` exists because `botmaker plugin publish`'s
stdout is the entry JSON — `--dry-run > plugins/<id>.json` has to be a file a parser will read, which is the
same stdout/stderr rule `Console` opens with.
