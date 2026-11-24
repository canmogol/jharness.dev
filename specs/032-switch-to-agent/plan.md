# Plan 032 — Switch to a subagent

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- `SubagentRunner`:
  - Keeps each run's isolate after `execute` and closes the oldest idle runs beyond `MAX_OPEN` (16).
  - `talk(name, message, onDelta)` runs a turn on the caller's thread, after `AgentLoop.resume()`.
  - `interrupt(name)` for Esc.
  - `attend(name)`: the background gate asks instead of marking the decision unattended when the decision is for the attended subagent.
  - `closeAll()` at session close.
- `Session`: `talk`, `interruptSubagent`, `attend` and `subagentOpen`; `Subagent` gets `open`.
- TUI:
  - `talking` changes the prompt, and messages go to `talkTurn`, which works like `turn` (raw mode, Esc, prompter not idle).
  - `/switch` sets `following` and `attend`; for a running subagent, it waits with `watch` until the turn ends.
  - `/switch main` resets all of this. `/switch` is in `HELP`.
- Tests: `BackgroundSubagentTest` (talking, attended questions, the open cap), `TuiTest.switchTalksToASubagent`, and the pseudo-terminal script's switch step.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | Yes |
| II Host owns side effects | The subagent acts through its gate as before | Yes |
| III Untrusted | Kept isolates are the same isolates; capped at 16 idle | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Only the subagent the user switched to can ask; the others stay unattended | Yes |
| VIII Events | Its turns emit the usual turn, step and model events under its name | Yes |
| IX Native first | No new dependency; the native pseudo-terminal check covers it | Yes |
| X Tested | Talk, attended and unattended questions, cap, TUI flow, native | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native pseudo-terminal check on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Many open isolates | At most 16 idle; closed with the session |
| The user forgets they are switched | The prompt names the subagent |
