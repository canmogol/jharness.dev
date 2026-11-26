# Plan 062 — Releases

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **Version.**
  - `jharness-events` filters `dev/jharness/events/version.properties` (`version=${project.version}`), registered as a native-image resource.
  - `Version.CURRENT` reads it.
  - `Main` uses a picocli `versionProvider`, since an annotation needs a constant.
  - `HttpOperations.USER_AGENT` uses `Version.CURRENT`.
- **Scripts.**
  - `set-version.sh` rewrites the root and module parent versions.
  - `release-version.sh` prints or checks the version.
  - `package.sh`, `install.sh` and `homebrew-formula.sh`.
  - `release-check.sh` exercises them with a `file://` base.
- **Workflow.** `release.yml`:
  1. `check` runs `release-version.sh`.
  2. `build` (matrix) builds natively, smoke-checks, packs, and runs `release-check.sh`.
  3. `release` downloads the archives, writes `SHA256SUMS`, attests, and runs `gh release create --verify-tag`.
  4. `homebrew` renders the formula and pushes it to the tap with the token.

  Action versions are pinned.
- **CI.** A "Release scripts" step after the native checks on every target.
- **Tests.** `VersionTest`; `MainTest` reads `Version.CURRENT`; `release-check.sh` in CI; `set-version.sh` round trip by hand.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | The release is the one native binary per target | Yes |
| II Host owns side effects | No change to the agent | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | The scripts are maintainer and user tools outside JHarness; JHarness itself is unchanged | Yes |
| V Proxy | No change | Yes |
| VI Strings | No change | Yes |
| VII Safe by default | `install.sh` checks SHA-256 and uses HTTPS only; no sudo; attestations for every file | Yes |
| VIII Events | No new action | Yes |
| IX Native first | Built and smoke-checked natively on every target; the version resource is registered | Yes |
| X Tested | `release-check.sh` on every target; `VersionTest` | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build on Linux and macOS: `--version` from the pom; `release-check.sh` on both
- [x] `set-version.sh` round trip; `release-version.sh` refusals
- [ ] CI on all targets
- [ ] The first release (v0.1.0), after the other v0.1 items

## Risks

| Risk | Response |
| --- | --- |
| A tag that doesn't match the pom | `release-version.sh` stops the workflow before any build |
| A tampered or broken download | `install.sh` checks SHA-256 and installs nothing on a mismatch |
| The tap token leaks | A fine-grained token with contents access to the tap only |
| A macOS binary built with the C locale | `LC_ALL=en_US.UTF-8` in the release build |
