# Spec 058 — Input at the bottom

Status: Done · Phase: P3 · Created: 2026-11-26

## Summary

On a real terminal, the prompt starts on the row above the status line, and output pushes the screen up from there, so the input stays at the bottom. A window that shrinks no longer draws the status line over the prompt: the resize finding from spec 053. Decision D-091.

## Roadmap items

- Input at the bottom (user request, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Start the input field at the bottom of the terminal and keep it there | User request, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | After the banner, JHarness asks the terminal where the cursor is (DSR 6) and moves it down to the row above the last one; the first prompt and everything after start there. Dumb terminals are left as they are. |
| AC-02 | Keys typed before the terminal answers reach the first prompt, Enter included. |
| AC-03 | A terminal that doesn't answer within 200 ms holds up nothing; the prompt starts where the cursor is. |
| AC-04 | After a resize, while the prompt reads or during a turn, the status line is on the last row and the prompt is not on it. `scripts/tui-tmux-check.py` checks the start row and passes at 80x24, 120x40 and 50x16, resizes included, on Linux and macOS. |
| AC-05 | README, D-091, Q100, roadmap, manual-check results. |

## Out of scope

- Redrawing the screen on resize (JLine and the terminal keep what they show).
- A setting to turn it off.

## Dependencies and assumptions

- Specs 042 (status line), 053 (the tmux check) and 055 (the live prompt).
- JLine replaces the terminal's WINCH handler while `readLine` runs, and its `LineReaderImpl.handleSignal` can be overridden.

## Open questions

None. Recommended design accepted while the user was away, 2026-11-26.
