# The one structural fact: two artifacts

| artifact | what it is | who consumes it |
|---|---|---|
| `botmaker-cli-<v>.jar` | a **library** — `com.botmaker.cli.validate` and its dependencies | the plugin registry's CI |
| `botmaker-cli-<v>-all.jar` | the **executable** jar (shade, `Main-Class`) | JBang, `java -jar` |

`validate` has two callers in two repositories and they must reach the same verdict, because **a pull
request that fails for a reason its author could not have seen coming is the experience the gate exists to
prevent**. So `com.botmaker.cli.validate` prints nothing, spawns no process, reaches no network and knows no
command line: it is handed a `PluginSubject` of resolved facts. Everything that resolves one — Maven, `gh`,
the filesystem — is in `com.botmaker.cli`.

**If you find yourself adding a rule to `ValidateCommand`, you are adding a rule the registry will not
enforce.** Put it in `PluginValidator`, as a `Check`.

The shade plugin therefore uses `shadedArtifactAttached` rather than replacing the main artifact, and
`createDependencyReducedPom=false` because this module also flattens, and two plugins rewriting one pom is a
race with no winner.

**picocli is declared `optional`, and that word is doing structural work.** `optional` means *not
transitive*: the shaded `all` jar carries picocli (an optional dependency is on this project's own runtime
classpath, which is what shade packages) and a consumer resolving the main artifact does not — checked with
`dependency:tree` from a throwaway consumer, which lists plugin-host, studio-api and jackson and no picocli.
So the rule above stops being a discipline and becomes a fact about the graph: the registry's CI cannot
accidentally depend on a parser, and `com.botmaker.cli.validate` cannot name one.

It replaced a hand-rolled `Args`, a usage text written as a Java text block and a table naming every option
each verb accepts — **three statements of one fact, which had already disagreed**: the first real
`botmaker plugin new` passed `--botmaker-version`, which was silently ignored and generated a project pinned to
something else. Add an option by adding a field; there is nowhere else to say it.
