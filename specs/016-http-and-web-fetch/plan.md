# Plan 016 — `api.http` and web fetch

Spec: `spec.md` · Status: Done · Created: 2026-11-22

## Approach

First, check jsoup under Native Image, then move `HttpClients` to `jharness-net` (package `dev.jharness.net.http`). `jharness-llm` and `jharness-sandbox` depend on `jharness-net`. New classes in `jharness-net`:
- `HttpOperations` checks requests, applies the header rules, sends through `HttpClients.create()`, follows redirects by hand, reads with caps and emits the events.
- `PageText` turns HTML into Markdown-like text with jsoup.

`HostApi` gets `http` and `fetch`. They go through the permission gate as kinds `HTTP` and `HTTP_WRITE`, pause the wall clock while waiting (as processes do, spec 013), and are cancelled by Esc. Decision D-054.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process or server; jsoup is compiled in | Yes |
| II Host owns side effects | Requests are made by host code only; JS gets a string result; every call goes through the permission gate | Yes |
| III Untrusted | No policy, isolate or limit change; the wall clock pauses while the host waits, as for processes, and the HTTP timeout bounds each call | Yes |
| IV No shell | No process | — |
| V Proxy | Every request uses the client from `HttpClients`, which always goes through the egress proxy; the architecture test keeps it the only builder | Yes |
| VI Strings | Requests and results are JSON strings | Yes |
| VII Safe by default | POST, PUT, PATCH and DELETE ask; plan mode denies them; no secrets are added to requests; headers and bodies stay out of the log | Yes |
| VIII Events | `http.request` and `http.response` for every call, plus the usual permission events | Yes |
| IX Native first | jsoup is checked in the native binary on Linux and macOS before adoption (T001); the smoke binary uses it | Yes |
| X Tested | Tests for request checks, header rules, redirects, caps, binary detection, approvals and plan mode, events, timeouts and the converter; a test that requests to a denied host fail at the proxy | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
// jharness-net: dev.jharness.net.http
public final class HttpClients { ... }            // moved unchanged from jharness-llm

public final class HttpOperations {
    public HttpOperations(EventBus bus, Supplier<Origin> origin, HttpSettings settings, Timeouts timeouts);
    public static Operation.Kind kind(ObjectNode req);   // HTTP for GET/HEAD, HTTP_WRITE otherwise
    public Request check(ObjectNode req);                // AC-01, AC-02; throws OperationException
    public ObjectNode send(Request r);                   // AC-03, AC-05, AC-07, AC-08
    public ObjectNode fetch(ObjectNode req);             // AC-06: send(GET) + PageText
}
record Request(String method, URI uri, Map<String, String> headers, String body, Duration timeout) {}

public final class PageText {                        // jsoup
    static Result of(String html, URI base);          // {title, text}
}

// jharness-config
record HttpSettings(long maxResponseBytes, int maxChars)   // "http": {"maxResponseBytes":..., "maxChars":...}
// Timeouts gains http (30 s) and httpMax (2 min)

// jharness-security
Operation.Kind: + HTTP, HTTP_WRITE (changesState); ProjectRules key "<METHOD> <host>"

// jharness-sandbox: HostApi
@HostAccess.Export public String http(String json);
@HostAccess.Export public String fetch(String json);
```

Sending: `HttpClient.sendAsync` with a body handler that stops reading at `maxResponseBytes` (`truncated: true`). The future is cancelled on timeout or Esc.

Redirects: `HttpClients` keeps `Redirect.NEVER`, and `HttpOperations` follows `Location` itself. 303 turns into GET; 307 and 308 keep the method. At most 5 hops; `redirects` counts them.

Proxy denials: the egress proxy marks its own 403 refusals with `X-JHarness-Proxy: denied`, so a plain-HTTP denial isn't mistaken for a site's 403. A refused HTTPS tunnel shows up as the JDK's tunnel error. Both become "blocked by the network rules: <host>". `HttpOperations` sends nothing when `HttpClients` has no session proxy set, so a missing proxy can never mean a direct connection.

### Settings

| Key | Default | Limit |
| --- | --- | --- |
| `timeouts.http` | 30 s | ≤ `timeouts.httpMax` |
| `timeouts.httpMax` | 2 min | — |
| `http.maxResponseBytes` | 5 MiB | 64 KiB to 50 MiB |
| `http.maxChars` | 100,000 | 1,000 to 1,000,000 |

### Events

| Type | Fields |
| --- | --- |
| `http.request` | `method`, `url` |
| `http.response` | `method`, `url`, `status`, `contentType`, `bytes`, `truncated`, `durationMs`, `redirects`, `error` (on failure) |

## Checks

- [x] `mvn install` on Linux (bubblewrap)
- [x] `mvn test` on macOS
- [x] Native smoke binary on Linux and macOS, including the `api.fetch` check
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| jsoup needs reflection or resources under Native Image | T001 checks it before anything depends on it; if it fails, raise it with the user (in-house converter as the fallback) |
| Fetched pages carry prompt injection | Pages come back as data in a JSON result; writes still ask; the network rules still decide hosts; the injection suite (next roadmap item) adds cases |
| A redirect leads from an allowed host to a private address | Each hop goes through the proxy, which checks the resolved IP against the built-in denies and the rules |
| A large or slow response holds the run | The read cap, the per-call timeout and Esc |
| Moving `HttpClients` breaks imports in `jharness-llm` | A mechanical move in its own task; the full build runs before any new code |
