# Spec 021 — Debug mode, `jharness log` and crash reports

Status: Done · Phase: P2 · Created: 2026-11-23

## Summary

The last Phase 2 item: tools to find out what went wrong. `--debug` shows every event and the model's thinking as it happens and prints stack traces. `jharness log` lists a project's sessions and prints one as a readable timeline, so a session can be audited without reading JSON (US-3.6). A host bug no longer ends with a bare message: JHarness writes a crash report to `~/.jharness/crash/`, which never leaves the machine. Each model response is logged with its status, request id and rate-limit headers, so a rate-limit problem can be traced. Decision D-059.

## Roadmap items

- `--debug`, `jharness log <session>`, local crash reports

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | A log of everything the agent did, so I can audit a session | `user-stories.md` (`jharness log <session>` shows it) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `jharness --debug` shows every event inline and the model's thinking, as `/events all` and `/thinking` do; `/events` and `/thinking` still change both. With `-p`, every logged event also goes to stderr as one JSON line. A host error prints its stack trace to stderr. |
| AC-02 | Each model response publishes `model.response` (`provider`, `model`, `attempt`, `status`, `durationMs` to the headers). It carries `requestId` when the server sent `request-id` or `x-request-id`, and `headers` with only these: `retry-after`, `retry-after-ms`, `x-should-retry`, `content-type`, `anthropic-ratelimit-*` and `x-ratelimit-*`. No other header is logged. |
| AC-03 | `jharness log` lists the sessions of the current workspace (`--cwd` works), newest first: id, start time, model, turns, events and errors. `--limit N` (default 20) and `--all` (every project, with its workspace) change the list. No sessions prints a short note. |
| AC-04 | `jharness log SESSION` prints one event per line: local time, turn, type and the type's main fields. SESSION is an id, a unique prefix of one, `last`, or a path to a `.jsonl` file. `--type PREFIX` (repeatable) keeps matching types; `--full` prints whole bodies instead of fields cut to 120 characters; `--json` prints the log lines as they are. |
| AC-05 | Text from the log never reaches the terminal as control sequences: in both outputs, C0 and C1 control characters, DEL and Unicode bidi controls are shown escaped (`\e`, `\n`, `‮`). |
| AC-06 | A SESSION that isn't a path must be letters, digits and `-` (at most 64); anything else, an unknown id or an ambiguous prefix exits 2 with a message (an ambiguous prefix lists the matches). A line that isn't JSON is shown as `line N: not JSON` and the rest still prints. |
| AC-07 | An unexpected exception or error in the host writes a crash report, prints `jharness: internal error (...); crash report: PATH` and exits 3. Settings, usage and I/O errors keep exit 2. An exception on another thread also writes a report and prints the line; the session goes on. |
| AC-08 | A crash report is a text file in `~/.jharness/crash/` (folder 0700, file 0600): version, time, OS, runtime, thread, session id and log path, the stack trace with its causes, and the time, turn and type of the last 30 events, with no event bodies. The home folder shows as `~`. It starts with a note to check it before sharing. Only the newest 20 reports are kept. Nothing is sent anywhere. |
| AC-09 | When a session is open, the crash is logged as `session.crashed` (`exception`, `report`) before the session closes. |
| AC-10 | The README, the help text and D-059 document all of this; the native `jharness` check in CI lists and prints a session. |

## Out of scope

- Uploading crash reports or any telemetry (never; D-033).
- Keeping a session alive after a crash in the main thread.
- Escaping control characters in the TUI's live output.
- Following a log as it grows (`tail -f` the file works).

## Dependencies and assumptions

- Session logs are `~/.jharness/sessions/<project-hash>/<session-id>.jsonl` (D-038); ids are `s-<base-36 ms>-<12 hex>`, so they sort by start time.
- `model.completed` already carries the reply and thinking (D-046), so `jharness log` has what the TUI showed.

## Open questions

None. Recommended options accepted while the user was away.
