# Tasks 054 — Subagents that don't block

Spec: `spec.md` · Plan: `plan.md`

- [x] T001 Remove `waitAgent` from the host api, metadata, types, examples and custom agents' tools; shim stub; `ApiContractTest`, `ExamplesTest` (AC-01)
- [x] T002 `agent.autoTurns`; `SubagentRunner` finish signal, `deliveryPending`, `awaitFinished`; `AgentLoop.autoTurn`, `Session.deliveryTurn`; `BackgroundSubagentTest` (AC-02, AC-03)
- [x] T003 TUI automatic turns, the prompt wake, the cap and Esc; `TuiTest` (AC-02, AC-03)
- [x] T004 `/answer`, `SubagentRunner.answer` and `held`, the notice and completion; tests (AC-04)
- [x] T005 Headless delivery; `MainTest` (AC-05)
- [x] T006 CI's native step (AC-06)
- [x] T007 README, D-087, D-065 and D-072 notes, Q96, roadmap (AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T002, T003 |
| AC-03 | T002, T003 |
| AC-04 | T004 |
| AC-05 | T005 |
| AC-06 | T006 |
| AC-07 | T007 |
