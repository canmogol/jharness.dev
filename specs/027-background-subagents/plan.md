# Plan 027 — Background subagents

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Config: `agent.subagentsRunning` (default 4, 1 to 16).
- Security: `Prompter.Answer.UNATTENDED`; `PermissionEngine` and `EgressProxy` deny it with their own reason. `Unattended`, a thread-local marker; a background subagent's gate sets it around each decision (decisions run on the isolate's thread), and the session wraps its prompter so marked decisions get `UNATTENDED` without asking.
- Sandbox: `Subagents` gets `waitAgent`, `agents` and `stopAgent`; `HostApi` exports them (`waitAgent` outside the run clock) and the shim, `api.d.ts` and the native metadata list them.
- Agent: `SubagentRunner` keeps a registry of runs. A background spawn starts a platform thread marked unattended, with the run's `CompletableFuture`. `stop` interrupts the run's loop and records `stopped`. `drainFinished()` gives the uncollected replies once; `AgentLoop` takes a notes supplier and puts them before the prompt, and `cancel()` stops a loop for good, even before its turn starts. `Session.close` stops every run and waits up to 5 s each. The TUI refuses `/undo` and `/rewind` while `Session.runningSubagents()` isn't empty. `AgentLoop.stepsNow()` for live step counts.
- TUI: a background run's started and completed lines go through `LineReader.printAbove`; its other step lines are skipped. `onEvent` is synchronized. `TerminalPrompter` is synchronized and answers `UNATTENDED` while no turn runs. `/agents`, `/agents NAME` (reads the session log, `LogFormat`), `/stop NAME|all`.
- CLI: `jharness log --agent` and `--source`.
- Decision D-065.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Background subagents act only through the same `api` and gate | Yes |
| III Untrusted | Own isolates with the `subagent` limits; at most 16 running; per-turn and step caps stay | Yes |
| IV No shell | No processes | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | The new methods take and return JSON strings | Yes |
| VII Safe by default | A background subagent can never get an approval: questions are denied | Yes |
| VIII Events | `subagent.started.background`; `subagent.completed` with `stopped` | Yes |
| IX Native first | Host methods in the reachability metadata; CI's native step runs a background subagent | Yes |
| X Tested | Spawn, wait, timeout, list, stop, running cap, unattended denial, notes, close, undo guard, TUI commands, log filters, native | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native background subagent check on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Two agents edit the same file | Each write is checkpointed; the gate and file writes are per call; documented |
| A background agent runs on after the user forgot it | `/agents` shows it; closing the session stops it |
| Questions popping up over the user's typing | Background agents never ask; idle questions are denied |
