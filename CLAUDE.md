# CLAUDE.md

Guidance for working in **botmaker-cli**, the `botmaker` command.

Read the umbrella `../CLAUDE.md` first, then `../botmaker-plugin-host/CLAUDE.md` (this module's loader) and
`../botmaker-plugin-archetype/CLAUDE.md` (what `botmaker plugin new` generates). The plan this module comes from is
phase 7 of the plugin-ecosystem plan.

The design, its history and the reasons behind it are in `docs/architecture/`, one file per section (moved
there unchanged on 2026-10-05). `docs/architecture/release-library.md` and `docs/release-port-divergences.md` are
history: the release library (`com.botmaker.cli.release`, `botmaker release`) was removed on 2026-11-13, when sluice
became BotMaker's release engine; `botmaker release` now only says so (`MovedCommand`).

## Read before touching

| Touching | Read (`docs/architecture/`) |
|---|---|
| the two artifacts, `com.botmaker.cli.validate`, picocli, the shade setup | `two-artifacts.md` |
| `packaging/nfpm.yaml`, the dnf/apt repositories | `packaging.md` |
| the `plugin`/`bot` verbs, `MovedCommand`, `doctor`, `BlankProject` | `nouns.md` |
| `com.botmaker.cli.publish` (`Publish`, `NextVersion`, `CatalogPr`, `Version`, `Level`, `Jitpack`), the publish commands | `publish-entry.md` |
| `com.botmaker.cli.catalog` (the check, the merge policy, the index) | `catalog-gate.md` |
| `Subjects`, `Console.reportAside` | `registry-gate.md` (the registry's gate, removed 2026-10-23; history) |
| the `EDITORS` check, why no JavaFX | `no-javafx.md` |
| `validate/StubContexts` | `stub-contexts.md` |
| `Mvn`, classpath resolution | `maven.md` |
| `plugin run`, launching Studio | `plugin-run.md` |

## Rules

- **`com.botmaker.cli.validate`, `com.botmaker.cli.publish` and
  `com.botmaker.cli.repair` print nothing, spawn no UI and know no command line.** A publish does every side effect through its `PublishHost` (the cli's runs every program through a `StepRunner`;
  Studio brings its own). A rule added to `ValidateCommand` is a rule the catalog will not enforce: put it in
  `PluginValidator`, as a `Check` (`two-artifacts.md`).
- **picocli stays `optional`**, so a consumer of the main artifact does not resolve it. `botmaker-java` is a plain
  dependency since 2026-11-09 (the user's call): `BotFiles.mainClass` reads a `.bmk`'s entry by its `BotEntry`, and the
  catalog's rebuild must read it alike. JDT comes through `botmaker-java` at the one version its pom names.
- **`com.botmaker.cli.mcp` reaches the core only over MCP** (2026-11-05): the cli links neither the core nor Studio.
  `McpBridge` finds or starts the core (`CoreProcess`, the saved `core-command.json`); `bot run` is a client of it
  through `CoreTools.Bridged`, and follows a run by `read_trace`'s cursor, never by matching its text. `bot run
  --runs` (`SeveralStart`) reads only the first words of `run_bot {runs}`, `K of N can run` / `K of N started`, which
  the core's `CLAUDE.md` names as read here. `botmaker tool` (`ToolRun`) reads the core's `operations` JSON
  (`mcp.Operations`), calls a plugin's tool by the MCP name it lists and starts a run through `BotRun`; it knows no
  plugin, and takes no operation's arguments (2026-11-19, the user's call): the bot is the configuration, so a run
  starts with what it reads from the bot and a tool whose schema requires a value is refused, left to Studio and the
  assistant. No option is built from a schema and no completion of its own.
- **`botmaker core` reads the core's dashboard feed and acts by its MCP tools** (2026-11-21, `CoreCommand`):
  `status`, `watch` and `dashboard` read `/dashboard/events` through `core.CoreFeed` by field name (the core's
  `CLAUDE.md` names what), and `CoreCards` is the page's summary ported. The token goes only to a core that answered
  `/mcp-hello`'s proof (`McpBridge.proven`), and only a link that is `http`, the core's host and port and path
  `/dashboard` reaches the OS opener; the key is never printed. `start`, `quit`, `restart`, `stop`, `open`, `close`
  and `reload` are each a `CoreCommand.Act` of one `Tool`; only `start`, `open` and `restart` start a core
  (`CoreTools.onThisComputer`), the rest go through `CoreTools.runningOnThisComputer`, which starts none. Exit 0 done,
  1 refused, 2 no core or unreachable (an answer marked `McpBridge.UNREACHED`).
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

Published through JitPack. Releases are cut from the umbrella with `sluice --cli <level|version>`.
