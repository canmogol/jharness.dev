#!/bin/sh
# The version in the root pom.xml (spec 062).
# Usage: scripts/release-version.sh --current   prints it
#        scripts/release-version.sh TAG         checks that TAG (such as v0.1.0) matches it, that it isn't a SNAPSHOT,
#                                               and that docs/releases/TAG.md has the release notes; prints the version
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
current=$(awk '/<artifactId>jharness-parent<\/artifactId>/ { found = 1; next }
  found && /<version>/ { gsub(/.*<version>|<\/version>.*/, ""); print; exit }' "$root/pom.xml")
[ -n "$current" ] || { echo "release-version.sh: no version in pom.xml" >&2; exit 1; }
[ $# -eq 1 ] || { echo "usage: scripts/release-version.sh --current | TAG" >&2; exit 2; }
if [ "$1" = --current ]; then
  echo "$current"
  exit 0
fi
tag=$1
echo "$tag" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$' || { echo "release-version.sh: tag $tag isn't vX.Y.Z" >&2; exit 1; }
[ "${tag#v}" = "$current" ] || { echo "release-version.sh: tag $tag doesn't match the pom's version $current (scripts/set-version.sh ${tag#v})" >&2; exit 1; }
[ -s "$root/docs/releases/$tag.md" ] || { echo "release-version.sh: docs/releases/$tag.md, the release notes, is missing" >&2; exit 1; }
echo "$current"
