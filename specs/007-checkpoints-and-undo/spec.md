# Spec 007 — Checkpoints, undo and interrupt

Status: Done · Phase: P1 · Created: 2026-11-18

## Summary

Save each file's content before the agent first changes it in a turn, so `/undo` and `/rewind N` can put files and the conversation back. Show changes as diffs: in the approval question in `ask` mode, and after the change in the other modes; `/diff` shows the last turn. Esc stops a running turn at once: the model stream and the running block.

Scope and options taken from the recommended choices while the user was away (2026-11-18). This is the last open Phase 1 roadmap item.

## Roadmap items

- Checkpoints, `/undo`, `/rewind`; diff review; Esc to interrupt

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.3 | See every change as a diff | `user-stories.md` |
| US-2.4 | Undo the agent's changes per turn | `user-stories.md` |
| US-2.5 | Stop the agent at once | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | Before the first `writeFile`, `editFile` or `git restore` of a file in a turn, its content (or its absence) is saved in the turn's checkpoint under `~/.jharness/checkpoints/<project-hash>/<session-id>/` (0700/0600, D-044); `checkpoint.saved` is emitted. |
| AC-02 | `/undo` restores the files of the last turn, removes files it created, and cuts the conversation back to before that turn; `/rewind N` does the same for every turn after N. `checkpoint.restored` lists the files. |
| AC-03 | If a file changed since the agent last wrote it, undo changes nothing and lists the files; `--force` restores anyway. |
| AC-04 | Undo restores files only; when an undone turn ran `git add`, `commit` or `restore`, the message says git state was not undone. Files over 10 MB are reported as not restorable. |
| AC-05 | Each file change carries a unified diff: the approval question shows it in `ask` mode; in `accept-edits` and `bypass` the TUI prints it after the change (`file.changed`). `/diff` prints the last turn's changes. |
| AC-06 | Esc during a turn stops the model stream and the running block within a second; the turn ends with stop reason `interrupted` and the session keeps working. |
| AC-07 | The TUI pseudo-terminal check covers Esc on a running block and `/undo`. |

## Out of scope

- Undoing git commits or other git state (D-044).
- Checkpoints across sessions (resume comes in P4).

## Dependencies and assumptions

- Specs 002–006 (PRs #5–#9). java-diff-utils 4.15 (pure Java; checked by the native build).

## Open questions

None.
