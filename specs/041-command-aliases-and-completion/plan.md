# Plan 041 — Command aliases and argument completion

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- CLI: `Tui.ALIASES` maps `/quit` and `/q` to `/exit`; `Tui.BUILT_IN` is the help's commands plus the aliases and is what `CommandRunner` reserves (the TUI and `-p`). The dispatcher maps an alias first. The `/exit` help line names the aliases.
- Completion: the completer handles words after the first with `Tui.argChoices(cmd, before, session)`, which returns ordered choices from fixed lists, the settings' profiles, the session's subagents and turns, and the proxy's rules files, or a user command's `args`. JLine's `AUTO_LIST` and `AUTO_MENU` are set, `MENU_COMPLETE` off.
- Agent: `CommandRunner.Command.args`, read from front matter or a JS `args` array in the existing probe, checked by `checkArgs`. A built-in name becomes a `Clash` with a `command.shadowed` event instead of an error; `clashes()` serves the TUI, which loads the commands at the start to print them.
- Events: `command.shadowed`; `commands.loaded` gains `shadowed`; `LogFormat` entry.
- Tests: `CommandTest.aliasesWinAndArgsAreDeclared`, the updated clash test; `TuiTest.quitAliases`, `aCommandFileCantTakeAnAlias`, `argumentChoices`, `tabCyclesThroughArgumentChoices`; the existing completion tests.
- D-079, Q85, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency; JLine is already in | Yes |
| II Host owns side effects | Completion only reads session state | Yes |
| III Untrusted | The JS `args` probe runs in the existing command isolate | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | No network use | Yes |
| VI Strings | `args` crosses as a JSON array of strings | Yes |
| VII Safe by default | A project can't take a built-in's name or alias | Yes |
| VIII Events | `command.shadowed`; `shadowed` on `commands.loaded` | Yes |
| IX Native first | Plain Java; the TUI pseudo-terminal check runs the native binary | Yes |
| X Tested | Aliases, clashes, every command's choices, Tab cycling, `args` parsing | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| An existing `q.md` or `quit.md` stops working | The start-up line names the file and the reason |
| Loading commands at the start is slower with many JS commands | Each is probed once, as before; it happened on the first Tab or `/help` anyway |
