# Tasks 023 — Build parameters

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-23

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Parameters

- [x] T001 Maven `install`, `skipTests`, `profiles`, `threads`; `BuildOperationsTest.mavenParameters` (AC-01, AC-04)
- [x] T002 Gradle `skipTests`, `parallel`, `continue`; npm `omitDev`; `BuildOperationsTest.gradleAndNpmParameters` (AC-02, AC-03, AC-04)
- [x] T003 `BuildOperations.summary` in the approval; `approvalSummaryShowsTheParameters` (AC-05)
- [x] T004 `api.d.ts` (AC-06)
- [x] T005 `BuildToolsTest.mavenInstallWithParameters` (AC-07)

## Group 2 — Docs

- [x] T006 README, D-061, roadmap (AC-08)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T002 |
| AC-03 | T002 |
| AC-04 | T001, T002 |
| AC-05 | T003 |
| AC-06 | T004 |
| AC-07 | T005 |
| AC-08 | T006 |
