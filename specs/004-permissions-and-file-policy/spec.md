# Spec 004 — Permissions and file policy

Status: Done · Phase: P1 · Created: 2026-11-18

## Summary

Replace spec 002's placeholder gate with the real permission engine: four modes, approvals asked while a JS block is running (with the watchdog paused), and "always for this project" approvals saved in the user folder. Add the file policy: read-only mounted folders and a deny list that applies in every mode. The terminal prompt itself comes with the TUI; this spec defines the `Prompter` interface it will implement.

Scope and options taken from the recommended choices while the user was away (2026-11-18).

## Roadmap items

- Permissions: ask, accept-edits, read-only (plan), bypass; permanent approvals per project
- Approvals inside a running JS block (watchdog pauses while waiting)
- File policy: workspace read-write, mounted folders read-only, deny list

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.1 | Every state-changing action asks first | `user-stories.md` |
| US-3.2 | Make an approval permanent for a project | `user-stories.md` |
| US-2.2 | Read-only (plan) mode makes no changes | `user-stories.md` |
| US-3.7 | Mounted folders are read-only | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | Each mode allows, asks or denies as in the D-041 table, for `writeFile`, `editFile` and each git operation; reads are allowed in every mode. |
| AC-02 | In `ask` mode the `Prompter` answers once, always or deny. A denial reaches the guest as an error saying the user denied it. With no prompter (headless), state-changing calls are denied. Every question emits `permission.asked`. |
| AC-03 | An "always" answer saves a rule to `~/.jharness/projects/<project-hash>/permissions.json` (0700/0600, D-040) and emits `permission.rule_added`. The same operation is then allowed without asking, in this and later sessions; other operations still ask. Files in the repository can't add rules. |
| AC-04 | Time spent waiting for an answer inside a running block doesn't count toward the wall clock; the block continues after the answer. |
| AC-05 | Folders in `files.mounts` can be read, listed and searched by absolute path; writes and edits there are denied as read-only. `readContext` lists the mounts. |
| AC-06 | Deny-list files (built-ins and `files.deny`) can't be read, written or edited in any mode, `bypass` included, and are left out of `listFiles` and `grep` with both `rg` and the Java fallback. Writes to `.jharness/**` are denied. |
| AC-07 | Settings `permissions.mode` and `files.mounts` / `files.deny` are validated: unknown modes, relative or missing mount folders and empty globs stop start-up. |
| AC-08 | The gate scenario runs in `ask` mode with a scripted prompter that approves; the smoke binary checks a denial and a saved rule. |

## Out of scope

- The terminal approval prompt and diff preview (TUI spec).
- Project trust and project settings that loosen rules (D-011).
- `--mode` on the command line (CLI spec, US-5.2).

## Dependencies and assumptions

- Specs 002 and 003 (PRs #5 and #6).

## Open questions

None.
