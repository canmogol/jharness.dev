# Spec 016 — `api.http` and web fetch

Status: Done · Phase: P2 · Created: 2026-11-22

## Summary

Agent code can make HTTP requests with `api.http` and read web pages as text with `api.fetch`. Both use the one `HttpClient` from `HttpClients`, so every request goes through the egress proxy and the network rules (spec 011). GET and HEAD are reads; POST, PUT, PATCH and DELETE ask for approval like a file write. Responses are capped and come back as text, and HTML pages are turned into Markdown-like text with jsoup. `HttpClients` moves to `jharness-net` so the `api` facade can use it. Decision D-054.

## Roadmap items

- `api.http` and web fetch

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.1 | The agent finds and fixes a bug | `user-stories.md` (reading library docs and issue pages while fixing) |
| US-3.3 | The proxy enforces the network rules for every client and tool | `user-stories.md` |
| US-3.1 | State-changing actions ask first | `user-stories.md` (HTTP writes) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.http({method, url, headers, body, timeoutMs})` takes a JSON object. `method` is one of GET (the default), HEAD, POST, PUT, PATCH and DELETE. `url` is an absolute `http` or `https` URL with no user info, at most 8,192 characters. `body` is a string of at most 1 MiB, allowed only for POST, PUT, PATCH and DELETE. Unknown fields and bad values fail with a message that names the rule. |
| AC-02 | Request headers: any name and value except `Host`, `Content-Length`, `Connection`, `Transfer-Encoding`, `Upgrade`, `Expect`, `Keep-Alive`, `TE`, `Trailer` and `Proxy-*` (case-insensitive), which are refused. Names must be HTTP tokens, values may not contain CR, LF or NUL, and there are at most 50 headers. JHarness sets `User-Agent: JHarness/<version>` unless the agent sets one. No user secret is added to any request. |
| AC-03 | Every request is sent with the client from `HttpClients`, through the egress proxy. A denied host fails with the proxy's reason, and a host no rule matches is asked about by the proxy as before (spec 011). GET and HEAD follow up to 5 redirects (301, 302, 303, 307, 308), each sent again through the proxy. Other methods return the 3xx response as is. `http`→`https` and cross-host redirects are allowed, since the proxy checks each hop. |
| AC-04 | Permissions: GET and HEAD are `http` operations that need no approval beyond the network rules. POST, PUT, PATCH and DELETE are `http-write` operations. They ask for approval with the method and URL, plan mode denies them, and "always" saves a `<METHOD> <host>` rule (for example `POST api.example.com`) in the user-folder permissions file (D-040). Permission events are emitted as for other kinds. |
| AC-05 | The response is `{status, url, headers, contentType, body, bytes, truncated, binary}`. `url` is the final URL after redirects. `headers` uses lowercase names, with repeated headers joined by `, `. At most `http.maxResponseBytes` (default 10 MiB, 64 KiB to 50 MiB) is read. `body` holds at most `http.maxChars` (default 200,000, 1,000 to 1,000,000) characters, decoded with the response charset or UTF-8. Text types (`text/*`, `application/json`, `application/xml`, `application/javascript` and `+json`/`+xml` types) and responses with no type return text. Others return `binary: true`, `body: ""` and the byte count read. |
| AC-06 | `api.fetch({url, timeoutMs})` sends a GET with `Accept: text/html, text/plain;q=0.9, */*;q=0.1` and follows redirects as in AC-03. For HTML it returns `{status, url, title, text, truncated}`, where `text` is Markdown-like: headings as `#`, links as `[text](absolute-url)`, list items, `pre`/`code` blocks fenced, and no `script`, `style`, `noscript`, `svg`, `nav`, `header`, `footer`, `form` or hidden elements. Other text types return the body as `text`, and binary types fail with "not a text page (<type>)". `text` is capped at `http.maxChars`. Non-2xx statuses still return the page. |
| AC-07 | Timeouts: `timeouts.http` (default 30 s) and `timeouts.httpMax` (default 2 min) in `settings.json`. `timeoutMs` may ask for up to `timeouts.httpMax`. A timeout, or Esc stopping the run, cancels the request and fails the call with "timed out after <n> ms" or "cancelled". The time spent waiting doesn't count against the run's wall clock, as for processes (spec 013). |
| AC-08 | Events: `http.request` `{method, url}` before sending and `http.response` `{method, url, status, contentType, bytes, truncated, durationMs, redirects}` after, with `status` 0 and an `error` field when the request failed. Request and response headers and bodies are never logged. |
| AC-09 | `HttpClients` moves from `jharness-llm` to `jharness-net`; model providers use it from there. The architecture test still allows `HttpClient` construction only in that class, now at its new path. |
| AC-10 | jsoup builds and runs in the native binary on Linux and macOS. The native smoke binary fetches a local test page with `api.fetch` (through the proxy, with an `allow` rule for the stub) and gets its title and text. |
| AC-11 | `api.d.ts`, the `api` description the model sees, documents `api.http` and `api.fetch` with their request and result types. The README lists them under the agent's operations. |

## Out of scope

- Secrets or user tokens in requests (needs its own decision).
- Cookies and sessions across calls (each call is independent; no cookie jar).
- Streaming responses, WebSockets and HTTP/2-only servers (the client stays on HTTP/1.1).
- Binary bodies returned to the model (images, archives).
- Web search (After v0.1: "Image input and web search").
- Offline mode (its own roadmap item; it will make the proxy deny by default).

## Dependencies and assumptions

- Spec 011 (egress proxy and rules; hosts with no matching rule are asked about by the proxy).
- Specs 003 and 004 (the `api` facade and the permission engine), spec 013 (process time doesn't count against the wall clock).
- jsoup 1.x has no reflection or resource needs that block Native Image; T001 checks this.

## Open questions

None.
