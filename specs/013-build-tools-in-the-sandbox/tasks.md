# Tasks 013 — Maven, Gradle and npm in the sandbox

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-19

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Network path to the proxy

- [x] T001 `ChildSandbox` grants (cache folders, network) and the proxy route: Seatbelt rule, bubblewrap socket bind and bridge argv; `ProxyBridge`; `EgressProxy.serveUnix`; password redaction in `ProcessRunner` (AC-05, AC-07, AC-08)
- [x] T002 Tests: a sandboxed client reaches a stub server through the proxy and can't connect directly; git and `rg` runs still have no network (AC-07, AC-09)

## Group 2 — Build operations

- [x] T003 `timeoutMs` on git and build calls (AC-04)
- [x] T004 `BuildOperations` for Maven, Gradle and npm: validation, fixed flags, wrappers, proxy settings, cache grants; unit tests of argv (AC-01, AC-02, AC-06, AC-08)
- [x] T005 `api.maven`, `api.gradle`, `api.npm`; `BUILD` permission kind and saved rules; trust check; `api.d.ts`; `Session` wiring (AC-03, AC-05)
- [x] T006 Real-tool tests (Maven, npm, Gradle when installed) in the sandbox (AC-02, AC-08, AC-09)

## Group 3 — Native and docs

- [x] T007 `__proxy-bridge` in the CLI and smoke binaries; native smoke check through the bridge; README and tech-stack notes (AC-07, AC-09)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T004 |
| AC-02 | T004, T006 |
| AC-03 | T005 |
| AC-04 | T003 |
| AC-05 | T001, T005 |
| AC-06 | T004 |
| AC-07 | T001, T002, T007 |
| AC-08 | T001, T004, T006 |
| AC-09 | T002, T006, T007 |
