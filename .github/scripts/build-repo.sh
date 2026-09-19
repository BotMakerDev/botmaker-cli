#!/usr/bin/env bash
# Builds the static dnf + apt repository published to GitHub Pages, out of the .rpm/.deb this release just
# produced. Users then get `sudo dnf install botmaker` and `sudo apt-get install botmaker`, and every later
# version arrives with the rest of their system updates.
#
#   build-repo.sh <artifacts-dir> <site-dir> <tag>
#
# THE PACKAGES THEMSELVES ARE HOSTED HERE, both formats — which is where this differs from
# botmaker-studio's otherwise identical script. There, each package is ~240 MB against a ~1 GB Pages soft
# limit, so the .rpm is staged only long enough for createrepo_c to hash it and `--baseurl` then points
# clients at the GitHub release CDN. This package is about 1 MB: the offload buys nothing and costs the one
# thing that went wrong there, a --baseurl spelling that leaves clients 404ing on a file deleted a line ago.
#
# ONLY EVER THE LATEST RELEASE. This is an upgrade channel, not an archive — every older version stays
# downloadable as a GitHub Release asset — and the deploy is a fresh artifact each time, so nothing
# accumulates.
#
# SIGNING is at the repository level: repodata/repomd.xml.asc for dnf, a clearsigned InRelease plus a
# detached Release.gpg for apt. That is a signature over the index, and an index carries the checksum of
# every package it lists, so it is a signature over the payload as well — which is all apt asks for, and
# is NOT all dnf asks for: `gpgcheck` covers the package's own header and `repo_gpgcheck` covers this
# index, the generated botmaker.repo below sets both, and v0.0.3 satisfied only the second. The rpm is
# signed in the release job now; see packaging/nfpm.yaml. With signing unconfigured the repository
# is still built and the generated snippets turn the checks off, so what is published stays
# self-consistent; that path is for dry runs, not for a real release.
set -euo pipefail

ARTIFACTS="${1:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
SITE="${2:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
TAG="${3:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"

REPO_SLUG="${GITHUB_REPOSITORY:-BotMakerDev/botmaker-cli}"
# Pages serves <owner>.github.io/<repo>, lowercased.
PAGES_URL="${PAGES_URL:-https://$(echo "${REPO_SLUG%%/*}" | tr '[:upper:]' '[:lower:]').github.io/${REPO_SLUG##*/}}"

shopt -s nullglob
rpms=("${ARTIFACTS}"/*.rpm)
debs=("${ARTIFACTS}"/*.deb)
[ ${#rpms[@]} -gt 0 ] || { echo "::error::no .rpm found in ${ARTIFACTS}"; exit 1; }
[ ${#debs[@]} -gt 0 ] || { echo "::error::no .deb found in ${ARTIFACTS}"; exit 1; }
RPM="${rpms[0]}"
DEB="${debs[0]}"

SIGNING=0
if [ "${BOTMAKER_SIGN:-0}" = "1" ] && [ -n "${GPG_KEY_ID:-}" ]; then
  SIGNING=1
else
  echo "::warning::signing not configured — publishing a repository nothing can verify."
fi

# gpg in batch/loopback mode, matching how ci.yml imports the key.
gpg_run() {
  gpg --batch --yes --pinentry-mode loopback --passphrase "${GPG_PASSPHRASE:-}" -u "${GPG_KEY_ID}" "$@"
}

mkdir -p "${SITE}/rpm" "${SITE}/deb/pool/main/b/botmaker"

# --- dnf --------------------------------------------------------------------------------------------
cp "${RPM}" "${SITE}/rpm/"
# --general-compress-type, NOT --compress-type: the latter covers only the *additional* metadata and would
# leave primary.xml on whatever the default is. gz because every dnf reads it, zstd/zck being newer.
createrepo_c --general-compress-type gz "${SITE}/rpm"

if [ "${SIGNING}" = "1" ]; then
  gpg_run --detach-sign --armor -o "${SITE}/rpm/repodata/repomd.xml.asc" "${SITE}/rpm/repodata/repomd.xml"
fi

# --- apt --------------------------------------------------------------------------------------------
# The package is `Architecture: all`, and apt fetches dists/stable/main/binary-<the host's arch>/Packages.
# There is a binary-all directory in the spec but no client looks in it unless the sources.list line says
# `[arch=all]`, which nobody types. So the same index is published under every architecture we claim, which
# is what an arch-independent package looks like in a hand-built archive.
ARCHES=(amd64 arm64 all)
for arch in "${ARCHES[@]}"; do
  mkdir -p "${SITE}/deb/dists/stable/main/binary-${arch}"
done
cp "${DEB}" "${SITE}/deb/pool/main/b/botmaker/"
(
  cd "${SITE}/deb"
  # Filename: in Packages is relative to this directory, which is why apt-ftparchive runs from here.
  apt-ftparchive packages pool > "${TMPDIR:-/tmp}/Packages.$$"
  for arch in "${ARCHES[@]}"; do
    cp "${TMPDIR:-/tmp}/Packages.$$" "dists/stable/main/binary-${arch}/Packages"
    gzip -9cf "dists/stable/main/binary-${arch}/Packages" \
      > "dists/stable/main/binary-${arch}/Packages.gz"
  done
  rm -f "${TMPDIR:-/tmp}/Packages.$$"

  # Hashes every file under dists/stable, so it runs after the loop above — and must NOT write into that
  # tree while doing so. Redirecting straight into dists/stable/Release makes apt-ftparchive walk over its
  # own partially-flushed output and hash it, putting a bogus entry inside the file that then gets signed.
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=BotMaker \
    -o APT::FTPArchive::Release::Label="BotMaker CLI" \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures="${ARCHES[*]}" \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Description="BotMaker CLI release channel" \
    release dists/stable > "${TMPDIR:-/tmp}/Release.$$"
  mv "${TMPDIR:-/tmp}/Release.$$" dists/stable/Release
)
if [ "${SIGNING}" = "1" ]; then
  gpg_run --clearsign -o "${SITE}/deb/dists/stable/InRelease" "${SITE}/deb/dists/stable/Release"
  gpg_run --detach-sign --armor -o "${SITE}/deb/dists/stable/Release.gpg" "${SITE}/deb/dists/stable/Release"
fi

# --- the public key and the two snippets --------------------------------------------------------------
# `signed-by=` accepts an ASCII-armored key as long as the file is named .asc, so one export serves apt and
# `rpm --import` both; there is no second, dearmored copy to keep in step.
if [ "${SIGNING}" = "1" ]; then
  gpg --export --armor "${GPG_KEY_ID}" > "${SITE}/botmaker.asc"
  rpm_gpg=$'gpgcheck=1\nrepo_gpgcheck=1\ngpgkey='"${PAGES_URL}/botmaker.asc"
  apt_opts="[signed-by=/etc/apt/keyrings/botmaker.asc] "
else
  rpm_gpg=$'gpgcheck=0\nrepo_gpgcheck=0'
  apt_opts="[trusted=yes] "
fi

cat > "${SITE}/botmaker.repo" <<EOF
[botmaker]
name=BotMaker CLI
baseurl=${PAGES_URL}/rpm
enabled=1
${rpm_gpg}
EOF

DNF_SNIPPET="sudo curl -fsSL -o /etc/yum.repos.d/botmaker.repo ${PAGES_URL}/botmaker.repo
sudo dnf install botmaker"

APT_SNIPPET="sudo install -d -m 755 /etc/apt/keyrings
sudo curl -fsSL -o /etc/apt/keyrings/botmaker.asc ${PAGES_URL}/botmaker.asc
echo \"deb ${apt_opts}${PAGES_URL}/deb stable main\" | sudo tee /etc/apt/sources.list.d/botmaker.list
sudo apt-get update && sudo apt-get install botmaker"

# --- landing page --------------------------------------------------------------------------------------
# One self-contained file with no assets: this site is metadata, and a stylesheet request would be one more
# thing to keep alive for a page people visit once.
cat > "${SITE}/index.html" <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>botmaker — package repository</title>
<style>
  :root { color-scheme: light dark; --fg: #1a1a1a; --bg: #ffffff; --muted: #5f6368; --line: #e0e0e0; --code-bg: #f5f5f5; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #e8e8e8; --bg: #16181c; --muted: #9aa0a6; --line: #2c2f36; --code-bg: #1f2228; }
  }
  body { margin: 0 auto; padding: 3rem 1.25rem 5rem; max-width: 46rem; color: var(--fg); background: var(--bg);
         font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  h1 { font-size: 1.6rem; margin: 0 0 .25rem; }
  h2 { font-size: 1.15rem; margin: 2.5rem 0 .5rem; padding-top: 1.25rem; border-top: 1px solid var(--line); }
  p.sub { color: var(--muted); margin: 0 0 2rem; }
  pre { background: var(--code-bg); border: 1px solid var(--line); border-radius: 6px;
        padding: .9rem 1rem; overflow-x: auto; font-size: .875rem; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  footer { margin-top: 3rem; color: var(--muted); font-size: .875rem; }
  a { color: inherit; }
</style>
</head>
<body>
<h1>botmaker</h1>
<p class="sub">dnf and apt repositories for <strong>${TAG}</strong>. Install once, then update with your
package manager like anything else on the system.</p>

<h2>Fedora / RHEL</h2>
<pre><code>${DNF_SNIPPET}</code></pre>

<h2>Debian / Ubuntu</h2>
<pre><code>${APT_SNIPPET}</code></pre>

<p>Later updates, either way: <code>sudo dnf upgrade botmaker</code> or
<code>sudo apt-get update &amp;&amp; sudo apt-get install --only-upgrade botmaker</code>.</p>

<h2>Anywhere else</h2>
<p>One jar, every OS: <code>jbang botmaker@BotMakerDev</code>, or download
<code>botmaker-cli-all.jar</code> from the
<a href="https://github.com/${REPO_SLUG}/releases">Releases</a> page and run it with
<code>java -jar</code>.</p>

<footer>
<p>The package installs the jar at <code>/usr/share/botmaker/</code> and a launcher at
<code>/usr/bin/botmaker</code>, and requires a Java 25 runtime. Maven is a recommended dependency rather
than a required one: every verb that resolves a coordinate shells out to your own <code>mvn</code>, and
the ones that do not need none.</p>
<p>This repository carries the <strong>latest release only</strong> — it is an upgrade channel, not an
archive.</p>
</footer>
</body>
</html>
EOF

echo "Site built at ${SITE} ($(du -sh "${SITE}" | cut -f1)), advertising ${TAG} at ${PAGES_URL}"
