# Plan 049 — Usage tracking

Spec: `spec.md` · Status: Done · Created: 2026-11-25

## Approach

- Config: `ModelPrice` record with `cost(...)`; `ModelProfile` gains `price` (null by default, old constructors kept); `ModelProfiles` parses and checks `price`.
- LLM: `ModelEvents.completed` adds `costUsd` from the profile's price; `HttpProvider` passes it; `ReplayProvider.price(...)` for tests.
- CLI: `Usage` adds up `model.completed` bodies, in total and by agent, and prints the table and notes; `HeadlessResult` uses it; `/usage` reads the session log; `UsageCommand` (`jharness usage`); `StatusState` keeps the cost, seeded from the log, for the new `cost` item; `LogFormat` shows `costUsd`.
- Settings: `cost` joins `UiSettings.STATUS_ITEMS` after `cache`.
- Tests: `PriceSettingsTest`, `ModelEventsTest.costIsAddedWithAPrice`, `UsageTest` (log, command, `--days`, headless, status), `TuiTest.usagePrintsTokensAndCost`; CI's native step.
- D-084, Q93, D-080 and D-082 notes, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | Plain Java; no new dependency | Yes |
| II Host owns side effects | Host-side counting from events; no `api` change | Yes |
| III Untrusted | Isolates unchanged; the status script gets one more number | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | No network use; prices are never fetched | Yes |
| VI Strings | Nothing new crosses the isolate boundary | Yes |
| VII Safe by default | Off until the user sets a price; output escaped | Yes |
| VIII Events | `costUsd` on `model.completed`; reports read the log | Yes |
| IX Native first | picocli code generation covers the new command; CI runs it natively | Yes |
| X Tested | Parsing, cost math, tables, command, headless, status | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness usage` check on Linux (CI step); TUI pseudo-terminal check
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A stale or wrong price | The user sets it; the docs say the cost is an estimate from the profile's price |
| Big logs make `jharness usage` slow | It reads only sessions in the `--days` window |
