# Spec 048 — Web session viewer

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

A read-only view of a session in the browser, served by the binary on `127.0.0.1`: `jharness view [SESSION]` for a saved or running session's log, `/view` in the UI for the running session live. It reads the same event stream as `--output stream-json`. Decisions D-053 and D-083.

## Roadmap items

- Read-only web session viewer on `127.0.0.1`, reading the same event stream as headless mode; no approvals from the browser; doesn't gate v0.1 (D-053)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | As a security-minded user, I want a log of everything the agent did, so I can audit a session. | `user-stories.md` |
| US-2.3 | As a developer, I want to see every change as a diff, so I know exactly what the agent did. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | The viewer listens on `127.0.0.1` only, in a new `jharness-web` module; the architecture test allows its socket and no other. |
| AC-02 | Every request needs the 256-bit token from the printed address, a `Host` of `127.0.0.1:PORT` or `localhost:PORT`, and a matching `Origin` when one is sent; otherwise 403. Only `GET` (else 405); unknown paths 404; at most 8 viewers. |
| AC-03 | Responses carry a CSP of `default-src 'none'` with only the page's own script, style and stream, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `nosniff` and `no-store`. |
| AC-04 | `/events` is Server-Sent Events of the stream-json lines: the backlog (the last 100,000), then live lines; a browser 20,000 lines behind is disconnected. |
| AC-05 | `jharness view [SESSION]` (default `last`; `--port`) follows the log until Ctrl-C and prints the address; a bad session exits 2. `/view` serves the running session, chunks included, prints the same address on a second call, and stops with the UI. |
| AC-06 | The embedded page shows turns, prompts, streamed and complete replies, steps, approvals, diffs, subagents, compaction and todos; thinking and every event on request; a filter by agent. It sets event text only as text; a test checks for HTML sinks. |
| AC-07 | The README, tech stack, plan and D-083 document it; CI's native step checks the page, the stream, a missing token, a wrong `Host` and a `POST`. |

## Out of scope

- Approvals, prompts or any change from the browser (D-053's later companion UI).
- Access from other machines except through an SSH port forward.
- Opening the browser.

## Dependencies and assumptions

- stream-json lines (D-082), session logs (D-038), `SessionLogs.resolve` (spec 021).

## Open questions

None.
