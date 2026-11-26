# Plan 066 — Compaction for thinking models, and the context window from the server

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **Config.** `ModelProfile` gets `summaryTokens` and `contextWindowSet`; `ModelProfiles` parses and checks `summaryTokens`, which the secret-name check allows.
- **Compaction.** In `AgentLoop.compact`:
  - the limit is the larger of `summaryTokens` and `maxTokens`, at most half the window;
  - the transcript leaves room for it;
  - one retry with twice the limit when the reply ran out empty;
  - the failure message and event fields.
- **Probe.** `ContextProbe` asks every kind of server at once with `sendAsync` on the provider's client (so through the proxy) and takes the first answer in a fixed order.
  - `OpenAiCompatibleProvider` caches the answer per base URL and model.
  - It combines the answer with the profile in `contextWindow()`.
  - `discoverContext()` publishes `model.context_window` and returns the notice.
- **Session and UI.**
  - `Session.open` and `switchModel` call `discoverContext()`, and `Session.contextNotice()` keeps the line.
  - The TUI prints it at start and after `/model`; `-p` prints warnings on stderr.
- **Tests.**
  - `CompactionTest`: the limit, the retry, the messages, `summaryTokens`.
  - `ContextProbeTest`: fake vLLM, llama.cpp, LM Studio v0 and v1, and Ollama servers; a hanging server; the window rules and notices.
  - `ContextSettingsTest`: parsing.
  - The testkit stub answers the probe paths with 404 so its queue is kept.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Only reads from the model server | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | The probe uses the provider's client, so the egress proxy and its model-endpoint rule | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | A smaller server context wins, so requests don't overflow; a set value is never raised | Yes |
| VIII Events | `model.context_window`; more fields on `context.compact_failed` | Yes |
| IX Native first | `HttpClient.sendAsync` and `CompletableFuture`, already in the binary | Yes |
| X Tested | Compaction, probe, settings | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets
- [ ] Against a real LM Studio, llama.cpp or Ollama server (none on the test machines; the user runs LM Studio)

## Risks

| Risk | Response |
| --- | --- |
| A server's API changes its field names | Nothing found means the default and a warning, as before |
| A hanging server slows the start | One 2 s deadline for all the asks |
| A retry doubles the cost of a failed compaction | Only when the reply ran out empty, once |
