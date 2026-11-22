# Plan 017 — Git network operations and offline mode

Spec: `spec.md` · Status: Done · Created: 2026-11-22

## Approach

`GitOperations` gets the four network ops. They use the same hardening prefix and filter overrides as today, plus a network prefix (AC-03), a check of the repository's config (AC-04) and a check of the remote's URL. They run with `Grants(network)` and a `GIT_CONFIG_*` environment that carries the proxy and any credentials. `ProcessRunner` allows the `GIT_CONFIG_*` and `GIT_ASKPASS` names and redacts a list of secrets instead of one.

Offline mode is one boolean, `offline`, passed in three places:
- to `NetworkRules`: unmatched connections are denied;
- to `BuildOperations`: the offline flags are added;
- to `GitOperations`: network ops are refused.

`Session` filters the automatic model endpoints, records `offline` in `session.started` and gives each piece the flag. Decision D-055.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process kinds; git is an existing allowed tool | Yes |
| II Host owns side effects | Typed ops only; the host builds every argument; results are JSON strings | Yes |
| III Untrusted | No isolate or limit change; time in git doesn't count against the run's wall clock, as today | Yes |
| IV No shell | git through `ProcessRunner` with typed argv; no `sh -c`; no askpass, helper or ssh program can run | Yes |
| V Proxy | git reaches the network only through the proxy port in the OS sandbox; offline mode only tightens the rules | Yes |
| VI Strings | Requests and results are JSON strings | Yes |
| VII Safe by default | pull, push and clone ask, and plan mode denies them; no force push; tokens only from user settings, only to the mapped host, redirects off, redacted from output and events | Yes |
| VIII Events | `process.started` and `process.completed` record network use; permission and network events as today; `session.started` gets `offline` | Yes |
| IX Native first | No new dependency; the native smoke binary runs a clone through the proxy | Yes |
| X Tested | Tests against a local HTTPS git server through the real proxy and sandbox, plus hostile-config, credential and offline tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
// jharness-process
public GitOperations(ProcessRunner runner, Executable git, Workspace ws, Duration timeout, Duration max,
        boolean offline, Map<String, String> credentials);   // host -> token, already looked up
ops: fetch (GIT_READ), pull, push, clone (GIT_WRITE)
NETWORK = [-c protocol.https.allow=always, -c protocol.{file,git,ssh,http,ext}.allow=never,
           -c http.followRedirects=false, -c http.cookieFile=]
env += GIT_ASKPASS="", GIT_CONFIG_COUNT=n, GIT_CONFIG_KEY_i/VALUE_i:
       http.proxy=http://u:p@127.0.0.1:<port>, http.proxyAuthMethod=basic,
       http.https://<host>/.extraHeader=Authorization: Basic <b64>
clone: cwd = workspace root, no GIT_WORK_TREE
BuildOperations(..., boolean offline): mvn -o, gradle --offline, npm_config_offline=true

// jharness-net
NetworkRules(general, project, trusted, automatic, boolean offline)

// jharness-config
Settings: + boolean offline, + GitSettings git (credentials: host -> secret name)
layered(): project "git" dropped (ignored), untrusted project keeps "offline": true

// jharness-cli
--offline -> session layer {"offline": true}
```

## Checks

- [x] `mvn install` on Linux (bubblewrap)
- [x] `mvn test` on macOS
- [x] Native smoke binary on Linux and macOS, including the git clone check
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A repository's config runs a program or redirects credentials | AC-04 refuses the known keys; command-line `-c` values beat repo config; the sandbox has only the proxy port |
| A token goes to the wrong host through a redirect or `insteadOf` | `extraHeader` is scoped to `https://<host>/`, redirects are off, and the push prompt shows the resolved URL |
| A token leaks in output | It is redacted from captured output and `process.completed`; git doesn't print headers without `GIT_TRACE`, which isn't passed |
| git's TLS or proxy behaviour differs on macOS (Apple git 2.39) | Tests run on both; the native smoke clones on both |
| Seatbelt now and then refuses a connect to the allowed proxy port with EPERM (about 3% of fresh runs on macOS 14, with any rule spelling) | git network runs that fail to reach the proxy port are retried up to 3 times; nothing was sent. The same flake can hit build tools (pre-existing, spec 013) |
| An HTTPS git server in tests is heavy | `git http-backend` as a CGI from `HttpsServer`; tests are skipped when git or a sandbox is missing |
