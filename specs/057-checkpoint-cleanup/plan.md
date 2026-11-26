# Plan 057 — Checkpoint cleanup

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **Setting.** `FileSettings.checkpointDays` (0 to 3650, default 30). The old two-argument constructor stays.
- **`Checkpoints.prune(home, workspace, current, days, now)`.** It lists the project's checkpoint folder without following links. It skips the current session, and any folder whose newest modification time in the tree is within `days`. The rest are deleted, deepest first, and it returns the sessions and bytes removed.
- **`Session.open`** prunes after it creates the session's checkpoints, and publishes `checkpoints.pruned` when anything went.
- **Tests:**
  - `CheckpointPruneTest`: old, recent, current, recently touched, another project, a link, `0`, no folder.
  - `SettingsTest`: the setting.
  - `UndoTest`: pruning at start and the event.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process or dependency | Yes |
| II Host owns side effects | The host removes its own data; the agent has no part | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | `java.nio.file.Files` only | Yes |
| V Proxy | No network | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Only this project's folders of other sessions, never through links; 30 days by default; 0 keeps for ever | Yes |
| VIII Events | `checkpoints.pruned` | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | Prune cases, setting, session start | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A long-idle session still open loses its undo | Age is the newest file in its folder, and the default is 30 days |
| A link sends the delete elsewhere | Links in the project folder are skipped; the walk doesn't follow links |
