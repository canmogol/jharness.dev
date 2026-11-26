#!/bin/sh
# Installs jharness from a GitHub release and checks its SHA-256 first (spec 062).
#
#   curl -fsSL https://raw.githubusercontent.com/canmogol/jharness.dev/master/scripts/install.sh | sh
#
# JHARNESS_VERSION      a version such as 0.1.0 (default: the latest release)
# JHARNESS_INSTALL_DIR  where the binary goes (default: ~/.local/bin; no sudo needed)
# JHARNESS_DOWNLOAD_BASE  a URL or file:// folder with the release files, instead of GitHub (for tests and mirrors)
set -eu

repo=canmogol/jharness.dev
version=${JHARNESS_VERSION:-latest}
dir=${JHARNESS_INSTALL_DIR:-$HOME/.local/bin}
base=${JHARNESS_DOWNLOAD_BASE:-}

die() {
  echo "install.sh: $*" >&2
  exit 1
}

command -v curl >/dev/null 2>&1 || die "curl is needed"
command -v tar >/dev/null 2>&1 || die "tar is needed"

os=$(uname -s)
arch=$(uname -m)
if [ "$os" = Darwin ] && [ "$arch" = x86_64 ] && [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || echo 0)" = 1 ]; then
  arch=arm64 # a Rosetta shell on Apple silicon
fi
case "$os/$arch" in
  Linux/x86_64 | Linux/amd64) target=linux-amd64 ;;
  Linux/aarch64 | Linux/arm64) target=linux-aarch64 ;;
  Darwin/arm64) target=macos-aarch64 ;;
  Darwin/x86_64) die "Intel Macs aren't supported: GraalVM has no macOS x64 build" ;;
  *) die "no jharness build for $os $arch (there are linux-amd64, linux-aarch64 and macos-aarch64)" ;;
esac

if [ -z "$base" ]; then
  if [ "$version" = latest ]; then
    base="https://github.com/$repo/releases/latest/download"
  else
    base="https://github.com/$repo/releases/download/v${version#v}"
  fi
  proto="=https"
else
  proto="=https,file"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM
file="jharness-$target.tar.gz"
fetch() {
  curl -fsSL --proto "$proto" --retry 3 "$base/$1" -o "$tmp/$1" || die "couldn't download $base/$1"
}
fetch "$file"
fetch SHA256SUMS

want=$(awk -v f="$file" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")
[ -n "$want" ] || die "SHA256SUMS has no line for $file"
if command -v sha256sum >/dev/null 2>&1; then
  got=$(sha256sum "$tmp/$file" | awk '{ print $1 }')
elif command -v shasum >/dev/null 2>&1; then
  got=$(shasum -a 256 "$tmp/$file" | awk '{ print $1 }')
else
  die "sha256sum or shasum is needed to check the download"
fi
[ "$got" = "$want" ] || die "$file doesn't match SHA256SUMS (got $got, expected $want); nothing was installed"

tar -C "$tmp" -xzf "$tmp/$file"
mkdir -p "$dir"
cp "$tmp/jharness-$target/jharness" "$dir/.jharness.new"
chmod 755 "$dir/.jharness.new"
mv -f "$dir/.jharness.new" "$dir/jharness"

echo "Installed $("$dir/jharness" --version 2>/dev/null || echo jharness) to $dir/jharness"
case ":$PATH:" in
  *":$dir:"*) ;;
  *) echo "$dir isn't on your PATH; add it, for example: echo 'export PATH=\"$dir:\$PATH\"' >> ~/.profile" ;;
esac
