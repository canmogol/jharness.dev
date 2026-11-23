# Plan 024 — `/` and Tab list the commands

Spec: `spec.md` · Status: Done · Created: 2026-11-23

## Approach

- `Tui.COMMANDS` parses `HELP`: each `  /name ...  description` line, first one per name.
- `Tui.completeCommand` is the `LineReader` completer: candidates only for a first word starting with `/`, each with its description.
- A `jharness-tab` widget is bound to Tab: on a lone `/` it calls `printAbove(HELP)`, otherwise it runs JLine's `complete-word`.
- `scripts/tui-pty-check.py` types `/` Tab and checks the list appears.
- Decision D-062.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | UI only | Yes |
| III Untrusted | No limit changes | Yes |
| IV No shell | No processes | Yes |
| V Proxy | No network | Yes |
| VI Strings | No `api` change | Yes |
| VII Safe by default | Completion only fills in command names; nothing runs until Enter | Yes |
| VIII Events | No new action | Yes |
| IX Native first | JLine is already in the native image; the native pseudo-terminal check covers it | Yes |
| X Tested | `TuiTest` (help on Tab, unique and ambiguous completion, list from help), native pseudo-terminal check | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native pseudo-terminal check on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A new command is added without a help line | `COMMANDS` comes from `HELP`, and the test lists the expected commands |
