# Plan 035 — Slash commands

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Agent:
  - `CommandRunner` loads and checks the files, splits off the front matter, and probes JS files for `run` and `description` in a short-lived isolate.
  - `run(name, args)` expands a command and publishes `command.ran`.
  - `Session.commands(builtIn)` loads the commands on first use with the session's main services, so a JS command's calls are gated and hooked.
- CLI:
  - The TUI's default branch runs a user command and sends its prompt with `turn` or `talkTurn`.
  - `/commands` lists them, `/help` adds them, and Tab completes them through the current session.
  - `Main` expands commands in `-p`.
- Events `commands.loaded` and `command.ran`; examples; tests; D-074, Q79, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | JS commands run in isolates in the process | Yes |
| II Host owns side effects | Commands act only through the `api` | Yes |
| III Untrusted | One isolate per run, subagent limits, fresh context | Yes |
| IV No shell | Markdown and JS only | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Arguments and results cross as JSON strings | Yes |
| VII Safe by default | Project commands only in trusted projects; built-ins can't be replaced; calls are gated | Yes |
| VIII Events | `commands.loaded`, `command.ran` | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | Templates, scripts, errors, trust, overrides, TUI, `-p`, examples | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A project command hides a user's | Project commands load only in trusted projects and `/commands` marks them `[project]` |
