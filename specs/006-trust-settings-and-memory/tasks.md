# Tasks 006 — Project trust, layered settings and JHARNESS.md

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-18

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Settings layers

- [ ] T001 Write `Settings.layered` with the merge rules, the untrusted filter and project-file errors (AC-03, AC-04, AC-05)

## Group 2 — Trust and memory

- [ ] T002 [P] Write `TrustStore` with tests (AC-01)
- [ ] T003 [P] Add both `JHARNESS.md` files to the system prompt (AC-06)

## Group 3 — Wiring

- [ ] T004 Decide trust in the CLI (stored answer, TUI question, headless untrusted), open sessions with layered settings, emit the events; tests and the pty check (AC-01, AC-02, AC-04, AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T002, T004 |
| AC-02 | T004 |
| AC-03 | T001 |
| AC-04 | T001, T004 |
| AC-05 | T001 |
| AC-06 | T003 |
| AC-07 | T004 |
