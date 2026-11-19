# Spec 010 — Thinking text and a quieter session log

Status: Done · Phase: P1 · Created: 2026-11-19

## Summary

The session log has one `model.delta` line per streamed chunk, and the whole reply is nowhere in one piece. Reasoning models (such as Qwen3 through LM Studio) also send thinking text, which JHarness ignores when it comes as a separate field and treats as part of the reply when it comes inline as `<think>…</think>`. This spec keeps deltas live-only, puts the reply and the thinking on `model.completed`, keeps thinking apart from the reply, and lets the TUI show it dim. Decision D-046. Ticks no roadmap item.

## Roadmap items

None (follows the Phase 1 "Event bus and JSONL session log" and "JLine TUI" items).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | Every action is an event in the session log | `user-stories.md` |
| US-3.4 | Agent code has no shell and runs under limits | `user-stories.md` (only the reply's code runs) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `model.delta` and `model.thinking_delta` reach bus subscribers but are not written to the session log; every other event still is. |
| AC-02 | `model.completed` carries `text` (the reply) and, when not empty, `thinking`, from every provider including replay. Each is cut at 65,536 characters, with `textCut` / `thinkingCut` set to the characters cut. |
| AC-03 | The OpenAI-compatible provider reads `delta.reasoning_content` and `delta.reasoning` as thinking, and a `<think>…</think>` at the start of the content as thinking, even when the tags are split across chunks. Leading blank lines before the reply are dropped. |
| AC-04 | The Anthropic provider reads `thinking_delta` blocks as thinking. |
| AC-05 | Thinking is never in `ChatReply.text`, the conversation or the code search: a `js` block inside `<think>` doesn't run. |
| AC-06 | Setting `ui.thinking` (`hide` default, `show`) and `/thinking` (toggle) control whether the TUI streams thinking, dim, before the reply. Bad values stop start-up with a message naming the key. |

## Out of scope

- Turning on Claude extended thinking (a request parameter and a profile setting).
- `<think>` blocks after the start of a reply.

## Dependencies and assumptions

- Specs 002 (providers, event bus, session log), 008 and 009 (TUI events and settings).

## Open questions

None.
