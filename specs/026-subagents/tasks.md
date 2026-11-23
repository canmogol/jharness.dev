# Tasks 026 — Subagents

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-23

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — The api

- [x] T001 `Subagents`, `ApiServices.subagents`, `HostApi.spawnAgent`, `Operation.Kind.AGENT`, shim, `api.d.ts`, native metadata; `ApiContractTest` (AC-01, AC-05, AC-09)

## Group 2 — Running a subagent

- [x] T002 `SubagentRunner`: request checks, prompt, gate, provider, isolate, loop, result, events (AC-01 to AC-05, AC-07)
- [x] T003 `TurnCounter.following`; `AgentLoop.child` for Esc (AC-06, AC-07)
- [x] T004 `SubagentTest` (AC-01 to AC-07)

## Group 3 — UI, native and docs

- [x] T005 TUI lines and `LogFormat` fields; `TuiTest.subagentLinesNameTheSubagent` (AC-08)
- [x] T006 CI native step spawns a subagent (AC-09)
- [x] T007 README, D-064, roadmap (AC-10)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T002, T004 |
| AC-02 | T002, T004 |
| AC-03 | T002, T004 |
| AC-04 | T002, T004 |
| AC-05 | T001, T002, T004 |
| AC-06 | T003, T004 |
| AC-07 | T002, T003, T004 |
| AC-08 | T005 |
| AC-09 | T001, T006 |
| AC-10 | T007 |
