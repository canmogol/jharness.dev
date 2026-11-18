# Tasks 003 — Agent loop with RunJS

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-18

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Contract and prompt

- [x] T001 Write `api.d.ts` and `ApiContractTest` (AC-05)
- [x] T002 [P] Write `system-prompt.md`, `examples.md` and `SystemPrompt` (AC-06)

## Group 2 — Replay

- [x] T003 Write `ReplayProvider` and `RecordingProvider` with tests (AC-07)

## Group 3 — Loop

- [x] T004 Create `jharness-agent`; write `JsBlocks` with tests (AC-01)
- [x] T005 Add the `agent` settings section and the new event types (AC-03, AC-04)
- [x] T006 Write `AgentLoop` and its tests: block, no block, errors, limits, max steps, model errors, result cap (AC-01, AC-02, AC-03, AC-04)
- [x] T007 Write `Session` (AC-01)

## Group 4 — Gate scenario

- [x] T008 Write the seeded-bug sample repository and the replay gate test (AC-08)
- [x] T009 [P] Write the live loop tests (AC-09)
- [x] T010 Add a replay loop run to the smoke binary (AC-01, AC-08)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T004, T006, T007, T010 |
| AC-02 | T006 |
| AC-03 | T005, T006 |
| AC-04 | T005, T006 |
| AC-05 | T001 |
| AC-06 | T002 |
| AC-07 | T003 |
| AC-08 | T008, T010 |
| AC-09 | T009 |
