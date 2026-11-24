# Plan 031 — Readable session paths

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- `jharness-events`:
  - `SessionLog.projectHash` becomes `projectFolder(Path workspace)`: slug plus 7 hex characters (AC-01). Every caller uses it: `SessionLog.pathFor`, `SessionLogs`, `ProjectRules.fileFor` and `Checkpoints.rootFor`.
  - `EventBus.newSessionId()` makes the time-based id (AC-02). `SessionLog.open` draws new random characters while the file exists.
- `SessionLogs`:
  - The time pattern becomes `s-(\d{4}-\d{2}-\d{2}T\d{6}Z)-[0-9a-f]{4}`.
  - Entries whose names don't match are skipped, and so are folders that don't end in `-` plus 7 hex characters.
  - "Newest first" sorts by the parsed time, then by the file's last write, then by name.
- Tests:
  - `SessionLogTest`: the slug rules, including upper case, spaces, unicode, a long path, the root, and two paths with the same slug.
  - `SessionLogsTest` and `LogCommandTest` with the new names; old names are ignored.
  - `EventBusTest`: the id format.
  - Existing tests that build ids by hand are updated.
- README layout paragraph; D-070, plus a note on D-038 that the layout changed.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | File names only | Yes |
| II Host owns side effects | Host code writes the files, as before | Yes |
| III Untrusted | Unchanged | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Saved permissions reset to asking; folders stay 0700 and files 0600 | Yes |
| VIII Events | The envelope's `session` holds the new id | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | Slug rules, collisions, ids, log lookup, old names ignored | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [ ] `mvn install` on macOS
- [ ] Native `jharness` checks on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| The slug shows the workspace path in file names under the home | The files were already under the user's 0700 `~/.jharness`; no new reader |
| Two sessions start in the same second | 4 random hex characters, and a retry while the file exists |
| A path's slug changes if the path changes case | It is a different real path, so it is a different project, as before |
