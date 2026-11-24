# Plan 039 — Token counting and compaction

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Config: `ModelProfile.contextWindow` with per-provider defaults, checked against `maxTokens`; `AgentSettings.autoCompact` and `compactAt`.
- LLM: `ModelProvider.contextWindow()` and `maxTokens()`, from the profile in `HttpProvider`, passed through `RecordingProvider` and the replay's agent views. `ReplayProvider` can claim a window and replay recorded token counts.
- Agent: `AgentLoop` keeps the last reported count and the conversation size then, estimates the rest, checks the threshold before each model call, and compacts with a summary request to its own provider. `liveTurns` and a `compactedThrough` mark keep undo and rewind correct. `context()` and `compact()` serve the TUI. `Session` and `SubagentRunner` pass the settings.
- CLI: `/context`, `/compact`, a dim line for automatic compaction, an error line for failures; `/rewind` prints the refusal; `LogFormat` entries.
- Events: `context.compacted`, `context.compact_failed`; `turn.completed` gains two fields.
- Tests: `ContextSettingsTest`; `CompactionTest` (first-turn and later-turn compaction, request shape, undo and rewind after it, `/compact`, a failed summary, auto off).
- D-077, Q83, tech stack, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No tokenizer or new dependency | Yes |
| II Host owns side effects | Compaction changes only the conversation; no `api` change | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | The summary goes through the session's model client, which uses the proxy | Yes |
| VI Strings | Nothing new crosses the isolate boundary | Yes |
| VII Safe by default | Permissions unchanged; the summary prompt treats the transcript as data | Yes |
| VIII Events | `context.compacted`, `context.compact_failed`, fields on `turn.completed` | Yes |
| IX Native first | Plain Java; CI's native steps run the loop | Yes |
| X Tested | Settings, thresholds, request shape, undo, failure, off switch | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A summary loses a detail the agent needs | Keeps the current turn's messages when small; the instructions list what to keep; `compactAt` and `autoCompact` are settings |
| The estimate is far off for some tokenizers | It only bridges since the last real count, and errs high |
| A wrong `contextWindow` | The README says to set it to what the model and server allow |
