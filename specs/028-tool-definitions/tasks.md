# Tasks 028 — Tool definitions

Spec: `spec.md` · Plan: `plan.md`

## Group 1 — Definitions

- [x] T001 `ToolDefinition` format and checks; `ToolDefinitionTest` (AC-01, AC-02, AC-03)
- [x] T002 `ToolDefinitions`: seed, load, project merge, hashes, describe; tests (AC-05, AC-07)
- [x] T003 `BuildOperations` from definitions, `Executables`, `ProcessRunner.checkEnv`, embedded defaults; `BuildOperationsTest` and the build tests against the shipped files (AC-02, AC-04)

## Group 2 — API, session and CLI

- [x] T004 `api.tool`, `api.tools`; shim, `api.d.ts`, metadata; `ToolApiTest` (AC-09, AC-10)
- [x] T005 Session loading, `tools.loaded`, prompt section, agents' `tool:NAME` (AC-10)
- [x] T006 Trust question, changed-tools question, banner, headless, `TrustStore` hashes, `jharness tools`; `ToolsFlowTest` (AC-06, AC-08)

## Group 3 — Security, native and docs

- [x] T007 Security suite on `api.tool`; REPO-11 (AC-11)
- [x] T008 CI native step for the tools (AC-12)
- [x] T009 README, D-066 note, roadmap (AC-13)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T001, T003 |
| AC-03 | T001 |
| AC-04 | T003 |
| AC-05 | T002 |
| AC-06 | T006 |
| AC-07 | T002 |
| AC-08 | T006 |
| AC-09 | T004 |
| AC-10 | T004, T005 |
| AC-11 | T007 |
| AC-12 | T008 |
| AC-13 | T009 |
