# Plan 064 — /clear

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **`AgentLoop.clear()`.**
  - Empties `conversation` and `liveTurns`, sets `clearedThrough` to the current turn, and resets `firstPrompt` and the known token count.
  - Publishes `conversation.cleared` and saves the state.
  - `rewind` refuses `n < clearedThrough`, and the saved snapshot's `compactedThrough` takes `clearedThrough` into account, so a resumed session stops there too.
- **`PlanState.clearTodos("main")`** empties the list and publishes `todo.updated`. `Session.clear()` does both.
- **TUI.** The `/clear` command and its help line; `waitsForTurn` lists it; the messages.
- **Status line.** `conversation.cleared` sets the context to 0, estimated.
- **Guide.** A `/clear` line; `scripts/sync-guide.py` updates the README.
- **Tests.**
  - `ClearTest`: the conversation, files, todos, undo and rewind, the next request, the same session, the event, resume.
  - `TuiTest`: the messages, plan mode kept.
  - `TuiLiveTest`: waits during a turn.
  - `GuideTest`.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | No file is touched | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Files never change; the log keeps the whole conversation | Yes |
| VIII Events | `conversation.cleared` | Yes |
| IX Native first | Checked in tmux with the native binary | Yes |
| X Tested | Loop, session, resume, TUI, live prompt | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] `/clear` in tmux with the native binary on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Undo restores files from turns the model no longer knows | Undo and rewind stop at the clear |
| A resumed session brings the old conversation back | The saved state is the empty conversation, with the clear's turn as the limit |
