# Tasks 022 — Escape control characters in live output

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-23

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Escaping

- [x] T001 `TerminalSafe.text`, `SafeWriter`, `stream`; `TerminalSafeTest` (AC-01, AC-04, AC-05)
- [x] T002 `Styler` escapes its input; `Tui` and `TerminalPrompter` print through `out`, styled text through `raw` (AC-01, AC-02, AC-03)
- [x] T003 `Main.run` wraps stdout and stderr (AC-04)
- [x] T004 `TuiEscapeTest` and `MainTest.headlessOutputIsEscaped` (AC-01 to AC-05)

## Group 2 — Docs

- [x] T005 README, D-060, roadmap (AC-06)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T002, T004 |
| AC-02 | T002, T004 |
| AC-03 | T002, T004 |
| AC-04 | T001, T003, T004 |
| AC-05 | T001, T004 |
| AC-06 | T005 |
