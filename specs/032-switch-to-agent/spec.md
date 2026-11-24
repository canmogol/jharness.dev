# Spec 032 — Switch to a subagent

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

`/switch NAME` makes the prompt talk to a subagent. Each message is a new turn in its own conversation, its approval questions are asked instead of being denied as unattended, and its output shows like the main loop's. `/switch main` goes back. Subagents stay open after their task so they can be talked to. Decision D-071.

## Roadmap items

- Switch to an agent, answer its questions and talk to it

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Switch to an agent, answer its questions and talk to it | User request, 2026-11-24 (review of spec 027) |
| US-4.4 | Delegate work to subagents | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `/switch NAME` on an open subagent prints `Talking to NAME (task). …` and changes the prompt to `NAME> `. An unknown name lists the subagents; a closed one says so. `/switch` alone shows usage. |
| AC-02 | A message typed while switched is a new turn of the subagent's loop, in its own conversation, prefixed with a note that the user is talking directly. The reply streams and the steps print with `[NAME]`, as in `/follow`. The main agent isn't handed that reply. |
| AC-03 | While the user is switched to it, the subagent's approval questions go to the user. When the user switches away, its questions are denied as unattended again. Other background subagents stay unattended. |
| AC-04 | Esc during a turn of the switched-to subagent stops that turn. Switching to a running subagent waits for its turn to end, asking its questions meanwhile. |
| AC-05 | `/switch main` prints `Back to the main agent; NAME keeps its conversation …`, and messages go to the main agent again. |
| AC-06 | Subagents keep their loop, conversation and isolate after their task. At most 16 idle ones stay open; the oldest idle one beyond that is closed. Closing the session closes them all. A stopped subagent can be talked to again. |
| AC-07 | `/switch` is in `/help` and Tab completion. The native pseudo-terminal check switches to a subagent, talks to it and goes back. |
| AC-08 | The README and D-071 document it. |

## Out of scope

- The main agent seeing the user's conversation with a subagent.
- Several conversations shown at once.

## Dependencies and assumptions

- Specs 026, 027 and 030.

## Open questions

None. Recommended options accepted while the user was away.
