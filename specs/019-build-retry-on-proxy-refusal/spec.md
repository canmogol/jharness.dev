# Spec 019 — Build retry on proxy refusal

Status: Done · Phase: P2 · Created: 2026-11-22

## Summary

On macOS, Seatbelt now and then refuses a sandboxed tool's connection to the proxy port with `EPERM` (spec 017). git already retries; Maven, Gradle and npm failed, and the README told the user to re-run them. JHarness can't retry the connection itself: the tool opens it inside the sandbox, and npm doesn't retry `EPERM`. So when a run fails and its output shows that refusal, JHarness runs the same approved command again, up to 3 runs in all, within the call's timeout. Decision D-057.

## Roadmap items

- Retries of build and git runs that the macOS sandbox refuses at the proxy port (follow-up to spec 017)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.5 | Build tools run in an OS sandbox | `user-stories.md` (builds keep working under Seatbelt) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | A Maven, Gradle or npm run that exits non-zero, didn't time out, and printed a refusal line is run again with the same argv, environment and grants, up to 3 runs in all. A refusal line has `EPERM` or "Operation not permitted" and `127.0.0.1:<proxy port>`, `localhost:<proxy port>` or `127.0.0.1 port <proxy port>` on the same line. |
| AC-02 | A run that succeeds, times out or fails any other way (a file `EPERM`, another port, `ECONNREFUSED`) is never repeated. |
| AC-03 | All runs share the call's timeout: a retry gets only the time left, and starts only if at least 1 s is left. The approval covers every run; nothing is asked again. |
| AC-04 | Each retry emits `process.retried` with `executable`, `attempt`, `attempts`, `reason` (`proxy-connect-refused`) and `remainingMs`. Each run still emits its own `process.started` and `process.completed`. The result is the last run's. |
| AC-05 | git network operations use the same retry, budget and event; their existing curl refusal message still counts. |
| AC-06 | The README says builds and git retry on their own; D-057 records the design. |

## Out of scope

- Finding the cause in Seatbelt. No fix exists in the profile; the research is summarized in D-057.
- Retrying inside the tools (npm doesn't retry `EPERM`; Maven and Gradle may retry on their own, which is fine).
- Linux, where the proxy bridge doesn't hit this. The retry applies on every OS, but a refusal line can't appear there.

## Dependencies and assumptions

- A repeated build is safe: the same operations already run again when the user re-runs them, and the approval was for the operation, not for one run.

## Open questions

None.
