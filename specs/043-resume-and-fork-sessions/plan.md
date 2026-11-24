# Plan 043 — Resume and fork sessions

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Agent:
  - `SessionState`: the saved record, `pathFor`, atomic private `save`, `parse` with checks, `list` (newest first) and `find`; `Resume(state, fork)`.
  - `AgentLoop`: `Snapshot`, `snapshot()`, `restore()` (conversation, turn count, compaction mark, a `restoredThrough` mark), a state sink called after each turn, `/compact`, `/undo` and `/rewind`; `/rewind` refuses turns before the mark. `TurnCounter.startAt`.
  - `Session`: `Access.resume`; the same id for `--resume`, a new one for `--fork`; restore before the first turn; `session.resumed`; the sink writes the state file; a fork saves at once; `resumed()` and `home()`.
- CLI: `--resume [ID]` and `--fork [ID]` (picocli `arity 0..1`, fallback `last`), found before anything opens; the UI's start lines; `LogFormat` entries.
- Events: `session.resumed`, `session.state_failed`.
- Tests: `ResumeTest` (saved privately, resume continues with undo limits, fork leaves the original, finding by last, id, prefix and errors, a compacted session); `MainTest.resumeAndForkHeadless`; `TuiTest.resumeShowsWhereItLeftOff`; CI's native step.
- D-081, Q87, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | The host writes the state file; no `api` change | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | No network use | Yes |
| VI Strings | Nothing new crosses the isolate boundary | Yes |
| VII Safe by default | State files are 0600 in the 0700 sessions folder, per workspace; permissions start from settings, not the saved session | Yes |
| VIII Events | `session.resumed`, `session.state_failed` | Yes |
| IX Native first | CI's native step resumes and forks | Yes |
| X Tested | Saving, finding, resume, fork, undo limits, CLI errors, the UI | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A large conversation makes each save slow | One write per turn; the conversation is already bounded by compaction |
| A crash mid-write | Temporary file and atomic move; the last complete turn survives |
| Files changed since the saved session | The model gets the conversation, not the files; earlier turns can't be undone |
