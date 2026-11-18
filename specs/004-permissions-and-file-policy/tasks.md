# Tasks 004 — Permissions and file policy

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-18

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Settings and file policy

- [x] T001 Add the `permissions` and `files` settings sections with validation (AC-07)
- [x] T002 Write `FilePolicy` with mounts and the deny list, and its tests (AC-05, AC-06)

## Group 2 — Permission engine

- [x] T003 Write `PermissionMode`, `Prompter`, `ProjectRules` and `PermissionEngine` with tests for every mode (AC-01, AC-02, AC-03)

## Group 3 — Wiring

- [x] T004 Use `FilePolicy` in `HostApi` and search (deny exclusions for `rg` and Java), mounts in `readContext`, `api.d.ts` update (AC-05, AC-06)
- [x] T005 Build the engine in `Session`; test approvals inside a running block with the watchdog paused (AC-02, AC-04)
- [x] T006 Run the gate scenario in `ask` mode; add denial and saved-rule checks to the smoke binary (AC-08)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T003 |
| AC-02 | T003, T005 |
| AC-03 | T003 |
| AC-04 | T005 |
| AC-05 | T002, T004 |
| AC-06 | T002, T004 |
| AC-07 | T001 |
| AC-08 | T006 |
