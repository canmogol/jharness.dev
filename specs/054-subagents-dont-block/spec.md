# Spec 054 — Subagents that don't block

Status: Done · Phase: P3 · Created: 2026-11-25

## Summary

A turn never waits for a subagent, and a subagent never interrupts the user. `api.waitAgent` is removed. When a background subagent finishes, its reply starts a main turn by itself, up to `agent.autoTurns` in a row; `-p` runs until no background subagent is left. A held question stays a notice until the user answers it with `/answer`, without leaving the main chat. Decision D-087.

## Roadmap items

- Subagents that don't block (user report, 2026-11-25)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | When I spawn an agent to do work, I want to keep using the main chat while it works | User report, 2026-11-25 |
| — | If a spawned agent needs to ask me something, I want to see it without being interrupted, and answer it when I choose, then go on in the main chat | User report, 2026-11-25 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.waitAgent` is gone from the host api, `api.d.ts`, the examples and the allowed `tools` of custom agents. Calling it throws an error that says to end the turn. The running-cap error no longer mentions it. |
| AC-02 | When a background subagent finishes and the user sits at an empty main prompt, the main agent takes a turn with its reply. A finish during a turn, while the user types or while talking to a subagent waits for the next turn. A subagent that was stopped starts no turn; its status comes with the next one. The turn's `turn.started` has `"auto": true`, and the TUI prints a line before it. |
| AC-03 | `agent.autoTurns` (0 to 100, default 10) caps the automatic turns in a row since the user's last message; then the TUI says so once and replies wait for the user's message. 0 never starts one. Esc on an automatic turn stops the chain. Bad values are refused. |
| AC-04 | `/answer [NAME]` asks a held question (the oldest by default) with the normal approval prompt and returns to the main chat. The held notice names `/answer NAME`; Tab completes held names; a name that holds nothing, or no held question, gets a message. An `/answer` that comes after the wait ended doesn't answer a later question. |
| AC-05 | `-p`: after the prompt's turn, each background reply starts a turn while subagents run, up to `agent.autoTurns`; the result object is the last turn's, with the steps of all. Subagents still running at the end are stopped with a line on stderr. |
| AC-06 | CI's native step runs a background subagent in `-p` and checks both turns and `auto=true`. |
| AC-07 | README, D-087, notes on D-065 and D-072, Q96, roadmap. |

## Out of scope

- Typing while the main agent's own turn runs.
- Asking held questions during a turn.

## Dependencies and assumptions

- Specs 027, 032 and 033 (background subagents, `/switch`, held questions).
- JLine ends `readLine` with `UserInterruptException` when its thread is interrupted while it waits for a key, and lets another thread call a widget while it reads.

## Open questions

None. Design confirmed by the user on 2026-11-25; recommended options accepted while the user was away.
