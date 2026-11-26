#!/bin/sh
# Checks the release scripts against a local binary (spec 062): package.sh, install.sh from a file:// folder (with a
# good and a tampered checksum), and the Homebrew formula. CI runs it on every target.
# Usage: scripts/release-check.sh BIN TARGET
set -eu
bin=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
target=$2
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dist="$work/dist"
"$root/scripts/package.sh" "$bin" "$target" "$dist" >/dev/null
# The other targets' archives, so the formula has three checksums; only this target's is installed.
for t in linux-amd64 linux-aarch64 macos-aarch64; do
  [ -f "$dist/jharness-$t.tar.gz" ] || cp "$dist/jharness-$target.tar.gz" "$dist/jharness-$t.tar.gz"
done
(cd "$dist" && if command -v sha256sum >/dev/null 2>&1; then sha256sum jharness-*.tar.gz; else shasum -a 256 jharness-*.tar.gz; fi) > "$dist/SHA256SUMS"

out=$(JHARNESS_DOWNLOAD_BASE="file://$dist" JHARNESS_INSTALL_DIR="$work/bin" sh "$root/scripts/install.sh")
echo "$out"
"$work/bin/jharness" --version | grep -q "^jharness " || { echo "release-check: the installed binary doesn't run" >&2; exit 1; }
echo "$out" | grep -q "isn't on your PATH" || { echo "release-check: no PATH hint" >&2; exit 1; }

# A tampered archive is refused and nothing is installed.
cp "$dist/SHA256SUMS" "$work/good"
sed "s/^[0-9a-f]\{8\}/00000000/" "$work/good" > "$dist/SHA256SUMS"
if JHARNESS_DOWNLOAD_BASE="file://$dist" JHARNESS_INSTALL_DIR="$work/bin2" sh "$root/scripts/install.sh" 2> "$work/err"; then
  echo "release-check: a bad checksum was installed" >&2
  exit 1
fi
grep -q "doesn't match SHA256SUMS" "$work/err" || { cat "$work/err" >&2; exit 1; }
[ ! -e "$work/bin2/jharness" ] || { echo "release-check: something was installed" >&2; exit 1; }

# The formula names the three archives with their checksums.
version=$("$bin" --version | awk '{ print $2 }')
"$root/scripts/homebrew-formula.sh" "$version" "$work/good" > "$work/jharness.rb"
for t in linux-amd64 linux-aarch64 macos-aarch64; do
  s=$(awk -v f="jharness-$t.tar.gz" '$2 == f { print $1 }' "$work/good")
  grep -q "sha256 \"$s\"" "$work/jharness.rb" || { echo "release-check: no checksum for $t in the formula" >&2; exit 1; }
done
grep -q "version \"$version\"" "$work/jharness.rb"
if command -v ruby >/dev/null 2>&1; then
  ruby -c "$work/jharness.rb" >/dev/null
fi
echo "release-check: pass"
