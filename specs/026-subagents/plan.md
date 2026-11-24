# Plan 026 — Subagents

Spec: `spec.md` · Status: Done · Created: 2026-11-23

## Approach

- Sandbox: `Subagents` interface; `ApiServices.subagents` (null = not available); `HostApi.spawnAgent` checks the request, passes the gate as `Operation.Kind.AGENT` (not state-changing) and runs the spawn outside the run clock; the JS shim and `api.d.ts` get `spawnAgent`; the native metadata lists it. `api.models()` lists the model profiles through `Subagents.models()`; `AgentSettings` gets `subagentsPerTurn` and `subagentMaxSteps`.
- Agent: `SubagentRunner` implements `Subagents`. Per spawn it builds a `TurnCounter.following` the parent's turn, the gate (the session's, plus `ToolsGate` and a plan gate for the custom agent), `ApiServices` without subagents, a provider (the agent's profile, else a new client for the session's profile, else the session's provider), an isolate from `IsolateManager` with the `subagent` profile, and an `AgentLoop`. It publishes `subagent.started` and `subagent.completed`.
- `AgentLoop.child` lets `interrupt` reach the running subagent.
- TUI: `[sub-N]` on step lines and the start and end lines. `LogFormat` fields for the new events.
- Decision D-064.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | The subagent acts only through the same `api` and gate | Yes |
| III Untrusted | Own isolate with the `subagent` limits; depth one; 10 per turn; steps capped by the session's limit | Yes |
| IV No shell | No processes | Yes |
| V Proxy | Unchanged; the subagent's model client goes through it | Yes |
| VI Strings | `spawnAgent` takes and returns JSON strings | Yes |
| VII Safe by default | Nothing loosens: same gate, plan mode and tools; a custom agent only narrows | Yes |
| VIII Events | `subagent.started`, `subagent.completed`; every subagent event names it | Yes |
| IX Native first | Host method in the reachability metadata; CI's native step spawns one | Yes |
| X Tested | Result, prompt, events, nesting, bad requests, narrowing by both agents, plan, the cap, undo, Esc, TUI, native | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native subagent check on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A block spawns subagents in a loop | 10 per turn, and each one's steps are capped |
| A subagent does what the user's main agent couldn't | It shares the gate, so plan mode, the main agent's tools and every approval still apply |
| A long subagent trips the parent's wall clock | The parent's clock stops during the spawn, as for builds |
