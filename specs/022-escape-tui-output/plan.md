# Plan 022 — Escape control characters in live output

Spec: `spec.md` · Status: Done · Created: 2026-11-23

## Approach

- `TerminalSafe.text` escapes like `escape` but keeps newlines and tabs and drops carriage returns. `TerminalSafe.SafeWriter` applies it to a `Writer`; `TerminalSafe.stream` applies it to a UTF-8 `PrintStream`, holding a multi-byte character until it is complete.
- `Tui` has two writers: `out`, the escaping one, for everything, and `raw` for `Styler` output and the `modifyOtherKeys` sequences only. `TerminalPrompter` gets both.
- `Styler` runs `TerminalSafe.text` on its input in `line`, `diff` and every painted string.
- `Main.run` wraps `out` and `err` with `TerminalSafe.stream`, which covers `-p`, errors and picocli output.
- Decision D-060.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Output only | Yes |
| III Untrusted | Model, file and tool text is treated as untrusted on the way to the terminal | Yes |
| IV No shell | No processes | Yes |
| V Proxy | No network | Yes |
| VI Strings | No `api` change | Yes |
| VII Safe by default | Always on; no setting turns it off | Yes |
| VIII Events | Event contents unchanged | Yes |
| IX Native first | Plain JDK | Yes |
| X Tested | Hostile text through every path, color and dumb terminals, headless, split bytes | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A new print bypasses the escaping writer | `raw` is used only with `Styler` output, which escapes its input; the test covers each path |
| A user wants raw bytes from `-p` | Out of scope; the session log keeps the exact text |
