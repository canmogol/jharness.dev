# Spec 009 — TUI usability

Status: Done · Phase: P1 · Created: 2026-11-19

## Summary

Four fixes to the JLine TUI from the user's first hands-on runs: multi-line input, colored Markdown and status lines, a way to see events, and the error message of a failed JavaScript step. It finishes the Phase 1 "JLine TUI" item before the P1 gate is run again; it ticks no new roadmap item.

## Roadmap items

None (fixes to the Phase 1 "JLine TUI" item).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | Every action is an event in the session log | `user-stories.md` (seeing the events while working) |
| US-2.1 | Describe a bug and have the agent find and fix it | `user-stories.md` (writing a longer task, following the steps) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | At the `> ` prompt, Alt+Enter and Ctrl-J insert a newline and Enter sends the whole text as one message. Pasted multi-line text is one message (bracketed paste). |
| AC-02 | Streamed replies are styled line by line: headings, `**bold**`, `` `inline code` ``, fenced code blocks and list bullets. Step lines, errors and diff `+` / `-` lines are colored. With `NO_COLOR` set or a dumb terminal, output is plain text with the same words. |
| AC-03 | `loop.result` carries `error` (cut at 2,000 characters) and `line` when the step's status is `error` or `limit`. The TUI prints the error and line under the step line. |
| AC-04 | The start line shows the session log path. Setting `ui.events` (`none`, `steps`, `all`; default `steps`) picks what the TUI shows: `none` hides step lines, `steps` is today's view, `all` also prints every event but `model.delta` as one line (`type` and compact body). Bad values stop start-up with a message naming the key. |
| AC-05 | `/events` shows the current level and the log path; `/events LEVEL` switches it for the rest of the session. `/help` lists it. |

## Out of scope

- Full Markdown rendering (tables, nested lists, links).
- `jharness log <session>` and `--debug` (Phase 2 roadmap items).
- A separate events file: the session log already is one and can be tailed.

## Dependencies and assumptions

- Specs 005 (TUI), 006 (layered settings) and 008 (loop result path).
- `ui` is a display setting; like other keys, it is ignored in an untrusted project's settings (D-043).

## Open questions

None.
