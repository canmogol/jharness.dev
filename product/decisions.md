# JHarness — Decisions

Decision log for JHarness. Each entry records what was decided, why, and what was rejected.
Status values: **Accepted** (settled), **Proposed** (recommended, not yet confirmed), **Open** (needs an answer before the related work starts).

Last updated: 2026-11-26

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
| D-008 | Agent code has no shell; the host runs typed git / Maven / Gradle / npm operations | Accepted; amended by D-066 (Maven, Gradle and npm come from tool definitions) |
| D-009 | v1 build tools: git, Maven, Gradle, npm | Accepted; amended by D-066 (build tools are definition files) |
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
| D-031 | Optional action reviewer, a decision model (System One API, to be used via /v1/systemone API, such as Jev or a locally running equivalent, i.e., Laya) that can only tighten decisions | Accepted |
| D-032 | Named model profiles in `settings.json`; switch per session or per agent | Accepted |
| D-033 | Offline mode | Accepted |
| D-034 | Internal isolate mode comes from the engine default, checked at startup; no experimental options | Accepted |
| D-035 | ModelJars in the native image: no-go for v0.1; local models through Ollama or llama.cpp; after v0.1, a Rust-backend model helper process | Accepted |
| D-036 | Memory protection keys off by default in v0.1; `auto` (probe at startup) and `on` are opt-in | Accepted |
| D-037 | The wall-clock watchdog escalates: context close, then engine close, then a recorded failure | Accepted |
| D-038 | Session logs live in the user folder, not the project | Accepted; folder and file names changed by D-070 |
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
| D-050 | Gradle on macOS may use any loopback port, for its daemon, and so can also accept LAN connections: a recorded exception to Article V | Accepted; amended by D-066 (`loopback` in a user-folder definition) |
| D-051 | Shift+Enter for a new line through xterm `modifyOtherKeys` (on only while the prompt reads); `agent.maxSteps` 100 by default, at most 500 | Accepted |
| D-052 | Command-execution checks: executables pinned to the file they resolved to; named environment variables only; argument size caps; git never runs programs from the repo's own config (filters, signing) and ignores `core.worktree` | Accepted |
| D-053 | A read-only web session viewer, built in Phase 4 next to headless mode but not gating v0.1, on `127.0.0.1` with a session token; no approvals from the browser yet; a companion UI with approvals and phone access comes after v0.1 | Accepted |
| D-054 | `api.http` and `api.fetch` through the egress proxy: GET and HEAD are reads, other methods ask; 10 MiB read and 200,000 characters returned by default (configurable), text only; headers and bodies logged only when debug switches are on, secrets redacted; no secrets injected; jsoup turns HTML into text; `HttpClients` moves to `jharness-net` | Accepted |
| D-055 | Git `fetch`, `pull`, `push` and `clone` over HTTPS only, through the proxy port in the OS sandbox; no force push; tokens per host from user settings only, scoped to that host and redacted; offline mode denies what no rule allows, runs build tools offline and refuses git network ops | Accepted |
| D-056 | Security suites: one catalogue of attack cases (injection, malicious repo, sandbox escape, runaway) against a real session with a model that obeys the attack; shared canary, attacker and file checks; run on the JVM and in the native smoke binary on Linux and macOS; skips never count as passes | Accepted |
| D-057 | A Maven, Gradle, npm or git network run that fails because the macOS sandbox refused its connection to the proxy port is run again, up to 3 runs in all, within the call's timeout, with a `process.retried` event | Accepted |
| D-058 | Model requests that fail before anything reached the user are retried: 408, 409, 429, 5xx, failed connections and overloaded stream errors; `retry-after` or exponential back-off; `retries`, `retryMaxWait` and `responseTimeout` per profile; Esc ends a wait | Accepted |
| D-059 | Debugging tools: `--debug` shows every event and the thinking and prints stack traces; `jharness log` lists and prints sessions with control characters escaped; host crashes write a local crash report (no event bodies, newest 20 kept, never sent) and exit 3; `model.response` logs status, request id and allow-listed headers | Accepted |
| D-060 | Everything JHarness prints (TUI, `-p`, errors) escapes C0 controls except newline and tab, DEL, C1 controls and bidi controls and drops carriage returns; only its own styling and picocli's fixed help text (colors on a terminal, off with `NO_COLOR`) reach the terminal raw | Accepted |
| D-061 | Typed build parameters that only skip work, pick a project profile or change parallelism: Maven `install`, `skipTests`, `profiles`, `threads`; Gradle `skipTests`, `parallel`, `continue`; npm `omitDev`; the approval shows them, "always" keys on the operation | Accepted; amended by D-066 (the parameters live in the shipped definitions) |
| D-062 | In the TUI, Tab on a lone `/` prints the `/help` text above the prompt; Tab after a partial `/command` completes it, listing matches with descriptions taken from the help text | Accepted |
| D-063 | Custom agents: a folder with `agent.json` (description, model, tools, maxSteps, plan), `prompt.md` and `helpers.js`; chosen with `--agent` at start; they only narrow; project agents only in trusted projects | Accepted |
| D-064 | `api.spawnAgent` runs a subagent: fresh conversation with the task, own isolate (`subagent` profile), the session's gate narrowed by a custom agent, optional model profile; depth one, 10 per turn and 100 steps by default (configurable); `api.models()` lists profiles; returns its final reply | Accepted |
| D-065 | Background subagents: `spawnAgent({background: true})`, `api.waitAgent`, `api.agents`, `api.stopAgent`; at most 4 running (configurable); they never ask (questions denied as unattended); uncollected replies come with the next prompt; Esc leaves them running, closing the session stops them; `/agents`, `/stop`, `jharness log --agent/--source` | Accepted; questions held in interactive sessions since D-072; `api.waitAgent` removed and replies start turns since D-087 |
| D-066 | Build tools are JSON definition files in `~/.jharness/tools/` (seeded on first run from `examples/.jharness/tools/`) and a trusted project's `.jharness/tools/`; typed fields map to fixed arguments; git and `rg` stay in code | Accepted |
| D-067 | `api.tool(name, request)` runs every defined tool and `api.tools()` lists them; `api.maven`, `api.gradle` and `api.npm` are removed | Accepted |
| D-068 | git and `rg` stay in Java code, not tool definitions: per-operation approvals, use in untrusted projects, injection defences, secrets, undo and structured results need host code | Accepted |
| D-069 | `/follow [NAME]` shows a running background subagent like the main loop (streamed reply, steps, diffs) until Esc or its end; it stays a view, and shared model clients name the subagent on its events | Accepted |
| D-070 | Project folders under `sessions/`, `projects/` and `checkpoints/` are the workspace path as a slug plus 7 hex characters; session ids are the UTC start time plus 4 hex characters; old folders are left alone | Accepted |
| D-071 | `/switch NAME` makes the prompt talk to a subagent: new turns in its own conversation, its questions asked instead of denied, Esc stops its turn; subagents stay open after their task (at most 16 idle); `/switch main` goes back | Accepted |
| D-072 | In an interactive session a background subagent's question waits (up to `agent.backgroundQuestionWait`, 10 min) with a notice; `/switch` asks it; `agent.backgroundQuestions: deny` and `-p` deny at once | Accepted; `/answer` asks in place since D-087 |
| D-073 | JS hooks: `NAME.js` in `~/.jharness/hooks/` and trusted projects' `.jharness/hooks/` define `preTool` (block only, fails closed), `postTool` (observe) and `stop` (go on, at most 3 times a turn); each runs in its own isolate with subagent limits, and its own api calls are gated and run no hooks | Accepted |
| D-074 | Slash commands: `NAME.md` templates (`$ARGUMENTS`) and `NAME.js` commands (`run(args)` returns a prompt, `{print}` or nothing) in `~/.jharness/commands/` and trusted projects; they can't replace built-ins and also work in `-p` | Accepted |
| D-075 | `api.todos` keeps each agent's todo list, shown in the TUI; `/plan` toggles plan mode, which notes itself in each message; the main agent's `api.proposePlan` asks the user to leave plan mode and carry out the plan | Accepted |
| D-076 | MCP client: our own small client over stdio and streamable HTTP; servers only from the user's `mcp` settings; stdio servers in the OS sandbox through the proxy; each `tools/call` asks (`kind: mcp`, "always" per server/tool); `api.mcp({list})` and the prompt list the tools | Accepted |
| D-077 | Token counting and compaction: each profile has a `contextWindow`; the model's own counts, else about 3.5 characters a token; at `agent.compactAt` (0.8) of the window minus the reply size, the model summarizes the older conversation; `/context` and `/compact`; turns in a summary can't be rewound | Accepted |
| D-078 | Prompt caching and `@file` mentions: Claude requests mark the system prompt and the last message for the 5-minute prompt cache (`promptCache`, on by default); `inputTokens` counts the whole prompt; `@path` in a user prompt attaches a file or folder under the file policy, within a size budget | Accepted |
| D-079 | Command aliases and argument completion: `/quit` and `/q` are aliases of `/exit`, not listed; built-in names and aliases win over command files, and the clash is reported; Tab after a command lists its choices and cycles through them; command files declare `args` | Accepted |
| D-080 | Status line, a new extension point: a line under the input on real terminals with built-in items chosen by `ui.statusLine`; `~/.jharness/statusline.js` can return the whole line from a JSON snapshot, in an isolate with no `api`; refreshed on events, throttled; failures fall back to the default line | Accepted |
| D-081 | Resume and fork: the main conversation is saved after each turn, compaction, undo and rewind as `<session-id>.state.json` (0600, atomic) next to the log; `--resume [ID]` continues the same session and log, `--fork [ID]` starts a new session from a copy; earlier turns can't be rewound | Accepted |
| D-082 | Headless output: `-p --output text|json|stream-json`; json is one result object (reply, stop reason, usage, files changed, denied actions); stream-json is every event as a JSON line then the result; exit 0 done, 1 failed or stopped early, 2 bad input; `--mode` sets the permission mode | Accepted |
| D-083 | Web viewer: `jharness-web` with the JDK `HttpServer` on `127.0.0.1`, a random token, `Host` and `Origin` checks, GET only and strict headers; Server-Sent Events of the stream-json lines; `jharness view [SESSION]` follows a log, `/view` serves the running session live; the architecture test allows its listening socket | Accepted |
| D-084 | Usage tracking: a profile's `price` (US dollars per million tokens, set by the user; no shipped list) puts `costUsd` on each `model.completed`; `/usage`, `jharness usage`, the headless result and a `cost` status item add the logged events up, by agent and session | Accepted |
| D-085 | Evals: small repository tasks with hidden checks in `evals/tasks/`, run by the maintainer script `scripts/eval.py` through headless `jharness -p --output json`; a check must fail before the run; CI runs recorded solutions; live scores per release in `docs/evals.md` | Accepted |
| D-086 | Manual checks: `scripts/viewer-check.mjs` (the web viewer in Chromium, Firefox, WebKit with Playwright, and Safari through safaridriver) and `scripts/tui-tmux-check.py` (the TUI at several sizes in tmux); never in CI; run after viewer or terminal UI changes and before each release, on Linux and macOS | Accepted |
| D-087 | Subagents never block the main chat: `api.waitAgent` is removed; a finished background subagent's reply starts a main turn by itself (at an empty prompt; `agent.autoTurns`, default 10 in a row); `-p` runs until no background subagent is left; held questions never interrupt and `/answer [NAME]` asks one in place | Accepted; automatic turns run behind a live prompt since D-088 |
| D-088 | The main prompt never blocks: on terminals that aren't dumb, main turns run on a worker while the prompt reads; output prints above it a line at a time; messages sent during a turn are queued and go next as one; Esc on the prompt stops the turn; the main agent's questions are held as notices (`question.waiting`) and `/answer main` asks them; commands that change the conversation wait for the turn; dumb terminals keep foreground turns | Accepted |
| D-089 | Deletes: `api.deleteFile` (a `write`, as `writeFile`) and `api.deleteFolder` (kind `delete`: asked even in accept-edits unless a saved rule or bypass allows it); checkpoints make both undoable; the root, `.git`, `.jharness`, mounts, denied files and folders with links, denied files, files over 10 MB or more than 1,000 files are refused | Accepted |
| D-092 | Go tool: shipped `go.json` (build, test, vet, mod-tidy, mod-download, version) and `gofmt.json` (check); proxy through `env`; `GOPROXY=https://proxy.golang.org` with no `direct`, `GOVCS=*:off`, `-buildvcs=false`, `GOTOOLCHAIN=local`, `GOENV=off`; caches under `~/go/pkg` and `~/.cache/go-build`; `~/` env values are home folders; Go hosts in `examples/network.rules`, not shipped | Accepted |

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

**Consequences.** A hook that needs git or a build tool calls `api.git` or `api.tool` (D-067), with the usual approvals. How hooks are written and run: D-073.

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
- **One back-end: a decision model behind the System One API** (`POST /v1/systemone`), with one client. Only the URL and key differ:
  - Jev, the hosted decision model. The action and its context leave the machine; the docs must say so.
  - A locally running equivalent, such as Laya, run by the user on this machine or the LAN.
- **Off by default.** Lands in P3, next to hooks.

**Configuration** (`settings.json`):

```json
"reviewer": {
  "enabled": true,
  "url": "http://127.0.0.1:8000/v1/systemone",
  "model": "jev-latest",
  "apiKeyEnv": "SYSTEMONE_API_KEY",
  "askBelow": 0.90,
  "denyBelow": 0.20,
  "timeoutMs": 3000,
  "onError": "ask"
}
```

The reviewer endpoint is allowed through the proxy automatically, like the model endpoint. Every verdict emits a `review.*` event with the confidence.

**How it asks** (spec 038). One `noul` question per operation, named `fits`: does this action fit the user's request? The `state` holds the main agent's latest request, the calling agent and a subagent's task, the workspace name, and the action (api, kind, summary, detail with long fields cut). The answer's `noul`, a probability from 0 to 1, is the confidence:

| `noul` | Effect |
| --- | --- |
| At or above `askBelow` | Decision unchanged |
| Below `askBelow` | Asks the user, with the reviewer's reason, even in bypass or accept-edits mode or with a saved rule |
| Below `denyBelow` | Denied without asking |

- Reviewed: every state-changing operation that plan mode doesn't deny, before the mode and saved rules. Not reviewed: reads, events, session state and the proxy's own network questions.
- Failure: any status other than 200, no answer within `timeoutMs`, or no `noul` from 0 to 1. `onError` is `ask` (the default; denied when no one can answer) or `deny`.
- User-only: a project's `reviewer` is ignored, because it could send the context to its own server or turn the reviewer off. A named key that isn't set stops start-up.
- Events: `reviewer.ready`, `review.verdict` (confidence, outcome `keep`, `ask` or `deny`) and `review.failed`.
- Hosts that serve the API: TypeSafe (`https://api.typesafe.ai/v1/systemone`, model `jev-latest`) and DigitalOcean (`https://inference.do-ai.run/v1/systemone`, model `typesafe-jev-1.13.0`).

**Why.** Rules can't judge intent: a prompt-injected commit of unrelated files, an unrequested npm script or a POST to an allowed host all pass the rules. A reviewer catches some of these. It can't be the security boundary because it is probabilistic and judges text an attacker may control.

**Costs.** A local decision model such as Laya is a separate server, outside the one-binary principle, so it stays optional and user-run. Jev sends the action and its context off the machine. Each reviewed operation adds latency.

**Source.** User request and acceptance on 2026-11-17. Changed by the user on 2026-11-24: one System One API back-end (Jev or a local equivalent such as Laya) instead of Kev, Jev or an own model profile. Refined by spec 038 on 2026-11-24: the `model` key, one `noul` question, the review order and user-only settings.

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

**What works offline:** a ModelJars model already in the cache or a model already pulled in Ollama; a local decision model such as Laya; Maven and Gradle with dependencies, plugins and the Gradle wrapper distribution already downloaded; npm with packages in its cache; git (except fetch and push), `rg` and file edits. JHarness itself never needs the network: no license check, no telemetry, no required update check.

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

The path to revisit is a separate native helper binary that runs the Rust FFM backend without Truffle, so the shared-arena limit doesn't apply. JHarness starts it through `ProcessRunner` and talks to it over stdio, like a stdio MCP server; no socket. Its first use is a small reviewer model for D-031 (about 0.8B), so the reviewer works when the user runs no local System One server such as Laya and doesn't use Jev. Most users are expected to run coding models through Ollama or llama.cpp, or use Claude. Before adoption: prove the helper builds and runs natively on linux-amd64 and macos-aarch64, and settle in that spec the constitution Article IV exception for the helper process and for `vectors-core`'s `sysctl` call inside it. Added 2026-11-18.

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

**Decision.** (Folder and file names since D-070: `sessions/SLUG-HASH/s-TIME-XXXX.jsonl`.) Each session's JSONL log is `~/.jharness/sessions/<project-hash>/<session-id>.jsonl`, where `<project-hash>` is the first 16 hex characters of the SHA-256 of the workspace's real path. Folders are created with mode 0700 and log files with 0600.

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
- `http.request` and `http.response` events record method, URL, status, type, size and time. Headers and bodies stay out of the log by default, since they may hold tokens.
- For debugging, `http.logHeaders` and `http.logBodies` (both off by default) add request and response headers and bodies to those events. Logged bodies are the request body (at most 1 MiB) and the text read from the response (at most `http.maxResponseBytes`); binary bodies log only their size. When headers are logged, `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` and any header whose name contains `token`, `key`, `secret`, `auth`, `password` or `session` (except `WWW-Authenticate`) are logged as `[redacted]`; `http.redactSecrets: false` turns that off. Bodies are never redacted.
- jsoup (MIT, pure Java) turns HTML into Markdown-like text, after a Native Image check on Linux and macOS. `HttpClients` moves from `jharness-llm` to `jharness-net`, so the sandbox can use it without depending on the model providers.

**Why.** Agents need to read documentation and call web APIs. One client and the proxy keep Article V intact. Treating writes like file writes keeps approvals consistent. Caps keep a large page from flooding the model's context.

**Rejected.** One `api.http` with a text mode (one call doing two jobs). GET only (blocks ordinary API work). Base64 binary bodies (images and archives waste context). Injecting secrets (reopens Article VII; needs its own design). An in-house HTML converter (weak on real-world HTML). Logging headers and bodies by default (they may hold tokens). One switch for headers and bodies together (too coarse). A smaller cap for logged bodies (debugging needs the whole response).

**Source.** User answers while specifying 016-http-and-web-fetch, 2026-11-22; debug logging switches added at the user's request before 016 merged, 2026-11-22.

## D-055 — Git network operations and offline mode

**Decision.**
- `api.git` adds `fetch`, `pull` (fast-forward only), `push` and `clone`. This completes D-039. `fetch` is a read; `pull`, `push` and `clone` ask, and plan mode denies them. Push never forces or deletes, and doesn't push tags.
- Only HTTPS remotes work: `protocol.https.allow=always`, and every other transport is set to never, ssh included (as D-025 says). Redirects and cookie files are off. Calls are refused when the repository's own config sets credential helpers, `core.askPass`, `core.sshCommand`, `protocol.*`, cookie files, or upload or receive packs.
- git runs in the OS sandbox with only the proxy port open (D-049). The proxy and its credentials reach git through `GIT_CONFIG_*` environment entries, never argv. No askpass, credential helper or terminal prompt runs.
- `"git": {"credentials": {"<host>": "<SECRET_NAME>"}}` in the user settings maps a host to a secret (D-042). The token is sent as an `extraHeader` scoped to `https://<host>/`, so it goes only to that host, and it is redacted from output and events. Project settings can't set it.
- Offline mode (`--offline`, or `"offline": true` in any settings layer, including an untrusted project's) works as follows:
  - the proxy denies everything no `allow` rule matches, and the files' `default` lines don't apply;
  - model endpoints are allowed automatically only on loopback or private addresses;
  - Maven runs with `-o`, Gradle with `--offline` and npm with `npm_config_offline=true`;
  - git network ops fail up front.
- On macOS, Seatbelt now and then refuses the first connect to the proxy port. git network runs that never reach the proxy are retried up to 3 times; nothing was sent at that point.

  This settles how D-033 is applied.

**Why.** US-2.6 and US-3.3. HTTPS through the proxy keeps Article V intact for git. A per-host token mapped by the user makes push useful without giving the repository or the model a say over secrets. Keeping explicit `allow` rules in offline mode lets a LAN mirror or a local model keep working.

**Rejected.** SSH remotes (they bypass the HTTP proxy). Plain-HTTP remotes (tokens in clear). The user's own git credential helpers (programs outside JHarness's control). Proxy settings on argv (visible in `ps`). Offline mode that ignores `allow` rules (it would break local mirrors).

**Source.** Recommended options accepted while the user was away, specifying 017-git-network-and-offline-mode, 2026-11-22.

---

## D-056 — Security test suites

**Decision.**
- `dev.jharness.testkit.security.SecuritySuite` holds one catalogue of cases with IDs: `INJ-NN` (prompt injection), `REPO-NN` (malicious repository), `ESC-NN` (sandbox escape) and `RUN-NN` (runaway code). It is in `src/main`, so the JVM tests (`SecuritySuiteTest`) and the native smoke binary (`jharness-smoke`, or `--security` for the suites only) run the same cases.
- Each case assumes the model is fully steered: a replay provider plays the attack's JavaScript. The case opens a real `Session` (proxy, OS sandbox, permission engine, isolate) with settings layered from a fake home and workspace.
- Every case gets the same shared checks:
  - home canaries never reach the model, the session log or the attacker;
  - a loopback attacker gets no connection;
  - nothing outside the workspace changes;
  - no marker file from hostile code exists.
- Containment never depends on the user except in the injection suite, where the user denies. In the repository and escape suites, the user approves every action and denies only new hosts.
- A case that can't run on a machine reports `skip`. A skip never counts toward the Phase 2 gate, which needs every case passing on Linux and macOS CI.
- Holes the suites find are fixed in the same spec, with the case as the regression test. Spec 018 found one: a CPU limit that trips inside the regex engine's backtracking had no message and was reported as `unknown`. It is now named `sandbox.MaxCPUTime` when the run lasted at least that limit.
- One recorded exception on macOS, RUN-08. A build-script grandchild that its parent orphans before the timeout outlives the process-tree kill: launchd adopts it, and Seatbelt has no PID namespace. It stays inside the Seatbelt sandbox, and the case checks this: no writes outside and no connections out. The case reports `exception`, not `pass`, on macOS. Closing it (for example, process groups through `posix_spawn`) is a follow-up, and a script that calls `setsid` would still escape it. The user accepted the exception for the Phase 2 gate on 2026-11-22.
- `docs/security-suites.md` lists every case, what contains it and the layer that does.

**Why.** Mission measure "sandbox-escape and injection suites 100% contained on Linux and macOS", the Phase 2 gate, and Article X. Testing against a real session, rather than each layer alone, shows that the layers work together. Assuming an obedient model keeps the result independent of any model's judgement.

**Rejected.** Live model runs as the suite (they aren't deterministic and don't show the worst case). Separate test lists for the JVM and native runs (they would drift). Counting skips as passes.

**Source.** Recommended options accepted while the user was away, specifying 018-security-test-suites, 2026-11-22.

---

## D-057 — Retry runs the sandbox refused at the proxy port

**Decision.**
- When a Maven, Gradle or npm run exits non-zero, didn't time out, and its output has a refusal line, JHarness runs the same argv, environment and grants again, up to 3 runs in all. A refusal line has `EPERM` or "Operation not permitted" and the proxy address with its exact port (`127.0.0.1:<port>`, `localhost:<port>` or `127.0.0.1 port <port>`).
- git network operations (D-055) use the same retry. Their curl message (`Failed to connect to 127.0.0.1 port <port>`) still counts.
- All runs share the call's timeout. A retry gets the time left and starts only if at least 1 s is left. The approval covers every run.
- Each retry emits `process.retried` (`executable`, `attempt`, `attempts`, `reason: proxy-connect-refused`, `remainingMs`). The result is the last run's.

**Why.** On macOS, Seatbelt now and then refuses a connect that its `localhost:<port>` rule allows. Another sandboxing project reproduced it with a minimal profile: on some macOS 14 machines, one connect in a short window about every 15 s fails, and macOS 26 didn't show it. No root cause, Apple bug or profile fix is known, and other tools use the same rule. JHarness can't retry the connection, because the tool opens it inside the sandbox, and npm doesn't retry `EPERM`. Re-running the approved command is what the README asked the user to do by hand.

**Rejected.** A wider rule such as `*:<port>` (it would let builds reach that port on any host, against Article V). A Unix-socket proxy path on macOS (only curl can use it). Retrying every failed build (it would repeat real failures). Asking again before a retry (the user already approved the operation).

**Source.** User, 2026-11-22: "Retry connections refused if possible; otherwise, we re-run the process." Recommended options accepted while the user was away, specifying 019-build-retry-on-proxy-refusal.

---

## D-058 — Model retries and rate limits

**Decision.**
- Each model profile (D-032) takes `retries` (0 to 10, default 4), `retryMaxWait` (1s to 10m, default 60s) and `responseTimeout` (5s to 30m, default 120s).
- A request is sent again only if nothing from it reached the user yet. Retried:
  - HTTP 408, 409, 429 and 5xx (Anthropic's 529 included);
  - failed connections, including a proxy tunnel that got a 5xx;
  - Anthropic `overloaded_error`, `rate_limit_error` and `api_error` events, and broken streams, before any text or thinking.
- An `x-should-retry` header overrides the status rule.
- Never retried: other 4xx, the response timeout, a tunnel the egress proxy refused, a missing key, cancellation.
- The wait is `retry-after-ms`, else `retry-after` (seconds or HTTP date), else 1 s doubling up to `retryMaxWait`, each cut to a random 50–100%. A server asking for more than `retryMaxWait` fails at once.
- Each retry publishes `model.retry` and shows a line in the UI. `model.error` comes once, after the last attempt, with `(gave up after N attempts)`. Esc ends a wait at once.

**Why.** Rate limits and overloads from hosted APIs, and local servers that are restarting, are passing failures that ended a whole turn. The status and header rules match the official Anthropic and OpenAI SDKs, so servers built for them behave as expected. Not resending after streaming keeps a reply from appearing twice. Not retrying the response timeout keeps a slow local model from being sent the same long prompt again; `responseTimeout` is the setting for that.

**Rejected.** Retrying inside the agent loop (a failed turn would redo work and lose the reason). Retrying after text was shown. Unlimited retries or waits. Slowing down early from rate-limit budget headers (later, if needed). Falling back to another profile (a separate feature).

**Source.** Roadmap item "Model profiles; retries and rate-limit handling". Recommended options accepted while the user was away, specifying 020-model-retries-and-rate-limits, 2026-11-23.

## D-059 — Debug mode, `jharness log` and crash reports

**Decision.**
- `--debug` shows every event inline and the model's thinking from the start (as `/events all` and `/thinking`), writes every logged event to stderr as a JSON line with `-p`, and prints stack traces. It is a command-line flag only; no settings layer sets it.
- `jharness log` lists the current workspace's sessions, newest first (`--all` for every project, `--limit N`). `jharness log SESSION` prints one event per line: local time, turn, type and the type's main fields cut at 120 characters (`--full` for whole bodies, `--type PREFIX` to filter, `--json` for the raw lines). SESSION is an id, a unique prefix, `last` or a path to a `.jsonl` file; an id is letters, digits and `-` only, so it can't reach outside the logs folder.
- Output of `jharness log` escapes C0 and C1 controls, DEL and Unicode bidi controls, in `--json` too (as `\uNNNN`, so the JSON keeps its value).
- An unexpected exception or error in the host writes `~/.jharness/crash/crash-<UTC time>[-<session>].txt` (folder 0700, file 0600) and exits 3; settings, usage and I/O errors keep exit 2. The report holds the version, time, OS, runtime, thread, session id and log path, the stack trace, and the time, turn and type of the session log's last 30 events. No event bodies; the home folder shows as `~`; a note says to check it before sharing. The newest 20 reports are kept. With a session open, `session.crashed` (`exception`, `report`) is logged first. An exception on another thread writes a report and a line on stderr, and the session goes on.
- Each model response publishes `model.response` with `status`, `attempt`, `durationMs`, `requestId` (from `request-id` or `x-request-id`) and only these headers: `retry-after`, `retry-after-ms`, `x-should-retry`, `content-type`, `anthropic-ratelimit-*`, `x-ratelimit-*`.

**Why.** US-3.6 asks for an audit log that `jharness log` shows; the JSONL log alone is hard to read. A crash report gives a bug report the facts without a debugger, and keeping it local follows D-033 (no telemetry). Leaving bodies out keeps prompts and file text out of something users paste into issues. Logged text is untrusted (it holds model replies, files and tool output), so printing it raw would let a hostile file rewrite the terminal. An allow list of headers can't leak a credential the way a deny list can, and request ids and rate-limit headers are what a provider asks for.

**Rejected.** Uploading crash reports or any telemetry. Event bodies in reports. Logging every model response header with redaction. A `debug` settings key (a project could turn it on; the flag is enough). Keeping the session alive after a crash on the main thread (its state is unknown). Escaping the TUI's live output in this spec (a separate change).

**Source.** Roadmap item "`--debug`, `jharness log <session>`, local crash reports". Recommended options accepted while the user was away, specifying 021-debug-log-and-crash-reports, 2026-11-23.

## D-060 — Escape control characters in live output

**Decision.** The TUI prints through one escaping writer; only `Styler` output (which escapes its input first) and the `modifyOtherKeys` sequences use the raw terminal writer. `jharness -p` wraps stdout and stderr the same way. The exception is picocli's own help, version and usage-error output (`--help`, `--version`, a bad option), which goes to the raw streams so its colors show (spec 044): its text is fixed in the code, and a usage error's message, which echoes the typed arguments, is escaped. Help colors follow picocli's auto mode (a terminal only) and are off when `NO_COLOR` is set. C0 controls other than newline and tab, DEL, C1 controls and Unicode bidi controls show as `\e`, `\xNN` or `\uNNNN`; a carriage return is dropped. It is always on. The session log keeps the exact text.

**Why.** Model replies, file contents in diffs, tool and server error messages and approval questions are untrusted text. Printed raw, they can change the terminal title, clear the screen, move the cursor to rewrite an approval question or reorder text with bidi controls. Escaping at the writer covers every print; escaping in `Styler` keeps colors working.

**Rejected.** Escaping at each print site (easy to miss one). Stripping instead of showing (hides that something was there). A setting to turn it off (no case needs raw control characters in a reply).

**Source.** Follow-up from spec 021, which escaped only `jharness log`; the user asked for it as spec 022 before the Phase 2 gate run, 2026-11-23. Refined 2026-11-24 (spec 044): `jharness --help` printed its colors as literal `\e[33m` text, so picocli's own output was moved to the raw streams.

## D-061 — Typed build parameters

**Decision.**
- Maven gets an `install` operation (fields as `verify`). New fields:
  - `skipTests: true` on `package`, `verify` and `install` → `-DskipTests` (refused with `tests`);
  - `profiles` (up to 10 ids, `!` to turn one off) → `-P`;
  - `threads` (1 to 32, or `1C` to `4C`) → `-T`.
- Gradle: `skipTests: true` on `build` and `check` → `-x test`; `parallel: true` → `--parallel`; `continue: true` → `--continue`.
- npm: `omitDev: true` on `ci` and `install` → `--omit=dev`.
- Booleans must be JSON booleans and strings must match their patterns; each field becomes one fixed flag. Nothing else reaches the command line.
- The approval question shows the parameters (`maven package · skipTests, profiles dev`); "always" still saves the operation alone.

**Why.** "Build without running the tests" and "build the dev profile" are everyday requests that the typed operations couldn't express. These flags only skip work, pick a profile the trusted project already defines or change parallelism, so they can't run new code, read or write new places or reach new hosts. `install` writes only to `~/.m2/repository`, which builds already write. Keeping "always" per operation means a saved approval for `maven package` covers the same build with or without tests.

**Rejected.** Free-form `-D` or Gradle `-P` properties (they configure plugins, which can run code or change paths). A general Gradle `-x TASK` (rarely needed beyond tests). `-U` and `-q` (little value). Making "always" depend on the parameters (a new prompt for every combination).

**Source.** User question about `mvn clean install -DskipTests`, then a request for skip-tests and other harmless, common parameters as their own spec, 2026-11-23.

## D-062 — `/` and Tab list the commands

**Decision.** Tab on a prompt line holding only `/` prints the `/help` text above the prompt and keeps the `/`. Tab after a partial first word starting with `/` completes the command name; several matches are listed with their descriptions. Names and descriptions are parsed from the `/help` text. The help lists the commands in alphabetical order, each with its forms (`/model NAME` under `/model`), then a `Keys:` section for `@PATH`, the newline keys and Esc (spec 046). Tab elsewhere completes nothing, as before.

**Why.** The user asked for `/` and Tab to show the commands. Completing names is what Tab does in every shell, and taking the list from the help text keeps one source.

**Rejected.** A separate command table (two lists to keep in step). Completing arguments and paths now (a larger change with its own questions, such as file policy for path completion).

**Source.** User request while 023 was merged, 2026-11-23; recommended options accepted while the user was away.

## D-063 — Custom agents

**Decision.**
- A custom agent is a folder `NAME/` in `~/.jharness/agents/` or the project's `.jharness/agents/` (D-017). A project agent replaces a user agent of the same name.
- `agent.json`: `description` (required), `model` (a profile), `tools` (allowed `api` methods; `readContext` always), `maxSteps` and `plan`. `prompt.md` is added to the system prompt; `helpers.js` is evaluated in each block's fresh context just before the block. Both are at most 32 KB, and links are refused.
- `jharness --agent NAME` picks it when the session starts; `--model` still wins over its `model`. `/agent` lists the agents. There is no switching within a session.
- An agent only narrows: methods outside `tools` are denied before the permission engine, `plan: true` is plan mode, `maxSteps` replaces the session's.
- Project agents load only in trusted projects; user agents always load.
- `session.started` records `customAgent` and `agentSource`. `examples/agents/reviewer/` ships as the working example.

**Why.** US-4.1 asks for a repo folder that defines prompt, model, tools and optional JS. Helpers in the same isolate give an agent reusable code without new privileges. A repository's prompt and helpers can steer the model, and its model choice can send code to another server, so they need project trust like other project settings (D-011). Picking the agent at start keeps one system prompt per session.

**Rejected.** Markdown front matter instead of `agent.json` (another parser; JSON matches settings). Agents that raise limits or permission modes. Running helpers in a separate context (functions couldn't be called from the block). Switching agents mid-session (the conversation was built under another prompt). Loading untrusted project agents with a warning.

**Source.** Roadmap item "Custom JS agents in `.jharness/agents/`". Recommended options accepted while the user was away, specifying 025-custom-agents, 2026-11-23.

## D-064 — Subagents

**Decision.**
- `api.spawnAgent({task, agent, model, maxSteps})` (a string is the task) runs a subagent and returns `{agent, reply, stopReason, steps, error?}`.
- A subagent is a new agent loop with a fresh conversation holding only the task, in a new isolate with the `subagent` sandbox profile. Its system prompt is the session's plus the custom agent's section and a note that it must end with a complete reply.
- It uses the session's permission gate, so approvals, plan mode and the main agent's `tools` apply. A named custom agent narrows further with `tools`, `plan`, `maxSteps` and `helpers.js`, and its `model` is used. A request's `model` (a profile name) wins over the agent's.
- `api.models()` returns the profile names with provider, model id and notes, and the session's current one; never base URLs or key variable names. It is refused inside a subagent.
- Depth one: `spawnAgent` is refused inside a subagent. `agent.subagentsPerTurn` (default 10, 1 to 1000) caps subagents per turn of the main agent. Steps default to `agent.subagentMaxSteps` (default 100, 1 to 500) and never exceed the session's `agent.maxSteps`.
- Spawning doesn't ask (`kind: agent`); the subagent's own calls do. The parent's block clock stops during the spawn; Esc stops the subagent and the turn.
- Its events carry `sub-N` and the parent's turn, so its changes are part of that turn for `/diff` and `/undo`. `subagent.started` and `subagent.completed` frame its run.

**Why.** US-4.4 and plan.md: a subagent in its own isolate that returns its result. A fresh conversation keeps the subagent focused and its context small. Sharing the gate means a subagent can never do what the main agent couldn't. Depth one and a per-turn cap bound the cost of a runaway block. Keeping the parent's turn makes undo cover all of a turn's changes.

**Rejected.** Parallel or background subagents (the gate and the TUI ask one question at a time; later: D-065). Nesting. Asking before each spawn (the subagent's own changes ask). Giving the subagent the parent's conversation (large and unfocused). Its own turn numbers (undo would miss its changes).

**Source.** Roadmap item "Subagents via `api.spawnAgent`". Recommended options accepted while the user was away, specifying 026-subagents, 2026-11-23. The `model` field, `api.models()` and the configurable limits were asked for by the user in review, 2026-11-24.

---

## D-065 — Background subagents

**Decision.**
- `api.spawnAgent({..., background: true})` returns `{agent, status: "running"}` at once; the subagent runs on its own thread. `agent.subagentsRunning` (default 4, 1 to 16) caps how many run at once; they count towards `agent.subagentsPerTurn`.
- `api.waitAgent(name | {agent, timeoutMs})` returns the subagent's result (default wait 10 minutes, at most 1 hour; on timeout `status: "running"`), outside the block clock; Esc ends the wait. `api.agents()` lists the session's subagents; `api.stopAgent(name)` stops one. A subagent can't call any of them.
- A background subagent never asks the user: a question becomes `Prompter.Answer.UNATTENDED` and the operation is denied with that reason. Saved rules, permission modes and session allows still apply. While no turn runs, the TUI answers every question that way too, including network questions.
- Replies that finished and weren't collected are put before the main agent's next prompt, once each (`subagent.delivered`).
- Esc stops the turn and a foreground subagent, not background ones. Closing the session (and so the end of `-p`) stops every running subagent with `stopReason: "stopped"`. `/undo` and `/rewind` wait until no background subagent runs.
- The TUI shows a background subagent's start and end lines (above the prompt while the user types), not its steps. `/agents` lists the subagents, `/agents NAME` shows one's last 20 events, `/stop NAME|all` stops them.
- Every event's envelope names its `agent` (`main`, `sub-N`) and `source` (`host`: written by JHarness; `guest`: sent by agent code with `api.emit`). `jharness log --agent NAME` and `--source host|guest` filter by them.

**Why.** The user asked to see which agents run, follow one and stop one, and to tell the agents' events apart (review of spec 026). Background work lets the main agent do other things meanwhile. Questions can't safely pop up from several threads while the user types, so unattended agents get none: they can never do more than rules already allow. Keeping undo away from running writers keeps checkpoints consistent.

**Rejected.** Background subagents that queue questions for later (blocks them indefinitely, confusing prompts). Typing commands during a turn (the terminal is in raw mode for Esc). Esc stopping background subagents too (they'd be lost with any turn; `/stop all` does that). A separate log file per agent (one timeline is easier to follow; the filters split it).

**Note (D-087).** `api.waitAgent` was removed: a finished background subagent's reply starts a main turn by itself (`agent.autoTurns`), and `-p` runs until none is left.

**Source.** User review of spec 026, 2026-11-24. Recommended options accepted while the user was away, specifying 027-background-subagents, 2026-11-24.

---

## D-066 — Build tools are definition files

**Decision.**
- Maven, Gradle and npm are no longer coded in Java. Each tool is a JSON file `NAME.json`; git and `rg` stay in code.
- **Where they live.** JHarness reads `~/.jharness/tools/` and, in a trusted project, `.jharness/tools/`. A project file with the same name replaces the user's for that project, and `{"disabled": true}` turns the user's tool off there (spec 028). An untrusted project's tool files are ignored.
- **Defaults.** `examples/.jharness/tools/` in this repo holds `maven.json`, `gradle.json` and `npm.json`, which give exactly today's operations and parameters (D-061). The build embeds them. On first run, when `~/.jharness/tools/` doesn't exist, JHarness creates it and copies them in; after that it reads only the folders, so the user edits, adds or deletes files freely. Upgrades never overwrite them: `jharness tools reset [NAME]` writes the shipped default again, and `jharness tools list` marks files that differ from it. The list is a table with the headers `NAME SOURCE EXECUTABLE STATUS OPERATIONS`, each column as wide as its longest value; `STATUS` is `ok`, `not found on PATH` or `broken`, and files that differ from the shipped default are named after it (spec 045).
- **Format.**
  - `description`, `executable` (a name looked up on `PATH`, never in the workspace), optional `wrapper` (`file`, optional `checksum` properties file that must pin `distributionSha256Sum`).
  - `proxy`: how the tool reaches the egress proxy, one of the built-in methods `maven-settings`, `java-options`, `npm`, `env` (`HTTP_PROXY`, `HTTPS_PROXY`) or `none`. `javaHome: true` passes `JAVA_HOME`. `env` sets fixed variables. `writable` lists cache folders (`~/…`) the OS sandbox lets it write besides the workspace and its temp folder.
  - `args` come before every operation; `offline` adds `args` or `env` in offline mode. `{proxySettings}` is Maven's generated settings file.
  - `operations`: each is an argv template. `{field}` placeholders say which fields the operation takes; a whole-element placeholder expands to the field's `args` or to nothing; one inside an element (`{project}build`) expands to a single string.
  - `fields`: `type` `flag` (JSON true adds its `args`), `string` (must fully match `pattern`), `enum` (`values`, each with its `args`), `integer` (`min`, `max`) or `folder` (an existing folder inside the workspace). `{}` in `args` is the value. `required`, `default`, `description`, and named host checks such as `npm-script` (the script must exist in `package.json`). `conflicts` lists fields that can't be used together.
- **Rules every definition follows.** Loading checks the file and fails with a clear message: unknown keys, bad patterns (anchored, at most 500 characters), placeholders without fields. A value that starts with `-` is always refused. The executable may not be a shell or a command runner (`sh`, `bash`, `dash`, `zsh`, `ksh`, `csh`, `tcsh`, `fish`, `env`, `sudo`, `su`, `doas`, `xargs`, `nohup`, `nice`, `timeout`, `busybox`) or contain a path separator. Every tool needs a trusted project, asks for approval, runs in the OS sandbox and reaches the network only through the proxy.
- **What only the user's folder may set.** `wrapper` (a workspace executable), `loopback` (D-050) and `writable` (spec 028: a writable home folder outlives the sandbox, such as `~/.gradle/init.d`, whose scripts run in every later build). A project definition that sets any of them is refused.
- **Project tools are shown.** The trust question lists the tools the project defines, each with its executable and operations and whether it replaces one of the user's, so trusting the project is a choice about them too. Each start in a trusted project prints one line naming the project's tools. When a project tool file is new or changed since the user last confirmed it, JHarness shows it and asks whether to use the project's tools this session; no means they are ignored. Confirmed versions are recorded with the trust entry (a hash per file).
- **Approvals.** The question names the tool, where its definition came from (`user` or `project`) and the fields. "Always" saves the tool, the operation and the resolved executable, so a changed executable asks again.
- **Layout.** `examples/.jharness/` mirrors `~/.jharness/`: `tools/` and `agents/` (the reviewer example moves from `examples/agents/`).

**Why.** Users want to tighten what a build tool may do (drop `install`, remove `threads`) or add a tool such as Go without waiting for a release. Definitions keep the D-008 rule that only typed, checked values reach a command line, while moving the list of operations and fields into data. The proxy methods, the sandbox and the checks stay in code, so a definition can't weaken them. Copying the defaults into the user's folder makes them visible and editable in the place JHarness reads; trusted projects may add tools because a trusted project already runs its own build code (D-011), but the user sees every project tool when trusting it and again whenever one changes; wrappers and loopback, which reach further, stay the user's choice.

**Rejected.** Keeping tools in Java (every change needs a release). Free-form argument lists or a command string in a definition (D-008). Embedded defaults that the user's files only override (the user asked for the files themselves to be the defaults). Projects only narrowing tools (the user chose to let trusted projects add them). Moving git and `rg` too (git has credentials, checkpoints and push rules; `rg` is JHarness's own search).

**Source.** User request and answers, 2026-11-24: JSON definitions, `examples/.jharness/tools/` as the defaults copied to `~/.jharness/tools/`, trusted projects may add tools and must show them when trusted, the agents example moves, git and `rg` stay in code. Amends D-008, D-009, D-050 and D-061, and constitution Articles IV and V.

---

## D-067 — One `api.tool` for every tool

**Decision.**
- `api.tool(name, request)` runs an operation of a defined tool, such as `api.tool("maven", {op: "package", skipTests: true})`. `request` takes `op`, the operation's fields and `timeoutMs`. It returns the same JSON as builds do today (`exitCode`, `output`, …).
- `api.tools()` returns each tool: `name`, `description`, `source` (`user` or `project`), its operations with the fields each takes (type, pattern, values, range, required, default, description), and `available` with a `reason` when it can't run here (project not trusted, executable not found, definition error).
- `api.maven`, `api.gradle` and `api.npm` are removed. The system prompt lists the tools and their operations from the definitions, so the model sees what this session allows; `api.d.ts` declares `tool` and `tools`.
- Custom agents' `tools` lists name `tool` and `tools`; `"tool:maven"` allows one tool only.

**Why.** Per-tool methods would need a new `api` method, shim line and native metadata for every user-defined tool. One method keeps the isolate boundary fixed (Article VI) while the set of tools changes. `api.tools()` answers "what can I run" at run time, including tools added after the session started its prompt.

**Rejected.** Keeping the three methods as short forms (two ways to do one thing; the user asked to remove them). Generating one method per tool (the frozen `api` would change with the user's files).

**Source.** User request, 2026-11-24. Replaces the `api.maven`, `api.gradle` and `api.npm` methods of specs 013 and 023.

---

## D-068 — git and `rg` stay in Java code

**Decision.** git (`api.git`) and `rg` (behind `api.grep` and `api.listFiles`) stay host code. They are not tool definitions, and D-066's format won't grow to cover them.

**Why.** A definition only runs a program with checked arguments and returns its output. git and `rg` need more, and each part is a security or reliability rule:

| Needs host code | git | `rg` |
| --- | --- | --- |
| Approval per operation | `status`, `diff`, `log` run without asking; `add`, `commit` ask; `pull`, `push`, `clone` ask and plan mode denies them. A definition has one kind: every run asks. | — |
| Untrusted projects | Reading git works before the project is trusted; definitions need trust. | `api.grep` and `api.listFiles` work before trust. |
| Injection defences | Paths after `--`, checked refs, hardened config through the environment (no system config, no prompts, proxy without argv). A user's file could drop any of them. | The file deny list is passed as exclusions, so secrets never show up; a file could drop them. |
| Secrets | Each host's token is injected only for that host and shown as `***` in output and logs. | — |
| Undo | git writes are recorded in checkpoints for `/undo` and `/rewind`. | — |
| Structured results | — | Output is parsed into `{matches: [{path, line, text}]}`, capped, with a Java fallback when `rg` is missing. |

Covering these in JSON would need per-operation kinds, secret injection, output masking and parsers: a programming language in a settings file, where every mistake is a hole. Restricting git is already possible with plan mode, a custom agent's `tools`, or by answering no.

**Rejected.** git and `rg` as definitions (above). A git definition beside the built-in one (two ways to run git, one of them weaker).

**Source.** User question in the review of spec 028, 2026-11-24: "write why we left git and rg in the Java code, and why they are not just tools."

---

## D-069 — Following a subagent

**Decision.**
- `/follow NAME` (or `/follow` when one background subagent runs) shows that subagent like the main loop: its reply streams as the model writes it, its thinking shows when `/thinking` is on, and its steps, results and diffs print with `[sub-N]`.
- It runs until Esc or until the subagent ends. Esc only stops watching; the subagent keeps running, and `/stop NAME` stops it. When it ends, JHarness prints its end line and returns to the prompt.
- It is a view. Typing to the subagent, switching to it, or answering its questions comes later (background subagents still never ask, D-065).
- Only running background subagents can be followed; a foreground one is already shown inside the turn. `/agents NAME` still prints past events.
- A subagent that shares the session's model client (sessions without a model profile, such as replays) gets a view of that client that publishes its events under the subagent's name, so its text can be followed too.

**Why.** The user asked to watch a running agent like the main loop, until Esc (review of spec 027). `/agents NAME` only showed the last 20 events after the fact.

**Rejected.** A split screen with every agent at once (JLine has no panes; one followed agent keeps the output readable). Esc stopping the subagent (watching and stopping are separate; `/stop` exists). Typing to the subagent now (planned separately).

**Source.** User request in the review of spec 027, 2026-11-24. Recommended options accepted while the user was away, specifying 030-follow-subagent, 2026-11-24.

---

## D-070 — Readable session paths

**Decision.**
- The folder for a workspace under `~/.jharness/sessions/`, `~/.jharness/projects/` and `~/.jharness/checkpoints/` is `SLUG-HASH`:
  - SLUG is the real path without its leading `/`, lower-cased, with each run of characters other than `a-z`, `0-9` and `.` turned into one `-`, trimmed of `-` and `.`, and cut to its last 80 characters when longer, since the end names the project (`root` if empty);
  - HASH is the first 7 hex characters of the SHA-256 of the real path.
  - For example `/Users/xyz/projects/jharness.dev` → `users-xyz-projects-jharness.dev-2787870`.
- A session id is `s-` + the UTC start time as `yyyy-MM-ddTHHmmssZ` + `-` + 4 random hex characters, such as `s-2026-11-24T105226Z-18a9`. A taken name gets new random characters. Sessions that start in the same second are ordered by their last write.
- The log is `sessions/FOLDER/SESSION.jsonl`, saved permissions `projects/FOLDER/permissions.json`, checkpoints `checkpoints/FOLDER/SESSION/`.
- Old folders (16 hex characters) and old ids (base-36 time) are not moved, converted or deleted, and JHarness ignores them. Their saved permissions no longer apply. Trust and confirmed project tools are keyed by path and unaffected.

**Why.** The user wants to find a project's logs, permissions and checkpoints by looking at the folder names. The short hash keeps paths that make the same slug (`/a/b` and `/a-b`) apart, and the time in the id makes `ls` sort sessions by start. The project is still in development, so there is no migration.

**Rejected.** Keeping the hash names (unreadable). The slug alone (collisions). Migrating old folders (the user asked not to).

**Source.** User request, 2026-11-24, accepted with the recommended details while the user was away (specifying 031-readable-session-paths). Changes the names in D-038, D-040 and D-044.

---

## D-071 — Switching to a subagent

**Decision.**
- `/switch NAME` makes the prompt talk to that subagent: it shows `NAME> `, and each message is a new turn in the subagent's own conversation, marked as coming from the user. `/switch main` goes back; the subagent keeps its conversation for later.
- While the user is switched to a subagent, that subagent is attended: its approval questions are asked, as in a main turn, instead of being denied as unattended (D-065). Other background subagents stay unattended.
- Its reply streams and its steps print as in `/follow`. Esc stops its current turn, as Esc stops a main turn. Switching to a subagent that is still running waits for its turn to end, asking its questions meanwhile.
- Subagents, foreground and background, stay open after their task: the loop, conversation and isolate are kept. At most 16 idle ones stay open; beyond that the oldest idle one is closed and can't be talked to. Closing the session closes them all.
- Replies the user gets by talking aren't handed to the main agent; the user took the conversation over. A stopped subagent can be talked to again.

**Why.** Background subagents can't ask (D-065), so work that needs an approval stalled. Switching lets the user answer and steer that subagent directly, without the main agent in between (roadmap, user request in the review of spec 027).

**Rejected.** Queuing background questions until the user looks (D-065). Asking every background question as it comes (prompts over the user's typing). Closing subagents when their task ends (nothing left to talk to).

**Source.** Roadmap item "Switch to an agent, answer its questions and talk to it". Recommended options accepted while the user was away, specifying 032-switch-to-agent, 2026-11-24.

---

## D-072 — Held background questions

**Decision.**
- In an interactive session, a background subagent's approval question no longer fails at once. Its call waits, and only that subagent pauses; its block clock doesn't run.
- JHarness prints one notice above the prompt: `▸ [sub-2] is waiting for you: write a.txt (/switch sub-2 to answer)`. `/agents` and `api.agents()` show its status as `waiting`. The events `subagent.waiting` (`api`, `summary`) and `subagent.wait_ended` (`outcome`: `asked`, `timeout`, `stopped`) record it.
- `/switch NAME` asks the held question at once, as a normal approval prompt (D-071).
- If no one switches within `agent.backgroundQuestionWait` (10s to 1h, default 10m), or the subagent is stopped, the question is denied as before.
- `agent.backgroundQuestions: "deny"` keeps D-065's immediate denial. `-p` and other sessions without a terminal deny at once. Network questions that reach the proxy while no turn runs are still denied.

**Why.** A background subagent that needed one approval failed its task. Holding the question until the user looks, with a time limit, gets the work done without questions popping up over the user's typing or waiting forever, the two reasons D-065 gave for denying.

**Rejected.** Asking at once above the prompt (prompts over typing, D-065). Waiting without a limit (a forgotten subagent would hang). Holding network questions too (the proxy can't tell which agent opened the connection).

**Note (D-087).** `/answer [NAME]` asks a held question in place, without switching; the notice names it.

**Source.** User question in the review of spec 032, 2026-11-24. Recommended options accepted while the user was away, specifying 033-held-questions. Changes D-065's "never asks" for interactive sessions.

---

## D-073 — JS hooks

**Decision.**
- **Files.** A hook is a JavaScript file `NAME.js` in `~/.jharness/hooks/`, or in a trusted project's `.jharness/hooks/`, which the trust question lists. Names use lower-case letters, digits and `-`. Files are at most 64 KB and can't be links, and a user file others can write is refused.
- **Functions.** A file defines any of three functions:
  - `preTool(call)` runs before every `api` call, before the permission gate. `call` has `agent`, `api`, `kind`, `summary` and `detail` (such as `path` or the git request). Returning `{block: "reason"}` refuses the call with `blocked by hook NAME: reason`. A pre-tool hook that throws or hits a limit blocks the call (fails closed).
  - `postTool(call)` runs after the call, with `status` (`ok`, `error`, `denied`, `blocked`), `result` (at most 10,000 characters) or `error`. It observes only; errors are logged.
  - `stop(turn)` runs when an agent's turn would end with a reply and no code. `turn` has `agent`, `turn`, `steps`, `continued`, `reply` and `calls` (`api`, `kind`, `summary`, `status`). Returning `{continue: "message"}` gives the agent that message and lets it go on, at most 3 times a turn.
- **Only tighten.** Hooks never approve. A call they let through still goes to the gate, and nothing a hook returns can change a call's arguments.
- **Where they run.** Each hook has its own isolate with the `subagent` sandbox limits and a fresh context per call (Article III). The caller's block clock is paused meanwhile.
- **Their own calls.** A hook's own `api` calls go through the session's gate and run no hooks, and its events carry `hook-NAME`.
- **Scope.** Hooks apply to the main agent, subagents and the session's start-up `readContext`. They run in name order, user hooks before project hooks, and the first block wins.
- **Visibility.** `hooks.loaded` lists the hooks and the broken files; `hook.ran` records each run (`outcome`: `ok`, `block`, `continue`, `error`). The TUI prints blocks, continues and errors, and `/hooks` lists the hooks.
- **Example.** `examples/.jharness/hooks/guard.js` blocks pushes to `main` or `master` and hand edits of lock files, and asks the main agent to run the tests when it changed files without doing so.

**Why.** US-4.2: team guardrails that apply automatically. D-022 rules out shell hooks. Checking before the gate means a guardrail holds even in bypass mode, and failing closed means a broken guardrail doesn't silently allow. Stop hooks let a team require a last step, such as tests, without changing the agent.

**Rejected.** Hooks that change a call's arguments (they would have to be checked again, and a change could loosen a call; later). Hooks that approve (Article VII). A hook isolate per call (start-up cost); one per hook with a fresh context per run keeps the rules. Failing open for pre-tool errors.

**Source.** Roadmap item "JS hooks (pre-tool, post-tool, stop); no shell hooks", D-022, US-4.2. Recommended options accepted while the user was away, specifying 034-js-hooks, 2026-11-24.

---

## D-074 — Slash commands

**Decision.**
- **Files.** A command is `NAME.md` or `NAME.js` in `~/.jharness/commands/`, or in a trusted project's `.jharness/commands/`, where it replaces the user's command of the same name. Names are lower-case letters, digits and `-`, starting with a letter. A file can't take a built-in name such as `/help`. Files are at most 64 KB and can't be links, and a user file others can write is refused.
- **Markdown.** The file's text is the prompt; `$ARGUMENTS` becomes what the user typed after the command. Optional front matter (`---` / `description: …` / `---`) describes it, and otherwise its first line does.
- **JS.** The file defines `run(args)` and may set a `description` string. `run` returns a prompt string or `{prompt}`, which is sent as the user's message, `{print: text}`, which is only shown, or nothing.
  - It runs in its own isolate with the `subagent` limits and a fresh context.
  - Its `api` calls go through the gate and the hooks like the main agent's.
- **Where commands run.** The prompt goes to the agent the prompt talks to: the main agent, or the subagent after `/switch`. `-p "/name args"` expands the same way.
- **Discovery.** `/commands` lists them and `/help` adds them under "Your commands"; Tab completes them. `commands.loaded` and `command.ran` record them.
- **Examples.** `examples/.jharness/commands/review.md` and `changed.js`.

**Why.** US-4.3: repeated prompts as one command. Templates cover most uses with no code. JS commands can gather context first, under the same sandbox and gate as agent code.

**Rejected.** Shell commands (D-008, D-022). Letting files replace built-ins (a repo could hide `/undo`). YAML or JSON command definitions (Markdown is the prompt itself).

**Source.** Roadmap item "Slash commands (Markdown templates and JS)", US-4.3. Recommended options accepted while the user was away, specifying 035-slash-commands, 2026-11-24.

---

## D-075 — Todo list and plan mode

**Decision.**
- **Todo lists.**
  - `api.todos(list)` sets the calling agent's list: at most 50 items, each `{text, status}` with `status` `pending`, `in_progress` or `done`; a string is a pending item. `api.todos()` reads it.
  - Each agent, main or subagent, has its own list. Setting one never asks: it is the agent's own session state (`Operation.Kind.SESSION`).
  - `todo.updated` records it, the TUI shows it as `✓`, `▸` and `☐` lines, and `/todos` prints it (the switched-to subagent's list after `/switch`).
- **Plan mode.**
  - Plan mode is the permission mode `plan` (D-041): every change is refused without asking. `/plan` turns it on, or off back to the mode before it.
  - While it is on, the prompt shows `[plan] `, and each message to the agent starts with a note that says so and points to `api.proposePlan`.
- **Proposing a plan.**
  - `api.proposePlan(text)`, only for the main agent and only in plan mode, shows the plan as Markdown and asks: leave plan mode and carry out this plan?
  - Yes turns plan mode off (back to the earlier mode) and returns `{"approved": true}`. No keeps it on and returns `false`.
  - Without a terminal (`-p`) the answer is no.
- **Custom agents.** A custom agent with `plan: true` is locked: `/plan` can't turn plan mode off, and `proposePlan` is refused.
- **Events.** `plan.mode`, `plan.proposed` and `plan.decided` record the changes.

**Why.** The roadmap's "todo list and plan mode": users see the agent's steps, and can have it investigate and propose before anything changes, with their approval as the switch. Reusing the permission mode keeps one definition of read-only.

**Rejected.** A separate plan-mode gate (two definitions of read-only). Leaving plan mode without the user (the model can't approve its own plan). A shared todo list for all agents (subagents would overwrite the main agent's).

**Source.** Roadmap item "Todo list and plan mode". Recommended options accepted while the user was away, specifying 036-todos-and-plan-mode, 2026-11-24.

---

## D-076 — MCP client

**Decision.**
- **Our own client.** JHarness has its own thin MCP client in `jharness-mcp`, with no SDK (tech-stack's fallback). It speaks JSON-RPC 2.0 over a stdio server's pipes (one message per line) or streamable HTTP (POST, JSON or event-stream answers, `Mcp-Session-Id`), protocol `2025-06-18`. It uses `initialize`, `tools/list` (with pages) and `tools/call`, answers a server's `ping`, and refuses its other requests.
- **Configuration.** Servers come only from the user's `~/.jharness/settings.json`. A project's `mcp`, trusted or not, is ignored (`settings.ignored`).
  - A stdio server: `"name": {"command": "npx", "args": [...], "env": {...}, "secretEnv": {"VAR": "SECRET"}, "writable": ["~/.npm"], "timeout": "60s"}`. `command` is a program on `PATH` outside the workspace; shells and command runners are refused, as for tools.
  - An HTTP server: `"name": {"url": "https://…", "tokenSecret": "SECRET"}`, which sends the secret as a bearer token.
- **Sandbox.** A stdio server runs through `ProcessRunner` in the OS sandbox (Article IV), with the workspace as its folder, its `writable` folders, and the network only through the egress proxy (`HTTP_PROXY`, `HTTPS_PROXY`). An HTTP server is reached through the proxy, and its endpoint is allowed automatically, like a model endpoint.
- **Start-up.** Servers start when the session opens; the processes start on the session's thread and the handshakes run in parallel, up to 30 s. A server that fails is listed with its error.
- **Calls.**
  - `api.mcp({server, tool, input, timeoutMs})` checks that the server and tool exist, then asks (`kind: mcp`, which changes state, so plan mode denies it). "Always" saves `server/tool` for the project. It returns the server's result JSON.
  - `api.mcp({list: true})` or `{server, list: true}` lists the tools with their input schemas without asking.
  - The system prompt lists the servers and up to 40 tools each, and says results are data.
- **Visibility.** `mcp.connected` and `mcp.called` record them; `/mcp` lists them. Secrets never appear in arguments or events.

**Why.** US-4.5 and D-023: the user's other tools, reached from JS like everything else, under the same permissions. A thin client keeps the binary small and native-friendly. Asking for every call is the safe default, because a server can do anything; "always" makes trusted tools quick.

**Rejected.**
- Trusting the servers' `readOnlyHint` to skip the question (it is the server's own claim).
- Project-defined servers (a repo could start a program).
- An MCP SDK (size and Native Image risk; three methods are enough).
- Resources and prompts (later).

**Source.** Roadmap item "MCP client", D-023, US-4.5. Recommended options accepted while the user was away, specifying 037-mcp-client, 2026-11-24.

## D-077 — Token counting and compaction

**Decision.** Long sessions keep working when the context fills up (US-2.8).

- **Window.** Each model profile has a `contextWindow` in tokens: 32,768 for `openai-compatible` and 200,000 for `anthropic` unless set. `maxTokens` must be below it.
- **Counting.** The tokens the model reported for the last request and reply, plus an estimate of about 3.5 characters a token for messages added since. Before any count, the estimate covers the system prompt and the conversation. No tokenizer ships in the binary.
- **When.** Before each model call, if the count reaches `agent.compactAt` (default 0.8, from 0.5 to 0.95) of the window minus the reply size, the conversation is compacted. `agent.autoCompact: false` turns this off. After a failed or unhelpful compaction the next one waits until the context has grown by a tenth of the window.
- **How.** The same model writes the summary (up to 2,048 tokens) from a transcript of the messages; long messages are cut in the middle and the oldest are left out if the transcript doesn't fit. The conversation becomes the summary, an acknowledgement, and the current turn's messages if they are small (under a quarter of the window), or else only the turn's prompt.
- **Undo.** `/undo` and `/rewind` still work for turns after the summary; turns inside it can't be rewound. File checkpoints are unchanged.
- **Commands and events.** `/context` shows the count and the threshold, `/compact` compacts between turns. `context.compacted` (with the summary) and `context.compact_failed` record it, and `turn.completed` carries `contextTokens` and `contextWindow`.
- **Subagents** compact the same way, each in its own conversation.

**Why.** Small local models have 32K windows, and a few build logs fill them. A summary by the working model needs nothing new in the binary. The model's own counts are exact for what it was sent; the estimate only bridges the messages since and errs high. Keeping the current turn's recent messages keeps the agent's place in a running task.

**Rejected.**
- A bundled tokenizer (size, and one per model family).
- Dropping old messages without a summary (loses the user's requests).
- A separate summary model (one more profile to set up; later, if needed).
- Rewinding into a summary (the messages are gone; the files could be restored but the conversation could not).

**Source.** Roadmap item "Token counting, auto-compaction, prompt caching, `@file` mentions", split so prompt caching and `@file` mentions follow in their own spec; US-2.8. Recommended options accepted while the user was away, specifying 039-token-counting-and-compaction, 2026-11-24.

## D-078 — Prompt caching and `@file` mentions

**Decision.** Repeated prompts cost less on Claude, and the user can hand the agent files by name.

- **Caching.** For `anthropic` profiles, the system prompt goes as a text block with `cache_control: {type: "ephemeral"}` (5 minutes), and so does the last message of each request. The next request reads everything up to the earlier breakpoint from the cache. `promptCache: false` turns it off; one-off requests, such as a compaction summary, are never marked. `openai-compatible` profiles send nothing extra and can't set `promptCache`; servers that cache report `prompt_tokens_details.cached_tokens`, which is read.
- **Counts.** `inputTokens` is the whole prompt, cached parts included, so the context count (D-077) stays right. `model.completed` adds `cacheReadTokens` and `cacheWriteTokens` when they aren't 0.
- **Mentions.** `@path` at the start of a word in a user prompt (TUI, `-p`, slash command templates), or `@"path with spaces"`, names a file or folder. It is read through the file policy (D-041): the workspace and read-only mounts, never the deny list. The prompt the user typed is unchanged in `turn.started`. The message sent to the model gets an `# Attached files` section with each file in a fence longer than any backtick run inside it, and each folder's listing.
- **Limits.** Up to 20 mentions; 50,000 characters a file; 200,000 a prompt and at most a quarter of the context window, at about 3.5 characters a token. A cut file says so and points to `api.readFile`. Binary files, missing paths and refused paths are not attached and are named. A word that doesn't exist and has no `/` or `.`, such as `@Override`, is left alone.
- **Display and events.** `prompt.attached` records the files, their sizes and what was skipped. The TUI prints `▸ attached …` or `▸ not attached: @x: reason`. Tab completes `@` paths, skipping deny-listed entries.

**Why.** An agent loop sends the same growing prefix on every step, so a cache breakpoint on the last message makes almost every request a cache read. Two breakpoints are enough for Anthropic's 20-block lookback, since each message is one block. A mention saves a model step and a round trip for files the user already knows matter. Going through the file policy means a mention can't reach anything the agent couldn't read itself.

**Rejected.**
- The 1-hour cache (writes cost twice as much; a session rarely pauses that long).
- A breakpoint on every message (only four are allowed, and two are enough).
- Mentions outside the file policy, such as `@~/.ssh/config` (the same rules as the agent's reads).
- Mentions in subagent tasks and agent-written text (the agent can read files itself).

**Source.** Roadmap item "Prompt caching, `@file` mentions"; US-2.8. Recommended options accepted while the user was away, specifying 040-prompt-caching-and-file-mentions, 2026-11-24.

## D-079 — Command aliases and argument completion

**Decision.** Common exits work, and Tab knows each command's arguments.

- **Aliases.** `/quit` and `/q` do what `/exit` does. The help and Tab completion list `/exit` alone, and its description names the aliases.
- **Clashes.** Built-in names and aliases are reserved. A command file in `~/.jharness/commands/` or the project's `.jharness/commands/` with one of those names is not used: `command.shadowed` records it, `commands.loaded` lists it under `shadowed`, and the TUI prints a line at the start.
- **Arguments.** After a command and a space, Tab lists the choices for the next argument, and Tab again cycles through them with the current one highlighted (JLine's list and menu):

  | Command | Choices |
  | --- | --- |
  | `/events` | `none`, `steps`, `all` |
  | `/model` | the profile names |
  | `/agents`, `/follow` | this session's subagents |
  | `/stop` | the subagents, then `all` |
  | `/switch` | `main`, then the subagents |
  | `/rewind` | the turns it can go back to, then `--force` |
  | `/network` | `allow`, `deny`, `remove`, `reload`; for `remove`, the targets in both rules files; after the target, `--project` |

  `/thinking` takes no argument, so it has no choices.
- **Command files.** A template's front matter can say `args: a, b, c` (or `[a, b, c]`), and a script can define `const args = [...]`. Up to 50 choices, each one word of up to 60 characters, completes the first argument.

**Why.** Users type `/quit` and `/q` out of habit from other tools. Letting a file take a built-in name would make the same keys do different things in different projects. Choices taken from the session (profiles, subagents, turns, rules) save looking them up first.

**Rejected.**
- Listing aliases in the help (two more lines for one command).
- Letting a project command replace a built-in (a cloned repo could change what `/exit` or `/undo` does).
- Completing free-form arguments such as hosts for `allow` (nothing to complete from).

**Source.** The user's request for exit aliases and argument completion, 2026-11-24. Recommended options accepted while the user was away, specifying 041-command-aliases-and-completion.

## D-080 — Status line

**Decision.** The TUI shows a status line under the input, and users can program it. This is a new extension point, next to agents, hooks, tools and commands.

- **Where.** At the bottom of a real terminal, as JLine's status area: a scroll region keeps it below the prompt and the output. Never on dumb terminals or in `-p`.
- **Built-in items.** `model` (profile and model), `context` (percent of the window, `~` when estimated), `mode` (permission mode, `[plan]` in plan mode), `agents` (background subagents running and held on a question), `cache` (the last request's cache reads), `cost` (every agent's cost so far, once a request had a price; spec 049, D-084), `turn`, `workspace`. Items with nothing to say are left out; the rest are joined by ` · `.
- **Setting.** `ui.statusLine`: `true` (the default, every item), `false` (no line), or a list of items in the order to show them.
- **Script.** If `~/.jharness/statusline.js` exists (a regular file, up to 64 KB, not writable by others), its `status(s)` returns the whole line. It runs in its own isolate with the subagent sandbox limits and without `api`, and gets a JSON snapshot of the same values plus `line`, the default rendering. Only the user's home can define it, never a project. The result is cut at the first line break and 1,000 characters and escaped with `TerminalSafe`.
- **Failures.** If the script throws, hits a limit, returns something other than a string, or the file can't be used, `statusline.failed` (reason, error) is recorded, the TUI says so once per distinct failure, and the default line shows in its place. A run over 1 second (10 seconds for the first, which warms the isolate up) turns the script off for the session.
- **Refresh.** After events that change a value (turn start and end, model replies, compaction, model switch, plan mode, background subagents) and before each prompt, at most every 250 ms, never per streamed chunk. The values are kept on the host from those events, so a refresh takes no lock on the agent loop, reads no file and starts no process. The script's isolate reports to a private event bus, so refreshes don't fill the session log.
- **Drawing.** While the prompt reads, the line is drawn through a JLine widget, under the line reader's lock; otherwise under the TUI's own lock, which streamed output and event lines also take. A resize between prompts resizes the area; during a prompt JLine does. The area is closed, and the scroll region reset, when the TUI ends.
- **Events.** `statusline.ready` (from `default` or `script`, the items) and `statusline.failed`.

**Why.** The values a user checks most often (context use, mode, background agents) take a command each today. A script with a snapshot covers personal layouts without new settings for each one. Running it like any other guest code, with no `api`, means a status line can't read files or reach the network. Values cached from events keep a refresh cheap and keep a slow loop from freezing the line.

**Rejected.**
- Shell commands for the status line, as in some other tools (no shell, constitution Article IV).
- Giving the script `api` access (a status line has no reason to read or write).
- Refreshing on every streamed chunk (flicker and cost).
- A project status line (a cloned repo could put misleading text there).

**Source.** The user's request for a default and a programmable status line, 2026-11-24. Recommended options accepted while the user was away, specifying 042-status-line.

## D-081 — Resume and fork sessions

**Decision.** A session can be continued later, or branched, from what it last saved (US-2.7).

- **What is saved.** The main agent's conversation, its last turn number, the compaction mark (D-077), the profile and model, the first prompt, the session it was forked from, and when. It is saved after each turn, `/compact`, `/undo` and `/rewind`, as `~/.jharness/sessions/<project>/<session-id>.state.json` next to the session log. The file is mode 0600 and replaced atomically, so a crash leaves the last complete turn.
- **Why not the log.** The log keeps what happened, not what the model was sent: results are capped there and attachments aren't in `turn.started`. Rebuilding the conversation from it would be lossy, so the state is its own file.
- **`--resume [ID]`.** Continues a saved session of the current folder: `last` or none for the newest, else an id or a unique start of one. It keeps the same session id, appends to the same log and goes on with the next turn number. `session.resumed` records it (from, fork, turns, messages, the saved profile and model).
- **`--fork [ID]`.** The same, but in a new session id with its own log and state; the saved session is left as it was. Its state file is written at once and names `forkedFrom`.
- **What starts fresh.** Background subagents, held questions, the todo list, plan mode and the context count (re-estimated, then counted on the next reply). The model is the one chosen now (`--model` or settings), not the saved one.
- **Undo.** File checkpoints are indexed in memory, so turns from before the resume can't be undone: `/rewind` to an earlier turn says so, and `/undo` reaches only new turns.
- **Where.** Only in the folder the session belongs to; both work in the UI and with `-p`.

**Why.** Users come back to long tasks the next day, and re-explaining costs a turn and context. Forking lets them try another direction without losing the first. A separate state file keeps resume exact and cheap to load.

**Rejected.**
- Rebuilding from the session log (lossy, see above).
- Restoring background subagents (their isolates and processes ended with the session).
- Restoring file checkpoints across runs (the files may have changed since; a later spec can persist the index).

**Source.** Roadmap item "Resume and fork sessions"; US-2.7. Recommended options accepted while the user was away, specifying 043-resume-and-fork-sessions, 2026-11-24.

## D-082 — Headless output and `--mode`

**Decision.** `jharness -p` serves scripts and CI (US-5.1, US-5.2).

- **`--output text`** (the default): the reply as it streams, as before.
- **`--output json`**: nothing until the turn ends, then one line: `{"type": "result", session, stopReason, reply, steps, error?, exitCode, durationMs, usage: {requests, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, costUsd?, unpricedRequests}, filesChanged, denied: [{agent, api, summary, reason}], subagents}`. Usage counts every agent's model requests; `costUsd` and `unpricedRequests` came with spec 049 (D-084).
- **`--output stream-json`**: every event as a JSON line, the envelope included, as it happens: the events from opening the session (from the session log, this run only), then each live one, streamed `model.delta` chunks too. The result object is the last line. This is the stream the web viewer reads (D-053).
- **Escaping.** Output is JSON escaped with `\u` for control and bidi characters, so it is valid JSON and safe on a terminal (D-060). Errors still go to stderr as text.
- **Exit codes**, in every output mode: 0 when the turn ended `done`; 1 when it failed or stopped early (`error`, `max_steps`, `interrupted`); 2 for bad flags or settings, with a result object (`session: null`) on stdout in the JSON modes. Before this, a turn stopped at `max_steps` exited 0.
- **`--mode ask|accept-edits|plan|bypass`** sets `permissions.mode` in the session layer, so it wins over the user's and the project's settings, in the UI and with `-p`. With `-p` nothing can be asked, so whatever the mode leaves to ask is denied and listed in `denied`.

**Why.** CI needs one parseable answer and a meaningful exit code; tools that show progress need the events as they happen. Reusing the event envelope means one format for the log, `--debug`, stream-json and the viewer. A mode flag lets a pipeline allow edits without changing settings files.

**Rejected.**
- A separate headless event format (a second schema to keep in step).
- Rule flags such as `--allow writeFile` (the modes cover CI's needs; rules files already exist).
- Keeping exit 0 for `max_steps` (a pipeline would treat an unfinished task as success).

**Source.** Roadmap item "Headless mode with text, JSON and stream-JSON output"; US-5.1 and US-5.2. Recommended options accepted while the user was away, specifying 047-headless-output, 2026-11-24.

## D-083 — The web session viewer

**Decision.** D-053's viewer, as built (spec 048).

- **Module and server.** `jharness-web` holds the server (`SessionViewer`, the JDK's `com.sun.net.httpserver`, which works in the native binary), the feed and a log tail. The architecture test allows its listening socket and no other; it opens no outbound connection, so Article V is unaffected.
- **Where.** Bound to `127.0.0.1` on a free port (`--port` for `jharness view`). From another machine, an SSH port forward.
- **Access.** A 256-bit random token per viewer, in the address (`?t=`), compared in constant time, on every request, assets included. `Host` must be `127.0.0.1:PORT` or `localhost:PORT` (against DNS rebinding); an `Origin`, when sent, must be the same. Only `GET`; anything else is 405. At most 8 viewers at once.
- **Headers.** A Content Security Policy that allows only the page's own script, style and event stream (`default-src 'none'`), `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` (so the token doesn't leak), `nosniff`, `no-store`.
- **Stream.** `/events` is Server-Sent Events of the same JSON lines as `--output stream-json` (D-082): what has happened so far, then each new event. A browser that falls 20,000 lines behind is disconnected; the backlog keeps the last 100,000 lines.
- **Two ways in.** `jharness view [SESSION]` (default `last`) reads a session's log and follows it as it grows until Ctrl-C, for finished sessions and ones running elsewhere; the log has no streamed chunks, so replies appear whole. It also prints each event in the terminal in `jharness log SESSION`'s format (spec 052); `--quiet` prints only the address. `/view` in the UI serves the running session from its event bus, chunks included, until the UI quits.
- **Page.** Embedded `index.html`, `app.js`, `markdown.js` and `app.css`, no outside resources. The page links its scripts and stylesheet with the token (`app.js?t=…`), since they need it too (spec 050 fixed links without it, which left the page empty). Its icon is an empty `data:` link (the CSP allows `data:` images), so browsers don't ask for `/favicon.ico` without the token (spec 052). Turns with their prompt, streamed replies, steps, approvals and denials, diffs, subagents, compaction, todos; thinking and every event on request; a filter by agent. Replies show as Markdown (spec 052), built as DOM nodes with links only to `http(s)` addresses. Event text is only set as text, never as markup, and a test checks the scripts for HTML sinks.
- **Read-only.** No endpoint changes anything; approvals stay in the terminal (D-053).
- **No browser start.** JHarness prints the address; opening a browser would start a process.
- **Browser check.** Checked by hand in real browsers before releases and after viewer changes (D-086).

**Why.** It follows D-053 with the simplest server that runs in the binary. The token, `Host` and `Origin` checks and the CSP cover the usual attacks on a local server: other pages in the browser, DNS rebinding, and injected event text. Reusing stream-json keeps one event format.

**Rejected.**
- A cookie instead of the token in the address (the address is what the user gets; `no-referrer` keeps it from leaking).
- WebSockets (Server-Sent Events are one-way, which is all a read-only page needs).
- Opening the browser automatically (a process start, and not wanted over SSH).

**Source.** Roadmap item "Read-only web session viewer on `127.0.0.1`"; D-053. Recommended options accepted while the user was away, specifying 048-web-viewer, 2026-11-24.

## D-084 — Usage tracking

**Decision.** Token use and cost are added up from the session log (spec 049).

- **Price.** A model profile may set `"price": {"input": 3, "output": 15, "cacheRead": 0.3, "cacheWrite": 3.75}`, in US dollars per million tokens, each 0 to 10,000. `input` and `output` are required. Without the cache prices, `anthropic` profiles use a tenth of `input` for reads and 1.25 times for writes (Anthropic's 5-minute cache rates), and `openai-compatible` ones `input`. No price is set by default and none ships with JHarness.
- **Cost.** With a price, `model.completed` gets `costUsd`: prompt tokens not read from or written to the cache at `input`, cache reads at `cacheRead`, cache writes at `cacheWrite`, and output at `output`. The log keeps it, so a report never applies today's price to old requests.
- **Reports.** One tally, in total and by agent:
  - `/usage`: this session's log, so a resumed session counts its earlier runs.
  - `jharness usage`: this folder's sessions from the last 30 days (`--days N`, `--all`), one row each with a total; `jharness usage SESSION` by agent; `--json`.
  - The headless result's `usage` (D-082) adds `costUsd` and `unpricedRequests`.
  - The status line's `cost` item (D-080), shown once a request had a price.
- **Unpriced requests.** Counted apart: the cost shows `-` when none had a price and ends in `+` when some didn't, with a note on how to set one.

**Why.** Users pick models by cost (US-1.3) and want to catch expensive runs. Prices change and differ by account, region and server, so a shipped list would go stale and look authoritative; the user's own price is honest. Counting from logged events reuses the audit trail and needs no new store.

**Rejected.**
- A built-in price table, or fetching prices (stale, and a network call).
- Spending limits that stop a session (later, if wanted; the numbers are now there).
- A separate usage database (the logs already hold every request).

**Source.** Roadmap item "Usage tracking (tokens, cost)". Recommended options accepted while the user was away, specifying 049-usage-tracking, 2026-11-25.

## D-085 — Evals on real repo tasks

**Decision.** Evals are repository tasks with hidden checks, run by a maintainer script (spec 051).

- **Tasks.** `evals/tasks/NAME/`: `task.json` (title, prompt, check, optional mode and timeout), `repo/` (the code the agent works in), `check/` (never shown to the agent) and `replay.jsonl` (a recorded solution). Six to start: bug fixes, a feature, a refactor and a question, in Java and JavaScript.
- **Runner.** `scripts/eval.py BIN`: a fresh git repository per task; the check runs first and must fail; then `jharness -p PROMPT --output json --mode accept-edits` (D-082); then the check is copied to `.eval/` and run. A question's check reads the reply and expects no changed files.
- **Score.** Pass or fail per task, with steps, time, tokens and cost (D-084) from the result object; the score is the share passed. `--out` writes JSON.
- **Where.** CI runs `--replay`, which checks the tasks and the runner (6/6 required). Live runs use the maintainer's settings and keys before each release; scores go in `docs/evals.md` and results in `evals/results/`.

**Why.** US-6.3 asks whether the agent gets better or worse between releases. Hidden checks measure outcomes, not the path taken. Headless JSON output already carries everything to score. A script rather than a `jharness eval` command, because checks run `javac`, `java` and `node`, which the binary must never start (Article IV). The baseline run catches a task whose check passes without a fix.

**Rejected.**
- `jharness eval` in the binary (it would start arbitrary check commands).
- Live model runs in CI (no key, cost, and flaky results gating merges).
- Public benchmark suites for now (large, networked, and slow); the format can take more tasks.

**Source.** Roadmap item "Evals on real repo tasks"; US-6.3. Recommended options accepted while the user was away, specifying 051-evals, 2026-11-25.

---

## D-086 — Manual browser and terminal checks

**Decision.** Two scripts check the web viewer and the TUI as a person sees them (spec 053). They run by hand, never in CI.

- **Viewer.** `node scripts/viewer-check.mjs BIN` records a session whose reply has Markdown, starts `jharness view` and opens it in Chromium, Firefox and WebKit through Playwright, and in Safari through `safaridriver` (`--browsers`). It checks that the page loaded its files, that the console has no errors or CSP violations, that no request got 400 or more (the favicon included), that the reply rendered as Markdown, and that `jharness view` printed the events. A screenshot per browser.
- **Terminal.** `scripts/tui-tmux-check.py BIN` runs the TUI in tmux at several sizes, checks the reply's styles with `capture-pane -e`, the status line's row before and after a resize, and a clean exit. It complements `scripts/tui-pty-check.py`, which CI runs.
- **When.** After any change to the viewer or the terminal UI, and before each release, on Linux and on macOS. Results of note go in `docs/manual-checks.md`.
- **Tools.** Playwright (Node, installed globally) and tmux are set up per machine, not by Maven or CI; without them the scripts say how to install them and exit 2.
- **Node for the viewer.** The browser script is JavaScript because Playwright's Node package needs only npm, which the machines already have; the evals stay in Python.

**Why.** Specs 048 to 050 showed that tests which fetch the files by hand miss what a browser does: the viewer shipped with 403s for its own assets and a 403 favicon. A real browser and a real terminal catch that; running them on every CI push would add minutes and system packages, so they run when they matter.

**Rejected.**
- Browsers in CI (minutes per run, browser system packages on every runner).
- Playwright in the Maven build (a large dependency for checks that run by hand).
- A Python Playwright script (needs pip and a virtual environment that a machine may lack).

**Source.** User request after the spec 052 feedback, 2026-11-25.

---

## D-087 — Subagents that don't block

**Decision.** A turn never waits for a subagent, and a subagent never interrupts the user (spec 054).

- **No `api.waitAgent`.** It is removed from the api, its types and the examples. A call throws an error that says to end the turn instead. `api.agents()` and `api.stopAgent` stay, and `agents()` shows `waiting` for a subagent that holds a question.
- **A finish starts a turn.** When a background subagent finishes and the user sits at an empty main prompt, the main agent takes a turn with the reply, as the note before its prompt (`turn.started` has `"auto": true`; the prompt says no message came from the user). A finish during a turn, while the user types, or while the prompt talks to a subagent waits; it comes with the next turn of either kind. A subagent that was stopped (`/stop`, `api.stopAgent`) starts no turn; its status comes with the next one.
- **A limit.** At most `agent.autoTurns` (0 to 100, default 10) such turns run in a row before a message from the user; 0 never starts one (D-065's pull model). Esc on an automatic turn stops the chain until the user writes again.
- **Headless.** `-p` runs the prompt's turn; then, while background subagents run, each reply starts a turn, up to `agent.autoTurns`. The run ends when none is left. The result is the last turn's, with the steps of all; subagents still running at the end are stopped, as before, with a line on stderr.
- **Questions.** A held question (D-072) still waits with its notice and the status line's held count. `/answer [NAME]` (the oldest by default; Tab completes the held ones) asks it there, on the subagent's thread through the normal approval prompt, and leaves the user in the main chat. `/switch NAME` still works.
- A foreground subagent (`background: false`) is unchanged: it runs as one step and asks its questions directly.

**Why.** The main model called `api.waitAgent` for 5 minutes while its subagent waited for an approval; the prompt is closed during a turn, so `/switch` couldn't be typed and nothing moved until Esc (user report, 2026-11-25). The user wants the main chat free while subagents work, and their questions visible but never in the way. A reply that starts a turn gives the main agent everything `waitAgent` did, fan-out included, without holding a turn; keeping both would leave the model the blocking one.

**Rejected.** Asking a held question at once while the main turn waits on that subagent (a fix for one case; the turn still blocks). Asking held questions during any turn (interrupts unrelated work). Keeping `waitAgent` with a short limit (still blocks, and the model picks it). Typing while the main agent's own turn runs (a larger change to the terminal; later if wanted). Starting turns without a limit (two subagents that start each other would run forever).

**Source.** User report and review of the blocked chat, 2026-11-25: "I don't want the main chat blocked"; "Start a main turn" when a subagent finishes; "why do we even have the `api.waitAgent`? What do you think if we remove it?" Recommended design accepted, specifying 054-subagents-dont-block. Changes D-065 (`waitAgent`, pull-only delivery, `-p` stopping subagents at once) and D-072 (`/answer`).

**Later.** D-088 (2026-11-26) keeps the prompt live during main turns, so an automatic turn no longer closes it, and "a finish while the user types" no longer applies: the reply starts a turn behind the prompt whenever no turn runs.

---

## D-088 — A main prompt that never blocks

**Decision.** On a terminal that isn't dumb, the main prompt reads at all times; main turns run in the background (spec 055).

- **Turns on a worker.** A message starts a main turn on a `main-turn` thread, and the prompt keeps reading. Automatic turns (D-087) start there too whenever no turn runs, with the same limit.
- **Output.** While a turn runs, output goes above the prompt through JLine's `printAbove`, a whole line at a time, so the line being typed is never broken. The status line's `turn` item says `working`.
- **The queue.** A message sent during a turn is queued with a line that says so. When the turn ends, the queued messages go as one message, in order and separated by blank lines, before any automatic turn. A user command whose prompt runs as a turn is queued the same way.
- **Esc.** Esc on the prompt stops the running turn (a lone Esc after a 150 ms wait, since Esc also starts Alt keys); the queue then goes. With no turn running it does nothing.
- **Questions.** The main agent's questions during a background turn (approvals, network hosts, a proposed plan) are held. They show a notice and count as held on the status line, with `question.waiting` and `question.wait_ended` (`asked` or `stopped`) events. `/answer main`, or `/answer` with no name, asks one with the normal prompt; Esc denies it and stops the turn. A held question reads the terminal only while `/answer` has parked the prompt, so two readers never compete.
- **Commands.** While a turn runs, `/compact`, `/diff`, `/undo`, `/rewind`, `/model NAME`, `/plan`, `/switch NAME` and `/follow` say they wait for the turn and do nothing; the others work. `/exit` and Ctrl-D stop the turn and quit.
- **Dumb terminals** have no raw keys and no redraw, so their turns stay in the foreground, as before.

**Why.** "When I'm on the main chat, I don't want the main chat to block, never" (user, 2026-11-26). After D-087 the prompt still closed for every main turn, automatic ones included, and keys typed then were lost.

**Rejected.** Sending a typed message into the running turn (steering; harder to follow, later if wanted). Asking the main agent's questions on the prompt as soon as it is empty (still takes the prompt the user may be about to use). Streaming tokens above the prompt (fragile redraws across terminals). Keeping foreground turns behind a setting (the user asked for "never").

**Source.** User request and answers, 2026-11-26: queue typed messages, notices with `/answer` for the main agent's questions, line-by-line output. Specifying 055-live-main-prompt. Changes D-087 (automatic turns behind the prompt) and the Esc watcher of US-2.5 for main turns.

---

## D-089 — File and folder deletes

**Decision.** Agents delete inside the workspace with two api methods (spec 056).

- **`api.deleteFile(path)`** removes one file. Its operation is a `write` with the path, so every mode and saved write rule treats it as `writeFile`. The question and `file.changed` (with `"deleted": true`) show the removal as a diff.
- **`api.deleteFolder(path)`** removes a folder and everything in it. Its operation kind is `delete`: asked in `ask` and `accept-edits`, allowed in `bypass`, refused in plan mode; "always" saves `{"kind": "delete", "path": …}` for that folder. The question gives the file count, the bytes and the first 20 paths. One `folder.deleted` event follows.
- **Undo.** Every file gets a checkpoint before it goes, so `/undo` and `/rewind` restore it, folders included; empty folders inside aren't restored.
- **Refused before any question**, so approval can't override them:
  - the workspace root, `.git` (anywhere) and `.jharness`;
  - mounts, paths outside the workspace and denied paths;
  - folders that hold a denied file, a symlink, a file over 10 MB (the checkpoint limit) or more than 1,000 files.

**Why.** Agents need to remove files and folders in the project (user request, 2026-11-26), and an empty `writeFile` isn't a delete. Two names keep a model from removing a tree when it meant a file. A folder can take much more than a file, so it asks even when edits are accepted. The limits keep every allowed delete fully undoable, and the walk never follows links out of the workspace.

**Rejected.** One `delete(path)` for both (a typo removes a tree). `delete(path, {recursive})` (the same risk behind a flag). Folders following the write rules (accept-edits would remove trees silently). Deleting through `git rm` or a build tool (no checkpoint, no question of its own).

**Source.** User request and answers, 2026-11-26: "Agents will need to delete files and folders inside the project folder"; `deleteFile` and `deleteFolder`; folders always ask. Specifying 056-file-and-folder-deletes.

---

## D-092 — Go tool

**Decision.** Go is a shipped tool definition, like Maven, Gradle and npm (spec 059).

- **Operations.**
  - `go.json`: `build`, `test` and `vet` over one package pattern (default `./...`), `test` with a `-run` filter, `mod-tidy`, `mod-download` and `version`.
  - `gofmt.json`: `check` (`gofmt -l .`) and `check-folder`, a second tool because a definition has one executable and `go fmt` rewrites files.
  - Patterns refuse values that start with `-` or leave the module.
  - No `generate`, `run`, `install` or `get`.
- **Proxy.** The existing `env` method sets `HTTPS_PROXY` and friends; no Go-specific code.
- **No other way out.**
  - `GOPROXY=https://proxy.golang.org`, without `direct`, so Go never fetches with git.
  - `GOSUMDB=sum.golang.org`.
  - `GOVCS=*:off`.
  - `GOFLAGS=-buildvcs=false`, since `go build` otherwise runs git to stamp versions.
  - `GOTOOLCHAIN=local`, since a `toolchain` line would download and run another Go.
  - `GOENV=off`, since `go env -w` settings could add flags.
- **Caches.** `GOPATH=~/go`, `GOMODCACHE=~/go/pkg/mod` and `GOCACHE=~/.cache/go-build` on every OS (macOS's default is under `~/Library`, which tools may not write); `~/go/pkg` and `~/.cache/go-build` are writable. For this, an `env` value that starts with `~/` is now a folder in the home, and must stay below it.
- **Offline.** `GOPROXY=off`; builds use the module cache or `vendor/`.
- **Hosts.** Not shipped: JHarness ships no allow rules for any tool, and an unlisted host asks at the proxy, where "always" saves it. `examples/network.rules` lists `proxy.golang.org`, `sum.golang.org` and `golang.org` with the Maven and npm hosts. Private modules come through the user's own module proxy, put first in `GOPROXY`, with `GONOSUMDB`, never `GOPRIVATE` or `GONOPROXY`, which mean git.

**Why.** The user asked for a Go tool like the Maven and npm ones, with downloads through the egress proxy and no direct fetches (2026-11-26). Go's defaults start git (`direct`, VCS stamping) and can run a downloaded toolchain, both outside the process rules (D-049, constitution Article IV). Shipping allow rules would widen every user's egress for one ecosystem.

**Rejected.**
- A per-operation executable for `gofmt` (a format change for one case).
- A list field type for several packages (one pattern covers a tree).
- Shipped allow rules for the Go hosts.
- `GOPRIVATE` for private modules (it means git).

**Source.** User request and answers, 2026-11-26: a separate gofmt tool, hosts in an example file, Go under `~/sdk` and in CI, one package pattern. Specifying 059-go-tool.

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
| Q34 | Does a yes/no check (a System One decision model such as Jev or Laya) improve command safety? | Yes for reliability and intent, only as an extra layer that can tighten; not as the security boundary | Catches off-task actions rules can't | D-031 |
| Q35 | How does the user choose a local model or Claude? | Named model profiles in `settings.json`; `--model` and `/model` | One switch for all providers | D-032 |
| Q36 | How is the reviewer configured? | `reviewer` section: System One URL, key, confidence thresholds, timeout, fail to ask | | D-031 |
| Q37 | Can a Java developer work offline with a local model and a local reviewer such as Laya? | Yes, with everything cached; an explicit offline mode | Works without a connection | D-033 |
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
| Q60 | How do git fetch, pull, push and clone reach the network, and what does offline mode do? | HTTPS only through the proxy port, tokens per host from user settings, offline denies what no rule allows and runs tools offline | Completes D-039 and D-033 without loosening Articles V and VII | D-055 |
| Q61 | How are the injection, malicious-repo and sandbox-escape suites built and run? | One catalogue of cases against a real session with an obedient model, shared canary checks, run on the JVM and in the native smoke binary | Gives the Phase 2 gate repeatable evidence on both OSes | D-056 |
| Q62 | What happens when the macOS sandbox refuses a build's connection to the proxy port? | The same approved run is repeated, up to 3 runs in all, within the call's timeout | Builds stop failing on a Seatbelt flake that has no known fix | D-057 |
| Q63 | How do model calls handle rate limits, overloads and failed connections? | Retry what failed before anything reached the user, after `retry-after` or a capped back-off, with per-profile limits; Esc ends a wait | A 429 or a restarting local server no longer ends the turn | D-058 |
| Q64 | How does a user debug a session, audit it and report a crash? | `--debug` for live detail, `jharness log` for a readable timeline with control characters escaped, and a local crash report with no event bodies, exit 3 | Closes the last Phase 2 item and US-3.6 without telemetry | D-059 |
| Q65 | Should live output escape control characters from model and file text? | Yes: one escaping writer for the TUI, `-p` stdout and stderr; only JHarness styling is raw | A hostile reply or file can't rewrite the terminal or an approval question | D-060 |
| Q66 | Can the model pass build parameters such as `-DskipTests`? | Only as typed fields: Maven `install`, `skipTests`, `profiles`, `threads`; Gradle `skipTests`, `parallel`, `continue`; npm `omitDev` | Common builds work while free-form arguments stay closed | D-061 |
| Q67 | What should `/` and Tab do in the TUI? | Print the `/help` text; Tab after a partial command completes it, listing matches with descriptions | Commands can be found without leaving the prompt | D-062 |
| Q68 | What is a custom agent and how is it chosen? | A folder with `agent.json`, `prompt.md` and `helpers.js`; `--agent NAME` at start; it only narrows tools, mode and steps; project agents need trust | US-4.1 without new privileges | D-063 |
| Q69 | How do subagents work? | `api.spawnAgent` runs a fresh loop in its own isolate under the session's gate, narrowed by a custom agent; depth one; per-turn and step limits configurable (10 and 100 by default); an optional model profile, listed by `api.models()`; returns the final reply | Big tasks split without a subagent ever doing more than the main agent | D-064 |
| Q70 | How does a user see, follow and stop running agents, and tell their events apart? | Background subagents with `/agents`, `/agents NAME` and `/stop NAME\|all`; every event names its agent (`main`, `sub-N`) and its source (`host` or `guest`), and `jharness log` filters by both | Parallel work stays visible and stoppable without stopping the main agent | D-065 |
| Q71 | Can users change, add and remove build tools without changing Java code? | Yes: tools are JSON definition files, seeded into `~/.jharness/tools/` and editable there; trusted projects can add their own | Lets a user tighten Maven or add Go without a release, under the same typed-argument and sandbox rules | D-066 |
| Q72 | How does an agent know which tools it can run? | `api.tools()` returns each tool's operations, fields and whether it can run here; the system prompt lists them too; `api.tool(name, request)` runs one | One generic call replaces per-tool methods, so new tools need no new api | D-067 |
| Q73 | Why aren't git and `rg` tool definitions too? | A definition only runs a program with checked arguments and returns its output; git and `rg` need host code for approvals per operation, untrusted projects, injection defences, secrets, undo and parsed results | Moving them would turn the format into a programming language, and a mistake in a file would become a security hole | D-068 |
| Q74 | How does a user watch what a running subagent does? | `/follow sub-N` streams its reply and steps like the main loop; Esc stops watching and the subagent keeps running | `/agents sub-N` only printed past events | D-069 |
| Q75 | How are session, permission and checkpoint folders named? | `users-xyz-projects-jharness.dev-2787870/s-2026-11-24T105226Z-18a9.jsonl`: the path as a slug with a short hash, and the start time | Easy to find by hand; the hash keeps paths with the same slug apart | D-070 |
| Q76 | How does the user answer a subagent's questions and talk to it? | `/switch sub-N`: the prompt becomes `sub-N>`, messages are new turns in its conversation, and its approvals are asked while you are switched to it | Background subagents can finish work that needs approval without the main agent in between | D-071 |
| Q77 | Can a background subagent's question reach the user instead of being denied? | Yes: it waits with a notice above the prompt, `/agents` shows `waiting`, and `/switch NAME` asks it; after 10 minutes it is denied | Background work that needs one approval no longer fails, without questions popping up over the user's typing | D-072 |
| Q78 | How do hooks work? | JS files with `preTool(call)`, `postTool(call)` and `stop(turn)`; a pre-tool hook returns `{block}` before the gate, a stop hook returns `{continue}`; hooks never approve | Team guardrails apply to every agent without a shell (D-022) | D-073 |
| Q79 | How are slash commands defined? | A Markdown file is a prompt template; a JS file's `run(args)` builds a prompt or prints text, in its own sandbox | Repeated tasks become one command without a new release | D-074 |
| Q80 | How do the todo list and plan mode work? | The agent sets its list with `api.todos([...])`; `/plan` makes the session read-only, and `api.proposePlan(text)` shows the plan and asks the user to approve it, which turns plan mode off | The user sees the agent's steps and approves a plan before anything changes | D-075 |
| Q81 | How are MCP servers configured and called? | In `~/.jharness/settings.json` `"mcp": {"servers": {...}}` (command or url); `api.mcp({server, tool, input})` calls a tool after approval; `api.mcp({list: true})` lists them | Lets the agent use the user's other tools under the same sandbox and permissions | D-076 |
| Q82 | How does the action reviewer ask System One? | One `noul` question per state-changing operation, with the user's request and the action as `state`; below `askBelow` asks, below `denyBelow` denies; failures ask | Maps the answer straight onto D-031's two thresholds | D-031 |
| Q83 | How do long sessions keep working when the context fills? | Count tokens (the model's counts, else an estimate); at 80% of the window minus the reply size, the model summarizes the older conversation; `/context`, `/compact` | Works with any model and adds nothing to the binary | D-077 |
| Q84 | How do prompt caching and `@file` mentions work? | Claude requests mark the system prompt and the last message for the 5-minute cache; `@path` attaches files and folders under the file policy within a size budget | Lower cost per step; files by name without a model step | D-078 |
| Q85 | Should `/quit` and `/q` work, and can Tab complete command arguments? | Yes: aliases of `/exit`, not listed; built-ins win over command files; Tab lists and cycles each command's choices, and command files declare `args` | Fewer keystrokes; built-ins mean the same thing everywhere | D-079 |
| Q86 | Should the TUI have a status line, and can users program it? | Yes: built-in items chosen by `ui.statusLine`; `~/.jharness/statusline.js` returns the whole line from a snapshot, sandboxed with no `api`; throttled refresh; failures fall back to the default | Key state at a glance; a new extension point that can't act | D-080 |
| Q87 | How are sessions resumed and forked? | The conversation is saved after each turn in a state file next to the log; `--resume` continues the same session, `--fork` copies it into a new one; earlier turns can't be rewound | Exact resume without re-explaining; branching without losing work | D-081 |
| Q88 | Should `jharness --help` keep picocli's colors? | Yes: picocli's help, version and usage errors use the raw streams, colored only on a terminal and not with `NO_COLOR`; usage errors escape the typed arguments; everything else stays escaped | The help was unreadable on a terminal | D-060 |
| Q89 | How should `jharness tools` list the tools? | A table: `NAME SOURCE EXECUTABLE STATUS OPERATIONS`, columns as wide as their longest value, escaped values; edited and broken files noted below | The rows didn't line up and had no headers | D-066 |
| Q90 | Should `/help` list the commands in alphabetical order? | Yes: commands A to Z with their forms; keys and `@PATH` in their own section at the end; Tab completion follows the same order | Easier to find a command | D-062 |
| Q91 | What does headless mode print, and how does CI set the permission mode? | `--output text`, `json` (one result object) or `stream-json` (every event, then the result); exit 0, 1 or 2; `--mode` for the session | Pipelines get one parseable answer and a meaningful exit code | D-082 |
| Q92 | How is the web viewer served and protected? | `jharness-web`, the JDK `HttpServer` on `127.0.0.1`; token, `Host` and `Origin` checks, GET only, a strict CSP; Server-Sent Events of the stream-json lines; `jharness view` and `/view` | Long sessions and diffs read better in a browser, and a local page can't change the session | D-083 |
| Q93 | How are tokens and cost tracked? | A profile's `price` puts `costUsd` on each `model.completed`; `/usage`, `jharness usage`, the headless result and a `cost` status item add the log up | Users pick models by cost and want to catch expensive runs; a shipped price list would go stale | D-084 |
| Q94 | How are evals run and scored? | Repository tasks with hidden checks in `evals/tasks/`, run by `scripts/eval.py` through headless JSON output; a check must fail first; CI runs recorded solutions; live scores per release | Shows whether releases get better or worse, without the binary starting check commands | D-085 |
| Q95 | How are the web viewer and the TUI checked in real browsers and terminals? | `scripts/viewer-check.mjs` (Playwright: Chromium, Firefox, WebKit; Safari through safaridriver) and `scripts/tui-tmux-check.py` (tmux, several sizes), by hand after viewer or TUI changes and before releases, never in CI | The viewer's 403s slipped past tests that fetched files by hand; browsers in CI would cost minutes per run | D-086 |
| Q96 | Should a turn wait for a subagent? | No: `api.waitAgent` is removed; a background subagent's reply starts a main turn (at most `agent.autoTurns` in a row, default 10); `-p` runs until none is left; `/answer` asks a held question in place | The main chat stayed blocked behind a wait while the subagent waited for the user, who couldn't type (user report, 2026-11-25) | D-087 |
| Q97 | Should the main prompt close while the main agent works? | No: turns run behind a live prompt; output above it a line at a time; typed messages queue and go next; Esc stops the turn; the main agent's questions are held for `/answer main` | Keys typed during a turn were lost, and automatic turns closed the prompt without the user asking (user, 2026-11-26) | D-088 |
| Q98 | How do agents delete files and folders? | `api.deleteFile` as a write; `api.deleteFolder` always asks outside bypass; both undoable; root, `.git`, `.jharness`, mounts, secrets, links and huge folders refused | Agents needed deletes, and a folder delete is the riskiest file change there is (user, 2026-11-26) | D-089 |
| Q101 | Does each new build tool need JHarness code changes? | No: a tool is a JSON definition over typed fields with a built-in proxy method. Go needed one general change, `~/` home paths in `env` values; Rust and similar tools should need only a file | The user asked before adding Go (2026-11-26) | D-092 |

---

## Open items to settle before implementation

None. P0 can start.
