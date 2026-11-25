# Spec 047 — Headless output

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

`jharness -p` gets `--output text|json|stream-json` and exit codes for pipelines, and `--mode` sets the permission mode from the command line. Decision D-082.

## Roadmap items

- Headless mode with text, JSON and stream-JSON output

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-5.1 | As a CI engineer, I want to run a task non-interactively with machine-readable output, so I can use JHarness in pipelines. | `user-stories.md` |
| US-5.2 | As a CI engineer, I want to set the permission mode on the command line, so runs never stop for prompts. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `--output text` (the default) prints the reply as before. `--output` other than text, json or stream-json, or without `-p`, stops with exit 2. |
| AC-02 | `--output json` prints only one line, at the end: `type: result`, `session`, `stopReason`, `reply`, `steps`, `error`, `exitCode`, `durationMs`, `usage` (requests, input, output, cache read and write tokens for every agent), `filesChanged`, `denied` (agent, api, summary, reason) and `subagents`. |
| AC-03 | `--output stream-json` prints every event of this run as a JSON line with its envelope: the opening events from the session log first, then live ones including `model.delta`, each once; then the result object. |
| AC-04 | Exit codes: 0 for a `done` turn; 1 for `error`, `max_steps` or `interrupted`; 2 for bad flags or settings, which in the JSON modes also print a result object with `session: null`. |
| AC-05 | `--mode ask|accept-edits|plan|bypass` sets the session's permission mode over the user's and project's settings; a bad value stops with exit 2. With `-p`, what is left to ask is denied. |
| AC-06 | JSON output escapes control and bidi characters as `\u` escapes, so stdout is valid JSON with no raw control characters. |
| AC-07 | The README and D-082 document it; CI's native step checks json, stream-json and `--mode`. |

## Out of scope

- Rule flags (`--allow`, `--deny`).
- Several prompts per run, or reading the prompt from stdin.

## Dependencies and assumptions

- Events and the session log (D-038), `-p` (spec 005), escaping (D-060).

## Open questions

None.
