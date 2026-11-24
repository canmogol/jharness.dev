# Spec 046 — Sorted help

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

`/help` listed the commands in the order they were added. It now lists them alphabetically, each with its forms, then a `Keys:` section. Tab completion follows the same order, since it reads the help. Refines D-062.

## Roadmap items

None (a usability fix to spec 024's help).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.3 | As a developer, I want slash commands for repeated tasks, so common prompts are one command. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `/help` (and `/` + Tab) lists the built-in commands from `/agent` to `/undo` in alphabetical order. A command's other forms (`/model NAME`, `/events LEVEL`, the `/network` forms, `/agents NAME`) stay right under it. |
| AC-02 | `@PATH`, the newline keys and Esc follow under `Keys:`. |
| AC-03 | Tab completion lists the commands in the same order; a test checks that the command list is sorted and that the keys come last. |
| AC-04 | D-062 notes it. |

## Out of scope

- The user's own commands, which `/help` lists after the built-ins as before (already sorted by name).

## Dependencies and assumptions

- Spec 024: the command list is parsed from the help text.

## Open questions

None.
