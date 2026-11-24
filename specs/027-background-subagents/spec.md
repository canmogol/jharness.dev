# Spec 027 — Background subagents

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

Follows spec 026. The main agent can start a subagent in the background with `api.spawnAgent({task, background: true})`, keep working, and collect the result with `api.waitAgent`. Several can run at once. The user sees the session's subagents with `/agents`, sees what one is doing with `/agents NAME`, and stops one with `/stop NAME` or all of them with `/stop all`. `jharness log` filters by agent and by source. Decision D-065.

## Roadmap items

- Background subagents, `/agents` and `/stop` (asked for by the user in the review of spec 026)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.4 | Delegate work to subagents so big tasks split into focused parts | `user-stories.md` |
| US-2.5 | Stop the agent at once, so a wrong direction doesn't keep running | `user-stories.md` (here: one subagent, without stopping the rest) |
| — | See which agents run and what each one does, and tell their events apart | User request, 2026-11-24 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `spawnAgent` takes `background: true`: it returns `{agent, status: "running"}` at once and the subagent runs on its own thread. At most `agent.subagentsRunning` (default 4, 1 to 16) background subagents run at a time; another is refused. They count towards `agent.subagentsPerTurn`. |
| AC-02 | `api.waitAgent(name | {agent, timeoutMs})` waits for a background subagent and returns the same result as a foreground spawn, plus `status`. `timeoutMs` defaults to 600,000 and is at most 3,600,000; on timeout it returns `{agent, status: "running"}`. The block clock stops while it waits, and Esc ends the wait. |
| AC-03 | `api.agents()` returns this session's subagents: `{agent, task, customAgent, model, background, status, steps, durationMs}`, where `status` is `running`, `done`, `max_steps`, `error`, `interrupted` or `stopped`. `api.stopAgent(name)` stops a running background subagent. All three are refused inside a subagent. |
| AC-04 | A background subagent never asks the user: an operation that would ask is denied with a reason that says so (`Prompter.Answer.UNATTENDED`). Saved project rules, permission modes and session allows still apply. While no turn is running, the TUI answers any question (such as a network question from a background subagent's request) the same way. |
| AC-05 | When the main agent's next turn starts, the replies of background subagents that finished and weren't collected with `waitAgent` are added to the start of its prompt, once each. |
| AC-06 | Esc stops the turn and a foreground subagent; background subagents keep running. Closing the session stops every running subagent (`subagent.completed` with `stopReason: "stopped"`), so `-p` ends them when its turn ends. `/undo` and `/rewind` are refused while a background subagent runs. |
| AC-07 | `subagent.started` gets `background`. The TUI shows a background subagent's start and end lines, above the prompt when the user is typing, but not its steps. |
| AC-08 | `/agents` lists the session's subagents: name, status, steps, custom agent, model, time and task. `/agents NAME` prints that subagent's last 20 events in the `jharness log` format. `/stop NAME` and `/stop all` stop running subagents. All three are in `/help` and Tab completion. |
| AC-09 | `jharness log` takes `--agent NAME` (repeatable; `main` for the main agent) and `--source host|guest`. |
| AC-10 | `api.d.ts`, the shim and the native metadata include `waitAgent`, `agents` and `stopAgent`. The native binary runs a background subagent in CI. |
| AC-11 | The README and D-065 document background subagents and how to tell the agents' events apart. |

## Out of scope

- Background subagents asking the user questions.
- Typing commands while a turn runs (only Esc is read then).
- Nested subagents; background subagents that outlive the session.

## Dependencies and assumptions

- Spec 026 (subagents). Shared services are thread-safe: `Checkpoints`, `ProjectRules` and the event bus are synchronized, and each subagent has its own isolate and loop.

## Open questions

None. Recommended options accepted while the user was away.
