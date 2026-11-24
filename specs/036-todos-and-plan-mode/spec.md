# Spec 036 — Todo list and plan mode

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

Agents keep a todo list with `api.todos`, which the TUI shows. `/plan` toggles plan mode (the read-only permission mode), and the main agent's `api.proposePlan` shows its plan and asks the user to leave plan mode and carry it out. Decision D-075.

## Roadmap items

- Todo list and plan mode

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.2 | Ask questions about an unfamiliar codebase in read-only mode | `user-stories.md` (plan mode) |
| — | See the agent's steps and approve a plan before anything changes | Roadmap |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.todos(list)` sets the calling agent's list: at most 50 items, each with a text of 1 to 200 characters and a status of `pending`, `in_progress` or `done`; a string item is pending. Bad lists are refused. `api.todos()` reads it. It never asks, and each agent has its own list. |
| AC-02 | `todo.updated` records each change. The TUI shows the list with `✓`, `▸` and `☐` and a done count. `/todos` prints the current agent's list. |
| AC-03 | `/plan` turns plan mode on, or off back to the earlier mode. While it is on, changes are refused without asking, the prompt starts with `[plan] `, and each message to the agent starts with a note about plan mode. |
| AC-04 | `api.proposePlan(text)` from the main agent in plan mode shows the plan and asks `Leave plan mode and carry out this plan?`. Yes turns plan mode off and returns `{"approved": true}`; no returns `false` and keeps it on. It is refused outside plan mode, for subagents, and for a custom agent with `plan: true`, which `/plan` can't unlock. |
| AC-05 | `plan.mode`, `plan.proposed` and `plan.decided` record plan mode. `api.d.ts`, the shim and the native metadata include `todos` and `proposePlan`. |
| AC-06 | The README and D-075 document it. |

## Out of scope

- Editing the plan in the approval question.
- A shared todo list across agents.

## Dependencies and assumptions

- The permission modes (D-041), subagents (spec 026), `/switch` (spec 032).

## Open questions

None. Recommended options accepted while the user was away.
