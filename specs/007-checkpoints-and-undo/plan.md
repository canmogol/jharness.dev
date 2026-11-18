# Plan 007 — Checkpoints, undo and interrupt

Spec: `spec.md` · Status: Draft · Created: 2026-11-18

## Approach

`Checkpoints` (in `jharness-sandbox`) is called by `HostApi` before a file changes and after it is written; it keeps per-turn manifests and saved contents on disk. `AgentLoop` records where each turn starts in the conversation and gains `undo`, `rewind` and `interrupt`. `AgentIsolate.cancel()` closes the running context. The TUI puts the terminal in non-canonical mode during a turn and watches for Esc, pausing the watcher while an approval question is open. Diffs come from java-diff-utils. Decision D-044.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | — |
| II Host owns side effects | Saving and restoring files is host code; restores are user commands, not guest calls | Yes |
| III Untrusted | Interrupt uses `Context.close(true)`, as the watchdog does | Yes |
| IV No shell | No new process | — |
| V Proxy | No network | — |
| VI Strings | No boundary change | — |
| VII Safe by default | Undo refuses to overwrite files the user changed unless forced | Yes |
| VIII Events | `checkpoint.saved`, `checkpoint.restored`, `file.changed`, `turn.completed` with `interrupted` | Yes |
| IX Native first | java-diff-utils, checked by the native build in CI | Yes |
| X Tested | Checkpoint, conflict, undo, rewind and interrupt tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

### Checkpoints

```java
public final class Checkpoints {
    void beforeChange(int turn, Path file, String display);   // saves once per file per turn
    void afterChange(int turn, Path file);                    // records the hash the agent left
    void touchedGit(int turn, String op);
    Restore restore(int fromTurnExclusive, boolean force);    // undo = restore(lastTurn - 1)
    String diff(int turn);                                    // unified diff, saved vs current
    record Restore(boolean done, List<String> restored, List<String> conflicts, List<String> notRestorable, boolean gitTouched) {}
}
```

Layout: `<root>/<turn>/manifest.json` (`{"files":[{"path","existed","blob","after"}],"git":[…]}`) and `<root>/<turn>/blobs/<n>`. `after` is the SHA-256 of the content the agent left. Undo checks, for each file, that the current content still has the newest `after` hash among the undone turns, then writes back the content saved by the earliest undone turn (or deletes the file if it didn't exist).

### Loop

- `turnStarts`: conversation size at the start of each turn. `undo()` / `rewind(n)` call `Checkpoints.restore`, then drop the messages from the first undone turn on.
- `interrupt()`: sets a flag, cancels the running block (`AgentIsolate.cancel()`), and interrupts the turn's thread, which stops a blocking model read. The loop ends the turn with `interrupted` at its next check.

### Diffs

`HostApi` computes the unified diff (3 lines of context, at most 200 lines) for `writeFile` and `editFile` and puts it in the operation's `detail.diff`, so the prompter can show it; after the write it emits `file.changed {path, diff}`.

### TUI

- `/undo [--force]`, `/rewind N [--force]`, `/diff`.
- During a turn: terminal attributes without `ICANON` and `ECHO` (output processing kept), a watcher thread reading with a 100 ms timeout; byte 27 → `interrupt()`. `TerminalPrompter` pauses the watcher while it asks.

## Checks

- [ ] `mvn -B -ntp verify`
- [ ] `mvn -B -ntp -Pnative -pl jharness-cli package -DskipTests && python3 -I scripts/tui-pty-check.py jharness-cli/target/jharness`

## Risks

| Risk | Response |
| --- | --- |
| An Esc byte is also the start of arrow-key sequences | Only while a turn runs; arrow keys aren't used then |
| A blocking model read ignores thread interrupts | The stub-server test hangs a stream and checks the interrupt ends it |
| Checkpoints fill the disk | Files over 10 MB are skipped; checkpoints are per session |
