# Tasks 018 — Security test suites

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-22

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Harness

- [x] T001 `SecurityCase`, `SecuritySuite` runner, outcomes (pass, fail, skip, exception) (AC-01, AC-08)
- [x] T002 `World`: fake home with canaries, git workspace, folder outside, attacker and docs servers, session driver, shared checks (AC-02, AC-03)

## Group 2 — Suites

- [x] T003 [P] Injection suite INJ-01 to INJ-10 (AC-04)
- [x] T004 [P] Malicious-repository suite REPO-01 to REPO-10 (AC-05)
- [x] T005 [P] Sandbox-escape suite ESC-01 to ESC-06 (AC-06)
- [x] T006 [P] Runaway suite RUN-01 to RUN-09 (AC-07)

## Group 3 — Findings

- [x] T007 RUN-02: name the CPU limit when it trips without a message; regression test in `AgentIsolateTest` (AC-09)
- [x] T008 RUN-08 on macOS: record the orphaned-grandchild exception, and check that the orphan stays sandboxed (AC-09)

## Group 4 — Runs and docs

- [x] T009 `SecuritySuiteTest` and the smoke binary's security checks and `--security` mode (AC-08)
- [x] T010 `docs/security-suites.md`, README, decision D-056, roadmap (AC-10)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T002 |
| AC-03 | T002 |
| AC-04 | T003 |
| AC-05 | T004 |
| AC-06 | T005 |
| AC-07 | T006 |
| AC-08 | T001, T009 |
| AC-09 | T007, T008 |
| AC-10 | T010 |
