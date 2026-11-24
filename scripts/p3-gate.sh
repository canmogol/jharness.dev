#!/usr/bin/env bash
# P3 gate (roadmap): one custom agent and one hook ship as working examples.
# Runs the shipped reviewer agent and guard hook from examples/.jharness/ against a small git repo,
# with recorded model replies, in the given jharness binary. Prints one "gate <check> pass|fail" line
# per check and exits 1 if any check fails.
#
# Usage: scripts/p3-gate.sh path/to/jharness
set -u
bin=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
failed=0
check() { # name, then a command that succeeds when the check passes
  local name=$1
  shift
  if "$@" > /dev/null 2>&1; then echo "gate $name pass"; else echo "gate $name fail"; failed=1; fi
}

# A sample repo with an uncommitted bug. Its remote is never reached: the hook blocks the push first.
ws="$tmp/shop"
mkdir -p "$ws/src"
git -C "$ws" init -q -b main
git -C "$ws" config user.email gate@example.invalid
git -C "$ws" config user.name gate
printf 'class Calculator {\n    int add(int a, int b) {\n        return a + b;\n    }\n}\n' > "$ws/src/Calculator.java"
git -C "$ws" add . && git -C "$ws" commit -q -m "Initial commit"
git -C "$ws" remote add origin https://git.example.invalid/shop.git
sed -i.bak 's/a + b/a - b/' "$ws/src/Calculator.java" && rm "$ws/src/Calculator.java.bak"
bug=$(cat "$ws/src/Calculator.java")

# 1. The custom agent: examples/.jharness/agents/reviewer, read-only, with its helpers.
home="$tmp/home-agent"
mkdir -p "$home/.jharness/agents"
cp -R "$root/examples/.jharness/agents/reviewer" "$home/.jharness/agents/"
printf '%s\n' \
  '{"reply":"```js\nconst files = changedFiles();\nfiles.join(\",\") + \"\\n\" + diffOf(files[0])\n```"}' \
  '{"reply":"```js\ntry { api.writeFile(\"src/Calculator.java\", \"fixed\"); \"written\" } catch (e) { e.message }\n```"}' \
  '{"reply":"src/Calculator.java:3: add subtracts; return a + b."}' > "$tmp/agent.jsonl"
"$bin" --home "$home" --cwd "$ws" --replay "$tmp/agent.jsonl" --agent reviewer -p "Review my changes" > "$tmp/agent.txt" 2>&1
"$bin" log --home "$home" --cwd "$ws" last --json > "$tmp/agent.jsonl.log"
check agent-loaded grep -q '"customAgent":"reviewer"' "$tmp/agent.jsonl.log"
check agent-helpers grep -q 'src/Calculator.java\\n.*a - b' "$tmp/agent.jsonl.log"
check agent-read-only grep -q "can't use api.writeFile" "$tmp/agent.jsonl.log"
check agent-reply grep -q "add subtracts" "$tmp/agent.txt"
check agent-changed-nothing test "$(cat "$ws/src/Calculator.java")" = "$bug"

# 2. The hook: examples/.jharness/hooks/guard.js blocks a lock-file edit and a push to main,
# and its stop hook sends the agent back once when it changed files without running tests.
home="$tmp/home-hook"
mkdir -p "$home/.jharness/hooks"
cp "$root/examples/.jharness/hooks/guard.js" "$home/.jharness/hooks/"
printf '{"permissions": {"mode": "accept-edits"}}' > "$home/.jharness/settings.json"
printf '%s\n' \
  '{"reply":"```js\nconst out = [];\nfor (const f of [() => api.writeFile(\"package-lock.json\", \"{}\"), () => api.git({op: \"push\", remote: \"origin\", ref: \"main\"})]) {\n  try { f(); out.push(\"ok\"); } catch (e) { out.push(e.message); }\n}\nout.join(\"\\n\")\n```"}' \
  '{"reply":"```js\napi.editFile(\"src/Calculator.java\", \"a - b\", \"a + b\")\n```"}' \
  '{"reply":"Fixed add."}' \
  '{"reply":"No build tool is set up in this sample repo, so I cannot run tests."}' > "$tmp/hook.jsonl"
"$bin" --home "$home" --cwd "$ws" --replay "$tmp/hook.jsonl" -p "Fix the add bug" > "$tmp/hook.txt" 2>&1
"$bin" log --home "$home" --cwd "$ws" last --json > "$tmp/hook.jsonl.log"
check hook-loaded grep -q '"type":"hooks.loaded"' "$tmp/hook.jsonl.log"
check hook-blocks-lock-file grep -q 'package-lock.json is written by the build tool' "$tmp/hook.jsonl.log"
check hook-blocks-push grep -q 'pushing to main is not allowed' "$tmp/hook.jsonl.log"
check hook-continues-once test "$(grep '"type":"hook.ran"' "$tmp/hook.jsonl.log" | grep -c '"outcome":"continue"')" = 1
check hook-reply grep -q "cannot run tests" "$tmp/hook.txt"
check hook-fix-applied grep -q 'a + b' "$ws/src/Calculator.java"
check hook-no-lock-file test ! -e "$ws/package-lock.json"
check hook-nothing-pushed sh -c "! grep '\"type\":\"process.started\"' '$tmp/hook.jsonl.log' | grep -q '\"args\":\[[^]]*\"push\"'"

exit $failed
