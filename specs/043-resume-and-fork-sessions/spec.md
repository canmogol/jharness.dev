# Spec 043 — Resume and fork sessions

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

After each turn JHarness saves the main conversation next to the session log. `jharness --resume [ID]` continues a saved session in the same log; `jharness --fork [ID]` starts a new session from a copy. Both work in the UI and with `-p`. Decision D-081.

## Roadmap items

- Resume and fork sessions

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.7 | As a developer, I want to resume yesterday's session, so I don't have to re-explain the task. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | After each turn, `/compact`, `/undo` and `/rewind`, the main conversation, last turn, compaction mark, profile, model, first prompt, origin and time are written to `<session-id>.state.json` next to the session log, mode 0600, through a temporary file and a move. A failed write is recorded as `session.state_failed`. |
| AC-02 | `--resume` and `--fork` take `last` (the default), an id or a unique start of one, among this folder's saved sessions; none, no match, several matches, or both flags stop with a clear message and exit 2. |
| AC-03 | `--resume` keeps the session id, appends to its log and goes on with the next turn number; the model gets the saved conversation. `session.resumed` (from, fork, turns, messages) records it. |
| AC-04 | `--fork` starts a new session id from a copy; its state is written at once with `forkedFrom`, and the saved session is unchanged. |
| AC-05 | In the UI, the start says which session it resumed or forked, with its turns, messages and first prompt, and shows the last reply. `/rewind` to a turn from before the resume is refused with a clear message; `/undo` reaches only new turns. |
| AC-06 | Background subagents, the todo list and plan mode start fresh; the model is the current choice. |
| AC-07 | The README and D-081 document it, and CI's native step resumes and forks a headless session. |

## Out of scope

- Restoring file checkpoints across runs, background subagents or held questions.
- A session picker in the UI.

## Dependencies and assumptions

- Session logs (D-038, D-070), compaction marks (D-077), checkpoints (D-044).

## Open questions

None.
