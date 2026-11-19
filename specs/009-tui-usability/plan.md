# Plan 009 — TUI usability

Spec: `spec.md` · Status: Done · Created: 2026-11-19

## Approach

All changes are in `jharness-cli`, plus a `ui` settings section in `jharness-config`, a getter for the settings on `Session`, and two optional fields on `loop.result` in `AgentLoop`. A new `Styler` turns text lines into ANSI with JLine's `AttributedStringBuilder`, or returns them unchanged when color is off. The TUI buffers streamed deltas until a newline and styles each full line; the rest is flushed at the end of the turn.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | — |
| II Host owns side effects | Display only | — |
| III Untrusted | No change to the sandbox | — |
| IV No shell | No new process | — |
| V Proxy | No network | — |
| VI Strings | No boundary change | — |
| VII Safe by default | `ui` from an untrusted project is ignored like other keys | Yes |
| VIII Events | `loop.result` gains `error` and `line`; no new action | Yes |
| IX Native first | JLine is already in the binary; the native CI build checks it | Yes |
| X Tested | Styler, settings, loop and TUI tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

- **Input:** widget `insert-newline` writes `\n` into the buffer; bound to `alt(\r)` and `^J` in the main keymap. `SECONDARY_PROMPT_PATTERN` is `. ` so wrapped lines line up. Bracketed paste is JLine's default.
- **Styler:** `Styler(boolean color)`; `line(String)` keeps fence state (```` ``` ```` / `~~~`); `step`, `error`, `diff`, `dim` helpers. Color is on when the terminal isn't `dumb` and `NO_COLOR` is unset.
- **Events:** `UiSettings(String events)` parsed like `AgentSettings`; `Tui` keeps the level, prints the log path at start, `/events [LEVEL]`.
- **Errors:** `AgentLoop` puts `error` (cut to 2,000 characters) and `line` on `loop.result` when status isn't `ok`.

## Checks

- [x] `mvn -pl jharness-cli -am test`
- [x] `mvn verify`
- [ ] By hand: multi-line input, colors and `/events all` in a real terminal

## Risks

| Risk | Response |
| --- | --- |
| Line-buffered styling delays text until a newline | Lines are short; the tail is flushed at the end of the turn |
| Some terminals send Alt+Enter differently | Ctrl-J works everywhere; `/help` names both |
