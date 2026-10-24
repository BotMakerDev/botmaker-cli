# CLAUDE.md

Guidance for working in **botmaker-cli**, the `botmaker` command.

Read the umbrella `../CLAUDE.md` first, then `../botmaker-plugin-host/CLAUDE.md` (this module's loader) and
`../botmaker-plugin-archetype/CLAUDE.md` (what `botmaker plugin new` generates). The plan this module comes from is
phase 7 of the plugin-ecosystem plan.

The design, its history and the reasons behind it are in `docs/architecture/`, one file per section (moved
there unchanged on 2026-10-05); `docs/release-port-divergences.md` records where the release port differs
from `release.sh`.

## Read before touching

| Touching | Read (`docs/architecture/`) |
|---|---|
| the two artifacts, `com.botmaker.cli.validate`, picocli, the shade setup | `two-artifacts.md` |
| `com.botmaker.cli.release`: `Plan`, `Forcing`, `Order`, gates, `Runner`, `Actions`, `ReleaseCommand` | `release-library.md` |
| `packaging/nfpm.yaml`, the dnf/apt repositories | `packaging.md` |
| the `plugin`/`bot` verbs, `MovedCommand`, `doctor`, `BlankProject` | `nouns.md` |
| `com.botmaker.cli.publish` (`Publish`, `NextVersion`, `CatalogPr`), the publish commands | `publish-entry.md` |
| `com.botmaker.cli.catalog` (the check, the merge policy, the index) | `catalog-gate.md` |
| `Subjects`, `Console.reportAside` | `registry-gate.md` (the registry's gate, removed 2026-10-23; history) |
| the `EDITORS` check, why no JavaFX | `no-javafx.md` |
| `validate/StubContexts` | `stub-contexts.md` |
| `Mvn`, classpath resolution | `maven.md` |
| `plugin run`, launching Studio | `plugin-run.md` |

## Rules

- **`com.botmaker.cli.validate`, `com.botmaker.cli.release` and `com.botmaker.cli.publish` print nothing, spawn
  no UI and know no command line.** A publish runs every program through its `StepRunner`. A rule added to `ValidateCommand` is a rule the catalog will not enforce: put it in
  `PluginValidator`, as a `Check` (`two-artifacts.md`).
- **picocli stays `optional`**, so a consumer of the main artifact resolves no parser.
- **Every release side effect goes through `Runner`**; a direct write on a write path ignores `--dry-run`. The
  three orders (`Module`, `Order.DECIDE`, `Order.TAG`) are transcribed exactly, and every pin
  (`Module.upstreams()`) is a forcing edge (`release-library.md`).
- **`ChangelogGate` invokes the module's own `tools/changelog-section.sh`**; never port it.
- **A gate's `SKIPPED` never stops a release, and `--force` overrides a gate that failed, never one that could
  not run.**
- **No dependency on `botmaker-studio` or the toolkit**: copy file shapes, never depend on an application
  (`nouns.md`, `stub-contexts.md`).
- **Maven is shelled to, never embedded** (`maven.md`).

## Building

```bash
mvn test        # CommandLineTest, PomsTest, PluginValidatorTest, EntryGateTest
mvn install     # the library and the -all jar
```

The build copies `botmaker-plugin-archetype`'s `template` zip into the jar (`plugin new`), from `~/.m2`: the zip
exists only after the archetype's `package`, so `mvn -pl botmaker-cli -am test` needs the archetype **installed**
first (`mvn -pl botmaker-plugin-archetype -am install`), or it copies a stale zip.

`PluginValidatorTest` **compiles its fixtures with javac and loads them through the real `PluginLoader`**,
against `System.getProperty("java.class.path")`. A mocked `StudioPlugin` would pass through the same code and
prove none of it, because it would never have been loaded. Keep it that way; every failure these checks
exist to catch is a failure of a real classloader over real bytecode.

Published through JitPack. Releases are cut from the umbrella with `../release.sh --cli <version>`.
