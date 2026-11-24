# Spec 038 — Action reviewer

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

An optional decision model reviews each state-changing operation before it runs: does it fit the user's request? JHarness asks it through the System One API (`POST /v1/systemone`), whether hosted, such as Jev on TypeSafe or DigitalOcean, or run locally, such as Laya. The reviewer can only tighten: an unsure answer makes an allowed operation ask, and a low one denies it. A failing reviewer never allows. Decision D-031.

## Roadmap items

- Optional action reviewer: a System One API decision model (Jev, or a local equivalent such as Laya); only tightens; fails to ask (D-031)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.8 | As a security-minded user, I want an optional reviewer model to flag actions that don't fit my task, so off-task or injected actions get stopped for my review. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `reviewer` in the user's settings has `enabled` (default false), `url`, `model` (default `jev-latest`), `apiKeyEnv`, `askBelow` (0.90), `denyBelow` (0.20), `timeoutMs` (3000, 500 to 60000) and `onError` (`ask` or `deny`). Unknown keys and bad values stop start-up; `url` is needed when enabled; a named key that isn't set stops start-up. A project's `reviewer` is always ignored. |
| AC-02 | For each operation it reviews, JHarness sends `model`, a `state` with the main agent's latest request, the calling agent, a subagent's task, the workspace name and the action (api, kind, summary, detail with long fields cut), and one `noul` question named `fits`. A key is sent as `Authorization: Bearer`. |
| AC-03 | Every state-changing operation that plan mode doesn't deny is reviewed, before the permission mode and saved rules; reads, events and session state are not. At or above `askBelow` the decision is unchanged; below it the user is asked, with the reviewer's reason shown, even in bypass or accept-edits mode or with a saved rule; below `denyBelow` it is denied without asking. |
| AC-04 | A status other than 200, no answer within `timeoutMs`, an unreachable server, or an answer without a `noul` from 0 to 1 counts as a failure: `onError: ask` asks the user (denied when no one can answer), `deny` denies. It never allows. |
| AC-05 | The reviewer's endpoint is allowed through the egress proxy automatically. `reviewer.ready`, `review.verdict` (api, kind, confidence, outcome) and `review.failed` (api, kind, error, outcome) record it; the key is never logged. The TUI prints a start-up line, saying when the context leaves the machine, and shows refusals and failures. CI's native step checks a refusal against a fake server. |
| AC-06 | The README, D-031 and the tech stack document it. |

## Out of scope

- `choice` or `score` questions, and more than one question per action.
- Reviewing reads, network asks from the proxy, or plan proposals.
- A built-in reviewer model (D-035's helper binary, later).

## Dependencies and assumptions

- D-031, constitution Article VII, the permission engine (D-010, D-041), the egress proxy (D-025).
- The System One API as documented by TypeSafe (`api.typesafe.ai`) and DigitalOcean (`inference.do-ai.run`): answers are under `answers.<name>`, and a `noul` answer is a probability from 0 to 1.

## Open questions

None.
