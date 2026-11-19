# Changelog

<!-- last-commit: 3e9a571a3c2584794f64ed52a3d7c6167a235849 -->

## 2026-11-19 · Phase 1 gate

- Recorded the by-hand P1 gate procedure and both attempts in `docs/p1-gate.md`. Attempt 1 (`qwen2.5-coder-7b-instruct`) fixed the bug but committed only after a second prompt; attempt 2 (`qwen/qwen3.6-27b`, after specs 008–010) passed. Phase 1 gate passed.
- Decision: the gate passes on a local model, and the live Claude run becomes a Phase 4 release check (D-045).

## 2026-11-19 · 010 Thinking text and a quieter session log

- Streamed `model.delta` and the new `model.thinking_delta` go to the TUI but not to the session log; `model.completed` now carries the whole reply as `text` and the thinking as `thinking`, each capped at 65,536 characters.
- Thinking text is captured from `reasoning_content` / `reasoning` fields, a leading `<think>…</think>` (tags may be split across chunks) and Claude thinking blocks. It is never run or sent back to the model.
- Added `ui.thinking` and `/thinking` to show the thinking dim in the TUI.
- Decision: streamed deltas stay off the log, and thinking is kept apart from the reply (D-046).

## 2026-11-19 · 009 TUI usability

- Alt+Enter or Ctrl-J inserts a newline at the prompt; Enter sends.
- Replies are colored as Markdown line by line; step lines, errors and diffs are colored; `NO_COLOR` turns colors off.
- The start line shows the session log path; added `ui.events` (`none`, `steps`, `all`) and `/events`.
- A failed step shows its error and line number; `loop.result` carries both.

## 2026-11-19 · 008 Loop notice for extra code blocks

- When a reply has more than one `js` block, the result sent back tells the model how many didn't run, and `loop.code` records `ignoredBlocks`. Found in P1 gate attempt 1, where a dropped commit block was reported as done.

## 2026-11-19

- Claude may run `git switch` and `gh pr create` in this repo.


## 2026-11-18 · 007 Checkpoints, undo and interrupt

- Added per-turn checkpoints that save a file's content before the agent first changes it; `/undo` and `/rewind N` restore files and cut the conversation back, refusing when a file changed since (`--force` overrides).
- File changes now carry a unified diff: shown in the approval question in `ask` mode, printed after the change in other modes, and with `/diff` for the last turn.
- Esc stops a running turn at once (model stream and running block), with stop reason `interrupted`; the session log stays intact on interrupt.
- Decision: checkpoints hold file contents per turn, and undo restores files and the conversation, not git history (D-044).

## 2026-11-18 · 006 Project trust, layered settings and JHARNESS.md

- Added the project trust question, remembered in `~/.jharness/trusted.json`; headless runs and unknown folders are untrusted.
- Settings now merge user, project and session layers; an untrusted project's settings can only tighten (`files.deny`, `permissions.mode: plan`), and ignored keys are reported in a `settings.ignored` event.
- Workspace and user `JHARNESS.md` files are read into the system prompt, each capped at 32 KB.
- Decision: trust answers, settings layers and the untrusted-only-tightens rule (D-043).

## 2026-11-18 · 005 CLI and TUI

- Added the `jharness` command: JLine TUI with streamed replies, code steps, approvals (`y`/`a`/`n`) and history; headless `-p` mode; `--model`, `--cwd`, `--version`, `--help`.
- Added `/model` to list and switch profiles mid-session.
- API keys now come from the environment, then a 0600 `~/.jharness/secrets` file; key values never reach events or the log.
- CI builds the native `jharness` binary on all three targets and checks a headless run and the TUI; the README covers building and running.
- Decision: secrets from the environment, then `~/.jharness/secrets` (D-042).

## 2026-11-18 · 004 Permissions and file policy

- Added the permission engine with `ask`, `accept-edits`, `plan` and `bypass` modes; approvals are asked inside a running block with the watchdog paused.
- "Always for this project" approvals are saved per project in the user folder; repository files can't add rules.
- Added the file policy: read-only mounted folders and a deny list that applies in every mode, including search with `rg` and the Java fallback.
- Decisions: permanent approvals live in the user folder (D-040); the four permission modes, with the file policy in every mode (D-041).

## 2026-11-18 · 003 Agent loop with RunJS

- Added the agent loop: stream the reply, run the first JS block in the isolate, send back the result or the error with its line number, and stop at `agent.maxSteps`.
- Added `api.d.ts` (checked against the host methods) and a system prompt with examples and workspace context.
- Added the replay and recording providers, a replay test of the P1 gate scenario, and live loop tests against Ollama and Claude when configured.

## 2026-11-18 · 002 Host foundations

- Added the host modules: event bus with host-stamped envelopes and the JSONL session log, settings with validation, workspace confinement and a permission gate, and `ProcessRunner` with git and `rg` plus a Java search fallback.
- Added the isolate manager (one engine per agent, fresh context per run, limits, watchdog) and the `api` facade.
- Added the OpenAI-compatible and Claude providers with model profiles, a stub model server, and an architecture test that keeps process and network access in their modules.
- Added a native smoke binary and a CI matrix for the three targets.
- Claude may now commit and push feature branches in this repo.
- Decisions: session logs live in the user folder (D-038); git runs local operations only until the egress proxy (D-039).

## 2026-11-18 · 001 P0 Spike

- Added SpikeApi and SandboxConfig (built-in defaults, `--config` file, `--set` overrides, validated keys) and the isolate probe: echo, creation timing, RSS per engine, memory protection.
- Added the limits probe: CPU, heap and isolate memory, wall-clock watchdog, blocked vs. busy host time, config overrides; each limit is followed by a fresh context on the same engine.
- Added the JLine probe with the FFM terminal provider and an automated pseudo-terminal check.
- Added the ModelJars model probe (pure-Java and Rust backends, 8k-token prompt, opening without the marker JAR) and a manual CI model job.
- Moved the macOS CI job to `macos-26` and kept pushes from cancelling manual spike runs.
- The wall-clock watchdog now escalates from context close to engine close to a recorded failure (D-037).
- Recorded the P0 results in `docs/p0-results.md` and passed the Phase 0 gate.
- Decisions: isolate mode comes from the checked engine default (D-034); ModelJars is a no-go in the native image for v0.1, so local models run through Ollama or llama.cpp, with a Rust-backend helper process to revisit after v0.1 (D-035, changes D-013); memory protection keys are off by default, with opt-in `auto` and `on` (D-036, changes D-018).

## 2026-11-17 · 001 P0 Spike

- Added the P0 spike spec, plan and tasks.
- Made sandbox and engine limits configurable, with 256 KB stream caps.
- Raised run time limits and added operation timeouts and an output cap.
- Confirmed the P0 go/no-go thresholds (D-030).
- Added the spike Maven build, the GitHub Actions native matrix (linux-amd64, linux-aarch64, macos-aarch64), `SECURITY.md` and `CONTRIBUTING.md`.

## 2026-11-17

- Started the repository with the MIT license and README.
- Added the product docs: mission, constitution, roadmap, tech stack, decisions, user stories and architecture plan.
- Hooks are JavaScript only (D-022), and MCP is reached through `api.mcp` (D-023).
- Allowed `rg` for search (D-027).
- Added the optional action reviewer, model profiles and offline mode (D-031 to D-033).
- Added `CLAUDE.md` and enabled the p-spec plugin for the repo.
