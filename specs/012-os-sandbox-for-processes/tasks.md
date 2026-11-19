# Tasks 012 — OS sandbox for child processes

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-19

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Sandbox

- [x] T001 `ChildSandbox` with the policy, Seatbelt profile and bubblewrap arguments; unit tests of the generated profile and arguments (AC-01, AC-02, AC-03, AC-04)
- [x] T002 `ProcessRunner` wraps every argv and refuses to run when unavailable; probe; tests that writes, hidden reads and network fail and timeouts still kill the tree (AC-01–AC-06)

## Group 2 — Wiring and CI

- [x] T003 `Session`: private temp folder, probe, `sandbox.os`, git and search through the sandbox, Java search when unavailable; update tests (AC-05, AC-07)
- [ ] T004 CI installs bubblewrap on Linux; native smoke checks a sandboxed git run and a denied write (AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T002 |
| AC-02 | T001, T002 |
| AC-03 | T001, T002 |
| AC-04 | T001, T002 |
| AC-05 | T002, T003 |
| AC-06 | T002 |
| AC-07 | T003, T004 |
