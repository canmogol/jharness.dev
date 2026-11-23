# Tasks 019 — Build retry on proxy refusal

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-22

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Retry

- [x] T001 `ProcessRunner.proxyRefused` and its tests for each tool's line and the near misses (AC-01, AC-02)
- [x] T002 `ProcessRunner.retryingProxyRefusals`: 3 runs, one deadline, 1 s minimum, `process.retried` event type (AC-01, AC-02, AC-03, AC-04)
- [x] T003 [P] `BuildOperations.run` uses it; stand-in npm refused twice in the real sandbox, and a failing build that runs once. The npm proxy probes in `BuildToolsTest` and the smoke binary fail the way npm does (`connect EPERM 127.0.0.1:<port>`, non-zero exit), so they get the retry too (AC-01, AC-02, AC-04)
- [x] T004 [P] `GitOperations.runNetwork` uses it instead of its own loop (AC-05)

## Group 2 — Docs

- [x] T005 README, decision D-057, roadmap (AC-06)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T002, T003 |
| AC-02 | T001, T002, T003 |
| AC-03 | T002 |
| AC-04 | T002, T003 |
| AC-05 | T004 |
| AC-06 | T005 |
