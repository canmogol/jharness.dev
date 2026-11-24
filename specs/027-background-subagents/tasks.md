# Tasks 027 — Background subagents

Spec: `spec.md` · Plan: `plan.md`

## Group 1 — Core

- [x] T001 `agent.subagentsRunning`; `Prompter.Answer.UNATTENDED`, `Unattended`, engine and proxy handling (AC-01, AC-04)
- [x] T002 `SubagentRunner` registry, background spawn, wait, list, stop, notes, close; `AgentLoop` notes, undo guard, live steps (AC-01 to AC-06)
- [x] T003 `HostApi`, shim, `api.d.ts`, native metadata (AC-02, AC-03, AC-10)
- [x] T004 `BackgroundSubagentTest` (AC-01 to AC-07)

## Group 2 — UI, log and docs

- [x] T005 TUI: background lines, prompter while idle, `/agents`, `/agents NAME`, `/stop`; `TuiTest` cases (AC-04, AC-07, AC-08)
- [x] T006 `jharness log --agent` and `--source`; `LogCommandTest` cases (AC-09)
- [x] T007 CI native step runs a background subagent (AC-10)
- [x] T008 README, D-065, roadmap (AC-11)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T002, T004 |
| AC-02 | T002, T003, T004 |
| AC-03 | T002, T003, T004 |
| AC-04 | T001, T004, T005 |
| AC-05 | T002, T004 |
| AC-06 | T002, T004 |
| AC-07 | T002, T005 |
| AC-08 | T005 |
| AC-09 | T006 |
| AC-10 | T003, T007 |
| AC-11 | T008 |
