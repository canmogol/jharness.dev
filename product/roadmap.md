# JHarness — Roadmap

Features in build order. Each phase ends with a gate it must pass before the next starts. No dates are set yet.
How each phase is built and tested is in `../plan.md`; why each choice was made is in `decisions.md`.

Last updated: 2026-11-15

---

## Phase 0 — Spike

Prove the risky parts before building features.

- [ ] Native binary on Linux and macOS that runs JS in a `js-isolate` context under the UNTRUSTED policy and calls a host `api` method
- [ ] Measure binary size, start-up time, isolate creation time and memory
- [ ] Check memory protection keys on Linux
- [ ] Load and run a ModelJars model inside the native binary; measure speed on an 8k-token prompt
- [ ] Check that the default local models are available and qualified in the ModelJars catalog
- [x] Repository and MIT license
- [ ] CI skeleton, `SECURITY.md`, CONTRIBUTING

Spec: `../specs/001-p0-spike/`

**Gate:** numbers recorded; both OS builds run; go/no-go on ModelJars in the native image.

## Phase 1 — Core loop MVP

- [ ] Model providers: ModelJars, OpenAI-compatible, Claude (native)
- [ ] Model profiles in `settings.json`; `--model` and `/model` (D-032)
- [ ] Agent loop: stream reply, extract the JS block, run it, return result or error with line numbers
- [ ] Isolate manager: one Engine per agent, fresh Context per run, limits, watchdog
- [ ] `api`: readContext, readFile, writeFile, editFile, listFiles, grep, git, emit
- [ ] `ProcessRunner` with git and `rg` (search), Java search fallback when `rg` is missing
- [ ] `api.d.ts` and examples in the system prompt
- [ ] Permissions: ask, accept-edits, read-only (plan), bypass; permanent approvals per project
- [ ] Approvals inside a running JS block (watchdog pauses while waiting)
- [ ] Project trust prompt
- [ ] Checkpoints, `/undo`, `/rewind`; diff review; Esc to interrupt
- [ ] File policy: workspace read-write, mounted folders read-only, deny list
- [ ] Event bus and JSONL session log
- [ ] `JHARNESS.md` project memory; layered JSON settings
- [ ] JLine TUI
- [ ] Secrets from environment variables or a 0600 file
- [ ] Replay provider for deterministic tests

**Gate:** fixes a seeded bug in a sample repo end to end, with a local model and with Claude.

## Phase 2 — Build tools and hardening

- [ ] Maven, Gradle and npm typed operations through `ProcessRunner`
- [ ] Per-operation timeouts with `timeoutMs` (2 min default, 10 min max for builds) and process-tree kill
- [ ] Output returned to the model capped at the last 30,000 characters plus exit code; full output in the session log
- [ ] Command-execution checks (no shell, pinned executables, argument validation, git hardening, clean environment, process-tree kill)
- [ ] OS sandbox for build tools (bubblewrap or Landlock on Linux, Seatbelt on macOS)
- [ ] `api.http` and web fetch
- [ ] Egress proxy in the host: every HTTP client and command-line tool goes through it
- [ ] Rules files `~/.jharness/network.rules` (general) and `.jharness/network.rules` (project); DNS pinning; `/network` commands
- [ ] OS sandbox allows network only to the proxy port
- [ ] Offline mode: network default deny, Maven/Gradle/npm offline flags, no update checks (D-033)
- [ ] Injection, malicious-repo and sandbox-escape test suites, on JVM and native
- [ ] Model profiles; retries and rate-limit handling
- [ ] `--debug`, `jharness log <session>`, local crash reports

**Gate:** every runaway, out-of-memory, injection and escape test is contained on Linux and macOS.

## Phase 3 — Extensibility

- [ ] Custom JS agents in `.jharness/agents/`
- [ ] Subagents via `api.spawnAgent`
- [ ] JS hooks (pre-tool, post-tool, stop); no shell hooks
- [ ] Slash commands (Markdown templates and JS)
- [ ] MCP client (stdio, streamable HTTP), called from JS through `api.mcp`; stdio servers started through `ProcessRunner` in the OS sandbox
- [ ] Todo list and plan mode
- [ ] Optional action reviewer: Kev, Jev or own model; only tightens; fails to ask (D-031)

**Gate:** one custom agent and one hook ship as working examples.

## Phase 4 — Context and release (v0.1)

- [ ] Token counting, auto-compaction, prompt caching, `@file` mentions
- [ ] Resume and fork sessions
- [ ] Headless mode with text, JSON and stream-JSON output
- [ ] Usage tracking (tokens, cost)
- [ ] Evals on real repo tasks
- [ ] GitHub releases with checksums, Homebrew tap, install script
- [ ] macOS code signing and notarization
- [ ] `jharness update` with signature verification
- [ ] User guide, `api` reference, extension authoring guide

**Gate:** v0.1 tagged; signed binaries for Linux and macOS.

## After v0.1

- Long-lived background processes (dev servers, watch modes)
- Windows
- Extensions installed from a git URL
- More build tools: pnpm, yarn, Python (uv, pip, pytest), Go, Cargo, make, Docker
- Image input and web search
- A Java SDK for embedding JHarness
- IDE integration
