# Spec 062 — Releases

Status: Done · Phase: P4 · Created: 2026-11-26

## Summary

A pushed tag `vX.Y.Z` that matches the pom's version builds the native binary on linux-amd64, linux-aarch64 and macos-aarch64, and publishes a GitHub release with `SHA256SUMS`, build attestations and `install.sh`. It also commits a new formula to the Homebrew tap `canmogol/homebrew-jharness`. The pom becomes the one place the version lives. Decision D-095.

## Roadmap items

- GitHub releases with checksums, Homebrew tap, install script

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Install JHarness with one command on macOS and Linux | Mission, success measures |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | The version lives only in the poms. `jharness --version` and the `User-Agent` read it from a Maven-filtered `version.properties`, which is in the native image. `scripts/set-version.sh X.Y.Z` sets it in every module; `scripts/release-version.sh TAG` refuses a tag that doesn't match, a SNAPSHOT, or missing notes in `docs/releases/TAG.md`. |
| AC-02 | `.github/workflows/release.yml`, on a tag `vX.Y.Z`: checks the tag; builds the native binary on the three targets with a UTF-8 locale; smoke-checks the version, `tools check`, a replay turn and a charset read; packs `jharness-TARGET.tar.gz` (binary, LICENSE, README); writes `SHA256SUMS`; attests the files with `actions/attest-build-provenance`; and publishes the release with the notes. |
| AC-03 | `scripts/install.sh` picks the target (a Rosetta shell counts as Apple silicon; Intel Macs and other systems get a message). It downloads the latest release or `JHARNESS_VERSION` over HTTPS only, checks the SHA-256 and installs to `~/.local/bin` (or `JHARNESS_INSTALL_DIR`) without sudo, replacing the binary in one move. A mismatch installs nothing. It prints a hint when the folder isn't on `PATH`. |
| AC-04 | `scripts/homebrew-formula.sh` writes `Formula/jharness.rb` for macOS arm64 and Linux x86-64 and ARM64, with `sha256` lines and a `--version` test. The release workflow commits it to the tap with `HOMEBREW_TAP_TOKEN`, and warns and goes on when the secret is missing. |
| AC-05 | `scripts/release-check.sh BIN TARGET` packs a binary, installs it from a `file://` folder, refuses a tampered checksum and checks the formula. CI runs it on every target. |
| AC-06 | README install section, `docs/releasing.md`, D-095, Q104, roadmap. |

## Out of scope

- macOS code signing and notarization, and `jharness update` with signatures (their own roadmap items).
- Creating the tap repository and the token, and pushing tags: the maintainer does these (`docs/releasing.md`).

## Dependencies and assumptions

- CI's native builds (spec 002 onward); the GraalVM Free Terms allow free redistribution of the binaries.
- The tap repository `canmogol/homebrew-jharness` exists before the first release that should update it.

## Open questions

None. Design confirmed by the user on 2026-11-26: the pom is the source and the tag must match; checksums with GitHub attestations; the workflow commits to the tap; a tag publishes at once.
