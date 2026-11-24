# Spec 045 — Tools table

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

`jharness tools` printed rows like `gradle  user  gradle (not found on PATH)  build, test, …` that didn't line up and had no headers. It now prints a table. Refines D-066.

## Roadmap items

None (an output fix to spec 028's command).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.6 | As an extension author, I want a documented `api` with typings and examples, so I can build extensions quickly. | `user-stories.md` (tool definitions are an extension point) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `jharness tools` (and `tools list`) prints a header `NAME SOURCE EXECUTABLE STATUS OPERATIONS`, then one row per tool; each column is as wide as its longest value, two spaces apart, the last not padded. |
| AC-02 | `STATUS` is `ok`, `not found on PATH`, or `broken` for a file that can't be loaded; operations are comma-separated in the last column. |
| AC-03 | After the table, files that differ from the shipped default are named, and each broken file's error is given. |
| AC-04 | Every value is escaped with `TerminalSafe`, and widths count the escaped text. |
| AC-05 | A test checks the header and alignment; the README and D-066 describe the table. |

## Out of scope

- `show`, `check` and `reset` output.

## Dependencies and assumptions

- Spec 028 (`jharness tools`), spec 022 (`TerminalSafe`).

## Open questions

None.
