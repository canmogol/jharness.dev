# Spec 040 — Prompt caching and `@file` mentions

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

Claude requests use Anthropic's prompt cache, so each step of a turn reads the growing conversation from the cache instead of paying for it again. In a prompt, `@path` attaches a file or folder the agent may read, with Tab completion in the TUI. Decision D-078.

## Roadmap items

- Prompt caching, `@file` mentions

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.8 | As a developer, I want long sessions to keep working when context fills up, so big tasks can finish. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `anthropic` profiles have `promptCache` (default true). A non-boolean value, or `promptCache` on an `openai-compatible` profile, stops start-up. |
| AC-02 | With the cache on, a Claude request sends the system prompt as a text block and the last message as a text block, each with `cache_control: {type: "ephemeral"}`; earlier messages stay plain strings. With it off, or for a compaction summary, nothing is marked. OpenAI-compatible requests are unchanged. |
| AC-03 | `inputTokens` is the whole prompt including cached parts (Anthropic: `input_tokens` plus `cache_read_input_tokens` plus `cache_creation_input_tokens`). Replies carry `cacheReadTokens` and `cacheWriteTokens` (OpenAI-compatible: `prompt_tokens_details.cached_tokens` as read); `model.completed` adds them when not 0, and `jharness log` shows them. |
| AC-04 | `@path` at the start of a word, or `@"path with spaces"`, in a TUI prompt, a `-p` prompt or a slash command's prompt, attaches the file's text or the folder's listing (up to 200 entries) under an `# Attached files` heading, each in a fence longer than any backtick run inside. Trailing punctuation is tried off. The typed prompt is what `turn.started` records. |
| AC-05 | Mentions follow the file policy: the workspace and read-only mounts; deny-listed and outside paths are refused, and deny-listed entries are left out of folder listings. Binary files are not attached. Up to 20 mentions; 50,000 characters a file; 200,000 a prompt and at most a quarter of the context window. A cut file says how much was kept and points to `api.readFile`. |
| AC-06 | `prompt.attached` records the files (path, characters, lines, cut, folder), the skipped mentions with reasons, and the total. The TUI prints `▸ attached …` and `▸ not attached: @x: reason`; words that aren't paths (no `/` or `.`) are left alone silently. Tab completes `@` paths, without deny-listed entries. |
| AC-07 | The README, the tech stack, the TUI help and D-078 document it. |

## Out of scope

- The 1-hour cache, and cache settings for OpenAI-compatible servers.
- Mentions in subagent tasks, `/switch` messages and agent-written text.
- Usage and cost tracking (its own roadmap item; it will read the cache counts).
- Image and PDF attachments.

## Dependencies and assumptions

- The file policy (D-041) and the context window (D-077).
- Anthropic's cache needs a prefix of at least 1,024 tokens on most models; the system prompt is longer, and a shorter prefix is simply not cached.

## Open questions

None.
