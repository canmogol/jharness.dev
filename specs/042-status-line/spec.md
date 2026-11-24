# Spec 042 — Status line

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

A status line under the input on real terminals shows the model, context use, permission mode, background subagents, cache reads, the turn and the workspace. `ui.statusLine` picks the items. `~/.jharness/statusline.js` can return the whole line from a JSON snapshot, in an isolate with no `api`. A new extension point, decision D-080.

## Roadmap items

- Status line: built-in items and `~/.jharness/statusline.js` (added at the user's request)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.8 | As a developer, I want long sessions to keep working when context fills up, so big tasks can finish. | `user-stories.md` |
| US-4.6 | As an extension author, I want a documented `api` with typings and examples, so I can build extensions quickly. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | On a real terminal a line under the input shows the items of `ui.statusLine` in order: `model`, `context` ("41% of 200K", `~` when estimated), `mode` (`[plan]` in plan mode), `agents` (running and held background subagents), `cache` (last request's reads), `turn`, `workspace`; items with nothing to say are left out. `true` (default) shows all, `false` none; unknown or repeated items stop start-up. Never on dumb terminals or in `-p`. |
| AC-02 | If `~/.jharness/statusline.js` exists, its `status(s)` returns the whole line. It runs in its own isolate with the subagent limits and no `api`, gets a snapshot of the same values plus the default `line`, and its result is cut at the first line break and 1,000 characters and escaped with `TerminalSafe`. The file must be a regular file of up to 64 KB, not writable by others. |
| AC-03 | If the script throws, hits a limit, returns something other than a string or the file can't be used, `statusline.failed` (reason, error) is recorded, the TUI prints one notice per distinct failure, and the default line shows instead. A run over 1 s (10 s for the first) turns the script off for the session. |
| AC-04 | The line refreshes after events that change its values and before each prompt, at most every 250 ms, never per streamed chunk. Values are cached on the host from events; a refresh takes no agent-loop lock and starts no process. The script's isolate events don't reach the session log. |
| AC-05 | Streamed output, `printAbove`, the Esc watcher's raw mode and resizes don't garble the line: it is drawn through a JLine widget while the prompt reads and under the TUI's lock otherwise; resizes between prompts resize the area; it is closed and the scroll region reset when the TUI ends. |
| AC-06 | `statusline.ready` and `statusline.failed` are events; `jharness log` formats them. |
| AC-07 | The README, the tech stack and D-080 document it. |

## Out of scope

- A project status line, more than one line, colors from the script.
- Shell commands or `api` calls from the status line.

## Dependencies and assumptions

- JLine's `Status` area (already in the binary), the isolate sandbox (D-003), context counting (D-077), cache counts (D-078).

## Open questions

None.
