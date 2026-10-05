# Maven is shelled to, never embedded

Maven Resolver as a library would resolve against **its own** idea of the local repository, the mirrors and
the settings — not the user's. The promise of `validate` is that it answers what the registry will answer,
and both keep it by running the build tool the author already has configured. `mvnw` in the project wins over
`$MAVEN_HOME` over `mvn` on the PATH: a project carrying a wrapper has said which Maven it wants.

`dependency:build-classpath` is run at **runtime** scope, and that is load-bearing: the `provided` contract is
absent from a runtime classpath, which is exactly the set a host puts on the loader. A contract that appeared
there would be resolved child-first and become a second `Class` object — the failure `provided` exists to
prevent — and the validator would be testing something no host will ever run.
