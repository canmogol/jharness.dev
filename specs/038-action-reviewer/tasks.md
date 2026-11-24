# Tasks 038 — Action reviewer

Spec: `spec.md` · Plan: `plan.md`

- [x] T001 `ReviewerSettings`, layering, the key as a secret; `ReviewerSettingsTest` (AC-01)
- [x] T002 `ActionReviewer`, `Caller`, `PermissionEngine.useReviewer`; `ReviewerGateTest` (AC-03)
- [x] T003 `SystemOneReviewer`, `review.*` events; `SystemOneReviewerTest` (AC-02, AC-04, AC-05)
- [x] T004 Session wiring, proxy allowance, subagent marks, TUI lines; `ReviewerSessionTest` (AC-01, AC-02, AC-03, AC-05)
- [x] T005 CI native step with a fake server (AC-05)
- [x] T006 README, D-031, tech-stack, roadmap (AC-06)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001, T004 |
| AC-02 | T003, T004 |
| AC-03 | T002, T004 |
| AC-04 | T003 |
| AC-05 | T003, T004, T005 |
| AC-06 | T006 |
