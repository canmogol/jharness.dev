# Spec 063 — Capabilities overview

Status: Done · Phase: P4 · Created: 2026-11-26

## Summary

One short text says what JHarness can do and what each command does, in one line per command. It sits near the top of the README, and the agent gets it in its system prompt, so it can tell the user which command to type. A test keeps it in step with `/help`, the CLI and the README. Decision D-096.

## Roadmap items

- Capabilities and commands overview (user request, 2026-11-26); a first part of "User guide, `api` reference, extension authoring guide"

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | A short description of JHarness's capabilities and its commands, one line each | User request, 2026-11-26 |
| — | The agent knows the commands, so it can point me to the right one | User request, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `jharness-agent/src/main/resources/dev/jharness/agent/guide.md` has six capability bullets, a table of the terminal UI's commands, the keys, and a table of the command line, one line per entry. |
| AC-02 | The system prompt has a "# JHarness itself" section with the guide, saying the user types these commands and the agent can't run them. It is about 1,000 tokens. The guide is a native-image resource. |
| AC-03 | The README's "What it does" section, between `<!-- guide: … -->` and `<!-- /guide -->` markers, is the same text; `scripts/sync-guide.py` copies it. |
| AC-04 | `GuideTest` fails when a `/help` command, a CLI subcommand or a visible option is missing from the guide, when the README differs, or when the system prompt lacks the guide. |
| AC-05 | D-096, Q105, roadmap. |

## Out of scope

- The full user guide, `api` reference and extension authoring guide (the rest of that roadmap item).
- A setting to leave the guide out of the prompt.

## Dependencies and assumptions

- Specs 006 (the system prompt) and 029 (prompt examples).

## Open questions

None. The user chose to include the system-prompt part on 2026-11-26; the README placement was the recommended option, accepted while the user was away.
