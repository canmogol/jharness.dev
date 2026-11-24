# Plan 048 — Web session viewer

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- `jharness-web` (depends on `jharness-events`):
  - `EventFeed`: a capped backlog and non-blocking listeners, in order.
  - `LogTail`: reads a log and polls for complete appended lines.
  - `SessionViewer`: `HttpServer` on the loopback address; token, `Host`, `Origin` and method checks in `refused`; security headers; embedded files; `/events` as Server-Sent Events with a per-client queue and pings.
  - Page: `index.html`, `app.js`, `app.css`, in the native image's resources.
- CLI: `LiveFeed` (this run's events from the log, then live ones, each once); `ViewCommand` (`jharness view`); `/view` in the UI, closed when it quits; the `/view` help line.
- Architecture test: scans `jharness-web` and allows `SessionViewer`'s socket.
- Tests: `SessionViewerTest` (token, headers, 403, 404, 405, Host and Origin, SSE backlog and live, tail, no HTML sinks, cap); `ViewTest` (`jharness view` over HTTP, a bad session, `LiveFeed` order and chunks); `TuiTest.viewPrintsTheAddress`; CI's native step.
- D-083, Q92, tech stack, plan, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | The JDK's server, embedded files; the native build includes them | Yes |
| II Host owns side effects | The viewer only reads events; no `api` change | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts; the browser isn't opened | Yes |
| V Proxy | It opens no outbound connection; its loopback listening socket is the one exception D-053 and D-083 accept, and the architecture test pins it | Yes |
| VI Strings | Nothing crosses the isolate boundary | Yes |
| VII Safe by default | Loopback only, token, Host and Origin checks, GET only, strict CSP, text-only rendering | Yes |
| VIII Events | It reads the event stream; no new action | Yes |
| IX Native first | CI's native step serves and fetches the page and stream | Yes |
| X Tested | Every check above, both ways in, the page's sinks | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness view` checks on Linux (CI step); TUI pseudo-terminal check
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Another local user reads the session | They need the token, printed only in your terminal |
| A web page in the browser probes the port | No token, and `Origin` and `Host` checks; the CSP and `no-store` stop reuse |
| Event text with markup | Set with `textContent` only; tested |
