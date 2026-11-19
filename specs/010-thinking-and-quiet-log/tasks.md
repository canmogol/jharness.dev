# Tasks 010 — Thinking text and a quieter session log

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-19

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Providers and events

- [x] T001 `ChatReply.thinking`, `ThinkSplitter` and its tests (split tags, no tags, leading blank lines) (AC-03, AC-05)
- [x] T002 OpenAI-compatible and Anthropic thinking; `model.thinking_delta`; `text` / `thinking` with caps on `model.completed` for every provider; provider tests (AC-02, AC-03, AC-04)
- [x] T003 `SessionLog` skips the delta types; update log and smoke checks (AC-01)

## Group 2 — Loop and TUI

- [x] T004 Loop test: a `js` block inside `<think>` doesn't run (AC-05)
- [x] T005 `ui.thinking`, `/thinking` and dim thinking in the TUI; settings and TUI tests (AC-06)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T003 |
| AC-02 | T002 |
| AC-03 | T001, T002 |
| AC-04 | T002 |
| AC-05 | T001, T004 |
| AC-06 | T005 |
