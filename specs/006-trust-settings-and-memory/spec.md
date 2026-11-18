# Spec 006 — Project trust, layered settings and JHARNESS.md

Status: Draft · Phase: P1 · Created: 2026-11-18

## Summary

Ask once per folder whether the user trusts the project, and remember the answer in the user folder. Layer settings: user, then project, then session; an untrusted project's settings can only make JHarness stricter. Read `JHARNESS.md` (project and user) into the system prompt as standing instructions.

Scope and options taken from the recommended choices while the user was away (2026-11-18).

## Roadmap items

- Project trust prompt
- `JHARNESS.md` project memory; layered JSON settings

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-1.4 | Ask once whether I trust a project, so a cloned repo can't run its own code without my say-so | `user-stories.md` (settings; builds and scripts come in P2) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | The TUI asks the trust question the first time a folder is opened, saves the answer in `~/.jharness/trusted.json` (0600, D-043) and doesn't ask again. Headless runs never ask; an unknown folder is untrusted. |
| AC-02 | The question names the folder and says that trusting lets its `.jharness/settings.json` loosen rules. |
| AC-03 | Settings merge user, then project, then session layers key by key, later winning; `files.deny` lists are joined. `--model` is the session layer. |
| AC-04 | For an untrusted project only `files.deny` and `permissions.mode: plan` apply from its settings; every other key is ignored and listed in a `settings.ignored` event. |
| AC-05 | Project settings are validated like user settings; an error names the project file and stops start-up. |
| AC-06 | The system prompt contains the workspace's `JHARNESS.md` and `~/.jharness/JHARNESS.md` under their own headings, each cut at 32 KB with a note; missing files are fine. |
| AC-07 | `session.started` records whether the project is trusted; answering the question emits `project.trusted`. |

## Out of scope

- What trust gates in P2: wrappers, builds and npm scripts.
- Editing trust from inside a session (`/trust`); users can edit `trusted.json`.

## Dependencies and assumptions

- Specs 002–005 (PRs #5–#8).

## Open questions

None.
