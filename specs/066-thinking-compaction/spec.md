# Spec 066 — Compaction for thinking models, and the context window from the server

Status: Done · Phase: P4 · Created: 2026-11-26

## Summary

With a local thinking model (`qwen/qwen3.6-27b` in LM Studio), compaction failed every now and then with "the summary was empty". The summary request asked for at most 2,048 tokens, and the model's reasoning, which servers count against that limit, used them all. The same user's status bar showed `of 33K` for a model loaded with 144,000 tokens, because `openai-compatible` profiles assume 32,768 unless they set `contextWindow`.

- **Summary room.** A new profile key, `summaryTokens` (default 4,096), sets the summary's size. The request asks for the larger of it and `maxTokens`. If the reply runs out of tokens with nothing written, JHarness retries once with twice the room, then fails with a clear message.
- **Context window.** When a profile doesn't set `contextWindow`, JHarness asks the server at start and on `/model`. vLLM, llama.cpp, LM Studio, Ollama and hosted model lists are all asked, with one 2 s deadline. A server that loaded the model with less than the profile says wins. A start-up line says what was found, or that JHarness is guessing.

Decision D-099.

## Roadmap items

- Compaction for thinking models and the context window from the server (user report, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Compaction works with a local thinking model | User report, 2026-11-26 |
| — | JHarness knows my local model's real context length without my setting it | User report, 2026-11-26 |
| — | I can make the summary bigger | User request, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | A profile key `summaryTokens` (512 to 32,768, default 4,096; when set, at most half of `contextWindow`) sets the summary size. The summary request's limit is the larger of `summaryTokens` and `maxTokens`, at most half the window. The instructions ask for about three quarters of `summaryTokens` in words. |
| AC-02 | A summary reply that is empty and stopped at its limit (`length` or `max_tokens`) is asked again once with twice the limit, at most three quarters of the window. An empty reply that didn't run out isn't retried. |
| AC-03 | A failed summary says why: "the model used all N tokens thinking and wrote no summary; raise "summaryTokens" or "maxTokens" in the profile, or turn the model's thinking off", or "the summary was empty (stop reason: X)". `context.compact_failed` adds `stopReason`, `outputTokens`, `thinkingChars`, `attempts` and `maxTokens`. |
| AC-04 | For an `openai-compatible` profile, the start and `/model` ask the server once per base URL and model, with one 2 s deadline, through the egress proxy: <br>• vLLM and hosted model lists: `/v1/models`, `max_model_len` or `context_length`; <br>• llama.cpp: `/props`, `n_ctx`; <br>• LM Studio: `/api/v1/models`, a loaded instance's `context_length`, or `/api/v0/models`, `loaded_context_length`; <br>• Ollama: `/api/ps`, `context_length`, or `/api/show`, `num_ctx`. <br>A model's trained maximum is never used. |
| AC-05 | The window is the profile's `contextWindow` when set, else the server's, else 32,768. When the server reports less than a set `contextWindow`, the server's number wins. A `model.context_window` event records it (`window`, `from`, `server`, `reported`, `set`). |
| AC-06 | The TUI prints a line at start and after `/model`: <br>• the window the server reported (dim); <br>• a warning when the server loaded less than the profile says; <br>• a warning when nothing was reported and the 32,768 default applies. <br>`-p` prints the warnings on stderr. |
| AC-07 | The user guide's models section, settings reference and troubleshooting, D-099, Q108, the roadmap. |

## Out of scope

- Turning a server's thinking off for the summary (each server spells it differently).
- Asking Anthropic, whose windows are known.
- Asking again during a session when the server reloads the model.

## Dependencies and assumptions

- Specs 039 (compaction), 046 (thinking text), 049 (usage events).

## Open questions

None. Design confirmed by the user on 2026-11-26: `summaryTokens` per profile, default 4,096; a bigger limit, then one retry; ask the server at start, a set value wins; a smaller server value is used and reported.
