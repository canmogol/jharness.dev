# Plan 002 — Host foundations

Spec: `spec.md` · Status: Draft · Created: 2026-11-18

## Approach

A Maven multi-module build at the repository root, laid out as in `plan.md` §2, with modules added in dependency order: events, config, security, process, sandbox, llm, testkit. Each module has JUnit 5 tests on the JVM. `jharness-testkit` holds the stub model server, fixtures and a `Smoke` main class that the `native` profile builds into a binary; CI runs that binary on all three targets (AC-14). The spike stays under `spike/` untouched.

Answers taken from the user (2026-11-18): plan.md modules plus a native test; `readContext` is a workspace snapshot; providers are tested against a stub server, live tests are manual; session logs go in the user folder (D-038); a permission gate interface; workspace confinement only; local git only (D-039).

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new runtime; git and `rg` are host tool operations | Yes |
| II Host owns side effects | All file, git and search work is host code behind `api`; every call passes the `PermissionGate` | Yes |
| III Untrusted | Implements D-003 and D-004: UNTRUSTED policy, limits from settings, fixed settings rejected, fresh context per run, D-037 watchdog | Yes |
| IV No shell | `ProcessRunner` takes an argv list; executables resolved outside the workspace; git and `rg` only | Yes |
| V Proxy | Model providers connect directly to the configured endpoint until the proxy lands in P2 (D-025). Every `HttpClient` comes from one factory, so the proxy plugs in there; git has no network operations (D-039) | Exception (D-025: proxy in P2) |
| VI Strings | Every `api` method takes and returns `String`; structured data is JSON | Yes |
| VII Safe by default | The default gate denies state-changing calls; API keys only from environment variables, never settings files or events | Yes |
| VIII Events | Every `api` call, permission decision, process, run, limit and model call emits an event | Yes |
| IX Native first | New dependency: Jackson (tree model only). Checked by the native smoke binary on all targets | Yes |
| X Tested | Confinement, argv building, git hardening, `rg` flag blocking, limits and the architecture rule all have tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

### Modules

| Module | Package | Contents |
| --- | --- | --- |
| `jharness-events` | `dev.jharness.events` | `Event` (envelope + Jackson `ObjectNode` body), `EventTypes` (known types and required fields), `EventBus`, `SessionLog`, `Json` helpers |
| `jharness-config` | `dev.jharness.config` | `Settings` loader for `~/.jharness/settings.json`, `SandboxSettings`, `Timeouts`, `ModelProfile`, `ModelProfiles` |
| `jharness-security` | `dev.jharness.security` | `PermissionGate`, `Operation`, `Decision`, `DefaultGate`, `Workspace` (path confinement) |
| `jharness-process` | `dev.jharness.process` | `ProcessRunner`, `Executables`, `ProcessResult`, `GitOperations`, `RipgrepSearch`, `JavaSearch`, `Search` |
| `jharness-sandbox` | `dev.jharness.sandbox` | `IsolateManager`, `AgentIsolate`, `Watchdog`, `RunResult`, `Api` (the facade) |
| `jharness-llm` | `dev.jharness.llm` | `ModelProvider`, `ChatRequest`, `ChatReply`, `OpenAiCompatibleProvider`, `AnthropicProvider`, `Sse`, `http.HttpClients` (the only `HttpClient` factory) |
| `jharness-testkit` | `dev.jharness.testkit` | `StubModelServer`, recorded streams, `TempWorkspace`, architecture test, `Smoke` main |

### Event envelope

```json
{"id":"01J…","ts":"2026-11-18T10:00:00.123Z","session":"s-…","agent":"main","turn":3,"source":"host","type":"api.call", "...": "body fields"}
```

- `id`: time-ordered unique id; `ts`: UTC instant; `source`: `host` or `guest`.
- Host types in 002: `session.started`, `api.call`, `permission.decided`, `process.started`, `process.completed`, `sandbox.run_started`, `sandbox.run_completed`, `sandbox.limit_exceeded`, `sandbox.watchdog`, `model.request`, `model.delta`, `model.completed`, `model.error`.
- Guest types: `agent.*` and `custom.*` only, through `api.emit`; the guest's envelope fields are overwritten by the host.
- Size cap 64 KB, checked on the raw string before parsing. Full process output is held in `process.completed` up to 1 MB and cut off with a note after that.

### Settings (`~/.jharness/settings.json`)

```json
{
  "model": "ollama-qwen",
  "models": {
    "ollama-qwen": { "provider": "openai-compatible", "baseUrl": "http://localhost:11434/v1", "model": "qwen2.5-coder:7b" },
    "claude":      { "provider": "anthropic", "model": "<Claude model id>", "apiKeyEnv": "ANTHROPIC_API_KEY" }
  },
  "sandbox":  { "main": { "sandbox.MaxCPUTime": "30s" }, "subagent": { } },
  "timeouts": { "process": "2m", "rg": "60s" }
}
```

- Providers: `openai-compatible`, `anthropic`. A key named `apiKey` (or any key ending in `Key` other than `apiKeyEnv`) is rejected.
- `sandbox` keys and units follow the spike's `SandboxConfig` (sizes `MB`/`GB`, durations `ms`/`s`/`m`), rewritten under this spec. Fixed: policy, isolate mode, `sandbox.MaxThreads`.
- A missing file means defaults.

### Permission gate

```java
interface PermissionGate { Decision decide(Operation op); }
record Operation(String api, String kind, String summary, ObjectNode detail) {}  // kind: read | write | git-read | git-write | emit
record Decision(boolean allowed, String reason) {}
```

`DefaultGate` allows `read`, `git-read` and `emit`; it denies `write` and `git-write` with "needs approval; approvals arrive with the permissions feature".

### `api` contract (strings in, strings out)

| Method | Argument | Returns |
| --- | --- | --- |
| `readContext()` | — | `{"workspace","os","arch","date","git":{"branch","dirty"} or null,"files":[top-level names]}` |
| `readFile(path)` | path | file text (UTF-8, up to 1 MB, else error) |
| `writeFile(path, content)` | path, text | `{"path","bytes"}` |
| `editFile(path, oldText, newText)` | path, texts | `{"path","replacements":1}`; `oldText` must occur exactly once |
| `listFiles(json)` | `{"path"?,"glob"?,"max"?}` | `{"files":[…],"truncated":bool}` |
| `grep(json)` | `{"pattern","path"?,"glob"?,"ignoreCase"?,"max"?}` | `{"matches":[{"path","line","text"}],"truncated":bool}` |
| `git(json)` | `{"op", …}` (D-039 operations) | `{"exitCode","output","truncated"}` |
| `emit(json)` | event JSON | event `id` |

Errors are thrown as host exceptions with a clear message; the guest sees a JS error. The run result reports a guest error's message and line number.

### Isolate manager

- `IsolateManager.create(agentName, profile)` builds one `Engine` (option `engine.MaxIsolateMemory`, `useSystemProperties(false)`) and checks the isolate mode is `internal` (D-034).
- `AgentIsolate.run(String js)` creates a fresh `Context` (UNTRUSTED, `HostAccess.UNTRUSTED`, limits from settings, output and error caps), binds `api`, evaluates the code on the agent's platform thread, and returns `RunResult(status, value, error, line)`; `status` is `ok`, `error`, `limit` or `cancelled`.
- The watchdog follows D-037: context close at the wall clock, engine close 10 s later, failure event and isolate marked dead 30 s after that; delays in `sandbox` settings (`watchdog.engineCloseAfter`, `watchdog.failAfter`). Time spent in a permission decision is not counted (D-003).

### ProcessRunner

- `Executables.resolve("git")` searches `PATH` at startup, skips entries inside the workspace, and keeps the absolute path.
- `ProcessRunner.run(Executable, List<String> args, Path cwd, Duration timeout)`: clean environment (`PATH` from the resolved tools only, `HOME`, `LANG=C.UTF-8`, `GIT_CONFIG_NOSYSTEM=1`, `GIT_TERMINAL_PROMPT=0`), stdin closed, output read on a virtual thread, process tree killed on timeout (`ProcessHandle.descendants()` then the process), last 30,000 characters returned.
- Git prefix on every call: `-c core.hooksPath=/dev/null -c core.fsmonitor=false -c protocol.allow=never -c protocol.ext.allow=never -c core.pager=cat --no-pager`. Arguments that start with `-` are rejected inside path lists; paths go after `--`.
- `rg`: `rg --json [-i] [-g glob] -e <pattern> -- <path>` for grep, `rg --files [-g glob] -- <path>` for listing; the blocked flags are never added, and patterns or globs can't add flags because they follow `-e` and `-g`.

### Model providers

- `ModelProvider.stream(ChatRequest, Consumer<String> onDelta)` returns `ChatReply(text, stopReason, usage)`.
- OpenAI-compatible: `POST {baseUrl}/chat/completions` with `stream: true`; parse `data:` lines until `[DONE]`.
- Anthropic: `POST https://api.anthropic.com/v1/messages` (base URL configurable for tests), headers `x-api-key` and `anthropic-version: 2023-06-01`, `stream: true`; parse `content_block_delta` and `message_delta`, and handle `error` events.
- Every HTTP client comes from `HttpClients.create()`; it is where the P2 `ProxySelector` goes.

## Checks

- [ ] `mvn -B -ntp verify`
- [ ] `mvn -B -ntp -Pnative -pl jharness-testkit -am package -DskipTests && jharness-testkit/target/jharness-smoke`

## Risks

| Risk | Response |
| --- | --- |
| `rg` is not installed on the development Mac | Fallback is tested locally; CI installs ripgrep and tests the `rg` path |
| Jackson needs reflection config in the native image | Tree model only; the smoke binary proves it on every target |
| A process tree escapes the kill on timeout | Kill descendants first, then the parent. Tested with a test-only `Executable` for a fixture script that starts a child `sleep`; production code can only resolve git and `rg` |
| Watchdog engine close leaves an agent unusable | The isolate is marked dead; the manager builds a new one and emits an event |
| Live provider behaviour differs from recorded streams | Live tests run when keys or `OLLAMA_URL` are set; results noted in the PR |
