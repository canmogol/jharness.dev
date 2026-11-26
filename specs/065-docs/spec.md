# Spec 065 — User guide, api reference and extension guide

Status: Done · Phase: P4 · Created: 2026-11-26

## Summary

Three documents for people who use and extend JHarness:

- `docs/user-guide.md`, one page, covers everything a user does with JHarness.
- `docs/api.md` is generated from `api.d.ts`, the same file the model gets, and a test fails when the two drift apart.
- `docs/extensions.md` says how to write custom agents, slash commands, hooks, tool definitions, MCP server entries and a status line script, each with an example in `examples/` that a test loads.

The README keeps its overview, install and build-from-source sections, and links to the three. Decision D-098.

## Roadmap items

- User guide, `api` reference, extension authoring guide

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Learn how to install, configure and use JHarness from one guide | Roadmap Phase 4 |
| — | Look up every `api` method, its arguments and its result | Roadmap Phase 4 |
| — | Write my own agent, command, hook, tool or MCP entry from a working example | Roadmap Phase 4 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `docs/user-guide.md` is one page with a contents list. It covers install, models and keys, the terminal UI, permissions and trust, files and undo, build tools, the network, git, the web, subagents, sessions and logs, headless runs, and troubleshooting. It ends with a settings reference that names every top-level key of `settings.json`. |
| AC-02 | `docs/api.md` is generated from `api.d.ts`. It has the overview, a table of every method with its first sentence, one section per method with its signature, its whole comment and links to its types, and every type as declared. `scripts/api-reference.sh` writes it, and `ApiReferenceTest` fails when the file differs from what the generator makes. |
| AC-03 | `docs/extensions.md` covers custom agents, slash commands, hooks, tool definitions (every key and field type), MCP servers and the status line script: where each file goes, what trust it needs, what it may and may not do, and its example. |
| AC-04 | Every example is a file in `examples/` that a test loads: the reviewer agent, both commands, the hook and the shipped tools already are. A new `examples/settings.json` (model profiles, an MCP stdio and HTTP server, git credentials) parses as user settings, and a new `examples/statusline.js` runs as a status line script. |
| AC-05 | `DocsTest`: every `examples/` path and relative link in the three documents exists, and every top-level settings key is in the user guide. |
| AC-06 | The README links to the three documents, and its long "Build and run" section moves into the user guide; D-098, Q107, roadmap. |

## Out of scope

- A documentation website or generator other than the `api.md` script.
- Translations.
- Changes to `api.d.ts` itself, beyond what the generator needs to read it.

## Dependencies and assumptions

- The examples of specs 028 (tools), 034 (hooks), 035 (commands), 025 (custom agents), 037 (MCP) and 042 (status line).
- Spec 063's guide stays the README's overview and the system prompt's command list.

## Open questions

None. Design confirmed by the user on 2026-11-26: the user guide is one long page; the `api` reference is generated from `api.d.ts` with a test; one tested example for each extension.
