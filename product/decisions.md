# JHarness — Decisions

Decision log for JHarness. Each entry records what was decided, why, and what was rejected.
Status values: **Accepted** (settled), **Proposed** (recommended, not yet confirmed), **Open** (needs an answer before the related work starts).

Last updated: 2026-11-17

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
| D-013 | Three model providers: in-process ModelJars, OpenAI-compatible, native Claude | Accepted |
| D-014 | Default local models: Qwen2.5-Coder-7B, DeepSeek-Coder-V2-Lite | Accepted |
| D-015 | Synchronous `api`; virtual threads for host I/O; isolates on platform threads | Accepted |
| D-016 | macOS (Apple Silicon) and Linux first; Windows later | Accepted |
| D-017 | Extensions are plain folders in v1 | Accepted |
| D-018 | Memory protection keys: Linux only, optional | Accepted |
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

**Decision.** A provider interface with three implementations:
1. **ModelJars / Integrallis Models** — local models running inside the JHarness process.
2. **OpenAI-compatible Chat Completions** — Ollama, llama.cpp, vLLM and other providers.
3. **Native Anthropic Messages** — Claude.

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

**Decision.** Turn on `engine.MemoryProtection=true` on Linux when the CPU supports protection keys, detected at startup. Not available on macOS or Windows.

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
| Gradle | `-Dhttp(s).proxyHost/-Port` system properties on the command line, which also cover the wrapper download |

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

---

## Open items to settle before implementation

| Item | Why it blocks | Blocks |
| --- | --- | --- |
| Go/no-go thresholds for the P0 spike (`specs/001-p0-spike/plan.md`) | The spike can't be judged without them | P0 |
