# Plan 046 — Sorted help

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Reorder `Tui.HELP`: commands A to Z with their forms, then `Keys:` with `@PATH`, the newline keys and Esc.
- `TuiTest.commandsComeFromTheHelp`: the new order, a check that the list is sorted, and that `Keys:` comes after the commands.
- D-062 and Q90.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No change | Yes |
| II Host owns side effects | Help text only | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | No network use | Yes |
| VI Strings | Nothing crosses the isolate boundary | Yes |
| VII Safe by default | No change | Yes |
| VIII Events | No new action | Yes |
| IX Native first | Plain text; the TUI pseudo-terminal check reads the help | Yes |
| X Tested | Order and sections checked | Yes |
| XI Spec first | This spec | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

None.
