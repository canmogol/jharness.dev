# Plan 020 — Model retries and rate limits

Spec: `spec.md` · Status: Done · Created: 2026-11-23

## Approach

- `ModelProfile` gets `retries`, `retryMaxWait` and `responseTimeout`; `ModelProfiles` parses and range-checks them. The old seven-argument constructor keeps the defaults.
- `HttpProvider.stream` becomes a loop over `attempt`. `attempt` sends once and throws a `Failure` that carries whether it may be retried, the server's `retry-after` and a short reason. Static rules: `retryableStatus`, `retryableIo`, `retryAfter`, `backoff`.
- `ModelException` gets a package-private `retryable` flag. `AnthropicProvider` sets it for overloaded, rate-limit and API error events. `attempt` only uses it if nothing was streamed yet.
- Waits go through `RetryWait`. The default waits on a monitor that `cancel()` notifies, and tests pass one that records instead. `ModelProviders.create` has an overload that takes it.
- `model.retry` joins the host event types; `Tui` prints it as a step line.
- `StubModelServer.Reply.with(name, value)` adds response headers for the tests. Decision D-058.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Only the host's model client changes | Yes |
| III Untrusted | No limit removed; retries are capped (10) and so is each wait (10 min) | Yes |
| IV No shell | No processes | Yes |
| V Proxy | Requests still go through `HttpClients` and the proxy; a refused tunnel is never retried | Yes |
| VI Strings | No `api` change | Yes |
| VII Safe by default | Defaults: 4 retries, 60 s longest wait. A request is resent only when nothing reached the user, so a reply is never shown twice | Yes |
| VIII Events | `model.retry`; `model.request` carries `attempt`; `model.error` once at the end | Yes |
| IX Native first | Plain JDK; no reflection | Yes |
| X Tested | Rule tests, stub-server tests for both providers, Esc during a wait, the JDK tunnel message pinned | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native smoke on Linux and macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A slow local model is sent the same long prompt again | The response timeout is never retried; set `responseTimeout` per profile instead |
| Retries hide a server that is down | Each retry is a visible line and an event; the defaults give up after about 15 s of back-off |
| A JDK update changes the tunnel message, so proxy refusals get retried | `ProxyWiringTest` pins the message |
| A partial reply is shown, then the request is sent again | Nothing is resent once text or thinking was streamed |
