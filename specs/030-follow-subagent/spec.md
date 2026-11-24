# Spec 030 — Follow a subagent

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

`/follow [NAME]` shows a running background subagent the way the main loop is shown: its reply streams, then its steps, results and diffs, until Esc or until it ends. Esc stops watching; the subagent keeps running. A subagent that shares the session's model client now publishes its model events under its own name, so its text can be followed. Decision D-069.

## Roadmap items

- Follow a subagent live (`/follow sub-N`)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Watch what a running agent does, like the main loop, until Esc | User request, 2026-11-24 |
| US-2.5 | Stop the agent at once | `user-stories.md` (`/stop` from the follow view's prompt) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `/follow NAME` on a running background subagent prints `Following NAME (task, N steps so far).`, then that subagent's streamed reply text, its thinking when `/thinking` is on, and its step, result, error and diff lines with `[NAME]`, as they happen. |
| AC-02 | `/follow` alone follows the only running background subagent; with none or several it says which are running. An unknown name, or one that has ended, gets a message pointing to `/agents NAME`. |
| AC-03 | Esc stops following and prints `Stopped following NAME; it keeps running …`; the subagent goes on. When the subagent ends, its end line prints, then `NAME has ended; …`, and the prompt returns. Dumb terminals have no Esc and follow until the end. |
| AC-04 | While one subagent is followed, other background subagents still show only their start and end lines. |
| AC-05 | A provider shared with a subagent publishes the subagent's `model.request`, `model.delta`, `model.thinking_delta`, `model.completed` and `model.error` under the subagent's name (`ModelProvider.forAgent`). |
| AC-06 | `/follow` is in `/help` and Tab completion. The native pseudo-terminal check follows a subagent, presses Esc, and stops it. |
| AC-07 | The README and D-069 document it. |

## Out of scope

- Typing to a subagent, switching to it, answering its questions.
- Following a foreground subagent (it is shown inside the turn) or several at once.

## Dependencies and assumptions

- Specs 026 and 027 (subagents, background subagents and their events).

## Open questions

None. Recommended options accepted while the user was away.
