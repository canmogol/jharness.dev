# Plan 042 — Status line

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Config: `UiSettings.statusLine` (a list; `true`, `false` or item names), `STATUS_ITEMS`.
- Agent: `Session.permissionMode()` (read without locks) and `Session.home()`.
- CLI:
  - `StatusState` keeps the values from events (`apply` says whether one changed them), renders the default line and the snapshot.
  - `StatusScript` loads and checks `statusline.js`, runs `status(s)` in an isolate on a private bus with `api` deleted, with a timeout, and returns a line or a reason.
  - `StatusLine` throttles refreshes on a timer thread, renders (script or default), publishes failures once, and hands text to the painter.
  - `Tui` starts it on real terminals, draws with JLine `Status` through a widget while reading and under its own lock otherwise, handles `WINCH` between prompts, refreshes before each prompt, and closes it at the end; `stream` takes the same lock as `onEvent`.
- Events: `statusline.ready`, `statusline.failed`; `LogFormat` entries.
- Tests: `SettingsTest.statusLineSetting`; `StatusLineTest` (values from events, script snapshot and no `api`, failures, a slow script, throttling and fallback, isolate events off the log); `TuiTest.statusLineOnRealTerminalsOnly` and `aBrokenStatusScriptFallsBack`; the TUI pseudo-terminal check on the native binary.
- D-080, Q86, tech stack, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | JLine's `Status` is already in the binary | Yes |
| II Host owns side effects | The script has no `api`; the host draws its string | Yes |
| III Untrusted | The script runs in its own isolate with the subagent sandbox profile and limits; the host adds a timeout | Yes |
| IV No shell | No process starts for the status line | Yes |
| V Proxy | No network use | Yes |
| VI Strings | A JSON snapshot goes in, a string comes out | Yes |
| VII Safe by default | Home only, file checks like commands, escaped output, falls back on failure | Yes |
| VIII Events | `statusline.ready`, `statusline.failed` | Yes |
| IX Native first | The pseudo-terminal check runs the native binary with the line on | Yes |
| X Tested | Settings, values, script results and failures, throttling, drawing on real and dumb terminals | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks on Linux, including the TUI pseudo-terminal check
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Output from another thread interleaves with a draw | Widget under JLine's lock while reading; the TUI lock otherwise, which `stream` and `onEvent` take |
| A terminal without scroll regions | JLine's `Status` is then unsupported and draws nothing |
| A slow or looping script | Isolate limits, a 1 s timeout, then the default line for the session |
| Refresh cost during long streams | Event-driven, throttled to 250 ms, no per-chunk refresh |
