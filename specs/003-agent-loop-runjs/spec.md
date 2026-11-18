# Spec 003 — Agent loop with RunJS

Status: Done · Phase: P1 · Created: 2026-11-18

## Summary

The agent loop on top of spec 002: send the conversation to the model, stream the reply, take the first JavaScript block, run it in the agent's isolate, send the result or the error with its line number back, and repeat until the model replies without code. The model learns the `api` from `api.d.ts` and examples in the system prompt. A replay provider plays back recorded replies, so the loop and the P1 gate scenario are tested without model calls.

Scope taken from the recommended options while the user was away (2026-11-18).

## Roadmap items

- Agent loop: stream reply, extract the JS block, run it, return result or error with line numbers
- `api.d.ts` and examples in the system prompt
- Replay provider for deterministic tests

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.1 | Describe a bug and have the agent find and fix it | `user-stories.md` (search and edit; builds come in P2) |
| US-6.2 | Deterministic tests of the agent loop without model calls | `user-stories.md` |
| US-3.6 | Every action is an event | `user-stories.md` (loop events) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | The loop sends the system prompt and conversation, streams the reply, and takes the first fenced block marked `js` or `javascript`. With a block, it runs it in the agent's isolate and sends the result back as the next user message; without one, the turn ends with the reply text. |
| AC-02 | A run's error goes back to the model with its message and line number; a limit or watchdog stop goes back with the limit's name. The next step runs in a fresh context. |
| AC-03 | A turn stops after `agent.maxSteps` steps (default 25) with stop reason `max_steps`, and on a model error with stop reason `error`. Results sent to the model are capped at `timeouts.outputChars`. |
| AC-04 | Each turn emits `turn.started` and `turn.completed`, and each step emits `loop.code` and `loop.result`, besides the events from spec 002. |
| AC-05 | `api.d.ts` declares every `api` method with its argument and result shapes; a test fails if the declaration and the host methods differ. |
| AC-06 | The system prompt contains `api.d.ts`, worked examples, the rules for replying (one block per reply, strings in and out, no code when done) and the workspace context from `api.readContext`. |
| AC-07 | The replay provider plays back replies from a JSONL recording, emits the same events as a real provider, and fails clearly when the recording runs out. A recording provider wraps a real provider and writes such a file. |
| AC-08 | A replay test runs the P1 gate scenario: in a sample repository with a seeded bug, the agent searches, reads, edits the file, commits, and ends the turn; the file is fixed and the commit exists. |
| AC-09 | Live loop tests against Ollama and Claude run the same scenario when `OLLAMA_URL` or `ANTHROPIC_API_KEY` is set. |

## Out of scope

- Permission modes and approvals (the next spec); tests use an allow-all gate.
- The CLI, TUI, `--model`, `/model` and Esc to interrupt.
- Compaction, prompt caching and subagents.

## Dependencies and assumptions

- Spec 002 (branch `002-host-foundations`, PR #5).
- The result of a run is sent back as a user message, so providers need no tool-call support (D-007).

## Open questions

None.
