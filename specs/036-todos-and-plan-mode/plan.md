# Plan 036 — Todo list and plan mode

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Sandbox: `Planning` interface; `ApiServices.planning`; `HostApi.todos` and `proposePlan` as `Operation.Kind.SESSION`, which never asks. Shim, `api.d.ts`, metadata; `ApiContractTest` counts 19 methods.
- Security: `PermissionEngine.mode(PermissionMode)` switches the mode during a session.
- Agent:
  - `PlanState` keeps the lists per agent and validates them.
  - `toggle()` switches the mode, `note()` gives the prefix, and `proposePlan` asks the user through the prompter.
  - `Session` builds it, passes it to the main and subagent services, and puts the note before the drained subagent replies.
- CLI:
  - `/plan`, `/todos` and the `[plan] ` prompt.
  - `todo.updated`, `plan.proposed` and `plan.mode` lines.
  - The prompter's own question for `proposePlan`.
- Testkit: `World` reads only result messages, so the plan-mode note isn't mistaken for a result.
- Tests: `PlanTest`, `TuiTest.planModeAndTodos`. D-075, Q80, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | Yes |
| II Host owns side effects | Todos and plans are host state; changes still go through the gate | Yes |
| III Untrusted | Unchanged | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Lists and plans cross as JSON strings | Yes |
| VII Safe by default | Only the user can leave plan mode; a plan agent stays locked | Yes |
| VIII Events | `todo.updated`, `plan.mode`, `plan.proposed`, `plan.decided` | Yes |
| IX Native first | Host methods in the reachability metadata | Yes |
| X Tested | Lists, validation, per-agent lists, plan mode refusals, approve, reject, locks, TUI | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| The model never proposes a plan | The note in each message says how; `/plan` turns plan mode off by hand |
