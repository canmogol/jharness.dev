# Tasks 009 — TUI usability

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-19

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Data

- [x] T001 [P] Add `error` and `line` to `loop.result` for failed steps; loop test (AC-03)
- [x] T002 [P] Add the `ui` settings section with `events`; settings tests (AC-04)

## Group 2 — TUI

- [x] T003 Multi-line input: newline widget, key bindings, secondary prompt; TUI test (AC-01)
- [x] T004 `Styler` for Markdown lines, steps, errors and diffs, with plain output for `NO_COLOR` and dumb terminals; unit tests (AC-02)
- [x] T005 Show step errors, the log path, the `ui.events` levels and `/events`; TUI tests (AC-03, AC-04, AC-05)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T003 |
| AC-02 | T004 |
| AC-03 | T001, T005 |
| AC-04 | T002, T005 |
| AC-05 | T005 |
