# Spec 060 — Tool update notices and write hints

Status: Done · Phase: P3 · Created: 2026-11-26

## Summary

A user whose `~/.jharness/tools/` came from an older JHarness never got `go.json`, and the model spent several steps guessing why Go couldn't write its cache. Two changes. At the start, JHarness names the shipped tools the user lacks and the ones whose file differs from the shipped one, once per shipped version, with the `jharness tools reset NAME` command. When a tool's run fails and its output reads like a refused write, `api.tool`'s result gets a `hint`: where the tool may write, and what to tell the user. Decision D-093.

## Roadmap items

- Tool update notices and write hints (user report, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | After updating JHarness, I want to know about new or changed shipped tools without reading the changelog | User report, 2026-11-26 |
| — | When a tool can't write somewhere, the model should tell me why instead of guessing | User report, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | When a session opens and `~/.jharness/tools/` exists, each shipped tool that is missing from it, or whose file differs from the shipped one, is reported once per shipped version. `~/.jharness/tools-notified.json` (0600) keeps the shipped file's SHA-256 per tool. A first start, which writes every shipped tool, reports nothing. |
| AC-02 | The TUI prints the lines under the banner (dim); `-p` prints them on stderr with `jharness: `. Missing: "This JHarness ships a tool you don't have in ~/.jharness/tools/: go. Add it with: jharness tools reset go". Different: "Your ~/.jharness/tools/maven.json differs from the one this JHarness ships (jharness tools show maven); jharness tools reset maven writes the shipped one in its place". A `tools.updates` event lists both. |
| AC-03 | When a tool run exits non-zero and its output contains "read-only file system", "operation not permitted" or "permission denied", `api.tool`'s JSON gets a `hint`. The hint names the workspace (except `.jharness/`), the private temp folder and the tool's writable folders, says no api call changes that, and names `jharness tools show NAME` and `jharness tools reset NAME` for the user. Other failures and successful runs get no hint. |
| AC-04 | Tests: notices once per version and the state file; the TUI and `-p` lines; the hint for a fake result and for a real Go test in the OS sandbox writing to the home. README, D-093, Q102, roadmap. |

## Out of scope

- Updating the user's files without asking.
- Hints for network refusals (the proxy already names the host).

## Dependencies and assumptions

- Specs 028 (tool definitions), 059 (the Go tool).

## Open questions

None. The user asked for the suggested design on 2026-11-26.
