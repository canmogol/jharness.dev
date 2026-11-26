# Spec 055 — A main prompt that never blocks

Status: Done · Phase: P3 · Created: 2026-11-26

## Summary

On a terminal with raw keys, the main prompt stays live while the main agent works: turns run in the background, the reply prints above the prompt line by line, and Esc on the prompt stops the turn. A message typed during a turn is queued and sent when the turn ends. The main agent's own questions become a notice, like a held subagent's, and `/answer main` asks one. Commands that would change the conversation while it runs say they wait. Dumb terminals keep turns in the foreground. Decision D-088.

## Roadmap items

- A main prompt that never blocks (user report, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | When I'm on the main chat, I don't want the main chat to block, ever | User report, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | On a terminal that isn't dumb, the prompt reads while a main turn runs, including automatic turns (spec 054). The turn's output prints above the prompt, whole lines at a time; the line being typed stays as it was. The status line's `turn` item says `working` while a main turn runs. |
| AC-02 | A message sent during a main turn is queued: a line says so, and when the turn ends, the queued messages go to the agent as one message (in order, separated by blank lines), as the user's next message. A user command whose prompt runs as a turn is queued the same way. |
| AC-03 | Esc at the prompt stops the running main turn; queued messages then go next. When no turn runs, Esc does nothing. |
| AC-04 | The main agent's questions during a background turn (approvals, network hosts, a proposed plan) never take the prompt: a notice says "the main agent is waiting for you", the status line counts it as held, and the turn waits. `/answer main` asks it with the normal prompt and returns to the main chat; `/answer` with no name asks it first; Tab completes `main`. Esc on the prompt denies it and stops the turn. |
| AC-05 | While a main turn runs, `/compact`, `/diff`, `/undo`, `/rewind`, `/model NAME`, `/plan`, `/switch NAME` and `/follow` print that they wait until the turn ends (Esc stops it) and do nothing. Other commands work. `/exit` and Ctrl-D stop the turn, then quit. |
| AC-06 | Dumb terminals run turns in the foreground, as before; their tests are unchanged. |
| AC-07 | Checked in tmux with the native binary on Linux and macOS: typing during a turn, a queued message, Esc, `/answer main`. README, D-088, a note on D-087, Q97, roadmap. |

## Out of scope

- Sending a message into the running turn (steering).
- Live prompts while talking to a subagent after `/switch`, or while `/follow` watches one.
- Token-by-token output above the prompt.

## Dependencies and assumptions

- Specs 009, 014, 042 and 054 (the prompt, its keys, the status line, automatic turns).
- JLine's `printAbove` is safe from any thread while `readLine` runs, and prints plainly when it doesn't.

## Open questions

None. Design confirmed by the user on 2026-11-26: queue typed messages, notices with `/answer` for the main agent's questions, line-by-line output.
