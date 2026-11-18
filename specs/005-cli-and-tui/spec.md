# Spec 005 — CLI and TUI

Status: Done · Phase: P1 · Created: 2026-11-18

## Summary

The `jharness` command: a JLine terminal UI that streams the agent's replies, shows each code step, and asks for approvals; `--model` and `/model` to pick a model profile; a headless `-p` mode for one prompt; and API keys from the environment or a 0600 secrets file. With this, the P1 gate can be run by hand against a local model and Claude.

Scope and options taken from the recommended choices while the user was away (2026-11-18).

## Roadmap items

- Model profiles in `settings.json`; `--model` and `/model` (D-032)
- JLine TUI
- Secrets from environment variables or a 0600 file

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-1.3 | Use Claude or any OpenAI-compatible server, switched by profile | `user-stories.md` |
| US-3.1 | Every state-changing action asks first | `user-stories.md` (the terminal prompt) |
| US-3.2 | "Always for this project" | `user-stories.md` (the prompt's option) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `jharness` opens a session in the current folder (or `--cwd DIR`) and starts the TUI. `--model NAME` picks a profile; an unknown name or bad settings print the error and exit with code 2. `--version` and `--help` work. |
| AC-02 | `jharness -p PROMPT` runs one turn without the TUI, prints the reply, and exits 0, or 1 if the turn ended with an error. State-changing calls are denied, since no one can answer. |
| AC-03 | The TUI edits lines with JLine and keeps history in `~/.jharness/history` (0600). It streams the reply, shows each code step and its status, and quits on Ctrl-D or `/exit`. `/help` lists the commands. |
| AC-04 | An approval question shows the operation's summary and takes `y` (once), `a` (always for this project) or `n` (deny); anything else asks again. |
| AC-05 | `/model` lists the profiles and marks the current one; `/model NAME` switches for the rest of the session, keeps the conversation, and emits `session.model_changed`. |
| AC-06 | A profile's `apiKeyEnv` is looked up in the environment, then in `~/.jharness/secrets` (D-042). The file is refused unless it is the user's and 0600. Key values never appear in events or the session log. |
| AC-07 | A native `jharness` binary builds in CI on all three targets; `--version` and a headless run with a recorded reply (`--replay FILE`, a hidden test option) pass. |

## Out of scope

- Esc to interrupt, checkpoints, `/undo`, `/rewind` and diff review (next specs).
- Project trust, layered settings and `JHARNESS.md`.
- `--mode` and JSON output (P4, US-5.2).

## Dependencies and assumptions

- Specs 002–004 (PRs #5–#7). picocli 4.7.7 with its annotation processor for Native Image config; JLine 3.30.17 with the FFM provider (checked in P0).

## Open questions

None.
