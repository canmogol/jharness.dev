# Spec 031 — Readable session paths

Status: Draft · Phase: P3 · Created: 2026-11-24

## Summary

Session logs, saved permissions and checkpoints live under folders named by a 16-character hash of the workspace, and sessions are named by a base-36 time. Both are hard to find by hand. This spec names the project folder after the workspace path, with a short hash so different paths never share a folder, and names each session by its UTC start time:

`~/.jharness/sessions/users-xyz-projects-jharness.dev-2787870/s-2026-11-24T105226Z-18a9.jsonl`

`projects/` (permissions) and `checkpoints/` use the same folder name. Old folders aren't moved: they stay where they are, JHarness ignores them, and the user can delete them. Decision D-070.

## Roadmap items

- None: a user request (2026-11-24) that changes D-038's file layout.

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Find a project's session logs, permissions and checkpoints by looking at the folder names | User request, 2026-11-24 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | The project folder name is `SLUG-HASH`. SLUG is the workspace's real path without its leading `/`, lower-cased, with every run of characters other than `a-z`, `0-9` and `.` replaced by one `-`, with `-` and `.` trimmed from both ends, and cut to at most 80 characters (`root` when it would be empty). HASH is the first 7 hex characters of the SHA-256 of the real path. Example: `/Users/xyz/projects/jharness.dev` gives `users-xyz-projects-jharness.dev-` plus 7 hex characters. |
| AC-02 | A session id is `s-` + its UTC start time as `yyyy-MM-ddTHHmmssZ` + `-` + 4 lowercase hex characters from `SecureRandom`, such as `s-2026-11-24T105226Z-18a9`. If a log with that name already exists, new random characters are drawn. Sorting ids by name sorts sessions by start time. |
| AC-03 | The session log is `~/.jharness/sessions/FOLDER/SESSION.jsonl`, saved permissions are `~/.jharness/projects/FOLDER/permissions.json` and checkpoints are `~/.jharness/checkpoints/FOLDER/SESSION/`, all using the one folder name from AC-01. The envelope's `session` field is the new id. |
| AC-04 | `jharness log` lists, resolves (id, unique prefix, `last`, path) and shows sessions by the new names, and takes the start time from the id. `jharness log --all` lists every project folder in the new format. Folders and files in the old format (16 hex characters, or base-36 ids) are ignored, not migrated or deleted. |
| AC-05 | Saved permissions from old folders no longer apply, so JHarness asks again once. Trust (`trusted.json`, keyed by real path) and tool hashes are unchanged. |
| AC-06 | Folder and session names still pass `jharness log`'s argument check (`[A-Za-z0-9-]{1,64}` for ids; a folder name never contains `/`). Two paths that give the same SLUG get different folders because of HASH. |
| AC-07 | The README and D-070 describe the layout, and say how to delete the old folders. |

## Out of scope

- Moving or converting old sessions, permissions or checkpoints.
- Changing event ids, crash report names or the history file.

## Dependencies and assumptions

- D-038 (session logs), spec 021 (`jharness log`), D-044 (checkpoints), D-010 (saved permissions).
- Still in development, so nothing is kept for old layouts.

## Open questions

None. The HASH and the random-suffix details are the recommended choices.
