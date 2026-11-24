# Spec 029 — Prompt examples

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

The system prompt's examples grow from four to eleven short, task-shaped blocks, chosen for small local models: exploring a project, finding and running a tool, running one test class, checking git status, fetching a page, a background subagent, and progress events. One test runs every block in a real session against a small test project, so an example can't go stale when the api changes. D-068 records why git and `rg` stay in Java code rather than becoming tool definitions.

## Roadmap items

- Prompt examples for small models, each run by a test against a test project; why git and `rg` stay in Java

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-1.2 | Run a local model, so my code never leaves my machine | `user-stories.md` (here: a small local model still writes working blocks) |
| — | Know why git and `rg` aren't editable tools | User request, 2026-11-24 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `examples.md` holds 11 examples, each a short multi-step task in one `js` block of at most 12 lines, covering `listFiles`, `readFile`, `grep`, `editFile`, `git` (diff, status, add, commit), `tools`, `tool`, `fetch`, `spawnAgent` with `background: true`, `waitAgent`, `emit` and a refused `writeFile`. Each shows the habits small models miss: `JSON.parse` on results, checking `exitCode` and `status`, cutting long output, and `try`/`catch` around refusals. |
| AC-02 | `ExamplesTest` checks that every `api.X(` in the examples is a real api method and that the list stays short. |
| AC-03 | `ExamplesTest` runs every block, unchanged except for the documentation URL, in one session as the model would send it, against a test project. The project is a git repository with a README, `src/App.java`, a stand-in Maven tool, a local web page and a background subagent's reply. It checks that each block succeeds and that its result holds what the example promises. Tool blocks are skipped only where the OS sandbox is missing. |
| AC-04 | D-068 and the README say why git and `rg` stay in Java code. `api.d.ts` no longer says subagents default to 30 steps. |

## Out of scope

- Examples chosen per model or per available tools.
- Changing the rest of the system prompt.

## Dependencies and assumptions

- Specs 026 to 028 (subagents, background subagents, tool definitions).

## Open questions

None. Recommended options accepted while the user was away.
