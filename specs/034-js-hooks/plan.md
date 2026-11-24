# Plan 034 — JS hooks

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Sandbox:
  - `Hooks` interface (`preTool`, `postTool`); `ApiServices.hooks`, with a 10-argument constructor that passes null.
  - `HostApi.call` runs `preTool` before the gate and `postTool` after every outcome, with the run clock paused.
- Agent:
  - `HookRunner` loads, checks and probes the files, holds one isolate and one `ApiServices` (without hooks) per hook, runs each function with the call as JSON, and records calls per agent for `stop`. It publishes `hooks.loaded` and `hook.ran`.
  - `AgentLoop.StopHook` continues a done turn at most 3 times.
  - `Session` builds the runner, passes it to the main services and loop, and closes it.
  - `SubagentRunner` passes the hooks to subagents' services and loops.
- CLI: `hook.ran` lines, `/hooks`, the project's hooks in the trust question, broken hooks in the banner.
- Example `examples/.jharness/hooks/guard.js`; `HookTest`; a CI native step.
- D-073, Q78, the D-022 note, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | Hooks run in isolates inside the process | Yes |
| II Host owns side effects | Hooks act only through the `api` and its gate | Yes |
| III Untrusted | Each hook is an isolate with `SandboxPolicy.UNTRUSTED`, the subagent limits, one thread and a fresh context per run | Yes |
| IV No shell | JS only (D-022) | Yes |
| V Proxy | Hooks' network calls go through `api.http`/`fetch` and the proxy | Yes |
| VI Strings | Calls and results cross as JSON strings | Yes |
| VII Safe by default | Hooks only block; pre-tool failures block; project hooks only in trusted projects | Yes |
| VIII Events | `hooks.loaded`, `hook.ran`; hooks' own events carry `hook-NAME` | Yes |
| IX Native first | No new dependency; CI runs the example hook natively | Yes |
| X Tested | Block, fail closed, post-tool, stop and its cap, trust, broken files, subagents, the example | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks (with the hook) and the TUI pseudo-terminal check on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Hooks slow every call | One isolate per hook; only hooks that define a function run for it |
| A broken project hook blocks everything | It shows in `/hooks` and the banner; untrust the project or delete the file |
