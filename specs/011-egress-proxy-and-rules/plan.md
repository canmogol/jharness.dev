# Plan 011 — Egress proxy and network rules

Spec: `spec.md` · Status: Draft · Created: 2026-11-19

## Approach

A new module `jharness-net` (depends on `jharness-events` and `jharness-security`) holds the rules parser and evaluator, and `EgressProxy`. `Session.open` starts the proxy with the rules for the workspace and trust state, plus the model endpoints as automatic allows, and hands its address and credentials to `HttpClients.useProxy`. The TUI gets `/network` and shows network questions through the existing `Prompter`. Decisions D-012, D-025, D-047.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | The proxy runs inside the host process; no new process | Yes |
| II Host owns side effects | Network access and rules-file writes are host code; agent code still can't write `.jharness/**` | Yes |
| III Untrusted | No change to the sandbox | — |
| IV No shell | No new process | — |
| V Proxy | This is the proxy: every `HttpClient` goes through it; sockets only in `jharness-net` and `HttpClients` (architecture test) | Yes |
| VI Strings | No boundary change | — |
| VII Safe by default | Shipped default `ask`; built-in denies; untrusted projects only add denies; per-session credentials | Yes |
| VIII Events | `network.allowed`, `network.blocked`, `network.asked` | Yes |
| IX Native first | No new dependency; JDK sockets and virtual threads, checked by the native build in CI | Yes |
| X Tested | Parser, evaluator, proxy (CONNECT, HTTP, 407, DNS pinning, rebinding), ask and architecture tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

### Rules

```java
record Rule(Action action, Target target, int port /* 0 = any */, String file, int line) {}
sealed interface Target { record Host(String name) {} record Suffix(String suffix) {} record Cidr(InetAddress base, int bits) {} }
record RulesFile(Path path, Optional<Action> defaultAction, List<Rule> rules) { static RulesFile load(Path p); }

final class NetworkRules {
    NetworkRules(RulesFile general, RulesFile project, boolean trusted, List<Rule> automatic);
    Decision decide(String host, int port, InetAddress ip);   // per resolved IP
    record Decision(Action action, String reason) {}           // ALLOW, DENY, ASK
}
```

`*.github.com` matches subdomains, not `github.com`. An IP or CIDR rule matches the resolved IP (or a literal IP host). Automatic allows (model endpoints) are explicit `allow` rules in the general layer. Built-in denied ranges: `127.0.0.0/8`, `::1`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16` (includes `169.254.169.254`), `fc00::/7`, `fe80::/10`, `0.0.0.0/8`, `100.64/10`.

### Proxy

```java
public final class EgressProxy implements AutoCloseable {
    static EgressProxy start(NetworkRules rules, Prompter prompter, EventBus bus, Supplier<Origin> origin, Path projectRules);
    InetSocketAddress address();
    PasswordAuthentication credentials();      // user "jharness", random 32-byte token
    void reload(NetworkRules rules);
}
```

- `ServerSocket` on `127.0.0.1:0`; a virtual thread per client connection; request head up to 16 KB.
- Missing or wrong `Proxy-Authorization: Basic` → `407` with `Proxy-Authenticate: Basic realm="jharness"`.
- `CONNECT`: resolve, decide each IP, connect to the first, reply `200`, copy both ways on two virtual threads.
- Absolute-form HTTP: same decision; one request per client connection; the request line is rewritten to origin form, `Proxy-*` headers dropped, `Connection: close` sent both ways; request bodies with `Content-Length` only (else `411`); the response is copied as it arrives.
- Session allows from `y` answers are kept in a set of `host:port`.

### Wiring

- `HttpClients.useProxy(InetSocketAddress, PasswordAuthentication)` sets the `ProxySelector` and an `Authenticator` that answers only proxy requests; `Main` sets `jdk.http.auth.tunneling.disabledSchemes=""` first.
- `Session.open` builds `NetworkRules` from `~/.jharness/network.rules`, `<workspace>/.jharness/network.rules`, trust, and every profile's endpoint host and port; closes the proxy on `close`.
- New `Operation.Kind.NETWORK`; `TerminalPrompter` shows `Allow network: connect host:port?`.
- `/network list|allow|deny|remove|reload` in `Tui`; edits append or remove lines and call `reload`.

## Checks

- [ ] `mvn -pl jharness-net -am test`
- [ ] `mvn verify`
- [ ] By hand: a turn against LM Studio through the proxy; `/network list`

## Risks

| Risk | Response |
| --- | --- |
| The JDK refuses Basic auth on CONNECT | Set `jdk.http.auth.tunneling.disabledSchemes` empty at start-up; a test covers HTTPS through the proxy |
| A pooled client connection is reused after the proxy closed it | `Connection: close` on plain HTTP responses; CONNECT tunnels stay open until either side closes |
| The model endpoint is on a private address (LM Studio on the LAN) | It is an explicit automatic allow, so the built-in denies don't block it |
