# Tasks 002 — Host foundations

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-18

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

Order and grouping: the recommended module-by-module order, accepted on the user's instruction while away (2026-11-18).

## Group 1 — Build and events

- [x] T001 Create the root `pom.xml` (Java 25, dependency management, modules) and empty module poms; `mvn verify` passes (AC-01)
- [x] T002 Write `Event`, `EventTypes` and envelope stamping with size and guest-type checks (AC-02)
- [x] T003 Write `EventBus` with ordered delivery to subscribers (AC-02)
- [x] T004 Write `SessionLog`: path from D-038, 0700/0600, append and read back (AC-03)

## Group 2 — Settings and security

- [x] T005 Write the `Settings` loader with `sandbox` and `timeouts` validation and the API-key rule (AC-04)
- [x] T006 [P] Write `ModelProfiles` resolution by name and default (AC-05)
- [x] T007 [P] Write `PermissionGate`, `Operation`, `Decision` and `DefaultGate` (AC-06)
- [x] T008 [P] Write `Workspace` confinement with tests for absolute, `..` and symlink escapes (AC-07)

## Group 3 — Processes, git and search

- [x] T009 Write `Executables` and `ProcessRunner`: argv only, clean environment, timeout with tree kill, output cap, `process.*` events (AC-10)
- [x] T010 Write `GitOperations` for the D-039 operations with the hardening prefix and argument checks (AC-11)
- [x] T011 [P] Write `RipgrepSearch` and `JavaSearch` behind `Search`, same result format, flag checks (AC-12)

## Group 4 — Isolates and api

- [x] T012 Write `IsolateManager` and `AgentIsolate`: engine per agent, fresh context, limits from settings, isolate mode check, `sandbox.*` events (AC-08)
- [x] T013 Write the `Watchdog` with D-037 escalation, pausing during permission decisions (AC-08)
- [x] T014 Write the `Api` facade: all eight methods, gate, confinement, events, JS error line numbers (AC-06, AC-07, AC-09)

## Group 5 — Model providers

- [x] T015 Write `HttpClients`, `Sse` and `ModelProvider` (AC-01, AC-13)
- [x] T016 [P] Write `OpenAiCompatibleProvider` and its stub-server tests (AC-13)
- [x] T017 [P] Write `AnthropicProvider` and its stub-server tests (AC-13)
- [x] T018 Add live tests that run only when `OLLAMA_URL` or `ANTHROPIC_API_KEY` is set (AC-13)

## Group 6 — Architecture and native

- [x] T019 Write the architecture test for `ProcessBuilder`, `Runtime.exec`, `HttpClient` and `Socket` use (AC-01)
- [x] T020 Write the `Smoke` main and the `native` profile in `jharness-testkit` (AC-14)
- [x] T021 Add the CI workflow: JVM tests, `rg` installed, native smoke on all three targets (AC-01, AC-12, AC-14)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T015, T019, T021 |
| AC-02 | T002, T003 |
| AC-03 | T004 |
| AC-04 | T005 |
| AC-05 | T006 |
| AC-06 | T007, T014 |
| AC-07 | T008, T014 |
| AC-08 | T012, T013 |
| AC-09 | T014 |
| AC-10 | T009 |
| AC-11 | T010 |
| AC-12 | T011, T021 |
| AC-13 | T015, T016, T017, T018 |
| AC-14 | T020, T021 |
