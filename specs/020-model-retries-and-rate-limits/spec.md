# Spec 020 — Model retries and rate limits

Status: Done · Phase: P2 · Created: 2026-11-23

## Summary

Model profiles exist since Phase 1 (D-032), but one failed request ends the turn: a 429 from Claude, an overloaded API, a local server that is still starting, a connection reset. This spec adds retries to both providers. A request that fails before anything reached the user is sent again, after the server's `retry-after` or an exponential back-off. Each profile sets how many retries, the longest wait, and how long to wait for a response to start. Esc ends a wait at once, and each retry is an event and a line in the UI. Decision D-058.

## Roadmap items

- Model profiles; retries and rate-limit handling

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-1.3 | Use Claude or any OpenAI-compatible server | `user-stories.md` (profiles keep working through rate limits and restarts) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | A profile takes `retries` (0 to 10, default 4), `retryMaxWait` (1s to 10m, default 60s) and `responseTimeout` (5s to 30m, default 120s, the old fixed value). Other values stop start-up with the key named. |
| AC-02 | Retried: HTTP 408, 409, 429 and every 5xx (529 included); a failed connection (refused, reset, connect timeout, a proxy tunnel that got 502, 503 or 504); and, before any text or thinking was streamed, an Anthropic `overloaded_error`, `rate_limit_error` or `api_error` event or a broken stream. An `x-should-retry` header from the server overrides the status rule. |
| AC-03 | Never retried: other 4xx (400, 401, 403, 404, 413, 422 and the rest); the response timeout; a tunnel the egress proxy refused (403, 407); a missing API key; cancellation; any failure after text or thinking reached the user. |
| AC-04 | The wait is `retry-after-ms`, else `retry-after` (seconds or an HTTP date). Without one, it is 1 s, 2 s, 4 s and so on, capped at `retryMaxWait`, each cut to a random 50–100%. A server that asks for longer than `retryMaxWait` fails at once, with the wait it asked for in the message. |
| AC-05 | After the last attempt the error says `(gave up after N attempts)`. `model.error` is published once, at the end; a retry that then succeeds publishes none. |
| AC-06 | Each retry publishes `model.retry` (`provider`, `model`, `profile`, `attempt`, `attempts`, `waitMs`, `reason`, `message`), and each attempt its own `model.request` with `attempt`. The UI shows `▸ HTTP 429 from anthropic, retrying in 7 s (attempt 2 of 5)` unless events are `none`. |
| AC-07 | Esc during a wait ends it at once and the turn ends as `interrupted`. |
| AC-08 | The README documents the profile keys, and D-058 the design. |

## Out of scope

- Reading rate-limit budget headers to slow down before a 429.
- Falling back to another profile when one keeps failing.
- Retrying tool calls (`api.http`, processes); spec 019 covers the proxy refusal for processes.

## Dependencies and assumptions

- Model requests go through the egress proxy (spec 011), so a refused host shows up as a 403 tunnel or response, which is never retried.
- The JDK reports a refused tunnel as `Tunnel failed, got: <code>`; a test pins this.

## Open questions

None.
