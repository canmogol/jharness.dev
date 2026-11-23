# Spec 024 — `/` and Tab list the commands

Status: Done · Phase: P2 · Created: 2026-11-23

## Summary

In the terminal UI, typing `/` and pressing Tab prints the same command list as `/help`, without losing what was typed. Tab after part of a command name completes it, and when several commands match, JLine lists them with their descriptions. Decision D-062.

## Roadmap items

- `/` and Tab list and complete the slash commands (new item, user request)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.3 | Slash commands for repeated tasks | `user-stories.md` (the built-in commands are found and completed from the prompt; custom commands come in Phase 3) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | With only `/` on the prompt line (spaces around it allowed), Tab prints the `/help` text above the prompt; the line keeps the `/`. |
| AC-02 | Tab after a partial first word starting with `/` completes it to the command (`/mo` → `/model `). When several match, they are listed with each command's description. |
| AC-03 | The commands and descriptions come from the `/help` text, so the list can't drift from it. |
| AC-04 | Tab anywhere else behaves as before (no completions). |
| AC-05 | The native binary does it too: the pseudo-terminal check in CI types `/` and Tab. |
| AC-06 | The help line for `/help`, the README and D-062 say so. |

## Out of scope

- Completing command arguments (`/model NAME`, `/network allow X`) and file paths.

## Dependencies and assumptions

- JLine 3 `Completer` and widgets, already used for the prompt (spec 005, 009, 014).

## Open questions

None. Recommended options accepted while the user was away.
