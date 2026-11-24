# Spec 041 — Command aliases and argument completion

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

`/quit` and `/q` leave like `/exit`. After a command and a space, Tab lists that command's choices and Tab again cycles through them. Command files can declare their own choices. Decision D-079.

## Roadmap items

- `/quit` and `/q` aliases; Tab completes command arguments (added at the user's request)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.3 | As a developer, I want slash commands for repeated tasks, so common prompts are one command. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `/quit` and `/q` do what `/exit` does. `/help` and Tab completion list `/exit` alone, described as "quit (also /quit, /q, or Ctrl-D)". |
| AC-02 | A user or project command file named like a built-in command or alias is not used. `command.shadowed` (name, file, from) records it, `commands.loaded` lists it under `shadowed`, and the TUI prints "Command FILE is not used: /NAME is a built-in command" at the start. |
| AC-03 | After a command and a space, Tab lists the next argument's choices, and Tab again cycles through them with one highlighted (JLine menu completion). |
| AC-04 | The choices: `/events` none, steps, all; `/model` the profile names; `/agents` and `/follow` the subagents; `/stop` the subagents and `all`; `/switch` `main` and the subagents; `/rewind` the turns, then `--force`; `/network` allow, deny, remove, reload, the rules' targets for `remove`, then `--project`. `/thinking` takes no argument. |
| AC-05 | A template's front matter `args: a, b` (or `[a, b]`) or a script's `const args = [...]` gives the choices for a user command's first argument: up to 50, each one word of up to 60 characters; anything else makes the file an error. |
| AC-06 | `/` + Tab, `@path` completion and command-name completion work as before. |
| AC-07 | The README and D-079 document it. |

## Out of scope

- More aliases, or user-defined aliases.
- Completing free-form arguments such as a host for `/network allow`.

## Dependencies and assumptions

- Slash commands (spec 035), Tab completion (spec 024), `@path` completion (spec 040).

## Open questions

None.
