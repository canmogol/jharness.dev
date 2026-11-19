# Plan 010 — Thinking text and a quieter session log

Spec: `spec.md` · Status: Done · Created: 2026-11-19

## Approach

`ChatReply` gains `thinking`. `HttpProvider.parse` takes a second consumer for thinking chunks; `stream` publishes `model.thinking_delta` and puts `text` / `thinking` on `model.completed` through one helper that also caps them. A small `ThinkSplitter` in `jharness-llm` routes a leading `<think>…</think>` in OpenAI-compatible content to thinking. `SessionLog.accept` skips the two delta types. The TUI gets `ui.thinking` and `/thinking`. Decision D-046.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | — |
| II Host owns side effects | No new side effect | — |
| III Untrusted | Only reply text is searched for code; thinking never runs | Yes |
| IV No shell | No new process | — |
| V Proxy | No new connection | — |
| VI Strings | No boundary change | — |
| VII Safe by default | `ui` from an untrusted project is ignored like other keys | Yes |
| VIII Events | `model.thinking_delta` is a new event; `model.completed` gains fields; the log keeps every event but the live deltas (D-046) | Yes |
| IX Native first | No new dependency | — |
| X Tested | Provider, splitter, log, settings and TUI tests; a test that a `js` block in `<think>` doesn't run | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
public record ChatReply(String text, String thinking, String stopReason, int inputTokens, int outputTokens) {}

abstract ChatReply parse(InputStream in, Consumer<String> text, Consumer<String> thinking);

/** Splits a leading <think>…</think> off streamed content; feed() chunks, finish() at the end. */
final class ThinkSplitter {
    ThinkSplitter(Consumer<String> text, Consumer<String> thinking);
    void feed(String chunk);
    void finish();
}

/** ModelEvents (package-private): the model.completed body for any provider. */
static ObjectNode completed(String provider, String model, ChatReply r);
```

`ThinkSplitter` states: `START` buffers until the text (after leading whitespace) is either `<think>` (→ `THINK`) or can't become it (→ `TEXT`, flushing the buffer without leading blank lines); `THINK` sends thinking but holds back the last 7 characters until `</think>` is seen; after it, `TEXT` drops leading whitespace once, then passes chunks through. `finish()` flushes what is held.

## Checks

- [x] `mvn -pl jharness-llm,jharness-events -am test`
- [x] `mvn verify`
- [ ] By hand: a turn with `qwen3` through LM Studio, `ui.thinking: show`, and the log tailed

## Risks

| Risk | Response |
| --- | --- |
| A server sends thinking in a field we don't know | The reply is unaffected; add the field when seen |
| A model writes a literal `<think>` at the start of a real answer | Rare; the text still lands in `thinking` on `model.completed` |
