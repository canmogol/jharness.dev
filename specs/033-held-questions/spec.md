# Spec 033 — Held background questions

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

In an interactive session, a background subagent's approval question waits for the user instead of being denied. A notice appears above the prompt, `/agents` shows the subagent as `waiting`, and `/switch NAME` asks the question. After a time limit, or when the subagent is stopped, the question is denied as before. Decision D-072.

## Roadmap items

- Held background questions (user request in the review of spec 032)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Bring a background subagent's question to my attention instead of denying it | User request, 2026-11-24 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | In an interactive session with `agent.backgroundQuestions` set to `wait` (the default), a background subagent's question holds its call: no prompt appears and only that subagent pauses. `subagent.waiting` records the question's `api` and `summary`. |
| AC-02 | The TUI prints `▸ [NAME] is waiting for you: SUMMARY (/switch NAME to answer)` above the prompt, even at `/events none`. `/agents` and `api.agents()` show the status `waiting`. |
| AC-03 | `/switch NAME` asks the held question at once, and the answer decides it (`subagent.wait_ended` with `asked`). |
| AC-04 | After `agent.backgroundQuestionWait` (10s to 1h, default 10m) the question is denied with a reason saying no one answered (`timeout`), and the TUI says so. Stopping the subagent ends the wait at once (`stopped`). |
| AC-05 | `agent.backgroundQuestions: "deny"`, `-p` and sessions without a terminal deny at once, as in D-065. Bad values for either setting are refused. |
| AC-06 | The README and D-072 document it. |

## Out of scope

- Network questions from the proxy while no turn runs.
- Several held questions shown in one list.

## Dependencies and assumptions

- Specs 027 and 032.

## Open questions

None. Recommended options accepted while the user was away.
