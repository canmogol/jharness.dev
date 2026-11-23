# Tasks 020 — Model retries and rate limits

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-23

Format: `- [x] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 1 — Settings

- [x] T001 `retries`, `retryMaxWait`, `responseTimeout` on `ModelProfile`, parsed and checked in `ModelProfiles`; `SettingsTest.profileRetrySettings` (AC-01)

## Group 2 — Retries

- [x] T002 Retry rules: `retryableStatus`, `retryableIo`, `retryAfter`, `backoff`; `RetryRulesTest` (AC-02, AC-03, AC-04)
- [x] T003 `HttpProvider` attempt loop, `Failure`, give-up message, one `model.error` at the end, retryable Anthropic stream errors (AC-02, AC-03, AC-05)
- [x] T004 `RetryWait` and a wait that `cancel()` ends; `ModelProviders.create` overload (AC-07)
- [x] T005 `model.retry` event, `attempt` on `model.request`, UI line (AC-06)
- [x] T006 `ModelRetryTest` against the stub server; `ProvidersTest` updated; `ProxyWiringTest` pins the JDK tunnel message; `TuiTest` checks the retry line; the native smoke checks a 429 retry (AC-02 to AC-07)

## Group 3 — Docs

- [x] T007 README, decision D-058, roadmap, tech stack (AC-08)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T001 |
| AC-02 | T002, T003, T006 |
| AC-03 | T002, T003, T006 |
| AC-04 | T002, T006 |
| AC-05 | T003, T006 |
| AC-06 | T005, T006 |
| AC-07 | T004, T006 |
| AC-08 | T007 |
