# Tasks 016 — `api.http` and web fetch

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-22

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — jsoup check and the move

- [x] T001 jsoup Native Image check on Linux and macOS: add the dependency to `jharness-net` and convert a page in the native smoke binary (AC-10)
- [x] T002 Move `HttpClients` to `jharness-net` (`dev.jharness.net.http`); `jharness-llm` and `jharness-sandbox` depend on `jharness-net`; architecture test path (AC-09)

## Group 2 — `api.http`

- [x] T003 [P] `Timeouts.http`/`httpMax` and `HttpSettings` with limits; settings tests (AC-07, AC-05)
- [x] T004 [P] `Operation.Kind` `HTTP` and `HTTP_WRITE`; `<METHOD> <host>` rules in `ProjectRules`; permission tests incl. plan mode (AC-04)
- [x] T005 `HttpOperations.check`: methods, URL, body, header rules, `User-Agent`; tests (AC-01, AC-02)
- [x] T006 `HttpOperations.send`: proxy client, redirects, read cap, text/binary, charset, timeout and cancel, events; tests against a local stub through the egress proxy (AC-03, AC-05, AC-07, AC-08)
- [x] T007 `HostApi.http` with the permission gate and wall-clock pause; API tests (AC-04, AC-07)

## Group 3 — `api.fetch`

- [x] T008 `PageText` converter with jsoup; converter tests (AC-06)
- [x] T009 `HttpOperations.fetch` and `HostApi.fetch`; tests for HTML, plain text, binary refusal and non-2xx (AC-06)

## Group 4 — Smoke and docs

- [x] T010 Native smoke: `api.fetch` of a local page through the proxy (AC-10)
- [x] T011 `api.d.ts` types for `http` and `fetch`; README operations (AC-11)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T005 |
| AC-02 | T005 |
| AC-03 | T006 |
| AC-04 | T004, T007 |
| AC-05 | T003, T006 |
| AC-06 | T008, T009 |
| AC-07 | T003, T006, T007 |
| AC-08 | T006 |
| AC-09 | T002 |
| AC-10 | T001, T010 |
| AC-11 | T011 |
