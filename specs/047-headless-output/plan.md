# Plan 047 — Headless output

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- CLI:
  - `Main`: `--output` and `--mode` options, checked before anything opens; `--mode` goes into the session settings layer.
  - `headless`: text as before; json collects and prints the result; stream-json subscribes first, then prints this run's opening events from the session log, deduplicated by event id under the output lock, then live events.
  - `exitCode`; `startFailed` prints a result object for errors before the session opens.
  - `HeadlessResult`: usage from `model.completed`, files from `file.changed`, denials from `permission.decided`, subagents from `subagent.started`.
- Tests: `MainTest.jsonOutputIsOneResultObject`, `streamJsonAndModeFlag`, `exitCodesAndBadFlags`; CI's native step.
- CI: the `jharness tools` check updated for spec 045's table, which it still matched in the old format.
- D-082, Q91, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Output only; `--mode` uses the existing permission engine | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | No network use | Yes |
| VI Strings | Nothing new crosses the isolate boundary | Yes |
| VII Safe by default | The default stays text and the settings' mode; `-p` still denies what it would ask; JSON output is escaped | Yes |
| VIII Events | The stream is the events; no new action | Yes |
| IX Native first | CI's native step runs both JSON modes | Yes |
| X Tested | Result fields, stream order, mode, exit codes, bad flags, escaping | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks on Linux (CI step, P3 gate, TUI check)
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A pipeline relied on exit 0 after `max_steps` | The README and D-082 say so; the result names the stop reason |
| `--mode bypass` in CI | It is the user's explicit flag; the file policy still applies in every mode |
