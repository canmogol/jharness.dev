# Tasks 007 — Checkpoints, undo and interrupt

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-18

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Checkpoints and diffs

- [x] T001 Write `Checkpoints` with save, restore, conflicts, git notes and diff, and its tests (AC-01, AC-02, AC-03, AC-04)
- [x] T002 Call it from `HostApi`; add diffs to operations and `file.changed` (AC-01, AC-05)

## Group 2 — Loop

- [x] T003 Add `undo`, `rewind` and conversation cuts to `AgentLoop` (AC-02)
- [ ] T004 Add `interrupt` to `AgentLoop` and `cancel` to `AgentIsolate`; tests for a running block and a hanging model stream (AC-06)

## Group 3 — TUI

- [ ] T005 Add `/undo`, `/rewind`, `/diff`, diff display and the Esc watcher; extend the pty check (AC-02, AC-05, AC-06, AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T002 |
| AC-02 | T001, T003, T005 |
| AC-03 | T001 |
| AC-04 | T001 |
| AC-05 | T002, T005 |
| AC-06 | T004, T005 |
| AC-07 | T005 |
