# Tasks 025 — Custom agents

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-23

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Definitions

- [x] T001 `AgentDefinition.find`, `resolve`, `read`, `describe`; `CustomAgentTest.foundInTheUserAndTrustedProjectFolders`, `definitionsAreChecked`, `apiNamesMatchTheHost` (AC-01, AC-02, AC-03)

## Group 2 — Running as an agent

- [x] T002 `ToolsGate`, plan mode and step limit in `Session.open`; `session.started` fields (AC-06, AC-08)
- [x] T003 `AgentIsolate.useHelpers` and the helpers error (AC-07)
- [x] T004 `SystemPrompt` agent section (AC-05)
- [x] T005 `--agent` with the agent's model; `/agent` and the banner; `MainTest.agentOption`, `TuiTest.agentCommandAndBanner` (AC-04, AC-08)

## Group 3 — Example and docs

- [x] T006 `examples/agents/reviewer/`; `CustomAgentTest.theReviewerExampleRuns`, `planModeStepLimitAndBrokenHelpers`; CI native step (AC-09)
- [x] T007 README, D-063, roadmap (AC-10)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T001 |
| AC-03 | T001 |
| AC-04 | T005 |
| AC-05 | T004, T006 |
| AC-06 | T002, T006 |
| AC-07 | T003, T006 |
| AC-08 | T002, T005 |
| AC-09 | T006 |
| AC-10 | T007 |
