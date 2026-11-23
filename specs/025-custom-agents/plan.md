# Plan 025 — Custom agents

Spec: `spec.md` · Status: Done · Created: 2026-11-23

## Approach

- `AgentDefinition` (jharness-agent): `find` lists user and project folders (project wins, untrusted project agents flagged), `resolve` picks one, `read` parses and checks `agent.json` and reads `prompt.md` and `helpers.js` without following links, `describe` makes the `/agent` lines.
- `ToolsGate` wraps the session's permission gate and denies methods outside `tools`. Every `api` method goes through the gate, so this covers all of them.
- `Session.Access.agent(def)`: `Session.open` sets plan mode, the step limit, the gate, the helpers and the system prompt from it, and records it in `session.started`.
- `AgentIsolate.useHelpers` evaluates `helpers.js` as its own source in each fresh context before the block.
- `SystemPrompt.build(..., agent)` appends the agent section.
- `Main --agent`: resolves the agent once trust is known and puts its `model` in the session layer unless `--model` is given. `Tui`: `/agent` and the banner.
- `examples/agents/reviewer/`; CI's native `jharness` step runs it. Decision D-063.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency; agents are files read at start | Yes |
| II Host owns side effects | Helpers reach the world only through `api` | Yes |
| III Untrusted | Helpers run in the same isolate, context and limits as agent code; no limit can be raised | Yes |
| IV No shell | No processes | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | No `api` change | Yes |
| VII Safe by default | An agent only narrows: fewer methods, plan mode, fewer steps. Untrusted projects' agents don't load | Yes |
| VIII Events | `session.started` records the agent; denials are `permission.decided` events | Yes |
| IX Native first | Plain files and JSON; the native CI step runs the example | Yes |
| X Tested | Discovery, trust, validation, links, size, tools, plan, steps, helpers errors, example session, CLI, TUI, native | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `--agent` check on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A repository ships an agent that steers the model or picks a hosted model | Project agents load only in trusted projects |
| helpers.js does something hostile | It is agent code like any block: same isolate, limits and `api`, and everything it calls still asks |
| The tools list drifts from the host's methods | A test compares it with `HostApi`'s exported methods |
