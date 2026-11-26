# Plan 058 — Input at the bottom

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **Start.** `Tui.startAtBottom()` runs after the banner and the status line's first draw.
  1. In raw mode, it writes `ESC [6n` and reads the answer for up to 200 ms.
  2. It line-feeds the cursor down to `rows - 2`.
  3. Keys read meanwhile go back to JLine with `runMacro`, so the first prompt reads them as typed.
- **Resize.** `TuiReader` extends `LineReaderImpl` and runs a hook before `handleSignal(WINCH)`; the TUI's own WINCH handler, used when the prompt isn't reading, calls the same hook. `keepRoomForStatus()` then:
  1. saves the cursor;
  2. resets the scroll region;
  3. restores the cursor;
  4. writes a line feed (it scrolls only when the cursor is on the last row) and moves the cursor back up.

  JLine then sets the new scroll region and draws the status line on a row the prompt doesn't use.
- **Reader.** `Tui` builds `TuiReader` itself with what `LineReaderBuilder` set: history, completer, options, variables.
- **Tests.**
  - `TuiTest`: the move from a known row, typed-ahead keys, a terminal that doesn't answer.
  - `scripts/tui-tmux-check.py`: the start row; the resize checks now pass.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency; a JLine subclass | Yes |
| II Host owns side effects | Terminal output only | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | No network | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | A terminal that doesn't answer keeps the old behaviour | Yes |
| VIII Events | No new action | Yes |
| IX Native first | Checked with native builds on Linux and macOS | Yes |
| X Tested | Unit tests, the pty check and the tmux check | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build on Linux and macOS; `scripts/tui-tmux-check.py`, `scripts/tui-pty-check.py` and the spec 055 tmux scenarios on both
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| The cursor answer is lost among typed keys | Keys outside the answer are replayed to the prompt |
| A terminal never answers | 200 ms limit |
| A shrink drops the prompt onto the status row | A line feed before JLine resizes keeps a row free |
