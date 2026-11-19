# Tasks 008 — Loop notice for extra code blocks

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-19

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Notice for extra blocks

- [x] T001 Add `JsBlocks.find` and `Found`; make `first` a wrapper; unit tests for counts with `js`, `javascript`, other info strings and an unclosed last block (AC-01)
- [x] T002 Use `find` in `AgentLoop`: `ignoredBlocks` on `loop.code`, note after the cap; loop tests with and without extra blocks and with a result over the cap (AC-02, AC-03)
- [x] T003 Replay regression of gate attempt 1 in `jharness-testkit`: edit and commit in one reply, note sent, commit in the next reply, turn `done`, fix committed (AC-04)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T002 |
| AC-03 | T002 |
| AC-04 | T003 |
