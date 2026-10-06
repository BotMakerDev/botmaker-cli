# What `publish` puts in an entry, and why none of it is the pom's `<version>`

An entry is a set of pointers, and every one of them is followed before the pull request is opened. That is
the same argument as the validator's: **a submission that fails in the registry's CI for a reason its author
could not have seen coming is what this command exists to prevent**, and a pointer nobody has followed is
exactly such a reason.

`verifiedVersion` is the **newest git tag** (`project/Tags.newest`), not `Poms.coordinate(pom).version()`,
which is what stood there until 2026-09-04. JitPack builds a tag on demand and serves the artifact under
that tag whatever the pom says — this project's own poms carried a cosmetic `0.0.0-SNAPSHOT` for precisely
that reason until 2026-10-06 — so a pom version resolves only where it happens to equal the tag. Worse in the ordinary case:
`botmaker plugin new` generates `0.1.0-SNAPSHOT`, JitPack resolves no snapshot, and so *the first thing a new author
does* produced an entry the gate could not download. A snapshot is now refused here, by name, with the
sentence that says what to do; `--tag` overrides the lookup (`--version` is the help mixin's).

Two more pointers, same shape and same argument: the coordinate is resolved through `Subjects.fromCoordinate`
— the gate's own first step — and `--repo` through `gh repo view`. Both run on a real run only:
`--dry-run` is the by-hand path taken when `gh` is unavailable, so a check needing `gh` cannot be what stops
it. The snapshot refusal is *not* exempted there, because the dry run's stdout is a file the author submits.
