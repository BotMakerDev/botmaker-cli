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
| the `plugin`/`bot` verbs, `MovedCommand`, `doctor`, `BlankProject`, `GalleryEntry` | `nouns.md` |
| `plugin publish`'s entry, `verifiedVersion` | `publish-entry.md` |
| `com.botmaker.cli.gallery` (`GalleryGate`, `CatalogBuilder`, `ListingPolicy`) | `gallery-gate.md` |
| `RegistryGate`, `Bundled`, `Subjects`, `Console.reportAside` | `registry-gate.md` |
| the `EDITORS` check, why no JavaFX | `no-javafx.md` |
| `validate/StubContexts` | `stub-contexts.md` |
| `Mvn`, classpath resolution | `maven.md` |
| `plugin run`, launching Studio | `plugin-run.md` |

## Rules

- **`com.botmaker.cli.validate` and `com.botmaker.cli.release` print nothing, spawn no UI and know no command
  line.** A rule added to `ValidateCommand` is a rule the registry will not enforce: put it in
  `PluginValidator`, as a `Check` (`two-artifacts.md`).
- **picocli stays `optional`**, so a consumer of the main artifact resolves no parser.
- **Every release side effect goes through `Runner`**; a direct write on a write path ignores `--dry-run`. The
  forcing edge set and the three orders (`Module`, `Order.DECIDE`, `Order.TAG`) are transcribed exactly
  (`release-library.md`).
- **`ChangelogGate` invokes the module's own `tools/changelog-section.sh`**; never port it.
- **A gate's `SKIPPED` never stops a release, and `--force` overrides a gate that failed, never one that could
  not run.**
- **No dependency on `botmaker-studio` or the toolkit**: copy file shapes, never depend on an application
  (`nouns.md`, `stub-contexts.md`).
- **Maven is shelled to, never embedded** (`maven.md`).

## Building

```bash
mvn test        # CommandLineTest, PomsTest, PluginValidatorTest, RegistryTest
mvn install     # the library and the -all jar
```

`PluginValidatorTest` **compiles its fixtures with javac and loads them through the real `PluginLoader`**,
against `System.getProperty("java.class.path")`. A mocked `StudioPlugin` would pass through the same code and
prove none of it, because it would never have been loaded. Keep it that way; every failure these checks
exist to catch is a failure of a real classloader over real bytecode.

Published through JitPack. Releases are cut from the umbrella with `../release.sh --cli <version>`.
