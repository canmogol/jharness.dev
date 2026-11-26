# Tasks 055 — A main prompt that never blocks

Spec: `spec.md` · Plan: `plan.md`

- [x] T001 `AboveWriter`; turns on a worker; the queue; automatic turns from the worker; the `working` status; tests (AC-01, AC-02, AC-06)
- [x] T002 Esc widget on the prompt; tests (AC-03)
- [x] T003 Held main questions in `TerminalPrompter`, `question.*` events, `/answer main`, its completion and the status count; tests (AC-04)
- [x] T004 Commands that wait for the turn; `/exit` and Ctrl-D during a turn; tests (AC-05)
- [x] T005 Existing xterm tests wait for their turns; dumb terminals unchanged (AC-06)
- [x] T006 tmux checks on Linux and macOS; README, D-088, D-087 note, Q97, roadmap (AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T001 |
| AC-03 | T002 |
| AC-04 | T003 |
| AC-05 | T004 |
| AC-06 | T001, T005 |
| AC-07 | T006 |
