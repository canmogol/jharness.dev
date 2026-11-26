#!/bin/sh
# Packs a native jharness binary for a release (spec 062).
# Usage: scripts/package.sh BIN TARGET OUTDIR
#   writes OUTDIR/jharness-TARGET.tar.gz with jharness-TARGET/{jharness, LICENSE, README.md}
set -eu
if [ $# -ne 3 ]; then
  echo "usage: scripts/package.sh BIN TARGET OUTDIR" >&2
  exit 2
fi
bin=$1 target=$2 out=$3
case "$target" in
  linux-amd64|linux-aarch64|macos-aarch64) ;;
  *) echo "package.sh: unknown target $target (linux-amd64, linux-aarch64, macos-aarch64)" >&2; exit 2 ;;
esac
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dir="$work/jharness-$target"
mkdir -p "$dir" "$out"
cp "$bin" "$dir/jharness"
chmod 755 "$dir/jharness"
cp "$root/LICENSE" "$root/README.md" "$dir/"
tar -C "$work" -czf "$out/jharness-$target.tar.gz" "jharness-$target"
echo "$out/jharness-$target.tar.gz"
