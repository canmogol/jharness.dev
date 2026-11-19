# Spec 011 — Egress proxy and network rules

Status: Done · Phase: P2 · Created: 2026-11-19

## Summary

Start the host egress proxy of D-025: a small HTTP forward proxy on `127.0.0.1` with per-session credentials that every Java HTTP client goes through, applying the rules in `~/.jharness/network.rules` and `.jharness/network.rules` (D-012), with built-in denies for internal addresses and DNS pinning. Model endpoints are allowed automatically, so model traffic works unchanged. Unmatched hosts ask in the TUI; `/network` lists and edits the rules. Command-line tools are pointed at the proxy in spec 013, after the OS sandbox (spec 012, D-047).

## Roadmap items

- Egress proxy in the host: every HTTP client and command-line tool goes through it
- Rules files `~/.jharness/network.rules` (general) and `.jharness/network.rules` (project); DNS pinning; `/network` commands

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.3 | Allow and deny domains and IP ranges, so the agent only talks to hosts I accept | `user-stories.md` |
| US-3.6 | Every action is an event in the session log | `user-stories.md` (network decisions) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | Rules files parse `default ask|allow|deny`, `allow X` and `deny X`, where X is an exact host, `*.suffix`, an IPv4 or IPv6 address, or a CIDR, each with an optional `:port`; `#` starts a comment. A bad line stops start-up with the file, line number and reason. A missing file is empty. |
| AC-02 | Evaluation follows D-025: built-in denies (loopback, private, link-local, unique-local, metadata) apply unless an `allow` line names the address explicitly (exact host or IP/CIDR, not a wildcard or the default); the project file is checked first (all lines if trusted, only `deny` lines if not); then the general file; within a file `deny` beats `allow`; no match falls to the `default` (project's when trusted), else `ask`. |
| AC-03 | The proxy listens on `127.0.0.1` on a random port and requires `Proxy-Authorization` with the session's random credentials; without them it answers 407. |
| AC-04 | For `CONNECT host:port` and absolute-form `http://` requests, the proxy resolves the host itself, checks every resolved IP, connects to a checked IP (DNS pinning), and blocks (403) when any resolved IP is denied. HTTPS is tunnelled without decryption; plain HTTP responses, including streams, are relayed as they arrive. |
| AC-05 | Each decision emits `network.allowed`, `network.blocked` or `network.asked` with host, port, IP and the rule or reason. |
| AC-06 | `ask` asks through the `Prompter`: `y` allows that `host:port` for the rest of the session, `a` also appends `allow host:port` to the project's `.jharness/network.rules` (D-047), `n` blocks. Headless runs block. |
| AC-07 | `HttpClients` builds every client with the proxy and its credentials; the host and port of every model profile's endpoint are allowed automatically. A turn against the stub model server works through the proxy. |
| AC-08 | The architecture test allows sockets only in `jharness-net` and `HttpClients`; a test shows a direct connection attempt from another module fails the build check. |
| AC-09 | `/network list` shows both files, the built-in denies and the session's allowed hosts; `/network allow|deny|remove X [--project]` edits the general file, or the project file with `--project` (trusted projects only), and reloads; `/network reload` re-reads both files. |

## Out of scope

- Pointing git, Maven, Gradle and npm at the proxy (spec 013).
- The OS sandbox that limits child processes to the proxy port (spec 012).
- `api.http` and web fetch; git `fetch`, `pull`, `push`, `clone` (D-039 stays).
- Offline mode (D-033).

## Dependencies and assumptions

- Specs 002 (HttpClients, events, architecture test), 004 (Prompter), 006 (trust), 009 (TUI commands).
- The JDK disables Basic auth for proxy tunnels by default; the host sets `jdk.http.auth.tunneling.disabledSchemes` to an empty value before the first client is built.

## Open questions

None.
