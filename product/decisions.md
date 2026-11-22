# JHarness — Decisions

Decision log for JHarness. Each entry records what was decided, why, and what was rejected.
Status values: **Accepted** (settled), **Proposed** (recommended, not yet confirmed), **Open** (needs an answer before the related work starts).

Last updated: 2026-11-22

---

## Decision index

| ID | Decision | Status |
| --- | --- | --- |
| D-001 | Ship one native executable built with Oracle GraalVM Native Image | Accepted |
| D-002 | Run agent JavaScript in GraalJS polyglot isolates, inside the same process | Accepted |
| D-003 | Use the `UNTRUSTED` sandbox policy with hard CPU and memory limits; limit values are configurable | Accepted |
| D-004 | One `Engine` (one isolate) per agent; a fresh `Context` per run | Accepted |
| D-005 | Only strings and JSON cross the isolate boundary | Accepted |
| D-006 | Events are stringified JSON with a host-stamped envelope | Accepted |
| D-007 | RunJS is the model's only action | Accepted |
| D-008 | Agent code has no shell; the host runs typed git / Maven / Gradle / npm operations | Accepted |
| D-009 | v1 build tools: git, Maven, Gradle, npm | Accepted |
| D-010 | State-changing operations ask for approval; approvals can be made permanent per project | Accepted |
| D-011 | Project trust gates project code and project settings; trusted settings may override user rules | Accepted |
| D-012 | Users define network allow and deny rules for domains and IPs | Accepted |
| D-013 | Model providers: OpenAI-compatible and native Claude in v0.1; in-process ModelJars after v0.1 (D-035) | Accepted |
| D-014 | Default local models: Qwen2.5-Coder-7B, DeepSeek-Coder-V2-Lite | Accepted |
| D-015 | Synchronous `api`; virtual threads for host I/O; isolates on platform threads | Accepted |
| D-016 | macOS (Apple Silicon) and Linux first; Windows later | Accepted |
| D-017 | Extensions are plain folders in v1 | Accepted |
| D-018 | Memory protection keys: off by default in v0.1, opt-in `auto` or `on` (D-036) | Accepted |
| D-019 | Name: JHarness; instructions file `JHARNESS.md`; config in `.jharness/` | Accepted |
| D-020 | Oracle GraalVM under GFTC; the product is free and open source | Accepted |
| D-021 | Develop on the JVM, build native in CI | Accepted |
| D-022 | Hooks are JS only in v1 (no shell hooks) | Accepted |
| D-023 | MCP tools are reached from JS through `api.mcp(...)` | Accepted |
| D-024 | Source license: MIT | Accepted |
| D-025 | One host egress proxy for all network traffic, configured by two simple rules files | Accepted |
| D-026 | Product docs live in `product/`, feature specs in `specs/NNN-name/`, rules in `product/constitution.md` | Accepted |
| D-027 | `rg` (ripgrep) is an allowed tool for `api.grep` and `api.listFiles`, with a Java fallback | Accepted |
| D-028 | Per-operation timeouts, and a cap on the process output returned to the model | Accepted |
| D-029 | No long-lived background processes in v1 | Accepted |
| D-030 | P0 go/no-go thresholds; local 7B speed is a soft target | Accepted |
| D-031 | Optional action reviewer (Kev, Jev or own model) that can only tighten decisions | Accepted |
| D-032 | Named model profiles in `settings.json`; switch per session or per agent | Accepted |
| D-033 | Offline mode | Accepted |
| D-034 | Internal isolate mode comes from the engine default, checked at startup; no experimental options | Accepted |
| D-035 | ModelJars in the native image: no-go for v0.1; local models through Ollama or llama.cpp; after v0.1, a Rust-backend model helper process | Accepted |
| D-036 | Memory protection keys off by default in v0.1; `auto` (probe at startup) and `on` are opt-in | Accepted |
| D-037 | The wall-clock watchdog escalates: context close, then engine close, then a recorded failure | Accepted |
| D-038 | Session logs live in the user folder, not the project | Accepted |
| D-039 | Until the egress proxy (P2), git runs local operations only | Accepted |
| D-040 | Permanent approvals are saved in the user folder per project, never in the repository | Accepted |
| D-041 | Permission modes ask, accept-edits, plan and bypass; the file policy applies in every mode | Accepted |
| D-042 | Secrets come from the environment, then `~/.jharness/secrets` (KEY=value, 0600 only) | Accepted |
| D-043 | Trust answers in `~/.jharness/trusted.json`; settings layers user, project, session; untrusted projects only tighten | Accepted |
| D-044 | Checkpoints hold file contents per turn; `/undo` and `/rewind` restore files and the conversation, not git history | Accepted |
| D-045 | The P1 gate passes on a local model; the live Claude run moves to the v0.1 release checks | Accepted |
| D-046 | Streamed deltas stay off the session log; `model.completed` carries the reply and the thinking text, which is never run or sent back | Accepted |
| D-047 | Phase 2 order: proxy and rules, then the OS sandbox, then build tools; in-house proxy in `jharness-net`; "always" network answers go to the project rules file | Accepted |
| D-048 | Every child process runs in an OS sandbox (Seatbelt; bubblewrap on Linux): writes only to the workspace and a private temp folder, credential folders hidden, no network until spec 013; no sandbox, no process | Accepted |
| D-049 | Build tools: typed Maven, Gradle and npm operations in trusted projects; network only to the proxy port (Seatbelt rule on macOS, an in-binary bridge over a Unix socket on Linux); writes to their caches only | Accepted |
| D-050 | Gradle on macOS may use any loopback port, for its daemon, and so can also accept LAN connections: a recorded exception to Article V | Accepted |
| D-051 | Shift+Enter for a new line through xterm `modifyOtherKeys` (on only while the prompt reads); `agent.maxSteps` 100 by default, at most 500 | Accepted |
| D-052 | Command-execution checks: executables pinned to the file they resolved to; named environment variables only; argument size caps; git never runs programs from the repo's own config (filters, signing) and ignores `core.worktree` | Accepted |
| D-053 | A read-only web session viewer, built in Phase 4 next to headless mode but not gating v0.1, on `127.0.0.1` with a session token; no approvals from the browser yet; a companion UI with approvals and phone access comes after v0.1 | Accepted |
| D-054 | `api.http` and `api.fetch` through the egress proxy: GET and HEAD are reads, other methods ask; 10 MiB read and 200,000 characters returned by default (configurable), text only; no secrets injected; jsoup turns HTML into text; `HttpClients` moves to `jharness-net` | Accepted |

---

## D-001 — One native executable (Oracle GraalVM Native Image)

**Decision.** JHarness ships as a single native binary. The Java host is compiled with Native Image; the JS engine and its isolate support are embedded.

**Why.** One file to install, fast startup for a CLI, no JVM to manage on the user's machine. Native Image hosts support polyglot isolates, so sandboxing does not force a JVM.

**Rejected.** A JVM distribution (needs a Java install, slower start). Building on GraalVM Community Edition first (possible since 25.1, see D-020).

**Consequences.** Large binary and heavy builds (accepted). Every library must work under Native Image, and host access needs reflection metadata.

## D-002 — Agent JS in polyglot isolates, one process

**Decision.** Agent JavaScript runs in GraalJS polyglot isolates using `engine.IsolateMode=internal`, so everything stays in one OS process. The process may have many threads.

**Why.** Each isolate has its own heap and garbage collector, so agent code cannot touch host objects or another agent's memory. The user wants one process, not helper processes.

**Rejected.** `engine.IsolateMode=external` (a separate OS process per isolate): stronger separation, but breaks the one-process requirement.

## D-003 — `UNTRUSTED` sandbox policy with hard limits

**Decision.** Every agent context uses `SandboxPolicy.UNTRUSTED` and `HostAccess.UNTRUSTED`, with `engine.MaxIsolateMemory` (hard), `sandbox.MaxHeapMemory` (soft), `sandbox.MaxCPUTime`, `sandbox.MaxThreads=1`, `sandbox.MaxASTDepth`, output and error stream caps, plus a wall-clock watchdog.

**Why.** Agent-written code must be treated as untrusted: even a trusted model can be steered by prompt injection from files, web pages or tool output. UNTRUSTED refuses to build a context when a required limit is missing, so limits can't be forgotten.

**Default limits (tune in P0).** Main agent: 512 MB isolate memory, 256 MB soft heap, 30 s CPU and a 15-minute wall clock per run, 256 KB output and 256 KB error stream caps. Subagents: half the memory; the same time limits and stream caps.

**Wall clock.** The run's wall clock does not count time spent waiting for a user approval or for a subagent to finish. Individual host operations inside a run have their own timeouts (D-028).

**CPU time** only guards against runaway JS. GraalVM counts CPU time spent in host callbacks, but a thread blocked waiting for a build uses no CPU; P0 confirms this (spec 001, AC-14). Source for the 30 s and 15 min values: user, 2026-11-17.

**Configurable.** Every limit value can be changed in the `sandbox` section of `~/.jharness/settings.json` (and of a trusted project's `.jharness/settings.json`, D-011), per profile (`main`, `subagent`), without rebuilding. Fixed: the UNTRUSTED policy and host access, `engine.IsolateMode=internal`, one thread. A limit can be changed but never removed; unknown keys and bad values stop start-up. Source: user request, 2026-11-17.

**Consequences.** When a limit trips, the context is cancelled and cannot be reused. The host emits `sandbox.limit_exceeded`, returns an error to the model and creates a fresh context.

## D-004 — One Engine per agent, fresh Context per run

**Decision.** Each agent gets its own `Engine` (its own isolate). Each RunJS block, hook or handler runs in a new `Context`.

**Why.** Contexts that share an engine share one isolate heap, so per-agent hard memory caps need separate engines. Fresh contexts give a clean state for each run and recover simply from a cancelled context.

**Consequences.** Total memory ≈ concurrent agents × isolate memory + host + local model weights (see D-013). Concurrent agents must be capped.

## D-005 — Strings and JSON at the boundary

**Decision.** The `api` facade takes and returns strings (JSON for structured data). No live host objects are handed to the guest.

**Why.**
- Isolates have separate heaps. A host object passed into an isolate stays on the host heap; every field read or method call becomes a cross-isolate call, and strings are copied anyway.
- `HostAccess.UNTRUSTED` blocks guest access to host arrays, lists, maps, buffers and iterators.
- A one-time JSON copy is microseconds; a model call is hundreds of milliseconds or more. Zero-copy isn't worth the complexity.

**Rejected.** Sharing host objects for zero-copy access (works only without isolates, e.g. the `CONSTRAINED` policy, which gives up heap separation).

## D-006 — Events are stringified JSON

**Decision.** JS emits events with `api.emit(JSON.stringify(e))`. The host checks the size first (64 KB to start), parses into a Jackson tree, validates by type, stamps the envelope (`id`, `ts`, `session`, `agent`, `turn`, `source`), publishes it on the bus and appends it to the session log. Guests may only emit `agent.*` and `custom.*` types.

**Why.** Fits D-005, avoids reflection config for DTO binding under Native Image, and makes the session log a replayable source of truth.

## D-007 — RunJS is the model's only action

**Decision.** The model acts only by writing JavaScript. The host takes the JS block from the model's reply and runs it in the agent's isolate; every file, search, git, build, HTTP and subagent operation is an `api.*` call inside that block.

**Why.**
- One execution path and one permission path to secure and test.
- One block can do work that would take hundreds of tool calls (for example, filter 400 files).
- Small local models write a fenced code block far more reliably than tool-call JSON, so local models don't need native tool calling.

**Rejected.** Native tool calls (Read, Edit, Grep) beside RunJS.

**Consequences.** The model must know the `api` exactly: a TypeScript `.d.ts` of the `api` and examples go into the system prompt (moves to P1). JS errors go back to the model with line numbers.

## D-008 — No shell for agent code; typed host operations

**Decision.** Agent code cannot run shell commands or start processes. The host runs git, Maven, Gradle and npm through one `ProcessRunner`, using typed operations (`{"op":"diff","paths":["src"]}`), never free-form arguments or `sh -c`.

**Why.** A shell gives the model unlimited power and opens argument-injection attacks (`--upload-pack=...`, `-c core.sshCommand=...`). Typed operations let the host validate every argument and apply one set of checks.

**Note.** The overview principle "there are no child processes" means none started by agent code. The host itself does start child processes, only through `ProcessRunner`.

## D-009 — v1 build tools: git, Maven, Gradle, npm

**Decision.** v1 supports git, Maven (`mvn`, `./mvnw`), Gradle (`./gradlew`, `gradle`) and npm, each with a short list of operations.

**Why.** Covers both Java build systems and JavaScript projects, the most likely first users.

**Not in v1.** `npx` (runs arbitrary packages), pnpm, yarn, Python tools, Go, Cargo, make, Docker.

## D-010 — Approvals

**Decision.** Every state-changing operation asks for approval by default. The user can make an approval permanent for the project.

**Source.** User answer to "Which git, Maven, Gradle and npm operations need approval by default?"

**Why.** Safe by default, without forcing the user to approve the same safe build step every time.

## D-011 — Project trust; trusted settings may override

**Decision.** JHarness asks once per project folder whether to trust it. Untrusted projects can't run wrappers, builds, npm scripts or load project settings that loosen rules (they may only add denies). Once trusted, the project's `.jharness/settings.json` may override the user's rules.

**Source.** User answer: "when a project is trusted, its settings file can override it."

**Why.** A cloned repo is untrusted input until the user says otherwise.

**Consequence to note in the docs.** Trusting a project hands its settings file the power to loosen permissions and network rules. The trust prompt must say so.

## D-012 — Network allow and deny rules

**Decision.** Users define which domains and IPs may be reached. Entries: exact host, `*.suffix`, IPv4/IPv6, CIDR, each with an optional `:port`. A `default` of `deny`, `ask` or `allow` covers anything unmatched (shipped default `ask`). Loopback, private, link-local and metadata ranges are denied unless a rule names them explicitly. Hostnames are resolved and each resolved IP is checked; the connection is pinned to the checked IP. Managed with `/network list|allow|deny|remove`.

**Where the rules live and who enforces them:** see D-025 (rules files and egress proxy). This replaces the earlier `network` block in `settings.json`.

**Why.** User requirement. DNS pinning stops DNS-rebinding bypasses; the built-in denies stop SSRF to internal services.

## D-013 — Three model providers

**Decision.** A provider interface. v0.1 ships two implementations; the third waits (changed by D-035 on 2026-11-18):
1. **OpenAI-compatible Chat Completions** — Ollama, llama.cpp, vLLM and other providers. Local models in v0.1 run this way.
2. **Native Anthropic Messages** — Claude.
3. **ModelJars / Integrallis Models** — local models inside the JHarness process. After v0.1, when D-035's conditions are met.

**Why.**
- ModelJars keeps local use in one process (D-002's goal) and pins weights by SHA-256.
- The OpenAI-compatible client is the fallback for local models if ModelJars doesn't work in Native Image, and covers other providers.
- Anthropic's own OpenAI-compatible endpoint is meant for testing and does not support prompt caching, so Claude gets a native client.

**Risk.** ModelJars inside a native image is unproven. P0 must check native-image compatibility, Vector API speed, opening models without marker JARs on the classpath, and speed on 8k–32k-token prompts.

## D-014 — Default local models

**Decision.** Qwen2.5-Coder-7B-Instruct (Q8_0 or Q4_K_M) as the default; DeepSeek-Coder-V2-Lite-Instruct (16B MoE, 2.4B active) as the alternative; larger versions when the machine has more memory.

**Source.** User answer.

**Notes.** Qwen2.5-Coder-7B is Apache 2.0. DeepSeek-Coder-V2 uses the DeepSeek Model License, which has use-based restrictions; the docs must say so. Whether these are qualified in the ModelJars catalog is still unchecked.

## D-015 — Synchronous `api`, virtual threads for host I/O

**Decision.** `api` calls are synchronous. The host does blocking I/O (model streaming, HTTP, processes) on virtual threads. Each agent's isolate runs on a platform thread.

**Why.** Synchronous code is easier for both people and models to read. Virtual threads with a polyglot context are still experimental, and in a native image each one is backed by its own OS thread, so isolates gain nothing from them.

## D-016 — Platforms

**Decision.** macOS on Apple Silicon and Linux (amd64, aarch64) first. Windows after v0.1.

**Why.** User priority. GraalVM no longer supports macOS on x64.

## D-017 — Extensions are plain folders

**Decision.** Custom agents, hooks and slash commands live in `.jharness/agents|hooks|commands/` (project) and `~/.jharness/...` (user). No package manager in v1; install from a git URL may come later.

**Note.** "Extensions" means plugins for JHarness itself, not Node or Maven packages.

## D-018 — Memory protection keys

**Decision.** Off by default in v0.1, with `auto` and `on` as opt-in settings (changed by D-036 on 2026-11-18). Originally: turn on `engine.MemoryProtection=true` (`engine.IsolateMemoryProtection` in polyglot 25.0) on Linux when the CPU supports protection keys, detected at startup. Not available on macOS or Windows.

**Why.** Extra defense in depth where possible; the isolate and limits remain the main boundary.

## D-019 — Naming

**Decision.** Project name JHarness. Instructions file `JHARNESS.md`. Config folders `.jharness/` (project) and `~/.jharness/` (user). CLI `jharness`.

## D-020 — Oracle GraalVM (GFTC); free open-source product

**Decision.** Build with Oracle GraalVM. JHarness is free and open source.

**Why.** GFTC permits redistribution as long as it is not for a fee, which a free product meets. Oracle GraalVM also has the most complete sandbox features.

**Note for the README.** Anyone selling binaries built with Oracle GraalVM is bound by GFTC. Community Edition 25.1+ with `js-isolate-community` is the alternative.

## D-021 — Develop on the JVM, build native in CI

**Decision.** Day-to-day development and most tests run on the JVM (polyglot isolates also work with a HotSpot host). Native builds and native tests run in CI and before releases.

**Why.** Native builds are slow and memory-hungry.

## D-022 — Hooks are JS only in v1

**Decision.** Hooks run as JS in an isolate, under the same sandbox and `api` as agent code. Shell-command hooks are not supported in v1.

**Why.** Shell hooks would contradict D-008 (no shell). JS hooks keep one execution path, one set of limits and one permission path.

**Source.** Accepted by the user on 2026-11-17.

**Consequences.** A hook that needs git or a build tool calls `api.git`, `api.maven`, `api.gradle` or `api.npm`, with the usual approvals.

## D-023 — MCP tools through `api.mcp(...)`

**Decision.** With RunJS as the only action (D-007), MCP server tools are called from JS: `api.mcp('{"server":"jira","tool":"search","input":{...}}')`, returning the tool's result as a JSON string, through the same permissions as every other `api` call.

**Why.** The earlier design had MCP tools "join the tool registry", which assumed native tool calls. With RunJS only, MCP tools are reached the same way as everything else.

**Source.** Accepted by the user on 2026-11-17.

**Consequences.**
- The list of configured servers and their tools goes into the system prompt next to `api.d.ts`, so the model knows what it can call.
- stdio MCP servers are child processes. The host starts them through `ProcessRunner` from the user's own configuration (never from agent code), in the OS sandbox, with network access only through the egress proxy (D-025). The constitution's Article IV names them as an allowed process type.
- MCP servers over HTTP go through the egress proxy like any other HTTP traffic.

## D-024 — Source license: MIT

**Decision.** JHarness source code is under the MIT License, as in the repository's `LICENSE` file (Copyright (c) 2026 Can MOGOL).

**Why.** Short, permissive and widely understood; fits a free, open-source developer tool.

**Note.** Binaries built with Oracle GraalVM also carry GFTC terms (D-020); the README says so.

## D-025 — One host egress proxy, two rules files

**Decision.** The host runs a small HTTP forward proxy on `127.0.0.1` (random port, per-session credentials). Every outbound connection goes through it: the Java `HttpClient` (`api.http`, MCP over HTTP, model providers, model downloads) and every command-line tool the host starts (git, Maven, Gradle, npm). The proxy applies the rules from two plain-text files:

| File | Scope |
| --- | --- |
| `~/.jharness/network.rules` | General (user) rules |
| `.jharness/network.rules` | Project rules |

**File format.** One rule per line, `#` for comments:

```
# ~/.jharness/network.rules
default ask
allow registry.npmjs.org
allow repo.maven.apache.org
allow *.github.com
allow 10.20.0.0/16
allow build.internal.example.com:8443
deny  169.254.169.254
deny  *.corp.example.com
```

Keywords: `default`, `allow`, `deny`. Nothing else.

**Evaluation.**
1. Built-in denies (loopback, private, link-local, metadata ranges) apply unless an `allow` line names the address explicitly.
2. Project file, when the project is trusted (D-011): its lines are checked first and can override the general file. When the project is not trusted, only its `deny` lines count.
3. General file.
4. Within one file, `deny` beats `allow`.
5. No match: the `default` line (project `default` wins when trusted), else `ask`.

The configured model endpoint (for example `localhost:11434` for Ollama) and the ModelJars download hosts are allowed automatically, so model traffic works without rule edits.

**How each client is pointed at the proxy.**

| Client | How |
| --- | --- |
| Java `HttpClient` | A `ProxySelector` that always returns the proxy; created once in the host, the only way to build a client |
| npm | `HTTPS_PROXY` / `HTTP_PROXY` environment variables (with the session credentials) |
| git | `-c http.proxy=...` on every call; ssh remotes are blocked in v1 (`-c protocol.ssh.allow=never`) because ssh does not use an HTTP proxy |
| Maven | A generated global settings file passed with `--global-settings`, holding the proxy entry; the user's own settings still apply |
| Gradle | `http(s).proxyHost/-Port` system properties, which also cover the wrapper download; passed in `JAVA_TOOL_OPTIONS`, not on the command line (changed by D-049, so the password isn't visible in `ps`) |

**How it works.** For HTTPS, the proxy reads the `CONNECT host:port`, checks the rules, resolves the host itself, checks every resolved IP, and connects to the checked IP. It does not decrypt traffic. Plain HTTP is checked the same way per request. Every decision emits `network.allowed`, `network.blocked` or `network.asked`.

**Enforcement, not just configuration.** Environment variables and flags are advisory: a build script could open a socket directly. The OS sandbox for build tools (bubblewrap/Landlock on Linux, Seatbelt on macOS) allows network access only to the proxy's port, so traffic that skips the proxy fails.

**Why.**
- One place enforces the rules for everything, so the Java client and the command-line tools can't disagree.
- Simple text files are easy to read, review in a repo and edit by hand.
- Per-session credentials stop other local programs from using the proxy.

**Consequences.**
- Rules are read at start-up and when `/network` changes them; edits by hand are picked up on the next session (or `/network reload`).
- Untrusted projects can only tighten network rules.
- The proxy lands in P2 with build tools. Until then only model traffic exists, and it goes to the configured endpoint.

## D-026 — Folder structure for spec-driven development

**Decision.**

```
README.md            Index and how to work in this repo
plan.md              How JHarness gets built: architecture, modules, phases, testing
user-stories.md      Stakeholder-level stories
product/
  constitution.md    Rules every feature must follow
  mission.md         What and why, for whom
  roadmap.md         Features by phase, with gates
  tech-stack.md      Technology choices
  decisions.md       This log
specs/
  NNN-feature/
    spec.md          What to build: stories, acceptance criteria
    plan.md          How: design, contracts, constitution check
    tasks.md         Ordered, testable tasks
```

**Why.** Matches the product layer used by Agent OS (mission, roadmap, tech stack, decisions) and the per-feature spec, plan and tasks of GitHub Spec Kit, with Spec Kit's constitution for the non-negotiable rules. A folder named `spec/` for product docs would clash with per-feature `specs/`.

## D-027 — `rg` for search

**Decision.** `rg` (ripgrep) joins git, Maven, Gradle and npm as a tool the host may run through `ProcessRunner`. It backs `api.grep` and `api.listFiles`. When `rg` isn't installed, JHarness falls back to Java NIO glob and regex search, with the same results format.

**Source.** User decision on 2026-11-17.

**Why.** `rg` is much faster than a Java walk on large repos, respects `.gitignore` and is commonly installed. It only reads files and needs no network.

**How it runs.**
- Resolved to an absolute path at startup, like the other tools; never taken from the workspace.
- Typed operation only: pattern via `-e`, paths after `--`, `--json` output parsed by the host.
- Blocked flags: `--pre` and `--pre-glob` (they run a program on every file), `-z`/`--search-zip` (runs decompression programs), `-L`/`--follow` (symlinks could leave the workspace).
- `RIPGREP_CONFIG_PATH` is never set (clean environment), so no config file can add flags.
- The file policy applies: paths limited to the workspace and read-only mounted folders, and deny-list entries (`.env`, keys, `.git` internals) passed as `--glob '!…'` exclusions.
- Read-only, so it needs no approval (D-010). Timeout and output cap as for every process. No network access in its OS sandbox.

**Consequences.** Constitution Article IV lists `rg`. The command-execution test suites cover the blocked flags and deny-list exclusions.

## D-028 — Per-operation timeouts and output cap

**Decision.** Each host operation has its own timeout, separate from the run's CPU and wall-clock limits (D-003). The model may ask for a longer timeout on a single call with `timeoutMs`, up to the maximum.

| Operation | Default | Maximum the model can request |
| --- | --- | --- |
| git, Maven, Gradle, npm | 2 min | 10 min |
| `api.http` | 30 s | 2 min |
| `api.mcp` | 60 s | 10 min |
| `rg` | 60 s | 60 s |

All defaults and maximums are configurable in the `timeouts` section of `settings.json`. On timeout the whole process tree is killed and the call returns an error with the output so far. Esc cancels everything at once.

**Output to the model.** A process result returns the exit code and the last 30,000 characters of combined output (configurable, up to 150,000). The full output is kept in the session log, so nothing is lost and the model can ask for a specific part.

**Why.** A single `mvn test` can take minutes, which a run-level watchdog would kill. These values match Claude Code's Bash tool: 2 minutes by default, up to 10 minutes on request, and 30,000 characters of output returned. Large build logs would otherwise fill the model's context.

**Source.** Proposal accepted by the user on 2026-11-17.

## D-029 — No long-lived background processes in v1

**Decision.** Every process the host starts must finish within its timeout. Dev servers, watch modes and other long-lived background processes are not supported in v1.

**Why.** Background processes complicate the OS sandbox, cleanup on cancel and session resume. Revisit after v0.1.

**Source.** User decision on 2026-11-17.

## D-030 — P0 go/no-go thresholds

**Decision.** The P0 spike passes when: polyglot isolates work in the native binary on all three targets; cold start-up is under 2 s; isolate creation is under 300 ms median; ModelJars runs in the native binary at no less than 70% of its JVM speed; JLine works on macOS and Linux.

Local 7B model speed is a soft target, not a gate. On a 16 GB Apple Silicon Mac: good is an 8k-token prompt in under 120 s and at least 3 tokens/s; slow (still go, flagged in the model profile) is under 240 s and at least 1.5 tokens/s. Anything slower changes the recommended local setup (7B through Ollama or llama.cpp), not the plan.

**Why.** The start-up target was relaxed from 1 s to 2 s, and the 7B targets loosened, because a slightly slower local setup is acceptable while a failed gate would stall the project.

**Source.** User decisions on 2026-11-17. Details in `specs/001-p0-spike/plan.md`.

## D-031 — Optional action reviewer

**Decision.** An optional reviewer gives a yes/no judgement on each state-changing operation: does it fit the user's task and the project settings? It runs after the deterministic checks pass (typed operation, argument checks, file policy, permissions rules, proxy rules) and before the operation executes.

- **Only tightens.** It can turn "allow" into "ask the user" or "deny", never the other way round. It is never the only gate.
- **Fails safe.** If it is down, slow or returns an error, the operation falls back to asking the user.
- **Three back-ends**, one client:
  - `kev` — Kev, small open decision models (Apache-2.0, sizes 0.8B to 27B), run by the user on localhost, like Ollama.
  - `jev` — Jev, the hosted model behind TypeSafe's API. The action and its context leave the machine; the docs must say so.
  - `model` — one of the user's own model profiles (D-032), asked a yes/no question; no extra process.
  Kev and Jev share the System One API (`POST /v1/systemone`), so only the URL and key differ.
- **Off by default.** Lands in P3, next to hooks.

**Configuration** (`settings.json`):

```json
"reviewer": {
  "enabled": true,
  "provider": "kev",
  "url": "http://127.0.0.1:8000/v1/systemone",
  "apiKeyEnv": "KEV_API_KEY",
  "askBelow": 0.90,
  "denyBelow": 0.20,
  "timeoutMs": 3000,
  "onError": "ask"
}
```

The reviewer endpoint is allowed through the proxy automatically, like the model endpoint. Every verdict emits a `review.*` event with the confidence.

**Why.** Rules can't judge intent: a prompt-injected commit of unrelated files, an unrequested npm script or a POST to an allowed host all pass the rules. A reviewer catches some of these. It can't be the security boundary because it is probabilistic and judges text an attacker may control.

**Costs.** Kev is a separate Python server (CUDA, ROCm or MLX), outside the one-binary principle, so it stays optional and user-run. On a 16 GB machine next to a 7B coding model, Kev-0.8B is the practical size. Each reviewed operation adds latency.

**Source.** User request and acceptance on 2026-11-17.

## D-032 — Model profiles

**Decision.** Models are named profiles in `settings.json`; one is the default.

```json
{
  "model": "local-qwen",
  "models": {
    "local-qwen":  { "provider": "modeljars", "artifact": "<ModelJars coordinates for Qwen2.5-Coder-7B Q4_K_M>" },
    "ollama-qwen": { "provider": "openai-compatible", "baseUrl": "http://localhost:11434/v1", "model": "qwen2.5-coder:7b" },
    "claude":      { "provider": "anthropic", "model": "<Claude model id>", "apiKeyEnv": "ANTHROPIC_API_KEY" }
  }
}
```

- Switch with `jharness --model <name>` or `/model <name>` in a session.
- Custom agents and subagents may name their own profile.
- API keys come from environment variables (or the 0600 secrets file), never from settings files.
- Each profile can carry model-profile notes (context size, JS-writing quality, "slow on this hardware", D-030).

**Source.** User question and acceptance on 2026-11-17.

## D-033 — Offline mode

**Decision.** `jharness --offline` or `"offline": true` in settings:
- sets the network default to `deny` (local model and reviewer endpoints stay allowed);
- passes `-o` to Maven, `--offline` to Gradle and `--offline` to npm;
- skips update checks.

**What works offline:** a ModelJars model already in the cache or a model already pulled in Ollama; Kev running locally; Maven and Gradle with dependencies, plugins and the Gradle wrapper distribution already downloaded; npm with packages in its cache; git (except fetch and push), `rg` and file edits. JHarness itself never needs the network: no license check, no telemetry, no required update check.

**What doesn't:** Claude, Jev, remote MCP servers and anything missing from the local caches. Each fails with a clear message.

**Why.** A Java developer with dependencies in `~/.m2` and a local model should be able to work without a connection.

**Source.** User question and acceptance on 2026-11-17. Lands in P2, with the build tools and the proxy.

## D-034 — Isolate mode from the engine default

**Decision.** Agent engines don't set `engine.IsolateMode`. In polyglot 25.0 it is an experimental option, and its default is `internal`. Engines are built with `useSystemProperties(false)`, so no `polyglot.*` system property can change it, and start-up fails unless the default is `internal`. Agent engines never call `allowExperimentalOptions(true)`.

**Why.** Setting the option needs `allowExperimentalOptions(true)`, which unlocks every experimental engine option. Relying on the checked default keeps D-002 (one process) without widening what the engine accepts.

**Source.** User answer while specifying 001-p0-spike, 2026-11-18.

## D-035 — ModelJars in the native image: no-go for v0.1

**Decision.** v0.1 does not run models in-process. Local models run through the OpenAI-compatible client against Ollama or llama.cpp; the default local models stay Qwen2.5-Coder-7B-Instruct and DeepSeek-Coder-V2-Lite-Instruct, served that way. This changes D-013. In-process ModelJars is revisited after v0.1 when all of these hold:
- ModelJars runs in the native binary at no less than 70% of its JVM speed (D-030);
- it starts no child process (constitution Article IV);
- the default coder models are in its catalog and qualified on the backend used.

The path to revisit is a separate native helper binary that runs the Rust FFM backend without Truffle, so the shared-arena limit doesn't apply. JHarness starts it through `ProcessRunner` and talks to it over stdio, like a stdio MCP server; no socket. Its first use is a small reviewer model for D-031 (Kev-class, about 0.8B), so the reviewer works when the user runs no Kev server and doesn't use Jev. Most users are expected to run coding models through Ollama or llama.cpp, or use Claude. Before adoption: prove the helper builds and runs natively on linux-amd64 and macos-aarch64, and settle in that spec the constitution Article IV exception for the helper process and for `vectors-core`'s `sysctl` call inside it. Added 2026-11-18.

**Why.** Measured in P0 (`docs/p0-results.md`):
- The pure-Java backend runs in the native image at 0.1–1.2% of its JVM speed, even with `-H:+VectorAPISupport`.
- The Rust FFM backend (`backend-native`) uses `Arena.ofShared`, which Native Image rejects in an image with runtime compilation, and the JS isolates need Truffle.
- Qwen2.5-Coder-7B and DeepSeek-Coder-V2-Lite are not in the ModelJars catalog.
- `vectors-core` runs `/usr/sbin/sysctl` through `ProcessBuilder` on macOS.

The plan named this fallback in advance (D-013 Why, `plan.md` risks), so P1 continues.

**Source.** P0 results, accepted with the user's instruction to use the recommended options, 2026-11-18.

## D-036 — Memory protection keys off in v0.1

**Decision.** The setting `engine.MemoryProtection` takes `off`, `auto` or `on`; the default is `off` in v0.1. This changes D-018.
- `off`: agent engines do not set `engine.IsolateMemoryProtection`.
- `auto`: at startup JHarness builds one probe engine with the option on and creates an isolate. If that works, agent engines use it; if it fails, they run without it and JHarness emits an event saying so.
- `on`: as `auto`, but startup fails if the probe fails.

With `auto` or `on`, agent engines allow experimental options, an exception to D-034 that only the user's setting turns on; guest code cannot set engine options. Make `auto` the default once the option has been tested on a Linux machine with protection keys.

**Why.** Protection keys are a nice-to-have; JHarness must run without them. In P0 both failure modes were ordinary errors at isolate creation, so a startup probe can detect them: linux-amd64 without `pku` fails with "Setting the protection of the heap memory failed", and linux-aarch64 and macOS report "Memory Protection not available". The option is experimental in polyglot 25.0 and the case where it works is untested, because no machine with protection keys was available, so it stays opt-in. The isolate and its limits remain the boundary (D-002, D-003).

**Source.** P0 results, accepted with the user's instruction to use the recommended options, 2026-11-18; changed to off-by-default with opt-in `auto` and `on` by user answer, 2026-11-18.

## D-037 — Watchdog escalation

**Decision.** When a run reaches its wall clock, the watchdog calls `Context.close(true)`. If the run has not ended 10 s later, it closes the agent's `Engine` with `close(true)`. If that has not ended it 30 s later, it records a failure event and stops the agent. Each step is an event. The delays are configuration values next to `wallClock` in P1.

**Why.** In one P0 CI run, a guest that filled most of its isolate heap was not stopped by `Context.close(true)` for 28 minutes. It did not reproduce in 14 other runs, so the next step must not depend on the first one working.

**Source.** P0 results, accepted with the user's instruction to use the recommended options, 2026-11-18.

## D-038 — Session logs in the user folder

**Decision.** Each session's JSONL log is `~/.jharness/sessions/<project-hash>/<session-id>.jsonl`, where `<project-hash>` is the first 16 hex characters of the SHA-256 of the workspace's real path. Folders are created with mode 0700 and log files with 0600.

**Why.** The repository stays clean, and an untrusted repository can't plant, read or replace session logs. Session logs can hold file contents and command output, so only the user may read them.

**Source.** User answer while specifying 002-host-foundations, 2026-11-18.

## D-039 — Local git only until the egress proxy

**Decision.** Until the egress proxy lands in P2 (D-025), `api.git` offers only operations that don't touch the network: `status`, `diff`, `log`, `show`, `add`, `commit`, `restore` and `branch` listing. `fetch`, `pull`, `push` and `clone` arrive with the proxy. Every call runs with hooks, `core.fsmonitor` and the `ext::` protocol disabled, and with `protocol.allow=never` so no transport can run.

**Why.** Constitution Article V allows no network connection that skips the proxy. Local operations are enough for the P1 gate (fix a seeded bug and commit it).

**Source.** User answer while specifying 002-host-foundations, 2026-11-18.

## D-040 — Permanent approvals in the user folder

**Decision.** "Always for this project" approvals are saved to `~/.jharness/projects/<project-hash>/permissions.json` (the hash as in D-038), with folders 0700 and the file 0600. A rule names what was approved: the kind of operation plus the file path for writes (`{"kind":"write","path":"src/App.java"}`), or the git operation (`{"kind":"git-write","op":"commit"}`). Nothing in the repository can add an allow rule.

**Why.** US-3.2 asks for approvals saved per project. Keeping them out of the repository means a cloned repo can't ship its own approvals; project settings that loosen rules come only with project trust (D-011).

**Source.** Recommended option accepted while the user was away, specifying 004-permissions-and-file-policy, 2026-11-18.

## D-041 — Permission modes and the file policy

**Decision.** The `permissions.mode` setting picks one of four modes:

| Mode | Reads | File writes and edits | git add, commit, restore |
| --- | --- | --- | --- |
| `ask` (default) | allowed | ask unless a saved rule allows | ask unless a saved rule allows |
| `accept-edits` | allowed | allowed | ask unless a saved rule allows |
| `plan` (read-only) | allowed | denied | denied |
| `bypass` | allowed | allowed | allowed |

The file policy applies in every mode, `bypass` included: paths stay inside the workspace or a mounted folder; mounted folders (`files.mounts`, absolute paths) are read-only; deny-list files can't be read or written and are left out of `listFiles` and `grep`. The built-in deny list is `.env` and `.env.*`, `*.pem`, `*.key`, `id_rsa*`, `id_ecdsa*`, `id_ed25519*` and `.git/**`; `files.deny` adds globs, and none can be removed. Writes to `.jharness/**` are always denied, so the agent can't change project settings. When no one can answer (headless), `ask` denies.

**Why.** D-010 makes state-changing operations ask by default; the other modes are the usual trade-offs between speed and control. The file policy guards secrets and reference folders whatever the mode.

**Source.** Recommended option accepted while the user was away, specifying 004-permissions-and-file-policy, 2026-11-18.

## D-042 — Secrets file

**Decision.** A secret named by a profile's `apiKeyEnv` is looked up in the environment first, then in `~/.jharness/secrets`: one `NAME=value` per line, `#` for comments, blank lines ignored. JHarness refuses to read the file unless it is a regular file owned by the user with mode 0600, and says how to fix it. Secret values never go into prompts, the guest, events or the session log.

**Why.** D-032 names environment variables or a 0600 file. A plain `NAME=value` file is easy to edit and matches the environment variable names, so a profile works with either source.

**Source.** Recommended option accepted while the user was away, specifying 005-cli-and-tui, 2026-11-18.

## D-043 — Trust store and settings layers

**Decision.**
- The trust answer for a folder is saved in `~/.jharness/trusted.json` (0600), keyed by the folder's real path, with the date. The terminal UI asks the first time a folder is opened; the question says that trusting lets the project's `.jharness/settings.json` loosen rules. Headless runs never ask: a folder not marked trusted is untrusted.
- Settings are layered: user `~/.jharness/settings.json`, then project `.jharness/settings.json`, then the session (command-line flags such as `--model`). Objects are merged key by key and later layers win; `files.deny` lists are joined, so a layer can only add denies.
- From an untrusted project only these apply: `files.deny` (joined) and `permissions.mode` when it is `plan`. Every other key is ignored, and the session log records which with a `settings.ignored` event.
- `JHARNESS.md` from the workspace root and `~/.jharness/JHARNESS.md` go into the system prompt as project and user instructions, each up to 32 KB. They are read whether or not the project is trusted: they are text for the model, not settings, and the guest's file reads are no different.

**Why.** D-011 says trust gates project settings that loosen rules and that untrusted projects may only add denies. A user-folder store keeps the answer out of the repository, as D-038 and D-040 do for logs and approvals.

**Source.** Recommended option accepted while the user was away, specifying 006-trust-settings-and-memory, 2026-11-18.

## D-044 — Checkpoints and undo

**Decision.**
- Before the agent first changes a file in a turn (`writeFile`, `editFile`, `git restore`), the host saves the file's content, or the fact that it didn't exist, in the turn's checkpoint under `~/.jharness/checkpoints/<project-hash>/<session-id>/` (0700, files 0600). Files over 10 MB are not saved; the change is still allowed, and undo reports it can't restore them.
- `/undo` restores every file changed in the last turn and removes files the turn created. `/rewind N` does the same for every turn after turn N. Both also cut the conversation back, so the model doesn't see the undone turns.
- Undo restores files only: commits, staged changes and other git state stay. The undo message says so when an undone turn ran `git add`, `commit` or `restore`.
- If a file changed since the agent last wrote it (the user edited it), undo stops, lists the files, and changes nothing; `/undo --force` restores anyway.
- Diff review: in `ask` mode the approval question shows the unified diff of the change; in `accept-edits` and `bypass` the TUI prints the diff after the change. `/diff` shows the last turn's changes.
- Esc while a turn runs stops the model stream and the running block; the turn ends with stop reason `interrupted`.

**Why.** US-2.3, US-2.4 and US-2.5. Saving contents per turn needs no git and works in folders that aren't repositories. Undoing commits would rewrite history the user may have pushed, so it stays manual.

**Source.** Recommended option accepted while the user was away, specifying 007-checkpoints-and-undo, 2026-11-18.

---

## D-045 — P1 gate without a live Claude run

**Decision.**
- The Phase 1 gate passes when the seeded-bug scenario is fixed end to end with a local model, run by hand in the `jharness` TUI and recorded in `docs/p1-gate.md`.
- The Claude half of the gate is covered for now by the Claude provider tests against the stub server (spec 002) and the replay run of the gate scenario (spec 003).
- The live Claude run (`LiveLoopTest.claude`, or the same scenario by hand) becomes a Phase 4 release check, done before v0.1 is tagged.

**Why.** No Claude API key is available yet. The provider code is tested against the wire format, and the loop is the same for both providers, so waiting would block Phase 2 for a check that doesn't change the design. A release still can't ship without it.

**Source.** User, 2026-11-19.

---

## D-046 — Session log and thinking text

**Decision.**
- `model.delta` and `model.thinking_delta` go to live subscribers (the TUI) but not to the session log. `model.completed` carries the whole reply as `text` and, when there is any, the thinking as `thinking`; each is cut at 65,536 characters, with `textCut` / `thinkingCut` giving the characters cut.
- Providers keep thinking apart from the reply: OpenAI-compatible `reasoning_content` and `reasoning` fields, a leading `<think>…</think>` in the content, and Anthropic `thinking_delta` blocks.
- Thinking is never part of the reply: it is not searched for code, not run, and not sent back to the model.
- The TUI hides thinking unless `ui.thinking` is `show` (or `/thinking` turns it on); shown thinking is dim.
- Turning on Claude extended thinking is left for later.

**Why.** One event per streamed chunk made the log hard to read and tail, and the reply was nowhere in one piece. Reasoning models send their thinking alongside the reply; inline `<think>` text could hold a code block that would have been run.

**Source.** User answers, specifying 010-thinking-and-quiet-log, 2026-11-19.

---

## D-047 — Phase 2 order, the proxy module, and saved network answers

**Decision.**
- Phase 2 starts with the egress proxy and rules files (spec 011), then the OS sandbox for child processes (spec 012), then Maven, Gradle and npm (spec 013). Builds never run before both the proxy and the sandbox exist.
- The proxy is built in-house in a new `jharness-net` module: a minimal HTTP/1.1 forward proxy (`CONNECT` for HTTPS, absolute-form requests for plain HTTP) on virtual threads, with no new dependency.
- An "always" answer to a network question appends `allow host[:port]` to the project's `.jharness/network.rules`. The host writes it; agent code still can't write `.jharness/**`. In an untrusted project the line is written but counts only once the project is trusted (D-025 step 2).
- A "once" answer allows that `host:port` for the rest of the session.

**Why.** Running builds with only offline flags would leave build scripts free to open sockets until the sandbox lands (Article V). A small proxy keeps the native binary free of Netty-sized dependencies. Saving answers in the project file keeps them next to the project and reviewable.

**Source.** User answers, specifying 011-egress-proxy-and-rules, 2026-11-19.

---

## D-048 — OS sandbox for child processes

**Decision.**
- Every process `ProcessRunner` starts runs inside an OS sandbox: Seatbelt (`/usr/bin/sandbox-exec` with a generated profile) on macOS, bubblewrap (`/usr/bin/bwrap`) on Linux. Landlock is not used for now: it would need a launcher inside the binary, and its network rules cover only TCP ports.
- Files: reads are allowed except `~/.jharness`, `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud` and `~/.netrc`; writes only to the workspace (not its `.jharness/`) and a private per-session temp folder passed as `TMPDIR`. On Linux the host creates an empty `.jharness/` first, so it can be mounted read-only.
- Network: none for every child process in spec 012 (git runs local operations only, D-039; `rg` needs none). Spec 013 opens the proxy port for the tools that need it.
- Fail closed: the sandbox is probed at session start (`sandbox.os` event). When it's missing or doesn't work (no bubblewrap, user namespaces blocked), process operations fail with an error that says how to fix it, search uses the Java fallback, and nothing runs unsandboxed. There is no setting to turn the sandbox off.

**Why.** Constitution Articles IV and V and US-3.5: a malicious build script must not reach outside the project. Both mechanisms ship with, or are one package away on, the target systems and need no code inside the binary.

**Source.** Recommended options accepted while the user was away, specifying 012-os-sandbox-for-processes, 2026-11-19.

---

## D-049 — Build tools in the sandbox

**Decision.**
- `api.maven`, `api.gradle` and `api.npm` run a short list of build and test operations (spec 013). Builds run project code, so they need a trusted project (D-011) and approval (D-010); "always" saves the tool and op.
- Wrappers (`./mvnw`, `./gradlew`) run when present; `./gradlew` needs `distributionSha256Sum`. Gradle always runs with `--no-daemon` (D-029); npm `ci` and `install` always run with `--ignore-scripts`.
- The sandbox opens one network path for build tools: the proxy port. On macOS this is a Seatbelt rule for `localhost:<port>`. On Linux, bubblewrap keeps its private network namespace, and the JHarness binary runs inside it as a bridge (`__proxy-bridge`) that forwards `127.0.0.1:<port>` over a Unix socket to the proxy. git and `rg` keep no network.
- Tools learn the proxy from a generated Maven global settings file, `JAVA_TOOL_OPTIONS` (Gradle and other JVMs) and `npm_config_*` variables, never from argv. The proxy password is redacted from captured output. This changes D-025's Gradle row, which put the properties on the command line.
- Gradle on macOS also gets loopback (changed by D-050; it was refused at first).
- Build tools may also write to their caches (`~/.m2/repository`, parts of `~/.gradle`, `~/.npm`), but not to the files that configure them (`~/.m2/settings.xml`, `~/.gradle/init.d`, `~/.gradle/gradle.properties`, `~/.npmrc`).

**Why.** US-2.1 and US-3.5. A bridge inside the namespace gives Linux the same "proxy port only" rule as macOS, without new dependencies (slirp4netns, pasta) or sharing the host network. Caches are shared with the user's own builds so cached dependencies keep working offline (D-033); their config files stay read-only so a build can't plant an init script that runs later outside JHarness.

**Source.** Recommended options accepted while the user was away, specifying 013-build-tools-in-the-sandbox, 2026-11-19.

---

## D-050 — Gradle on macOS uses loopback

**Decision.**
- On macOS, Gradle runs get three more Seatbelt rules: bind, accept and connect on any `localhost` port, TCP and UDP. Maven, npm, git and `rg` keep only the proxy port.
- This is a recorded exception to Article V, which now names it (constitution 1.5): a Gradle build on macOS can reach any service listening on the Mac's loopback (databases, Docker, a local proxy) without the egress proxy. It can also listen on `0.0.0.0` and accept connections from other machines on the LAN: Seatbelt's `localhost` rule matches a wildcard bind, and accepted connections aren't checked. It still can't open connections to outside hosts. A test pins this (and that Linux stays closed), so a change in Seatbelt is noticed.
- Linux is unchanged: bubblewrap's loopback is private to the sandbox, so Gradle needs no exception there.
- `process.started` carries `loopback: true` for these runs.
- Separately, JVM build tools get `-Djava.net.preferIPv4Stack=true`: Seatbelt's `localhost` doesn't match a dual-stack socket's `::ffff:127.0.0.1`, so without it a JVM can't reach the proxy (or Gradle its daemon).

**Why.** Gradle 9 forks a single-use daemon even with `--no-daemon`, and the client and daemon talk over loopback TCP; its lock handling uses loopback UDP. Seatbelt can't limit loopback to one process's own sockets, so the choice was this exception or no Gradle on macOS. Gradle runs already need a trusted project and approval, and the build code they run is the project's own.

**Rejected.** Loopback for every build tool (would also let tests start local servers, but widens the hole to Maven and npm). Keep refusing Gradle on macOS.

**Source.** User answer while finishing 013-build-tools-in-the-sandbox, 2026-11-20. Changes D-049's Gradle-on-macOS bullet.

---

## D-051 — Shift+Enter and the step limit

**Decision.**
- While the `> ` prompt reads a line, JHarness turns on xterm `modifyOtherKeys` level 1 (`ESC[>4;1m`) and resets it (`ESC[>4m`) after the line is read. Shift, Alt and Ctrl+Enter insert a newline, in both the `modifyOtherKeys` and CSI u forms. Alt+Enter (`ESC` `CR`) and Ctrl-J keep working; Enter sends.
- Terminals that don't report Shift+Enter send a plain Enter. The README shows how to map Shift+Enter to Alt+Enter in VS Code, kitty and iTerm2.
- `agent.maxSteps` defaults to 100 and may be 1 to 500. This changes spec 003's default of 25 and maximum of 200.

**Why.** Users expect Shift+Enter from chat tools. Level 1 changes only keys with no standard meaning, so Esc, Ctrl-C, Ctrl-D and arrows stay as they are; the kitty keyboard protocol would change Esc and Ctrl-letter keys too. 25 steps were too few for real tasks in the user's runs.

**Rejected.** The kitty keyboard protocol. Keeping Alt+Enter as the only new-line key.

**Source.** User request on 2026-11-20; recommended options accepted while the user was away, specifying 014-shift-enter-and-step-limit, 2026-11-20.

## D-052 — Command-execution checks

**Decision.**
- Every `Executable` is pinned to the file it resolved to (file key, size, modification time). `ProcessRunner` checks the pin before each start; a changed file fails the run with a `process.refused` event. `Executables.resolve` skips files and folders that are writable by others (`o+w`).
- Children get `PATH`, `HOME`, `LANG`, `LC_ALL` and the sandbox's `TMPDIR`, plus only the named variables that git and the build operations set. Any other name is refused.
- `ProcessRunner` refuses more than 4,096 arguments, any argument over 131,072 UTF-8 bytes, or more than 1 MiB in total.
- Git turns off every filter driver that the repository's own config defines (scope `local` or `worktree`), found with `git config --show-scope` before each call. It also turns off commit and tag signing, signature display, submodule recursion and automatic gc and maintenance, and it sets `GIT_WORK_TREE` to the workspace root when that root holds `.git`. Drivers from the user's global config keep working.

**Why.** Probes on git 2.53 showed that a cloned repo's `.git/config` could make `git add` and `git status` run a filter, `git commit` run `gpg.program`, and `git log` run it to verify a signature. They also showed `core.worktree` pointing git at a folder outside the workspace. The OS sandbox limits what those programs can reach, but D-011 says a repo's code runs only after trust, and git ran it either way. Pinning stops a binary swapped mid-session from running under an earlier approval.

**Rejected.** Turning off every filter driver, including global ones (it would break git-lfs for users who set it up globally). Parsing `.git/config` in Java (it misses includes). Refusing to run git in repos that define filters (it breaks normal repos).

**Source.** Roadmap item "Command-execution checks"; recommended options accepted while the user was away, specifying 015-command-execution-checks, 2026-11-21.

## D-053 — A read-only web session viewer first

**Decision.**
- A read-only web session viewer: the timeline, steps, diffs and thinking text of a live or finished session, in the browser. It reads the same event stream as headless mode's `stream-json` and is built in Phase 4 next to it. It doesn't gate v0.1: if it isn't ready, it ships in a later release.
- The browser can't send prompts, answer approvals or change anything. Approving from the browser is the goal: a companion UI that can goes on the After v0.1 list and needs its own decision. A full web client (`jharness serve`) is not planned.
- Phone and tablet access (LAN or remote, with real authentication and TLS) is left to that later decision. Until then the viewer is reachable only from the same machine, or through an SSH port forward.
- The viewer is served by the binary itself from embedded static files. It binds to `127.0.0.1` only, with a random per-session token and `Host` and `Origin` checks. Its spec settles the server (the JDK's `com.sun.net.httpserver` with Server-Sent Events, once a Native Image check passes), the module (`jharness-web`) and the architecture-test entry for its listening socket.
- The mission's "Not in v0.1" line now excludes a GUI that drives the agent, not this viewer.

**Why.** The user wants richer diffs, readable long sessions, use from a phone or tablet, and demos. Events are already the source of truth (Article VIII), so a viewer is a second reader of the stream that headless mode needs anyway. Keeping the browser out of approvals means a web page can't approve an action through a local server, at least until the injection and escape suites (the Phase 2 gate) exist.

**Rejected.** A companion UI with approvals in v0.1 (browser-reachable approvals before the security suites). A full web client (it moves JHarness away from a terminal agent). No web UI at all (long sessions and diffs read better in a browser).

**Source.** User accepted the recommendation in a discussion of the web UI, 2026-11-22.

## D-054 — `api.http` and `api.fetch`

**Decision.**
- `api.http({method, url, headers, body, timeoutMs})` sends one request and returns `{status, url, headers, contentType, body, bytes, truncated, binary}`. `api.fetch({url, timeoutMs})` GETs a page and returns readable text: `{status, url, title, text, truncated}`.
- Every request uses the one `HttpClient` from `HttpClients`, so it goes through the egress proxy and the network rules (D-025). Hosts that no rule matches are asked about by the proxy, as today.
- GET and HEAD are reads: the network rules alone decide. POST, PUT, PATCH and DELETE change state. They ask for approval (D-010), plan mode denies them, and "always" saves a `<METHOD> <host>` rule in the user-folder permissions file (D-040).
- By default at most 10 MiB of a response is read and 200,000 characters are returned. Both are settings: `http.maxResponseBytes` (64 KiB to 50 MiB) and `http.maxChars` (1,000 to 1,000,000). Text types come back as UTF-8; binary bodies come back as type and size only. GET and HEAD follow up to 5 redirects, and each hop goes through the proxy again; other methods don't follow redirects.
- The agent may set any request header except `Host`, `Content-Length`, `Connection`, `Transfer-Encoding`, `Upgrade`, `Expect` and `Proxy-*`. JHarness sets its own `User-Agent`. No user secret is ever added to a request; authenticated calls with the user's tokens need a later decision.
- Timeouts: `timeouts.http` (30 s) and `timeouts.httpMax` (2 min), as D-028 set out.
- `http.request` and `http.response` events record method, URL, status, type, size and time. Headers and bodies stay out of the log, since they may hold tokens.
- jsoup (MIT, pure Java) turns HTML into Markdown-like text, after a Native Image check on Linux and macOS. `HttpClients` moves from `jharness-llm` to `jharness-net`, so the sandbox can use it without depending on the model providers.

**Why.** Agents need to read documentation and call web APIs. One client and the proxy keep Article V intact. Treating writes like file writes keeps approvals consistent. Caps keep a large page from flooding the model's context.

**Rejected.** One `api.http` with a text mode (one call doing two jobs). GET only (blocks ordinary API work). Base64 binary bodies (images and archives waste context). Injecting secrets (reopens Article VII; needs its own design). An in-house HTML converter (weak on real-world HTML). Logging bodies (they may hold tokens).

**Source.** User answers while specifying 016-http-and-web-fetch, 2026-11-22.

---

## Questions and answers

Questions raised during design, the answers given, and why each mattered.

| # | Question | Answer | Why it mattered | Decision |
| --- | --- | --- | --- | --- |
| Q1 | Can objects be shared directly between isolates? | No: each isolate has its own heap. Handles are opaque tokens, not shared objects. | Set the data-passing model | D-005 |
| Q2 | Are host objects shared with a polyglot isolate, copied or serialized? | Not serialized or copied as a whole: the guest gets a reference; each access is a cross-isolate call; ints cross by value, strings are copied per read | Showed zero-copy isn't possible with isolates | D-005 |
| Q3 | Can we avoid copying entirely? | Only without isolates (CONSTRAINED policy). With isolates, pass JSON once and parse in the guest. | Chose sandboxing over zero-copy | D-003, D-005 |
| Q4 | Run agent JS or the loop in the isolate? | Agent code in the isolate; the loop in Java | Defined the host/guest split | D-002 |
| Q5 | What can agent code access? | Only the `api`: context data, HTTP, files, events | Defined the `api` | D-005, D-007 |
| Q6 | CPU and memory limits? | Yes | Required the UNTRUSTED limits | D-003 |
| Q7 | Native compile? | Yes, Oracle GraalVM, distribute only the native executable | | D-001, D-020 |
| Q8 | Internal or external isolate mode? | Internal: one process, multiple threads allowed | | D-002 |
| Q9 | Memory protection on macOS and Windows? | Use it on Linux; no equivalent found for macOS or Windows | | D-018 |
| Q10 | Is the product commercial? | No: open source and free | GFTC fits | D-020 |
| Q11 | Event format? | Stringified JSON | | D-006 |
| Q12 | Code mode or native tool calls? | Agent code has no shell, only API calls; the host may call shell commands. Later: RunJS only. | Removed the Bash tool; one action path | D-007, D-008 |
| Q13 | Which model providers? | OpenAI-compatible; must work with Claude or local LLMs. Later: use ModelJars. | | D-013 |
| Q14 | Synchronous or Promise-based `api`? | Synchronous, easier to understand; use virtual threads | | D-015 |
| Q15 | Extension package format? | Plain folders for v1 | | D-017 |
| Q16 | Windows at v0.1? | macOS and Linux first | | D-016 |
| Q17 | Project and instruction file names? | JHarness; instructions file similar | | D-019 |
| Q18 | Which operations need approval? | All state-changing operations; the user can make an approval permanent for the project | | D-010 |
| Q19 | Default local models? | Qwen2.5-Coder-7B (Q8_0 or Q4_K_M) or DeepSeek-Coder-V2-Lite-Instruct; bigger versions with more memory | | D-014 |
| Q20 | May a trusted project's settings loosen rules? | Yes, when trusted its settings can override | | D-011 |
| Q21 | Can users define allowed and denied domains and IPs? | Yes, required | | D-012 |
| Q22 | Which build tools in v1? | git, Maven, Gradle, npm (recommended) | | D-009 |
| Q23 | How are network rules enforced? | A proxy in the host, configured by a simple general file and a project file; the Java HttpClient and all command-line tools use it | One enforcement point | D-025 |
| Q24 | How should the docs be structured? | Product docs in `product/`, feature specs in `specs/`, plus a constitution | Spec-driven development | D-026 |
| Q25 | Which source license? | MIT, from the repository's `LICENSE` file | | D-024 |
| Q26 | Are hooks JS only in v1? | Yes, accepted | Keeps one execution path | D-022 |
| Q27 | Are MCP tools reached through `api.mcp`? | Yes, accepted | Fits RunJS as the only action | D-023 |
| Q28 | Can search use `rg`? | Yes, add it to the allowed tools | Fast search on large repos | D-027 |
| Q29 | Should sandbox and engine limits be configurable? | Yes, with defaults; output and error stream caps default to 256KB | Users can tune limits without rebuilding | D-003 |
| Q30 | Are 10 s CPU and 60 s wall clock enough? | No: 30 s CPU, 15-minute run wall clock, plus per-operation timeouts | A build or test run takes minutes | D-003, D-028 |
| Q31 | Cap the output returned to the model? | Yes: last 30,000 characters plus exit code; full log in the session | Keeps build logs from filling the context | D-028 |
| Q32 | Long-lived background processes in v1? | No, after v0.1 | Keeps sandbox and cleanup simple | D-029 |
| Q33 | What are the P0 go/no-go thresholds? | As proposed, but cold start-up under 2 s and a looser, soft target for the local 7B model | The spike can be judged | D-030 |
| Q34 | Does a yes/no check (Kev or Jev) improve command safety? | Yes for reliability and intent, only as an extra layer that can tighten; not as the security boundary | Catches off-task actions rules can't | D-031 |
| Q35 | How does the user choose a local model or Claude? | Named model profiles in `settings.json`; `--model` and `/model` | One switch for all providers | D-032 |
| Q36 | How is Kev or Jev configured? | `reviewer` section: provider, URL, key, confidence thresholds, timeout, fail to ask | | D-031 |
| Q37 | Can a Java developer work offline with a local model and Kev? | Yes, with everything cached; an explicit offline mode | Works without a connection | D-033 |
| Q38 | `engine.IsolateMode` is experimental in polyglot 25.0: set it or rely on the default? | Rely on the default `internal`, checked at startup; no experimental options | Keeps one process without unlocking experimental options | D-034 |
| Q39 | Does ModelJars run in the native binary fast enough (D-030)? | No: 0.1–1.2% of JVM speed; the Rust backend can't be built in; no 7B coder model in the catalog | v0.1 runs local models through Ollama or llama.cpp | D-035 |
| Q40 | Turn on memory protection keys? | Off by default in v0.1; `auto` probes at startup and continues without them, `on` requires them | Nice to have, experimental, and untested where it works | D-036 |
| Q41 | What if the watchdog's context close doesn't stop a run? | Escalate to engine close, then a recorded failure | One P0 run hung for 28 minutes after context close | D-037 |
| Q42 | Can the faster Rust model backend be used despite the Truffle conflict? | After v0.1, in a separate helper binary over stdio; first for a small reviewer model | In-process models are rare; most users run Ollama, llama.cpp or Claude | D-035 |
| Q43 | Where is the session log written? | `~/.jharness/sessions/<project-hash>/<session-id>.jsonl`, 0600 | Keeps repos clean; untrusted repos can't touch logs | D-038 |
| Q44 | Which git operations before the proxy exists? | Local ones only; network operations come with the proxy in P2 | Article V: no connection skips the proxy | D-039 |
| Q45 | Where are "always for this project" approvals saved? | The user folder, per project hash | A repo can't ship its own approvals | D-040 |
| Q46 | What do the permission modes allow, and does bypass skip the file policy? | Table in D-041; the file policy applies in every mode | Secrets and mounted folders stay protected | D-041 |
| Q47 | Where do API keys come from besides the environment? | `~/.jharness/secrets`, `NAME=value` lines, 0600 only | One lookup for both sources; a readable file is refused | D-042 |
| Q48 | Where are trust answers kept, how do settings layer, and what does an untrusted project's settings file do? | `~/.jharness/trusted.json`; user, project, session; untrusted: only `files.deny` and `plan` | A cloned repo can only make JHarness stricter | D-043 |
| Q49 | What do checkpoints save, and does undo touch git? | File contents per turn; files and conversation are restored, git history isn't | Works outside git; never rewrites history | D-044 |
| Q50 | Can the P1 gate pass without a Claude API key? | Yes, on a local model; the live Claude run becomes a v0.1 release check | Phase 2 isn't blocked on a key | D-045 |
| Q51 | Should every streamed chunk be in the session log, and can the thinking text be logged? | No: deltas are live only, and `model.completed` holds the reply and the thinking; thinking is never run | A readable log; thinking can't smuggle code into a run | D-046 |
| Q52 | Can builds run offline before the proxy and the OS sandbox exist, and where do "always" network answers go? | No: proxy, then sandbox, then builds; "always" appends to the project's `.jharness/network.rules` | Build scripts can't reach the network unchecked; saved answers stay with the project | D-047 |
| Q53 | How are child processes sandboxed, and what if the sandbox is missing? | Seatbelt on macOS, bubblewrap on Linux; workspace and private temp writable, credential folders hidden, no network yet; missing sandbox means no processes | A build script can't touch the rest of the machine; there is no unsandboxed fallback | D-048 |
| Q54 | How do Maven, Gradle and npm run, and how do they reach the network? | Typed build and test ops in trusted projects, with approval; the sandbox opens only the proxy port (Seatbelt rule; a bridge over a Unix socket on Linux); caches writable, their config files not | Builds keep using the user's caches while every connection goes through the rules | D-049 |
| Q55 | Should Gradle run on macOS, where its daemon needs loopback that Seatbelt can't narrow (and that also admits LAN connections)? | Yes, Gradle runs only, as a recorded Article V exception, LAN inbound included | Gradle works on every target; Maven and npm keep the proxy-only rule | D-050 |
| Q56 | Can Shift+Enter start a new line, and how many steps may a turn take? | Yes where the terminal reports it (`modifyOtherKeys`, only while the prompt reads); 100 steps by default, at most 500 | Familiar multi-line input without breaking Esc or Ctrl-C; longer tasks finish | D-051 |
| Q57 | Which command-execution checks are missing, and should git run filters and signing programs from a repo's config? | Pin executables, name the environment, cap arguments; no: repo-defined filters, signing and `core.worktree` are turned off, global drivers stay | A cloned repo can't run code through git, trusted or not | D-052 |
| Q58 | Should JHarness have a web UI, and when? | A read-only session viewer in Phase 4, next to headless mode, not gating v0.1; a companion UI with approvals and phone access after v0.1; no full web client | The viewer reuses the event stream and keeps the browser out of approvals for now | D-053 |
| Q59 | How do agents make HTTP requests and read web pages? | `api.http` and `api.fetch` through the proxy; writes ask; text-only, capped responses; no secrets injected; jsoup for HTML | Docs and APIs are reachable without loosening Articles V and VII | D-054 |

---

## Open items to settle before implementation

None. P0 can start.
