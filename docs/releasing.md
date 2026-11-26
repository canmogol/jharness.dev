# Releasing

How a JHarness release is made (spec 062, D-095). The pom's version is the only place the version lives; the binary,
the `User-Agent` and `jharness --version` read it from there.

## Once

1. Create the public repository `canmogol/homebrew-jharness` (empty, with a README if you like). Homebrew finds it as the tap `canmogol/jharness`.
2. Create a fine-grained personal access token with **Contents: read and write** on `canmogol/homebrew-jharness` only.
3. In `canmogol/jharness.dev` → Settings → Secrets and variables → Actions, add it as `HOMEBREW_TAP_TOKEN`.

Without the secret a release still publishes; the workflow warns that the tap wasn't updated.

## Each release

1. Check, by hand, on Linux and macOS:
   - the manual checks (D-086, [manual-checks.md](manual-checks.md));
   - the live Claude run (D-045);
   - the evals ([evals.md](evals.md)).
2. On a branch `release-vX.Y.Z`:
   1. Run `scripts/set-version.sh X.Y.Z`.
   2. Write the release notes in `docs/releases/vX.Y.Z.md`, from `CHANGELOG.md`.
   3. Open a PR and merge it once CI is green.
3. Tag the merge commit on `master` and push the tag:
   ```bash
   git checkout master && git pull
   git tag -a vX.Y.Z -m "JHarness X.Y.Z"
   git push origin vX.Y.Z
   ```
4. `.github/workflows/release.yml` then:
   1. refuses the tag unless it matches the pom and the notes exist;
   2. builds the native binary on linux-amd64, linux-aarch64 and macos-aarch64 and smoke-checks each;
   3. publishes the release with the archives, `SHA256SUMS`, `install.sh` and build attestations;
   4. commits `Formula/jharness.rb` to the tap.
5. Open a PR that runs `scripts/set-version.sh X.Y+1.0-SNAPSHOT`.

## Release files

| File | What |
| --- | --- |
| `jharness-linux-amd64.tar.gz`, `jharness-linux-aarch64.tar.gz`, `jharness-macos-aarch64.tar.gz` | `jharness-TARGET/{jharness, LICENSE, README.md}` |
| `SHA256SUMS` | `sha256sum` lines for the three archives |
| `install.sh` | The installer, the same as `scripts/install.sh` at the tag |

Names carry no version, so `releases/latest/download/NAME` always gives the newest release.

## Checking a download

```bash
sha256sum -c SHA256SUMS --ignore-missing     # macOS: shasum -a 256 -c SHA256SUMS --ignore-missing
gh attestation verify jharness-linux-amd64.tar.gz --repo canmogol/jharness.dev
```

The attestation shows that the file was built by this repository's release workflow from the tag. Signing keys for `jharness update` come with that roadmap item.

## Trying the scripts

`scripts/release-check.sh BIN TARGET` packs a local binary and installs it from a `file://` folder. It also checks that a tampered checksum is refused and that the formula names the three archives. CI runs it on every target.
