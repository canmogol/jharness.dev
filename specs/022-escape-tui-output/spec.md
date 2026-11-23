# Spec 022 — Escape control characters in live output

Status: Done · Phase: P2 · Created: 2026-11-23

## Summary

Spec 021 escapes control characters in `jharness log`, but the terminal UI and `-p` still print model replies, thinking, diffs of file contents, error messages and approval questions as they come. A reply or a file in a hostile repository can then send escape sequences: change the window title, clear the screen, move the cursor to rewrite an approval question, or reorder text with bidi controls. This spec escapes them everywhere JHarness prints, before the Phase 2 gate run. Decision D-060.

## Roadmap items

- Hardening before the Phase 2 gate (new item: control characters escaped in live output)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.1 | Agent code can't harm my machine | `user-stories.md` (the terminal is part of the machine) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | In the TUI, every printed string passes through one escaping writer: C0 controls except newline and tab, DEL, C1 controls and Unicode bidi controls show as `\e`, `\xNN` or `\uNNNN`, and a carriage return is dropped. Only JHarness's own styling and key-mode sequences reach the terminal raw. |
| AC-02 | `Styler` escapes every string it is given before adding color, so styled output is safe to print raw; colors still work. |
| AC-03 | Covered paths: streamed reply and thinking, diffs (in questions, on `file.changed` and `/diff`), step errors, retry lines, inline events, approval questions, `/network` and `/model` output, settings errors and the workspace path. Each goes through the escaping writer; a test sends hostile text through the reply, thinking, diff, step error, retry line, inline events, question and workspace path with a color and a dumb terminal. |
| AC-04 | `jharness -p` escapes its stdout and stderr the same way, including characters split across writes. |
| AC-05 | Newlines, tabs and ordinary Unicode (accents, symbols, emoji) print unchanged. |
| AC-06 | The README and D-060 say so. |

## Out of scope

- Escaping what the user types (JLine echoes it).
- Changing what goes into the session log (it keeps the exact text; `jharness log` escapes on output).

## Dependencies and assumptions

- `TerminalSafe` from spec 021.
- Output is UTF-8.

## Open questions

None. The user asked for this as spec 022, before the Phase 2 gate run.
