# Spec 064 — /clear

Status: Done · Phase: P4 · Created: 2026-11-26

## Summary

`/clear` empties the main agent's conversation so the model starts fresh. Unlike `/rewind 0`, it leaves the files as they are. The session and its log go on, and so do plan mode and subagents; the todo list goes with the conversation. `/undo` and `/rewind` stop at the clear, as they stop at a compaction. Decision D-097.

## Roadmap items

- `/clear` (user request, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Empty the conversation so the model starts fresh, keeping my files and the session | User request, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `/clear` removes every message of the main agent's conversation; the next request carries only the new message. Files are unchanged. The session id, its log, the turn count, plan mode, background and idle subagents, and usage all stay. The todo list is emptied. |
| AC-02 | Afterwards `/undo` says "Nothing to undo." and `/rewind N` refuses turns up to the clear ("turns up to N were cleared with /clear"). `/diff` shows no last-turn changes. |
| AC-03 | It clears at once and says what it did: the number of messages, that files are unchanged, that the log keeps everything, and that undo stops there. An empty conversation gets "The conversation is already empty." While talking to a subagent, it clears the main agent's conversation and says so. |
| AC-04 | While a main turn runs, `/clear` waits, like `/undo` and `/compact` (D-088). |
| AC-05 | A `conversation.cleared` event (messages, clearedThrough). The saved state holds the empty conversation, so `--resume` continues after the clear and can't rewind past it. The status line's context drops to empty. |
| AC-06 | A line in `/help`, in the guide (README and system prompt), D-097, a note on D-088, Q106, roadmap. |

## Out of scope

- Clearing a subagent's conversation.
- Asking for confirmation (the user chose not to).

## Dependencies and assumptions

- Specs 007 (undo), 039 (compaction), 043 (resume), 055 (commands that wait), 063 (the guide).

## Open questions

None. Design confirmed by the user on 2026-11-26: undo stops at the clear, subagents are kept, the todo list is reset, no confirmation.
