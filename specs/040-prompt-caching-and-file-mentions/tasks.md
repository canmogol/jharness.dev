# Tasks 040 — Prompt caching and `@file` mentions

Spec: `spec.md` · Plan: `plan.md`

- [x] T001 `promptCache` in model profiles; `PromptCacheSettingsTest` (AC-01)
- [x] T002 Cache breakpoints in Claude requests; no breakpoints for summaries (AC-02)
- [x] T003 Whole-prompt `inputTokens`, cache counts on replies, events, replay and `jharness log`; `ProvidersTest` (AC-02, AC-03)
- [x] T004 `Mentions`: find, resolve under the file policy, read, fence, cut, list folders, complete (AC-04, AC-05)
- [x] T005 `prompt.attached`, `AgentLoop` and `Session.turn`; the TUI, `-p`, Tab completion and lines (AC-04, AC-06)
- [x] T006 `MentionsTest` and `TuiTest.mentionsAttachAndComplete` (AC-04, AC-05, AC-06)
- [x] T007 README, tech stack, TUI help, D-078, Q84, roadmap (AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T002, T003 |
| AC-03 | T003 |
| AC-04 | T004, T005, T006 |
| AC-05 | T004, T006 |
| AC-06 | T005, T006 |
| AC-07 | T007 |
