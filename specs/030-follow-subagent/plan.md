# Plan 030 — Follow a subagent

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- `ModelProvider.forAgent(origin)`: the default returns the provider itself. `ReplayProvider` returns a view that shares its recording and lock but publishes under the given origin. `SubagentRunner` uses it when the subagent shares the session's provider.
- TUI:
  - A `following` field. `onEvent` sends the followed agent's events down the main loop's path: deltas stream through `stream`, and thinking and steps render as for the main loop.
  - Other background agents keep the start and end lines.
  - `follow()` puts the terminal in raw mode with an `EscWatcher`, polls until Esc or the end, then restores the terminal and prints the closing line.
  - `/follow` is in `HELP`.
- Tests:
  - `TuiTest.followShowsABackgroundSubagentLive` runs a real background subagent.
  - `SubagentTest` checks that model events carry the subagent's name.
  - The pseudo-terminal script follows a subagent, presses Esc and stops it.
- D-069, Q74, roadmap and README.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | Yes |
| II Host owns side effects | Display only | Yes |
| III Untrusted | Unchanged | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Watching changes nothing; background subagents still never ask | Yes |
| VIII Events | Reads existing events; model events now name the subagent | Yes |
| IX Native first | No new dependency; the native pseudo-terminal check covers it | Yes |
| X Tested | TUI test with a real subagent, event names, native Esc | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native pseudo-terminal check and native `jharness` checks on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Raw mode left on after following | Restored in `finally`, like a turn |
| A followed subagent that never ends | Esc stops following at any time |
