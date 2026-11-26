#!/bin/sh
# Writes the Homebrew formula for a release to stdout (spec 062).
# Usage: scripts/homebrew-formula.sh VERSION SHA256SUMS [BASE_URL]
set -eu
if [ $# -lt 2 ]; then
  echo "usage: scripts/homebrew-formula.sh VERSION SHA256SUMS [BASE_URL]" >&2
  exit 2
fi
version=${1#v}
sums=$2
base=${3:-https://github.com/canmogol/jharness.dev/releases/download/v$version}
sha() {
  s=$(awk -v f="jharness-$1.tar.gz" '$2 == f { print $1 }' "$sums")
  [ -n "$s" ] || { echo "homebrew-formula.sh: no checksum for jharness-$1.tar.gz in $sums" >&2; exit 1; }
  echo "$s"
}
mac=$(sha macos-aarch64)
amd=$(sha linux-amd64)
arm=$(sha linux-aarch64)
cat <<RUBY
# Written by scripts/homebrew-formula.sh in canmogol/jharness.dev; edits here are replaced at the next release.
class Jharness < Formula
  desc "Terminal coding agent whose model acts only through sandboxed JavaScript"
  homepage "https://github.com/canmogol/jharness.dev"
  version "$version"
  license "MIT"

  on_macos do
    on_arm do
      url "$base/jharness-macos-aarch64.tar.gz"
      sha256 "$mac"
    end
  end

  on_linux do
    on_intel do
      url "$base/jharness-linux-amd64.tar.gz"
      sha256 "$amd"
    end
    on_arm do
      url "$base/jharness-linux-aarch64.tar.gz"
      sha256 "$arm"
    end
  end

  def install
    bin.install "jharness"
  end

  test do
    assert_match "jharness #{version}", shell_output("#{bin}/jharness --version")
  end
end
RUBY
