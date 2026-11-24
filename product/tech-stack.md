# JHarness — Tech Stack

The stack is chosen to stay Native Image friendly: few reflection-heavy libraries, and thin clients of our own where an SDK might not compile.
Reasons for the main choices are in `decisions.md`.

Last updated: 2026-11-24

## Core

| Layer | Choice | Notes |
| --- | --- | --- |
| Language | Java 25 (LTS) | Records, sealed types; virtual threads for host I/O |
| Runtime and compiler | Oracle GraalVM 25.x with Native Image | GFTC license: free redistribution, not for a fee |
| JS engine | GraalJS via `org.graalvm.polyglot:polyglot` + `js-isolate` | One platform classifier per release target |
| Isolate mode | `engine.IsolateMode=internal` | One process; the engine default, checked at startup, not set (experimental option in polyglot 25.0, D-034) |
| Sandbox | `SandboxPolicy.UNTRUSTED`, `HostAccess.UNTRUSTED` | Limits listed below |
| Build | Maven + `native-maven-plugin` | No uber JAR; reachability metadata in `META-INF/native-image/` |

## Libraries

| Area | Choice | Notes |
| --- | --- | --- |
| CLI parsing | picocli | Annotation processor generates native-image config |
| Terminal UI | JLine 3 | Line editing, raw mode, ANSI; verify under Native Image in P0 |
| HTTP | `java.net.http.HttpClient` | SSE streaming for models; always built with a `ProxySelector` pointing at the egress proxy |
| HTML to text | jsoup 1.24.1 | `api.fetch` turns pages into Markdown-like text (D-054); checked in the native smoke binary on Linux and macOS |
| Egress proxy | Own small HTTP forward proxy in the host (CONNECT + plain HTTP), on `127.0.0.1` with per-session credentials | Enforces `~/.jharness/network.rules` and `.jharness/network.rules`; no TLS interception |
| JSON | Jackson tree model | No DTO binding, so little reflection config |
| Diff | java-diff-utils | Edit previews and patches |
| Search | `rg` (ripgrep) through `ProcessRunner`; Java NIO glob + regex when `rg` isn't installed | Read-only; `--pre`, `--search-zip` and `--follow` blocked (D-027) |
| MCP | Our own thin client (`jharness-mcp`, D-076) | stdio and streamable HTTP |
| Storage | JSONL session logs, JSON settings under `~/.jharness/` | No native database library |
| Tests | JUnit 5, jqwik (property-based), the plugin's `nativeTest` | Security suites run on JVM and native |

## Model providers

| Provider | Implementation | Use |
| --- | --- | --- |
| OpenAI-compatible | Own thin Chat Completions client | Ollama, llama.cpp, vLLM, other providers; the way local models run in v0.1 |
| Claude | Own thin Anthropic Messages client | Native client, because the OpenAI-compatible endpoint is for testing and drops prompt caching |
| Local, in-process | ModelJars + Integrallis Models | After v0.1: too slow in the native image in P0; revisit as a Rust-backend helper process (D-035) |

Default local models: Qwen2.5-Coder-7B-Instruct (Q8_0 or Q4_K_M); alternative DeepSeek-Coder-V2-Lite-Instruct. Served by Ollama or llama.cpp in v0.1 (D-035).

Models are chosen through named profiles in `settings.json` (D-032).

Model requests are retried per profile (D-058):

| Profile key | Default | Range |
| --- | --- | --- |
| `retries` | 4 | 0 to 10 |
| `retryMaxWait` | 60 s | 1 s to 10 min |
| `responseTimeout` | 120 s | 5 s to 30 min |
| `contextWindow` | 32,768 tokens (`openai-compatible`), 200,000 (`anthropic`) | 2,048 to 10,000,000; above `maxTokens` |
| `promptCache` | `true` (`anthropic`) | `true` or `false`; `anthropic` profiles only |

Compaction (D-077): at `agent.compactAt` (0.8) of the window minus `maxTokens`, the model summarizes the older conversation in up to 2,048 tokens. Tokens are the model's own counts, else about 3.5 characters a token.

Prompt caching (D-078): Claude requests mark the system prompt and the last message with a 5-minute `cache_control` breakpoint; OpenAI-compatible servers cache on their own, and their cached counts are read when reported.

`@path` mentions (D-078): up to 20 per prompt, 50,000 characters a file, 200,000 a prompt and at most a quarter of the context window (about 3.5 characters a token); folders list up to 200 entries.

## Action reviewer (optional, D-031)

A decision model behind the System One API (`POST /v1/systemone`), with one HTTP client:

| Example | Where it runs | Notes |
| --- | --- | --- |
| Jev | Hosted | Action and context leave the machine |
| Laya | User-run, on this machine or the LAN | A locally running equivalent; works offline |

One `noul` question per state-changing operation; the answer from 0 to 1 is the confidence (spec 038).

| Key | Default | Range |
| --- | --- | --- |
| `model` | `jev-latest` | Any model name the server knows, such as `typesafe-jev-1.13.0` on DigitalOcean |
| `askBelow` | 0.90 | 0 to 1 |
| `denyBelow` | 0.20 | 0 to `askBelow` |
| `timeoutMs` | 3000 | 500 to 60000 |
| `onError` | `ask` | `ask` or `deny` |

## Sandbox limits (defaults, tune in P0)

Every value below can be changed in the `sandbox` section of `settings.json`, per profile (D-003). The policy, isolate mode and thread count are fixed.

| Option | Main agent | Subagent |
| --- | --- | --- |
| `engine.MaxIsolateMemory` (hard) | 512 MB | 256 MB |
| `sandbox.MaxHeapMemory` (soft) | 256 MB | 128 MB |
| `sandbox.MaxCPUTime` per run | 30 s | 30 s |
| Wall clock per run (watchdog; excludes approval and subagent waits) | 15 min | 15 min |
| `sandbox.MaxThreads` | 1 | 1 |
| `sandbox.MaxASTDepth` | 100 | 100 |
| `sandbox.MaxOutputStreamSize` | 256 KB | 256 KB |
| `sandbox.MaxErrorStreamSize` | 256 KB | 256 KB |
| `engine.MemoryProtection` (`engine.IsolateMemoryProtection` in polyglot 25.0, experimental) | `off` by default in v0.1; `auto` or `on` opt-in (D-036) | Same |

## Operation timeouts and output (D-028)

| Operation | Default | Maximum the model can request |
| --- | --- | --- |
| git, Maven, Gradle, npm | 2 min | 10 min |
| `api.http` | 30 s | 2 min |
| `api.mcp` | 60 s | 10 min |
| `rg` | 60 s | 60 s |

Process results return the exit code and the last 30,000 characters of output (configurable up to 150,000); the full output stays in the session log. No long-lived background processes in v1 (D-029).

## Threads

- Agent isolates run on platform threads, one per agent.
- Virtual threads serve blocking host I/O: model streaming, HTTP, and git, Maven, Gradle, npm and `rg` processes.

## Build tools JHarness drives (v1)

| Tool | Notes |
| --- | --- |
| git | Hooks, fsmonitor, `ext::` protocol, signing, signature checks and repo-defined filter drivers disabled on every call; `GIT_WORK_TREE` pinned to the workspace (D-052) |
| Maven (`mvn`, `./mvnw`) | Batch mode; wrapper only in trusted projects |
| Gradle (`./gradlew`, `gradle`) | Plain console, `--no-daemon`; wrapper must pin `distributionSha256Sum` |
| npm | `--ignore-scripts` on install; `npm ci` with a lockfile |
| rg | Search only; read-only, no network, no approval needed |

## Network

All outbound traffic goes through the host egress proxy (decision D-025).

| Client | How it uses the proxy |
| --- | --- |
| Java `HttpClient` | `ProxySelector` set once in the host |
| npm | `HTTPS_PROXY` / `HTTP_PROXY` |
| git | `-c http.proxy=...`; ssh remotes blocked in v1 |
| Maven | Generated global settings file (`--global-settings`) with the proxy entry |
| Gradle (and JVMs a build starts) | `http(s).proxyHost/Port/User/Password` in `JAVA_TOOL_OPTIONS`, never on argv (D-049) |

## OS sandbox for build tools

The sandbox also limits network access to the proxy port, so a build script can't skip the proxy (D-049). git and `rg` get no network.

| Platform | Mechanism | Network for build tools |
| --- | --- | --- |
| Linux | bubblewrap (`/usr/bin/bwrap`); Landlock not used for now (D-048) | Private network namespace; `jharness __proxy-bridge` inside forwards `127.0.0.1:<port>` to the proxy's Unix socket |
| macOS | Seatbelt (`/usr/bin/sandbox-exec` with a generated deny-by-default profile) | `(allow network-outbound (remote ip "localhost:<port>"))`; Gradle also gets any `localhost` port (D-050) |

## Platforms and release

| Item | Choice |
| --- | --- |
| Targets | linux-amd64, linux-aarch64, macos-aarch64 (Windows later; GraalVM no longer supports macOS x64) |
| CI | GitHub Actions matrix |
| Distribution | GitHub releases with checksums, Homebrew tap, install script |
| macOS | Code-signed and notarized |
| Development | On the JVM; native builds in CI and before releases |
| Source license | MIT (`LICENSE`) |
