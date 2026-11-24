# Spec 035 — Slash commands

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

Users and trusted projects add slash commands as files: `NAME.md` is a prompt template with `$ARGUMENTS`, and `NAME.js` defines `run(args)`, which builds a prompt or prints text. They run in the TUI and in `-p`. Decision D-074.

## Roadmap items

- Slash commands (Markdown templates and JS)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.3 | As a developer, I want slash commands for repeated tasks, so common prompts are one command | `user-stories.md` (Markdown templates and JS commands in `.jharness/commands/`) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `NAME.md` and `NAME.js` load from `~/.jharness/commands/` and, in a trusted project, `.jharness/commands/`; a project command replaces the user's. Bad names, built-in names, links, files over 64 KB, user files others can write, two files for one name, and JS files that don't run or define no `run` are reported and not used. |
| AC-02 | `/NAME args` on a Markdown command sends its text, with `$ARGUMENTS` replaced and front matter removed, as the user's message. The front matter's `description`, or else the first line, describes it. |
| AC-03 | `/NAME args` on a JS command runs `run(args)` in its own isolate. A string or `{prompt}` is sent as the message, `{print}` is shown, and nothing does nothing. An error is shown. Its `api` calls go through the gate and the hooks. |
| AC-04 | The prompt goes to the main agent, or to the subagent after `/switch`. `jharness -p "/NAME args"` expands the same way, and a failing command exits 1. |
| AC-05 | `/commands` lists the commands and the broken files, `/help` adds them, and Tab completes them. `commands.loaded` and `command.ran` record them. |
| AC-06 | `examples/.jharness/commands/review.md` and `changed.js` work, which a test checks. The README and D-074 document commands. |

## Out of scope

- Commands with typed arguments or options.
- Shell commands.

## Dependencies and assumptions

- Hooks (spec 034) for the isolate pattern; the TUI (specs 009, 024).

## Open questions

None. Recommended options accepted while the user was away.
