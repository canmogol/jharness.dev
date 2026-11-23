# Tasks 021 — Debug mode, `jharness log` and crash reports

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-23

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Events

- [x] T001 `model.response` and `session.crashed` event types; `HttpProvider` publishes `model.response` with allow-listed headers; tests (AC-02, AC-09)

## Group 2 — `jharness log`

- [x] T002 [P] `TerminalSafe.escape`; tests with ESC, C1, DEL and bidi characters (AC-05)
- [x] T003 [P] `SessionLogs`: list a project's or every project's logs, resolve an id, prefix, `last` or path, check the id; tests (AC-03, AC-04, AC-06)
- [x] T004 `LogCommand` and `LogFormat`: list, timeline, `--type`, `--full`, `--json`, bad lines; `LogCommandTest` (AC-03 to AC-06)

## Group 3 — Debug and crashes

- [x] T005 `--debug`: TUI event level and thinking, `-p` events on stderr, stack traces; tests (AC-01)
- [x] T006 `CrashReport`: contents, recent events read from the session log, `~`, modes, pruning; `CrashReportTest` (AC-08)
- [x] T007 `Main`: exit 3, `session.crashed`, uncaught handler for other threads; tests (AC-07, AC-09)

## Group 4 — Docs and native

- [x] T008 README, help text, D-059, roadmap; CI's native `jharness` check runs `log` (AC-10)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T005 |
| AC-02 | T001 |
| AC-03 | T003, T004 |
| AC-04 | T003, T004 |
| AC-05 | T002, T004 |
| AC-06 | T003, T004 |
| AC-07 | T007 |
| AC-08 | T006 |
| AC-09 | T001, T007 |
| AC-10 | T008 |
