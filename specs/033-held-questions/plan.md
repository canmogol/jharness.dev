# Plan 033 — Held background questions

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- `AgentSettings`: `backgroundQuestions` and `backgroundQuestionWait`.
- `Unattended`:
  - `Waiter` and `run(agent, waiter, body)`.
  - `guard` calls the waiter when there is one, and otherwise answers `UNATTENDED` as before.
- `SubagentRunner`:
  - `holdQuestions(interactive)`; the background gate passes `this::await` when holding.
  - `await` publishes `subagent.waiting` and waits on a monitor until `attend(name)`, a stop or the limit. It then asks the real prompter, or answers `UNATTENDED`.
  - `Run.waiting` feeds the `waiting` status, and a stop wakes the wait.
- `Session.Access.interactive`, set by the terminal UI in `Main`.
- TUI: the notice lines. `/switch` makes the prompter ready before it marks the subagent attended, so the woken question can be asked.
- Tests:
  - `BackgroundSubagentTest`: held then asked; the time limit; a stop; deny mode; the settings.
  - `TuiTest.aHeldQuestionIsAnsweredThroughSwitch`.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | Yes |
| II Host owns side effects | Still the same gate and prompter | Yes |
| III Untrusted | The block clock is paused while the gate waits, as for any question | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Nothing is allowed without the user's answer; unanswered questions are denied | Yes |
| VIII Events | `subagent.waiting`, `subagent.wait_ended` | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | Held, asked, timeout, stop, deny mode, TUI flow | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A forgotten held question blocks a subagent | The time limit, and `/stop` ends it at once |
