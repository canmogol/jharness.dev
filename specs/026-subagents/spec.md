# Spec 026 — Subagents

Status: Done · Phase: P3 · Created: 2026-11-23

## Summary

The second Phase 3 item. `api.spawnAgent({task, agent, model, maxSteps})` runs a subagent: a fresh agent loop with its own conversation, holding only the task, and its own isolate (the `subagent` sandbox profile). It returns the subagent's final reply. A subagent has the same `api` and permission gate as the session, narrowed by the custom agent it runs as. It can't start subagents itself. Decision D-064.

## Roadmap items

- Subagents via `api.spawnAgent`

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.4 | Delegate work to subagents so big tasks split into focused parts | `user-stories.md` (`api.spawnAgent` runs a subagent in its own isolate and returns its result) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.spawnAgent(request)` takes `task` (required, at most 20,000 characters), `agent` (a custom agent name), `model` (a model profile name) and `maxSteps` (1 to the session's step limit; default the agent's `maxSteps`, else `agent.subagentMaxSteps` (default 100, 1 to 500), at most the session's). A string is the task. Other fields, a missing task, an unknown agent or an unknown profile are refused. |
| AC-02 | The subagent runs in a new isolate with the `subagent` sandbox profile and a fresh conversation whose first message is the task. Its system prompt is the session's (workspace context, `JHARNESS.md`), the custom agent's section and a note that it is a subagent and must end with a complete reply. |
| AC-03 | The call returns `{agent, reply, stopReason, steps, error?}`; `reply` is cut to the last 30,000 characters. |
| AC-04 | The subagent's `api` calls go through the session's permission gate, so changes still ask, plan mode still applies and a main custom agent's `tools` still bind it. A custom agent named in `agent` narrows further with its `tools`, `plan`, `maxSteps` and `helpers.js`. The request's `model` wins over the custom agent's `model`, which wins over the session's profile. |
| AC-05 | Inside a subagent `api.spawnAgent` is refused, so the depth is one. A turn of the main agent can start at most `agent.subagentsPerTurn` subagents (default 10, 1 to 1000). Spawning itself doesn't ask (`kind: agent`). |
| AC-06 | The parent's block clock stops while the subagent runs. Esc stops the subagent and then the parent's turn. |
| AC-07 | The subagent's events carry its name (`sub-1`, `sub-2`, …) and the parent's turn number. Its file changes belong to the parent's turn, so `/diff` and `/undo` include them. `subagent.started` (`task`, `customAgent`, `model`, `maxSteps`) and `subagent.completed` (`stopReason`, `steps`, `durationMs`, `error`) frame its run. |
| AC-08 | The TUI shows `▸ [sub-1] started as reviewer: …`, the subagent's step lines with its name and `▸ [sub-1] done after N steps`. `jharness log` shows the name on each line. |
| AC-09 | `api.d.ts` documents `spawnAgent`; the native binary runs a subagent in CI. |
| AC-10 | The README and D-064 document subagents. |
| AC-11 | `api.models()` returns `{current, models: [{name, provider, model, notes?}]}` for the profiles in settings, without base URLs or key variable names. It is refused inside a subagent, and a custom agent's `tools` may list it. |

## Out of scope

- Subagents running in parallel, or in the background.
- Nested subagents.
- Sharing the parent's conversation with the subagent.

## Dependencies and assumptions

- Custom agents (spec 025); the `subagent` sandbox profile (D-003).

## Open questions

None. Recommended options accepted while the user was away.
