# Spec 034 — JS hooks

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

Hooks are JavaScript files that run around the agents' `api` calls and at the end of their turns: `preTool(call)` can block a call before the permission gate, `postTool(call)` observes the result, and `stop(turn)` can make an agent go on. They live in `~/.jharness/hooks/` and trusted projects' `.jharness/hooks/`, run in their own isolates, and only tighten. `examples/.jharness/hooks/guard.js` is the Phase 3 gate's hook example. Decision D-073; D-022 (JS only).

## Roadmap items

- JS hooks (pre-tool, post-tool, stop); no shell hooks

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.2 | As a team lead, I want hooks that block risky actions, so team guardrails apply automatically | `user-stories.md` (a JS pre-tool hook can block a call) |
| US-4.6 | A documented api with typings and examples, including example hooks | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | Hook files `NAME.js` load from `~/.jharness/hooks/` and, in trusted projects, `.jharness/hooks/`. Bad names, links, files over 64 KB, user files others can write, files that don't run, and files with none of the three functions are reported in `hooks.loaded` and the TUI, and are not used. |
| AC-02 | `preTool(call)` runs before the permission gate for every `api` call of every agent, with `agent`, `api`, `kind`, `summary` and `detail`. `{block: reason}` refuses the call with `blocked by hook NAME: reason` (`api.call` status `blocked`), and the gate never sees it. A hook that throws or hits a limit blocks the call. |
| AC-03 | `postTool(call)` runs after each call with `status` (`ok`, `error`, `denied`, `blocked`) and the `result` (at most 10,000 characters) or `error`; its return value is ignored and its errors are logged. |
| AC-04 | `stop(turn)` runs when an agent's turn would end done, with `agent`, `turn`, `steps`, `continued`, `reply` and that agent's `calls` this turn. `{continue: message}` adds the message as a user message and the loop goes on, at most 3 times a turn. |
| AC-05 | Each hook runs in its own isolate (`subagent` limits, a fresh context per call) with the caller's block clock paused. Its own `api` calls go through the gate, run no hooks, and their events carry `hook-NAME`. |
| AC-06 | Hooks apply to subagents too, in name order, user hooks before project hooks; the first block wins. `hook.ran` records each run. The TUI shows blocks, continues and errors; `/hooks` lists the hooks; the trust question lists the project's hooks. |
| AC-07 | `examples/.jharness/hooks/guard.js` blocks pushes to `main` or `master` and hand edits of lock files, and asks the main agent to run the tests when it changed files without doing so. A test runs it, and CI runs it in the native binary. |
| AC-08 | The README and D-073 document hooks; D-022's note uses `api.tool`. |

## Out of scope

- Hooks that change a call's arguments.
- Shell hooks (D-022).
- Hooks on events other than api calls and turn ends.

## Dependencies and assumptions

- D-022, the isolate rules (Article III), the permission gate (D-010), subagents (spec 026).

## Open questions

None. Recommended options accepted while the user was away.
