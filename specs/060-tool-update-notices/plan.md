# Plan 060 — Tool update notices and write hints

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **`ToolDefinitions.updates(home)`.** For each shipped tool, it compares the user's file with the shipped text by SHA-256, and skips tools already reported for that shipped hash. It records what it reports in `~/.jharness/tools-notified.json`, written atomically with mode 0600, and returns `Updates(missing, differ)` with the lines to show.
- **`Session.open`** calls it before `seed`, keeps the result (`toolUpdates()`), and publishes `tools.updates`. `Tui.run` prints the lines under the banner; `Main`'s headless path prints them on stderr.
- **`BuildOperations.toJson(tool, result)`** adds `hint` when the run failed and the output matches `WRITE_REFUSED`. The hint lists the folders from `caches()`, with home paths written `~/…`. `HostApi.tool` uses it.
- **Tests.**
  - `ToolDefinitionTest`: notices.
  - `BuildOperationsTest`: the hint, and none when not needed.
  - `BuildToolsTest`: a real Go test writing to the home.
  - `TuiTest` and `MainTest`: the start-up lines, once.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | The host writes only its own state file; the user's tool files are never changed | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | The hint is a string field in the JSON result | Yes |
| VII Safe by default | Nothing updates by itself; the user runs `jharness tools reset` | Yes |
| VIII Events | `tools.updates` | Yes |
| IX Native first | No new dependency; a native build checked | Yes |
| X Tested | Notices, hint, real sandbox run, TUI and `-p` | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build and CI's native step on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A user who deleted a tool on purpose is told every start | Once per shipped version |
| A hint for a failure that isn't a refused write | It starts with "If this failed on a refused write" and only appears on a failed run with those words |
