# Plan 019 — Build retry on proxy refusal

Spec: `spec.md` · Status: Done · Created: 2026-11-22

## Approach

- `ProcessRunner.retryingProxyRefusals(executable, timeout, refused, attempt)` runs an attempt, and runs it again while it fails with a refusal, up to `PROXY_ATTEMPTS` (3). It keeps one deadline for all runs, stops when less than 1 s is left, and publishes `process.retried` before each retry.
- `ProcessRunner.proxyRefused(output, port)` matches a refusal line: `EPERM` or "Operation not permitted", and the proxy address with that exact port, on one line.
- `BuildOperations.run` wraps its `runner.run` call in the retry. `GitOperations.runNetwork` replaces its own loop with it, keeping its curl message as a second match.
- `process.retried` joins the host event types. Decision D-057.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | The host repeats a run it already approved; nothing new is reachable | Yes |
| III Untrusted | No limit removed: retries share the call's timeout, and attempts are capped at 3 | Yes |
| IV No shell | Same `ProcessRunner` call, same typed argv | Yes |
| V Proxy | Unchanged: the sandbox still allows only the proxy port | Yes |
| VI Strings | No `api` change | Yes |
| VII Safe by default | A build can print a refusal line on purpose to run up to 3 times, within the same timeout and sandbox; it gains nothing | Yes |
| VIII Events | `process.retried`; every run still emits `process.started` and `process.completed` | Yes |
| IX Native first | Plain Java; no reflection | Yes |
| X Tested | Matcher, budget and event unit tests; a stand-in npm refused twice in the real sandbox | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux (bubblewrap)
- [x] `ProxyRetryTest`, `BuildToolsTest`, `GitNetworkTest` and `SecuritySuiteTest` on macOS (Seatbelt)
- [x] Native smoke on Linux and macOS (3 runs on macOS)
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A real failure is retried, wasting time | The match needs the refusal and the proxy's exact port on one line; tests pin the near misses |
| A retry repeats side effects (a test that writes, a partial install) | Same as the user re-running it, which the README used to ask for; capped at 3 runs and one timeout |
| All 3 runs are refused | The last result returns, with its output; the events show the retries |
