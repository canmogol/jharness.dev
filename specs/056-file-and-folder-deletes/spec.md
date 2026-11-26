# Spec 056 — File and folder deletes

Status: Done · Phase: P3 · Created: 2026-11-26

## Summary

Agents can delete inside the workspace. `api.deleteFile(path)` removes one file and follows `writeFile`'s rules. `api.deleteFolder(path)` removes a folder and everything in it, and always asks unless a saved rule or bypass mode allows it. Every deleted file is saved first, so `/undo` and `/rewind` bring it back. Deletes that undo couldn't fully restore are refused. Decision D-089.

## Roadmap items

- File and folder deletes (user request, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Agents need to delete files and folders inside the project folder | User request, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.deleteFile(path)` deletes a file in the workspace and returns `{"path", "bytes"}`. Its operation is a `write` with the path, so ask, accept-edits, bypass, plan mode and saved write rules treat it as `writeFile`. The question shows the removal as a diff, and so does `file.changed` (with `"deleted": true`). A folder, a missing path or a file over 10 MB is an error. |
| AC-02 | `api.deleteFolder(path)` deletes a folder and its contents and returns `{"path", "files", "bytes"}`. Its operation kind is `delete`: it is asked in ask and accept-edits modes, allowed in bypass, refused in plan mode, and "always" saves a `delete` rule for that folder. The question says how many files and bytes go and lists the first 20. One `folder.deleted` event names the folder and the count, and `-p`'s `filesChanged` lists it as `path/`. |
| AC-03 | Refused before any question, even if approved: the workspace root, anything outside the workspace or in a mount, `.git` and `.jharness` and anything under them, paths on the deny list, and folders that contain a denied file, a symlink, a file over 10 MB or more than 1,000 files. |
| AC-04 | Each deleted file gets a checkpoint first. `/undo` and `/rewind` restore deleted files and their folders; empty folders inside a deleted folder aren't restored. `/diff` shows the removals. |
| AC-05 | `api.d.ts`, the JS shim, the native reachability metadata, custom agents' `tools`, the system prompt and an example name both methods. The TUI prints `▸ deleted folder X/ (N files)`. |
| AC-06 | Security suite: the refusals in AC-03, with the user approving every question. |
| AC-07 | README, D-089, Q98, roadmap. |

## Out of scope

- Moving or renaming files.
- Deleting in mounted folders, `.git` or `.jharness`.

## Dependencies and assumptions

- Specs 004 (file policy) and 007 (checkpoints and undo).

## Open questions

None. Design confirmed by the user on 2026-11-26: `deleteFile` and `deleteFolder`; folders always ask.
