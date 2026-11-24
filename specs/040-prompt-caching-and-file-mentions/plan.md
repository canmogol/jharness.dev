# Plan 040 — Prompt caching and `@file` mentions

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Config: `ModelProfile.promptCache`, on for `anthropic` by default, refused on `openai-compatible`; an 11-argument constructor keeps the default.
- LLM: `ChatRequest.cache` (true by default) and `ChatReply.cacheReadTokens` and `cacheWriteTokens`, with compatible constructors. `AnthropicProvider` marks the system block and the last message, and adds the cache counts into `inputTokens`. `OpenAiCompatibleProvider` reads `cached_tokens`. `ModelEvents` and `ReplayProvider` carry the counts.
- Agent: `Mentions` finds, resolves (through `FilePolicy.read`), reads and fences mentions within the budget, and completes paths. `AgentLoop.turn(prompt, attached, onDelta)` publishes `prompt.attached` and appends the section to the user message. `Session.turn`, `mentions` and `completeMention`. The compaction summary request is never cached.
- CLI: the TUI and `-p` call `Session.turn`; `@` Tab completion; `▸ attached` and `▸ not attached` lines; an `@PATH` help line; `LogFormat` entries.
- Events: `prompt.attached`; `model.completed` gains two optional fields.
- Tests: `PromptCacheSettingsTest`; `ProvidersTest` (marked request, counts, cache off, one-off request, OpenAI cached tokens); `MentionsTest` (finding, files and folders, fences, the policy, cutting and budget, completion, a session turn); `TuiTest.mentionsAttachAndComplete`.
- D-078, Q84, tech stack, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Mentions are read by the host for the user's prompt; no `api` change | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | Requests still go through the session's model client and proxy | Yes |
| VI Strings | Nothing new crosses the isolate boundary | Yes |
| VII Safe by default | Mentions use the file policy: deny list, workspace and read-only mounts only; attached text is marked as data | Yes |
| VIII Events | `prompt.attached`; cache counts on `model.completed` | Yes |
| IX Native first | Plain Java and JSON; CI's native steps run the loop and providers | Yes |
| X Tested | Settings, request shape, counts, policy refusals, limits, completion, TUI | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A mention pulls in a secret | The deny list applies, as for `api.readFile`; outside paths are refused |
| A big attachment crowds the context | A quarter of the window at most, 50,000 characters a file; compaction still applies |
| Attached text carries instructions | It is fenced under a heading that calls it data; the permission gate is unchanged |
| Cache writes cost more than plain input | Writes happen once per new prefix and are read back on the next step; `promptCache: false` turns it off |
