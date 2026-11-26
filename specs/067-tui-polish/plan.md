# Plan 067 — TUI polish

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **The line.**
  - `Tui.promptText()` puts a dim `─` line, one column short of the width, above `> ` on live terminals.
  - `TuiReader.promptOnResize` redraws the prompt with the new width on a resize.
  - `startAtBottom` moves to the third-last row.
- **Agent item.** `StatusState.agent`, the `agent` item in `UiSettings.STATUS_ITEMS`, set by `/switch` through `showAgent`.
- **Styles.** `Styler.paint` wraps each line on its own.
- **Hold.**
  - `AboveWriter` keeps partial text per thread in a map.
  - `hold()` makes other threads' text wait, and `release()` prints it.
  - `TerminalPrompter.askNow` holds around a question.
- **History.** `LineReader.DISABLE_HISTORY` is set around question reads (`askNow`, `Tui.answerLine`).
- **Questions at once.**
  - The prompter's `Holds.waiting` sets `answerNow` when the prompt talks to main, and calls a `jharness-ask` widget that interrupts the prompt's read.
  - The loop catches the interrupt, keeps the partial line for the next prompt (`restoreBuffer`) and runs `answer(session, "main")`.
  - The notice prints only while the prompt talks to a subagent.
- **Tests.**
  - `AboveWriterTest`, `StylerTest`, `StatusLineTest`.
  - `TuiLiveTest`: a question at once, typing kept, no `y` in the history, `n` denies.
  - `TuiTest`: the start row.
  - The tmux check on Linux and macOS.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | No change | Yes |
| III Untrusted | No change | Yes |
| IV No shell | No change | Yes |
| V Proxy | No change | Yes |
| VI Strings | No change | Yes |
| VII Safe by default | Questions are easier to read and answer; nothing is approved without an answer | Yes |
| VIII Events | `question.waiting` and `question.wait_ended` unchanged | Yes |
| IX Native first | tmux check with the native binary on Linux and macOS | Yes |
| X Tested | Writer, styles, status, live prompt, start row | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] `scripts/tui-tmux-check.py` with the native binary on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A question takes the prompt while the user types | The typed text comes back in the next prompt |
| Held output grows during a long question | Only text written while one question is open; it prints after the answer |
| A terminal without line-drawing characters | JLine falls back as for any `─`; the line is dim and harmless |
