# Plan 021 — Debug mode, `jharness log` and crash reports

Spec: `spec.md` · Status: Done · Created: 2026-11-23

## Approach

- `Main` gets `--debug` and a picocli subcommand `log` (`LogCommand`). `--debug` sets the TUI's event level and thinking at start and, with `-p`, subscribes a stderr writer.
- `LogCommand` reads logs with `SessionLog.read`-style parsing that keeps bad lines. `SessionLogs` (in `jharness-events`) finds a project's logs, resolves `last` and prefixes, and checks the id. `LogFormat` renders one event: a field list per known type, otherwise the whole body; every string goes through `TerminalSafe.escape`.
- `CrashReport` (in `jharness-cli`) writes the report, prunes old ones and keeps nothing but envelopes of recent events (the last 30 lines of the session log, read when the report is written). `Main.call` turns a `RuntimeException` or `Error` into a report and exit 3. Inside an open session it first publishes `session.crashed`. `Main.main` installs a default uncaught-exception handler for other threads.
- `HttpProvider.attempt` publishes `model.response` after the headers arrive, with an allow list of headers.
- New event types `model.response` and `session.crashed`. Decision D-059.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency; picocli's codegen covers the subcommand | Yes |
| II Host owns side effects | Only the host reads logs and writes reports, under `~/.jharness` | Yes |
| III Untrusted | No limit changes. Log text is untrusted output and is escaped before it reaches the terminal | Yes |
| IV No shell | No processes | Yes |
| V Proxy | No network; reports are never sent | Yes |
| VI Strings | No `api` change | Yes |
| VII Safe by default | Reports and logs are 0600 in 0700 folders, reports hold no event bodies, headers are allow-listed, the session argument can't name a path outside the logs folder | Yes |
| VIII Events | `model.response`, `session.crashed` | Yes |
| IX Native first | Plain JDK and picocli; the native `jharness` CI check runs `log` | Yes |
| X Tested | Log listing, timeline, filters, escaping, bad ids and lines, crash report contents and modes, pruning, `session.crashed`, exit 3, `--debug` output, `model.response` headers | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness log` on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A crash report pasted into an issue leaks a prompt or file | No event bodies; home shown as `~`; a note at the top says exception messages can quote files |
| A hostile file's text in the log rewrites the user's terminal when printed | Every control and bidi character is escaped, in `--json` too |
| `log ../../x` reads a file outside the logs folder | Ids are letters, digits and `-` only; a path must be given as a `.jsonl` file the user names |
| A crash while writing the session log crashes the report | Publishing `session.crashed` and writing the report each catch their own failure |
