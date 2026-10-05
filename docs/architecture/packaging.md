# Packaging — nfpm, and two things that are refused

`packaging/nfpm.yaml` builds `botmaker.rpm` and `botmaker.deb` from one description in the `release` job;
`.github/scripts/build-repo.sh` republishes them as dnf/apt repositories on GitHub Pages, so
`sudo dnf install botmaker` works and later releases arrive with the system's updates. Contents:
`/usr/share/botmaker/botmaker-cli-all.jar`, `/usr/bin/botmaker` (`packaging/botmaker`, a three-line `exec`),
and the docs. `/usr/bin` on `PATH` is the whole gain over `java -jar` — the jar's directory stops mattering.

**Not jpackage, which is what `botmaker-studio` uses**, and the difference is not a preference: jpackage
bundles a *runtime* into a ~240 MB app-image and generates the launcher, desktop entry, icon and MIME
registration around it, and the same tool has to produce Studio's `.msi` and `.dmg` — one packaging model
for three platforms. This is a 1 MB noarch jar with no desktop presence, so jpackage would generate
nothing worth having and would still force one runner per target OS.

**And not GraalVM `native-image`, structurally rather than by taste**: this program's job is loading
*arbitrary user classes* through a runtime `URLClassLoader` (`PluginLoader.open` over a classpath resolved
at run time). A closed-world image cannot, and no reflection configuration fixes it, because the classes do
not exist at image-build time.

**The packages carry no signature of their own**, and that is deliberate: the Pages repositories sign their
*indexes* (`repomd.xml.asc`, `InRelease`), an index carries the checksum of every package it lists, so the
signature already covers the payload. A package-level signature would verify the same bytes twice and would
need the private key on the build runner as a file.
