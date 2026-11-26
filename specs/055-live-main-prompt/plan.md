# Plan 055 — A main prompt that never blocks

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **Output above the prompt.** `Tui`'s two writers go through an `AboveWriter`. While a background turn runs, it keeps each thread's partial line and sends whole lines to `LineReader.printAbove`. Otherwise it writes straight to the terminal, as before.
- **Turns on a worker.** On terminals that aren't dumb, the loop only reads.
  - A message starts a turn on a `main-turn` thread, or joins the queue if one runs.
  - When a turn ends, the worker sends the queued messages as the next turn. If none are queued, it takes an automatic turn when a reply is pending (spec 054, same cap).
  - A finished subagent starts the worker when nothing runs; the `jharness-deliver` prompt wake stays only for dumb terminals.
- **Esc.** A `jharness-esc` widget bound to a lone Esc, with a short ambiguous-key timeout. It interrupts the loop and denies a held main question. Main turns no longer use `EscWatcher`.
- **Held main questions.** `TerminalPrompter` gets a hold mode for background turns.
  - A question that isn't a subagent's granted one (`Unattended.active()`) waits as a ticket: a `question.waiting` event, a notice, then `question.wait_ended` with `asked` or `stopped`.
  - `/answer main` grants the ticket and waits until it was asked, so the question never reads while the main prompt does.
- **Commands.** `waitsForTurn` refuses the listed commands while a turn runs. `/exit` and EOF interrupt the turn, deny held questions, and join the worker.
- **Status line.** `turn N · working` while busy; a held main question counts as held.
- **Tests.**
  - `TuiLiveTest`, with an xterm fed through a pipe: typing during a turn, a queued message, Esc, `/answer main`, a refused command, `/exit` during a turn.
  - The xterm tests in `TuiTest` and `TuiEscapeTest` that ran turns from scripted input wait for each turn to end; their checks stay.
  - `StatusLineTest`: `working`.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process or dependency | Yes |
| II Host owns side effects | Questions still go through the gate and the prompter; a held one allows nothing until answered | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged; its questions are held like the others | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | A held question waits or is denied (Esc, exit); it is never allowed by default | Yes |
| VIII Events | `question.waiting` and `question.wait_ended` for the main agent | Yes |
| IX Native first | JLine only; checked with a native build in tmux | Yes |
| X Tested | Live prompt, queue, Esc, `/answer main`, refusals, exit; existing tests kept | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build and CI's native step on Linux
- [x] Typing during a turn, the queue, Esc and `/answer main`, in tmux with the native binary on Linux and macOS
- [x] `scripts/tui-pty-check.py` (now answers with `/answer main`) and `scripts/tui-tmux-check.py` (120x40 passes; the resize finding from spec 053 is unchanged)
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Two threads read the terminal at once | A held question reads only after `/answer` grants it, while the prompt thread waits for its answer |
| Esc stops the isolate while JLine's lock is held, and the turn waits for that lock to print | The Esc widget stops the turn on its own thread (found by `TuiLiveTest`) |
| Output and the prompt line garble each other | Whole lines through `printAbove` only, under JLine's lock |
| Lock order between the TUI and JLine | The TUI's lock is always taken before JLine's; widgets take no TUI lock |
| A lone Esc is slow because it prefixes Alt keys | A 150 ms ambiguous-key timeout |
