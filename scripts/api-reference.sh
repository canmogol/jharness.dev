#!/usr/bin/env bash
# Writes docs/api.md from api.d.ts (spec 065). ApiReferenceTest fails when the two differ; run this after editing api.d.ts.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -q -ntp -pl jharness-sandbox -am test -Dtest=ApiReferenceTest -Dsurefire.failIfNoSpecifiedTests=false -Djharness.writeApiReference=true
echo "docs/api.md written"
