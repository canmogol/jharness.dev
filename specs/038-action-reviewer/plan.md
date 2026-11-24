# Plan 038 — Action reviewer

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Config: `ReviewerSettings`; `Settings.reviewer`; `layered` drops a project's `reviewer` like `git` and `mcp`.
- Security: `ActionReviewer` (`KEEP`, `ASK`, `DENY` with a confidence and reason); `Caller` marks which agent a decision is for; `PermissionEngine.useReviewer` asks it after the plan check and before the mode and rules. `ASK` copies the operation with a `review` detail and asks the prompter; `DENY` refuses.
- Net: `SystemOneReviewer` posts to the System One endpoint through `HttpClients.create()`, with the total wait bounded by `timeoutMs`. It keeps each agent's latest request from `turn.started` events, builds the state, maps `noul` to the thresholds and publishes `review.*`.
- Agent: `Session` resolves the key as a secret before anything starts, builds the reviewer, allows its endpoint through the proxy and offers `describeReviewer`. `SubagentRunner` marks each subagent's decisions with `Caller`.
- CLI: start-up line; `Reviewer:` line in the approval question; refusals and failures in the step stream.
- Tests: `ReviewerSettingsTest`, `ReviewerGateTest`, `SystemOneReviewerTest` (fake server: request shape, thresholds, every failure), `ReviewerSessionTest` (real session and proxy, main and subagent); CI native step with `fake_systemone_server.py`.
- D-031, tech-stack, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | The reviewer is an optional server the user runs or subscribes to; nothing new ships | Yes |
| II Host owns side effects | The host calls the reviewer; agent JS can't reach or change it | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | The client is built by `HttpClients.create()` and goes through the egress proxy, with the endpoint allowed like a model endpoint | Yes |
| VI Strings | Nothing new crosses the isolate boundary | Yes |
| VII Safe by default | Off by default; only tightens; failures ask or deny; projects can't set or turn it off; the key is a secret | Yes |
| VIII Events | `reviewer.ready`, `review.verdict`, `review.failed` | Yes |
| IX Native first | No new dependency (`java.net.http`); CI runs it natively | Yes |
| X Tested | Settings, gate order, client and failures, session, native | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` reviewer check on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A prompt-injected action talks the reviewer into a high `noul` | It only tightens and is never the only gate; the question says the action is data |
| A slow reviewer slows every change | 3 s default limit, then it asks |
| Jev sends code and diffs off the machine | Off by default; the start-up line and README say so; Laya keeps it local |
| The System One API changes | One small client; failures ask instead of allowing |
