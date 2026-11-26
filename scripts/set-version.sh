#!/bin/sh
# Sets JHarness's version in every module's pom.xml (spec 062): the root pom's version and each module's parent
# version. The pom is the only place the version lives; the binary reads it from there.
# Usage: scripts/set-version.sh 0.1.0      (a release)
#        scripts/set-version.sh 0.2.0-SNAPSHOT  (after it)
set -eu
[ $# -eq 1 ] || { echo "usage: scripts/set-version.sh VERSION" >&2; exit 2; }
new=$1
echo "$new" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$' || { echo "set-version.sh: $new isn't X.Y.Z or X.Y.Z-SNAPSHOT" >&2; exit 2; }
root=$(cd "$(dirname "$0")/.." && pwd)
old=$("$root/scripts/release-version.sh" --current)
[ "$old" != "$new" ] || { echo "already $new"; exit 0; }
for p in "$root"/pom.xml "$root"/jharness-*/pom.xml; do
  n=$(grep -c "<version>$old</version>" "$p" || true)
  [ "$n" -ge 1 ] || { echo "set-version.sh: $p has no <version>$old</version>" >&2; exit 1; }
  sed "s|<version>$old</version>|<version>$new</version>|" "$p" > "$p.tmp" && mv "$p.tmp" "$p"
done
echo "$old -> $new"
