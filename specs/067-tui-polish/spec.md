# Spec 067 — TUI polish: a line above the input, the agent in the status line, questions at once

Status: Done · Phase: P4 · Created: 2026-11-26

## Summary

Five improvements to the terminal UI, from the user's report with screenshots (2026-11-26):

- **A thin line above the input.**
- **The agent you're talking to in the status line.**
- **Questions at once.** A question from the agent the prompt talks to takes the prompt at once, without `/answer`.
- **Fix: thinking colors your typing.** Typing while thinking text streams no longer turns the input gray and italic.
- **Fix: output between a question and its answer.** Other agents' output no longer lands between a question and its answer line.
- **Fix: answers in the history.** Answers such as `y` no longer go into the prompt's history.

Decision D-100; it changes part of D-088.

## Roadmap items

- TUI polish (user request, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | See where my input starts, and which agent I'm talking to | User request, 2026-11-26 |
| — | Answer the agent I'm talking to without typing /answer | User request, 2026-11-26 |
| — | Read a question without other output running through it | User report, 2026-11-26 |
| — | Up arrow brings back my messages, not my y/n answers | User report, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | On a live terminal a dim full-width line (one column short, so it never wraps) sits above the input, and follows a resize. The input starts on the row above the status line, with the line above it. |
| AC-02 | A status line item `agent`, on by default after `context`, shows `main` or the subagent the prompt talks to after `/switch`. `ui.statusLine` can drop or move it, and status scripts get `s.agent`. |
| AC-03 | Styled text is styled line by line, so a line printed above the prompt never leaves a style open. Thinking text streamed while the user types doesn't color the input. |
| AC-04 | While a question is shown, other threads' output waits and prints after the answer, in whole lines. |
| AC-05 | Answers to approval, plan, trust and project-tools questions don't go into the prompt's history. |
| AC-06 | When the prompt talks to the main agent, a question from the main agent takes the prompt at once. The prompt's read ends, the question is asked as with `/answer main`, and the text being typed comes back in the next prompt. No notice is printed. While the prompt talks to a subagent, the main agent's question waits with the notice, as before (D-088). Background subagents' questions still wait for `/answer`. |
| AC-07 | User guide, extension guide, D-100, the note on D-088, Q109, roadmap. |

## Out of scope

- A setting to hide the line above the input.
- Changing how subagents' questions are asked while talking to them (they are already asked at once).

## Dependencies and assumptions

- Specs 042 (status line), 046 (thinking), 055 (live prompt), 058 (input at the bottom).

## Open questions

None. The user asked for these on 2026-11-26 and accepted the recommended defaults: a dim full-width line, and the `agent` item after the context.
