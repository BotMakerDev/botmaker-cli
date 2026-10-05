# What `plugin run` deliberately does not do

It does not create a bot project. Composing one means composing its pom, and **only the thing that knows the
whole plugin set can write the file that names them** — that is `MavenService` in Studio, and the reversal
that put it there (2026-08-26) is recorded in the umbrella `CLAUDE.md`. `run` points at a project that
already exists and adds one dependency, idempotently: it runs on every launch, and a pom rewritten every
time is a project Studio believes has changed every time.

`PROJECTS_ROOT` is duplicated from `studio/config/Constants` rather than imported, because importing it would
mean depending on an app with JavaFX, OpenCV and JNA behind it to learn one path.

Studio is launched through `--umbrella` (`javafx:run`), `--studio`/`$BOTMAKER_STUDIO`, or not at all. No
discovery: a packaged Studio has no canonical location on Linux, and guessing is worse than asking. The
project name reaches Studio as `--project=<name>`, a named JavaFX parameter added to `BotMakerStudio` in the
same phase as this module.
