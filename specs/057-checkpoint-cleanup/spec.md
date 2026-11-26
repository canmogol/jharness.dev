# Spec 057 — Checkpoint cleanup

Status: Done · Phase: P3 · Created: 2026-11-26

## Summary

The files that `/undo` restores are saved under `~/.jharness/checkpoints/` and were never removed. Undo only works in the session that saved them, and since spec 056 one session can save up to about 10 GB. Now, when a session opens, this project's checkpoints of other sessions untouched for `files.checkpointDays` days (default 30) are removed. Decision D-090.

## Roadmap items

- Checkpoint cleanup (user request, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Deleted files are kept so undo can bring them back, but not for ever | User request, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `files.checkpointDays` (0 to 3650, default 30; 0 keeps them for ever) is a known setting; other values are refused with a message. |
| AC-02 | When a session opens, the checkpoint folders of this project's other sessions whose newest file or folder is older than the setting are removed. The current session's folder, other projects' folders and anything a link points to are never touched. A folder that can't be removed is left for the next start. |
| AC-03 | A `checkpoints.pruned` event gives the sessions and bytes removed and the days, when anything was removed. |
| AC-04 | README, D-090, Q99, roadmap. |

## Out of scope

- Session logs and saved conversations (`--resume` needs them).
- Other projects' checkpoints, which go when a session opens there.

## Dependencies and assumptions

- Specs 007 (checkpoints) and 056 (deletes).

## Open questions

None. The user chose "after N days" on 2026-11-26; recommended defaults accepted while away.
