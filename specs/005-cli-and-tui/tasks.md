# Tasks 005 — CLI and TUI

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-18

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Secrets and model switching

- [ ] T001 Write `Secrets` with the file checks and use it in the providers; test redaction in events and the log (AC-06)
- [ ] T002 [P] Add provider swapping to `AgentLoop` and `Session.switchModel` with `session.model_changed` (AC-05)

## Group 2 — CLI

- [ ] T003 Create `jharness-cli` with picocli `Main`: options, exit codes, headless `-p`, hidden `--replay` and `--home` (AC-01, AC-02)
- [ ] T004 Write `Tui` and `TerminalPrompter` with JLine; tests on a dumb terminal over streams (AC-03, AC-04, AC-05)

## Group 3 — Native

- [ ] T005 Add the native profile for `jharness` and the CI steps (AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T003 |
| AC-02 | T003 |
| AC-03 | T004 |
| AC-04 | T004 |
| AC-05 | T002, T004 |
| AC-06 | T001 |
| AC-07 | T005 |
