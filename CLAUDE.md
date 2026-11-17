# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this repo is

JHarness: an open-source terminal coding agent shipped as one native executable (Java on GraalVM
Native Image), where the model acts only by writing JavaScript that runs in GraalJS polyglot
isolates. The repo is planned with spec-driven development. **The docs in this repo are the source
of truth**; nothing outside it (chat notes, older design docs) overrides them.

## Read first

| File | Why |
| --- | --- |
| `product/constitution.md` | Rules every change must follow. Read before planning or coding. |
| `product/decisions.md` | Accepted decisions (`D-NNN`) and the questions behind them. Don't contradict them. |
| `product/roadmap.md` | Phases, items and gates. |
| `product/tech-stack.md` | Technologies and default limits. |
| `product/mission.md`, `user-stories.md`, `plan.md` | Scope, stories, architecture and modules. |
| `specs/NNN-<slug>/` | The feature being worked on: `spec.md`, `plan.md`, `tasks.md`. |

## How work is done

- Every feature goes through the p-spec plugin (`p-spec@claude-basics`):
  1. `/p-spec:p-spec-next-feature` starts or resumes a feature: `NNN-<slug>` branch and `specs/NNN-<slug>/`.
  2. Work through `tasks.md` in order; tick each task `- [x]` when it's done. Every task names the acceptance criteria (`AC-NN`) it proves.
  3. `/p-spec:p-spec-finish-feature` checks the spec, ticks the roadmap items, commits and opens a PR.
  4. After the user merges, `/p-spec:p-spec-changelog` on `master` updates `CHANGELOG.md`.
- No production code without a spec (constitution Article XI). Spike code under `spike/` is thrown away or rewritten under a later spec.
- Fill in the constitution check in every `plan.md`. If a design would fail a row, raise it with the user instead of writing it.
- When an answer settles something architectural, add it to `product/decisions.md` (index row, `## D-NNN` section, Q&A row, `Last updated`) after the user confirms. Never change an accepted decision silently.
- Work on branches and open PRs to `master`. Don't push to `master` directly; the user merges.

## Commits and PRs

- Never add a `Co-Authored-By` line or any Claude attribution to commits, PR titles or PR bodies.
- Short imperative subjects (`Add ProcessRunner argument checks`). Spec-finishing commits: `Finish NNN: <Title>`.

## Code rules (from the constitution and decisions)

- Agent JavaScript reaches the world only through the `api` facade; only strings and JSON cross the isolate boundary.
- Isolates: `SandboxPolicy.UNTRUSTED`, `HostAccess.UNTRUSTED`, `engine.IsolateMode=internal`, one `Engine` per agent, a fresh `Context` per run, one thread. Limit values come from configuration; never remove a limit.
- Processes start only through `ProcessRunner` with typed arguments: git, Maven, Gradle, npm, `rg`, and stdio MCP servers from user config. Never `sh -c`, never a command string.
- All network traffic goes through the host egress proxy. Don't build an `HttpClient` or open a socket anywhere else.
- Every new action emits a JSON event with a host-stamped envelope.
- Every dependency must build and run under Native Image on Linux and macOS before it's adopted.
- Security rules are covered by tests; every security bug gets a regression test first.

## Build and test

- Java 25, Maven, Oracle GraalVM 25.x. Develop and run tests on the JVM; native builds run in CI (`.github/workflows/`) and before releases.
- Targets: linux-amd64, linux-aarch64, macos-aarch64. Windows comes after v0.1.

## Working from a Claude Code cloud session

- Maven Central, Oracle's GraalVM downloads and model hosts may be unreachable from the sandbox, so Java builds may not run there. Write the code, push the branch and let CI build it.
- GitHub Actions run logs, secrets, settings, tags and releases may be blocked for the session. Ask the user for failing logs, and give them the exact commands for tags and releases.

## Writing docs

Short and specific: tables for comparisons, numbered lists for steps, one idea per bullet. Keep the
existing formats of `product/` files and spec files.
