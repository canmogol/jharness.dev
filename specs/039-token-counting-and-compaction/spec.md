# Spec 039 — Token counting and compaction

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

JHarness counts how full the model's context is and, before it overflows, has the model summarize the older conversation so the session can go on. `/context` shows the count and `/compact` compacts by hand. Prompt caching and `@file` mentions, the rest of the roadmap item, follow in their own spec. Decision D-077.

## Roadmap items

- Token counting and auto-compaction (split from "Token counting, auto-compaction, prompt caching, `@file` mentions")

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.8 | As a developer, I want long sessions to keep working when context fills up, so big tasks can finish. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | Each model profile has `contextWindow` (32,768 for `openai-compatible`, 200,000 for `anthropic`; 2,048 to 10,000,000; above `maxTokens`). `agent.autoCompact` (default true) and `agent.compactAt` (0.8; 0.5 to 0.95) say when to compact. Bad values stop start-up. |
| AC-02 | The count is the tokens the model reported for the last request and reply plus about 3.5 characters a token for messages since; with no report yet, the estimate covers the system prompt and conversation. `turn.completed` carries `contextTokens` and `contextWindow`; `/context` shows the count, whether it is estimated, and the threshold. |
| AC-03 | Before each model call, at `compactAt` of the window minus `maxTokens`, the model writes a summary (at most 2,048 tokens) from a transcript of the older messages. The conversation becomes the summary, an acknowledgement, and the current turn's messages when they are under a quarter of the window, or else the turn's prompt with a note. |
| AC-04 | A failed or empty summary leaves the conversation unchanged and is recorded; the turn goes on, and another automatic attempt waits until the context has grown by a tenth of the window. |
| AC-05 | `/compact` summarizes the whole conversation between turns. After any compaction, `/undo` and `/rewind` work for later turns and refuse turns inside the summary with a clear message. |
| AC-06 | `context.compacted` (by, tokens before and after, messages before and after, the summary) and `context.compact_failed` record compaction; the TUI shows automatic ones and failures, and `jharness log` formats them. Subagents compact the same way. |
| AC-07 | The README, the tech stack and D-077 document it. |

## Out of scope

- Prompt caching and `@file` mentions (next spec).
- A bundled tokenizer, or a separate summary model.
- Usage and cost tracking (its own roadmap item).

## Dependencies and assumptions

- The model clients report `inputTokens` and `outputTokens` (spec 002); servers that report none fall back to the estimate.
- Undo and checkpoints (D-044).

## Open questions

None.
