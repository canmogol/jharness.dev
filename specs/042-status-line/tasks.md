# Tasks 042 — Status line

Spec: `spec.md` · Plan: `plan.md`

- [x] T001 `ui.statusLine` setting and `SettingsTest.statusLineSetting` (AC-01)
- [x] T002 `StatusState`: values from events, default line, snapshot (AC-01, AC-04)
- [x] T003 `StatusScript`: file checks, isolate without `api`, timeout, results (AC-02, AC-03)
- [x] T004 `StatusLine`: throttled refresh, fallback, `statusline.ready` and `statusline.failed` (AC-03, AC-04, AC-06)
- [x] T005 TUI drawing with JLine `Status`, widget and lock, resize, close; `LogFormat` (AC-01, AC-05, AC-06)
- [x] T006 `StatusLineTest` and `TuiTest` cases; the pseudo-terminal check (AC-01 to AC-06)
- [x] T007 README, tech stack, D-080, Q86, roadmap (AC-07)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T002, T005, T006 |
| AC-02 | T003, T006 |
| AC-03 | T003, T004, T006 |
| AC-04 | T002, T004, T006 |
| AC-05 | T005, T006 |
| AC-06 | T004, T005, T006 |
| AC-07 | T007 |
