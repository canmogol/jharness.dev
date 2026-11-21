# Spec 014 — Shift+Enter and a higher step limit

Status: Done · Phase: P2 · Created: 2026-11-20

## Summary

Two fixes from the user's hands-on runs. Shift+Enter starts a new line at the prompt, as it does in most chat tools, in terminals that report it. Alt+Enter and Ctrl-J keep working everywhere. `agent.maxSteps` defaults to 100 steps per turn, up from 25, and may be set up to 500, up from 200. Decision D-051. Scope taken from the recommended options while the user was away (2026-11-20). It ticks no roadmap item.

## Roadmap items

None (fixes to the Phase 1 "JLine TUI" and agent loop items).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.1 | Describe a bug and have the agent find and fix it | `user-stories.md` (writing a longer task; longer tasks need more steps) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | On a terminal that isn't dumb, JHarness asks the terminal to report modified keys (xterm `modifyOtherKeys` level 1, `ESC[>4;1m`) while the `> ` prompt reads a line, and resets it (`ESC[>4m`) as soon as the line is read, so the shell, approval questions and Esc during a turn see ordinary keys. |
| AC-02 | At the `> ` prompt, Shift+Enter, Ctrl+Enter and Alt+Enter insert a newline whether the terminal sends them as `modifyOtherKeys` sequences (`ESC[27;<mod>;13~`) or CSI u sequences (`ESC[13;<mod>u`). Alt+Enter as `ESC` `CR` and Ctrl-J still insert a newline; Enter sends. |
| AC-03 | `/help` and the README name Shift+Enter, and the README says how to map Shift+Enter to Alt+Enter in terminals that don't report it (VS Code, kitty, iTerm2). |
| AC-04 | `agent.maxSteps` defaults to 100 and accepts 1 to 500; values outside fail with a message naming the range. |

## Out of scope

- The kitty keyboard protocol (`ESC[>1u`): it also changes how Esc and Ctrl-letter keys are sent, which would break Esc to stop a turn and Ctrl-C and Ctrl-D at the prompt.
- Changing terminal settings for the user (a `/terminal-setup` command).
- The web UI (a separate discussion).

## Dependencies and assumptions

- Spec 009 (multi-line input), spec 003 (`agent.maxSteps`).
- Terminals that don't know `ESC[>4;1m` ignore it; those that don't report Shift+Enter send a plain Enter, which still sends the message.

## Open questions

None.
