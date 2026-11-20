# Plan 014 — Shift+Enter and a higher step limit

Spec: `spec.md` · Status: Done · Created: 2026-11-20

## Approach

`Tui` writes `ESC[>4;1m` before the main `readLine("> ")` and `ESC[>4m` in a `finally` after it, on terminals that aren't dumb. The `jharness-newline` widget from spec 009 gets more key bindings: the `modifyOtherKeys` and CSI u forms of Enter with Shift, Alt or Ctrl (modifier codes 2 to 8). `AgentSettings` changes its default and limit. Decision D-051.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process or binary | Yes |
| II Host owns side effects | Terminal I/O only, in the host | Yes |
| III Untrusted | No isolate change; the step limit stays a configured limit with a hard maximum (500) | Yes |
| IV No shell | No process | — |
| V Proxy | No network | — |
| VI Strings | No `api` change | — |
| VII Safe by default | Approvals still answer with single keys, with `modifyOtherKeys` off; more steps per turn don't skip any permission | Yes |
| VIII Events | No new action; `turn.completed` still reports `max_steps` | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | TUI test with the new key sequences and the terminal mode writes; settings test of the new range | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
// Tui
static final String MODIFY_KEYS_ON = "\u001b[>4;1m", MODIFY_KEYS_OFF = "\u001b[>4m";
private String readPrompt();          // on, readLine("> "), off in finally
// bindings to jharness-newline, for mod in 2..8 (Shift, Alt, Ctrl and their mixes):
//   ESC [ 27 ; mod ; 13 ~     (xterm modifyOtherKeys)
//   ESC [ 13 ; mod u          (CSI u)
// AgentSettings: defaults() = 100, MAX_STEPS_LIMIT = 500
```

### Terminal mappings in the README

| Terminal | Shift+Enter → Alt+Enter |
| --- | --- |
| VS Code | `keybindings.json`: `workbench.action.terminal.sendSequence` with `"text": "\u001b\r"` on `shift+enter` when `terminalFocus` |
| kitty | `map shift+enter send_text all \x1b\r` |
| iTerm2 | Settings → Profiles → Keys → Key Mappings: Shift+Enter, "Send Hex Code" `0x1b 0x0d` |

## Checks

- [x] `mvn -pl jharness-cli -am test`
- [x] `mvn verify` on macOS; CI on all targets
- [ ] By hand: Shift+Enter in a real terminal (iTerm2, Terminal.app, VS Code)

## Risks

| Risk | Response |
| --- | --- |
| A terminal prints `ESC[>4;1m` instead of ignoring it | It's a standard private CSI sequence that terminals skip; `TERM=dumb` turns it off |
| Other modified keys arrive as unknown sequences while the prompt reads | Level 1 leaves Ctrl-letter, Esc, arrows and Tab as they are; only unusual combinations change, and only while the prompt reads |
| JHarness dies while the prompt is reading, leaving the mode on | Only modified keys change, and `reset` or a new terminal clears it |
| 100 steps make a stuck turn cost more | Each step still has its own wall clock; Esc stops a turn; the user can lower `agent.maxSteps` |
