# Spec 008 — Loop notice for extra code blocks

Status: Done · Phase: P1 · Created: 2026-11-19

## Summary

The loop runs only the first `js` block of a reply (spec 003 AC-01). When a reply has more, the later blocks are dropped without a word, and the model may report work that never ran. In P1 gate attempt 1 (`docs/p1-gate.md` on branch `p1-gate`), the model put the edit and the commit in one reply; only the edit ran, and the model then said the fix was committed. This spec keeps the one-block rule and tells the model, in the result it gets back, how many blocks didn't run. It is a fix needed to pass the Phase 1 gate; it ticks no roadmap item.

## Roadmap items

None (fix for the Phase 1 gate).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.1 | Describe a bug and have the agent find and fix it | `user-stories.md` |
| US-3.6 | Every action is an event | `user-stories.md` (the count on `loop.code`) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `JsBlocks.find(reply)` returns the first `js` / `javascript` block and the number of later `js` / `javascript` blocks, with the same fence rules as today: other info strings don't count, and an unclosed last block counts. `JsBlocks.first` returns the same code as before. |
| AC-02 | When a step's reply had later blocks, the message sent back is the capped result followed by `Note: your reply had N more js block(s) after the first; they did not run. Send the next one in your next reply.` The note comes after the cap, so it is never cut. With no later blocks, the message is unchanged. |
| AC-03 | `loop.code` carries `ignoredBlocks: N` when N > 0, and no such field otherwise. |
| AC-04 | A replay regression test of gate attempt 1: one reply holds the edit and the commit blocks; only the edit runs, the model gets the note, the next reply sends the commit, and the turn ends `done` with the fix committed. |

## Out of scope

- Running every block in a reply (would change spec 003's design and needs a decision).
- Changing the system prompt's one-block rule or examples.
- A note on the final reply of a turn: a reply with any `js` block always runs its first block, so a turn never ends on a reply with ignored blocks.

## Dependencies and assumptions

- Specs 003 (loop, replay provider) and 007 (interrupt path in the loop).
- After merge, the P1 gate is re-run by hand and recorded in `docs/p1-gate.md` on branch `p1-gate`.

## Open questions

None.
