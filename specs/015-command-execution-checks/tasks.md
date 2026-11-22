# Tasks 015 — Command-execution checks

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-21

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Process runner checks

- [x] T001 [P] `Executable` pin and `verify()`; `ProcessRunner` checks it and emits `process.refused` (AC-04)
- [x] T002 [P] `Executables.resolve` skips `o+w` files and folders (AC-05)
- [x] T003 [P] Environment allowlist and argument limits in `ProcessRunner` (AC-06, AC-07)

## Group 2 — Git hardening

- [x] T004 New hardening flags and `--ignore-submodules=all` (AC-02)
- [x] T005 Repo-defined filter drivers turned off; `GIT_WORK_TREE` (AC-01, AC-03)

## Group 3 — Suite and docs

- [x] T006 `CommandExecutionTest` covering every check, with a hostile repo config (AC-08)
- [x] T007 D-052 in `product/decisions.md`; `process.refused` registered in `EventTypes`; tech-stack git row; the native smoke binary checks a hostile repo config (AC-04, AC-08)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T005, T006 |
| AC-02 | T004, T006 |
| AC-03 | T005, T006 |
| AC-04 | T001, T006, T007 |
| AC-05 | T002, T006 |
| AC-06 | T003, T006 |
| AC-07 | T003, T006 |
| AC-08 | T006, T007 |
