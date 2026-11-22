# Tasks 017 — Git network operations and offline mode

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-22

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Settings and offline rules

- [x] T001 [P] `offline` and `git.credentials` settings; `--offline`; project `git` dropped; untrusted `offline: true` kept; tests (AC-06, AC-08)
- [x] T002 [P] `NetworkRules` offline mode and the model-endpoint filter; tests (AC-08)
- [x] T003 [P] `ProcessRunner`: `GIT_CONFIG_*` and `GIT_ASKPASS` names, a list of secrets to redact; tests (AC-05, AC-06)

## Group 2 — Git network operations

- [x] T004 Request checks and argv for fetch, pull, push and clone; tests (AC-01, AC-02)
- [x] T005 Network prefix, repo-config refusals, remote URL check, proxy and credential environment; tests (AC-03, AC-04, AC-05, AC-06)
- [x] T006 Local HTTPS git server fixture; clone, fetch, pull and push through the proxy and sandbox; credentials and denied hosts (AC-05, AC-06, AC-11)
- [x] T007 `HostApi` kinds, prompt summary with the URL, file policy for `clone` dir, checkpoints; API tests (AC-07)

## Group 3 — Offline mode

- [x] T008 Offline flags for Maven, Gradle and npm; git refusals; tests (AC-09)
- [x] T009 `Session` wiring, `session.started.offline`, the TUI start line (AC-08, AC-10)

## Group 4 — Smoke and docs

- [x] T010 Native smoke: clone from a local HTTPS git server through the proxy (AC-11)
- [x] T011 `api.d.ts`, system prompt, README; roadmap (AC-12)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T004 |
| AC-02 | T004 |
| AC-03 | T005 |
| AC-04 | T005 |
| AC-05 | T003, T005, T006 |
| AC-06 | T001, T003, T005, T006 |
| AC-07 | T007 |
| AC-08 | T001, T002, T009 |
| AC-09 | T008 |
| AC-10 | T009 |
| AC-11 | T006, T010 |
| AC-12 | T011 |
