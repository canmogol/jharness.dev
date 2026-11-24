# P3 gate run

Gate (`../product/roadmap.md`): one custom agent and one hook ship as working examples.

Status: **passed** · Run: 2026-11-24 · Evidence: `scripts/p3-gate.sh` in the native binary, the JVM tests below, and CI's native steps

## Summary

| Target | Where | `scripts/p3-gate.sh` (native) | `mvn install` |
| --- | --- | --- | --- |
| linux-amd64 | A local machine | 13 pass | Pass |
| macos-aarch64 | A local machine (macOS 14.3) | 13 pass | Pass |
| linux-amd64, linux-aarch64, macos-aarch64 | CI, the `P3 gate` step | Runs on every push | Runs on every push |

The gate uses recorded model replies (`--replay`), so it checks the examples and JHarness, not a model's choices. A live model run of the examples belongs to the v0.1 release checks, like the Claude run in the P1 gate (D-045).

## The examples

| Example | File | What the gate checks |
| --- | --- | --- |
| Custom agent | `examples/.jharness/agents/reviewer/` | Loads as `--agent reviewer`; its helpers `changedFiles()` and `diffOf()` find the uncommitted bug; `api.writeFile` is refused because the agent's `tools` leave it out and `plan: true`; it replies with the finding; the file is unchanged |
| Hook | `examples/.jharness/hooks/guard.js` | Loads; `preTool` blocks a hand edit of `package-lock.json` and `git push` to `main` before any process starts; the agent's fix still applies; `stop` sends the agent back once because it changed a file without running tests, and not a second time |

## Checks

| Check | Pass if |
| --- | --- |
| `agent-loaded` | `session.started` has `customAgent: reviewer` |
| `agent-helpers` | The first result lists `src/Calculator.java` and its diff with `a - b` |
| `agent-read-only` | `api.writeFile` fails with "can't use api.writeFile" |
| `agent-reply` | The printed reply names the bug |
| `agent-changed-nothing` | `src/Calculator.java` is byte for byte the same |
| `hook-loaded` | `hooks.loaded` is in the log |
| `hook-blocks-lock-file` | The write fails with the hook's lock-file reason |
| `hook-blocks-push` | The push fails with "pushing to main is not allowed" |
| `hook-continues-once` | Exactly one `hook.ran` with `outcome: continue` |
| `hook-reply` | The agent's last reply is printed |
| `hook-fix-applied` | `src/Calculator.java` has `a + b` again (`accept-edits` mode) |
| `hook-no-lock-file` | `package-lock.json` doesn't exist |
| `hook-nothing-pushed` | No `git push` process started |

## Also covering the examples

| Test | What it covers |
| --- | --- |
| `CustomAgentTest` | Agent loading, `tools`, `plan`, helpers, project agents only when trusted |
| `HookTest` | The example `guard.js`, the stop hook, failing hooks block, project hooks only when trusted, subagents run hooks |
| `CommandTest` | The example slash commands `review.md` and `changed.js` |
| CI `Run native jharness` | The reviewer agent and the guard hook in the native binary on every target |

## Fix found while running the gate

The first script used a local bare repository as `origin`. JHarness refuses git network operations on remotes that aren't `https://` (D-055), so the push failed at that check before the hook could block it. The gate now uses an `https://` remote that is never reached, because the hook blocks the push first. JHarness was not changed.

## How to run it again

1. `mvn install`.
2. `mvn -Pnative -pl jharness-cli package -DskipTests`.
3. `scripts/p3-gate.sh jharness-cli/target/jharness`: every `gate <check>` line must be `pass`, and the script exits 0.

The CI workflow (`.github/workflows/ci.yml`) runs step 3 on every target.
