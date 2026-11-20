# Tasks 014 — Shift+Enter and a higher step limit

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-20

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Prompt keys and step limit

- [x] T001 [P] `modifyOtherKeys` on and off around the `> ` prompt; Shift, Alt and Ctrl+Enter bindings in both forms; TUI test (AC-01, AC-02)
- [x] T002 [P] `agent.maxSteps` default 100, maximum 500; settings test (AC-04)
- [x] T003 `/help` and README: Shift+Enter and the terminal mappings (AC-03)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T001 |
| AC-03 | T003 |
| AC-04 | T002 |
