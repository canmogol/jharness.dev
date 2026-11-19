# Tasks 011 — Egress proxy and network rules

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-19

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Rules

- [ ] T001 Create `jharness-net`; rules-file parser with line-numbered errors; tests (AC-01)
- [ ] T002 `NetworkRules` evaluator: built-in denies, trust, file order, deny over allow, defaults, automatic allows; tests (AC-02)

## Group 2 — Proxy

- [ ] T003 `EgressProxy` with credentials, CONNECT and plain HTTP, DNS pinning, events; tests with a local upstream (AC-03, AC-04, AC-05)
- [ ] T004 Ask path: session allows, "always" appends to the project file, headless blocks; tests (AC-06)

## Group 3 — Wiring

- [ ] T005 `HttpClients.useProxy` with the authenticator; `Session` starts the proxy with model endpoints allowed; a stub-server turn through the proxy (AC-07)
- [ ] T006 Architecture test: sockets only in `jharness-net` and `HttpClients` (AC-08)

## Group 4 — TUI

- [ ] T007 Network questions in `TerminalPrompter`; `/network list|allow|deny|remove|reload`; TUI tests (AC-06, AC-09)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T002 |
| AC-03 | T003 |
| AC-04 | T003 |
| AC-05 | T003 |
| AC-06 | T004, T007 |
| AC-07 | T005 |
| AC-08 | T006 |
| AC-09 | T007 |
