# Tasks 024 — `/` and Tab list the commands

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-23

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Tab

- [x] T001 `Tui.COMMANDS` from `HELP`; `TuiTest.commandsComeFromTheHelp` (AC-03)
- [x] T002 Completer and `jharness-tab` widget; `TuiTest.slashTabPrintsTheHelpAndCompletesCommands` (AC-01, AC-02, AC-04)
- [x] T003 `scripts/tui-pty-check.py` types `/` Tab (AC-05)

## Group 2 — Docs

- [x] T004 `/help` line, README, D-062, roadmap (AC-06)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T002 |
| AC-02 | T002 |
| AC-03 | T001 |
| AC-04 | T002 |
| AC-05 | T003 |
| AC-06 | T004 |
