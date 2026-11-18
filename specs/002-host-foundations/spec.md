# Spec 002 — Host foundations

Status: Draft · Phase: P1 · Created: 2026-11-18

## Summary

Build the host modules the agent loop stands on: the event bus and session log, settings, a permission gate and workspace confinement, `ProcessRunner` with git and `rg`, the isolate manager with the `api` facade, and the OpenAI-compatible and Claude model providers with model profiles. Every module is tested on the JVM, and a native integration test proves the stack under Native Image on all three targets. No agent loop, CLI or TUI yet; those come in the next specs.

## Roadmap items

- Model providers: OpenAI-compatible (local models through Ollama or llama.cpp), Claude (native) (D-035)
- Isolate manager: one Engine per agent, fresh Context per run, limits, watchdog
- `api`: readContext, readFile, writeFile, editFile, listFiles, grep, git, emit
- `ProcessRunner` with git and `rg` (search), Java search fallback when `rg` is missing
- Event bus and JSONL session log

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-1.2 | Run a local model through Ollama or llama.cpp | `user-stories.md` |
| US-1.3 | Use Claude or any OpenAI-compatible server, chosen by profile | `user-stories.md` (profile resolution only; `--model` and `/model` come with the CLI) |
| US-3.4 | Agent code has no shell and runs under limits | `user-stories.md` |
| US-3.6 | Every action is an event in the session log | `user-stories.md` (the log; `jharness log` comes later) |
| US-2.6 | Inspect history and commit through the agent | `user-stories.md` (local git operations, D-039) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `mvn verify` builds the modules `jharness-events`, `-config`, `-security`, `-process`, `-sandbox`, `-llm` and `-testkit` and runs their tests on the JVM. An architecture test fails the build if `ProcessBuilder` or `Runtime.exec` is used outside `jharness-process`, or an `HttpClient` or `Socket` is created outside the HTTP factory in `jharness-llm`. |
| AC-02 | The host stamps every event with the envelope `id`, `ts`, `session`, `agent`, `turn`, `source` and `type`, validates it by type, and rejects an event over 64 KB. Guest events may only use the `agent.*` and `custom.*` types; anything else is rejected with an error. Subscribers get events in publish order. |
| AC-03 | Every published event is appended as one JSON line to `~/.jharness/sessions/<project-hash>/<session-id>.jsonl` (D-038). The folder is 0700 and the file 0600 on Linux and macOS, and reading the file back gives the same events in order. |
| AC-04 | Settings load from `~/.jharness/settings.json` (sections `model`, `models`, `sandbox`, `timeouts`). Unknown keys, bad values and fixed sandbox settings stop start-up with a message naming the key. An API key written in a settings file is rejected; profiles name an environment variable instead (`apiKeyEnv`). |
| AC-05 | A model profile resolves by name, with the `model` setting as the default; an unknown name is an error that lists the known names. |
| AC-06 | Every `api` call passes a `PermissionGate` with a description of the operation, and each decision is an event. The default gate allows reads and denies state-changing calls with an error that says approval is needed; tests can install another gate. |
| AC-07 | `api` file paths resolve inside the workspace. Absolute paths outside it, `..` escapes and symlinks that lead outside are rejected, with tests for each. |
| AC-08 | The isolate manager gives each agent one `Engine` and each run a fresh `Context`, under `SandboxPolicy.UNTRUSTED`, with limits from the `sandbox` settings (defaults from `product/tech-stack.md`) and the isolate mode checked at startup (D-034). When a limit trips, the run returns an error, `sandbox.limit_exceeded` is emitted, and the next run works. The wall-clock watchdog escalates as in D-037, with an event per step. |
| AC-09 | JS calls `api.readContext`, `readFile`, `writeFile`, `editFile`, `listFiles`, `grep`, `git` and `emit` with strings or JSON strings and gets strings back. Each call emits an event. A JS error returns its message with the line number. |
| AC-10 | `ProcessRunner` starts processes from an argv list only, with the executable resolved to an absolute path outside the workspace at startup and a clean environment. It kills the process tree on timeout (defaults from the `timeouts` settings), returns the exit code and the last 30,000 characters of output, and puts the full output in the session log. |
| AC-11 | `api.git` runs the local operations of D-039 with hooks, `core.fsmonitor`, `ext::` and all transports disabled on every call; any other operation or flag-like argument is rejected. |
| AC-12 | `api.grep` and `api.listFiles` use `rg` with `-e`, `--` and `--json` and never pass `--pre`, `--pre-glob`, `-z` or `-L`. When `rg` is missing they fall back to Java search with the same result format; tests cover both. |
| AC-13 | The OpenAI-compatible provider streams Chat Completions and the Claude provider streams Anthropic Messages. Each text delta is a `model.delta` event and the full reply is returned. HTTP errors and cut-off streams become clear errors. Tested against a local stub server playing back recorded streams; live tests run only when `OLLAMA_URL` or `ANTHROPIC_API_KEY` is set. |
| AC-14 | A native integration test binary builds and passes in CI on linux-amd64, linux-aarch64 and macos-aarch64. It covers events and the session log, an isolate run calling every `api` method, git, `rg` and the fallback, and both providers against the stub server. |

## Out of scope

- The agent loop, system prompt and `api.d.ts` (next spec).
- Permission modes, approvals and permanent approvals; the file policy's mounted folders and deny list.
- `--model`, `/model`, the CLI and the TUI.
- Layered settings (project and session), `JHARNESS.md` and project trust.
- The egress proxy, git network operations (D-039), Maven, Gradle and npm.
- The `timeoutMs` override and the OS sandbox for processes (P2).

## Dependencies and assumptions

- Oracle GraalVM 25.0.4 and polyglot 25.0.5, as in spec 001.
- Jackson tree model (`jackson-databind` 2.21.x) and JUnit 5.
- Model traffic goes directly to the configured endpoint until the proxy lands (D-025); every `HttpClient` is built in one factory so the proxy can be plugged in.
- `rg` is installed on the CI runners; on a machine without it, the fallback is used.

## Open questions

None.
