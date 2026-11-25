# Plan 054 — Subagents that don't block

Spec: `spec.md` · Status: Done · Created: 2026-11-25

## Approach

- **api.** Remove `waitAgent` from `Subagents`, `HostApi`, the reachability metadata, `api.d.ts`, `AgentDefinition.API` and `examples.md`. The JS shim keeps a stub that throws the explanation.
- **Settings.** `AgentSettings.autoTurns` (0 to 100, default 10).
- **`SubagentRunner`:**
  - `finish` of a background run notifies a monitor and calls an `onFinished` listener.
  - `deliveryPending`, `awaitFinished` (headless), `held`.
  - `answer(name)` sets `answerNow` and wakes the held question, which `await` then asks with the real prompter; it waits until the answer. `await` clears a stale `answerNow` when a new question starts.
- **`AgentLoop.autoTurn`** marks `turn.started` with `auto`; **`Session.deliveryTurn`** runs it with `AUTO_PROMPT` when a reply is pending (the existing notes put the replies first).
- **TUI:**
  - The loop runs an automatic turn before reading the prompt when a reply is pending, counting them against `agent.autoTurns`; a user message resets the count; Esc caps it.
  - A finish on another thread calls the `jharness-deliver` widget, which interrupts the prompt's thread only if the buffer is empty; the loop catches `UserInterruptException` and clears the interrupt.
  - `/answer`, its help line and completion; the notice names `/answer`.
- **Headless.** `Main.deliver` after the prompt's turn.
- **Tests:**
  - `BackgroundSubagentTest`: the delivery turn, `/answer`, the late answer, the setting, the old tests without `waitAgent`.
  - `TuiTest`: an automatic turn, an idle prompt woken by a finish (piped input), the cap, `/answer`.
  - `MainTest`: headless delivery.
  - `ApiContractTest`, `ExamplesTest`; CI's native step.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process or dependency | Yes |
| II Host owns side effects | Questions still go through the gate and the prompter; nothing is allowed without an answer | Yes |
| III Untrusted | No isolate change; a removed host method narrows the api | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Automatic turns are capped (`agent.autoTurns`); held questions still time out and are denied | Yes |
| VIII Events | `turn.started` gains `auto`; `subagent.wait_ended` `asked` for `/answer` | Yes |
| IX Native first | No new dependency; checked with a native build and CI's native step | Yes |
| X Tested | Delivery, wake, cap, `/answer`, late answer, headless, contract | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build and CI's native step on Linux
- [x] Idle prompt woken by a finish, in tmux, with the native binary on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Subagents that start each other turn forever | `agent.autoTurns` caps the chain; Esc stops it |
| An interrupt meant for the prompt reaches a turn | The widget interrupts only while JLine reads (under its lock); the loop clears the flag after every read |
| A model trained on the old api calls `waitAgent` | The stub's error says what to do instead |
