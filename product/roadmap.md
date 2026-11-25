# JHarness — Roadmap

Features in build order. Each phase ends with a gate it must pass before the next starts. No dates are set yet.
How each phase is built and tested is in `../plan.md`; why each choice was made is in `decisions.md`.

Last updated: 2026-11-24

---

## Phase 0 — Spike

Prove the risky parts before building features.

- [x] Native binary on Linux and macOS that runs JS in a `js-isolate` context under the UNTRUSTED policy and calls a host `api` method
- [x] Measure binary size, start-up time, isolate creation time and memory
- [x] Check memory protection keys on Linux
- [x] Load and run a ModelJars model inside the native binary; measure speed on an 8k-token prompt
- [x] Check that the default local models are available and qualified in the ModelJars catalog
- [x] Repository and MIT license
- [x] CI skeleton, `SECURITY.md`, CONTRIBUTING

Spec: `../specs/001-p0-spike/`

**Gate:** numbers recorded; both OS builds run; go/no-go on ModelJars in the native image.

**Gate passed 2026-11-18:** numbers in `../docs/p0-results.md`; native builds run on linux-amd64, linux-aarch64 and macos-aarch64; ModelJars is a no-go for v0.1 (D-035), so local models use the OpenAI-compatible client.

## Phase 1 — Core loop MVP

- [x] Model providers: OpenAI-compatible (local models through Ollama or llama.cpp), Claude (native) (D-035)
- [x] Model profiles in `settings.json`; `--model` and `/model` (D-032)
- [x] Agent loop: stream reply, extract the JS block, run it, return result or error with line numbers
- [x] Isolate manager: one Engine per agent, fresh Context per run, limits, watchdog
- [x] `api`: readContext, readFile, writeFile, editFile, listFiles, grep, git, emit
- [x] `ProcessRunner` with git and `rg` (search), Java search fallback when `rg` is missing
- [x] `api.d.ts` and examples in the system prompt
- [x] Permissions: ask, accept-edits, read-only (plan), bypass; permanent approvals per project
- [x] Approvals inside a running JS block (watchdog pauses while waiting)
- [x] Project trust prompt
- [x] Checkpoints, `/undo`, `/rewind`; diff review; Esc to interrupt
- [x] File policy: workspace read-write, mounted folders read-only, deny list
- [x] Event bus and JSONL session log
- [x] `JHARNESS.md` project memory; layered JSON settings
- [x] JLine TUI
- [x] Secrets from environment variables or a 0600 file
- [x] Replay provider for deterministic tests

**Gate:** fixes a seeded bug in a sample repo end to end, with a local model and with Claude.

The local-model run is recorded in `../docs/p1-gate.md`; the live Claude run is a Phase 4 release check (D-045).

**Gate passed 2026-11-19:** attempt 2 with `qwen/qwen3.6-27b` fixed the seeded bug and committed it in one turn (`../docs/p1-gate.md`); fixes from attempt 1 landed in specs 008–010.

## Phase 2 — Build tools and hardening

- [x] Maven, Gradle and npm typed operations through `ProcessRunner`
- [x] Per-operation timeouts with `timeoutMs` (2 min default, 10 min max for builds) and process-tree kill
- [x] Output returned to the model capped at the last 30,000 characters plus exit code; full output in the session log
- [x] Command-execution checks (no shell, pinned executables, argument validation, git hardening, clean environment, process-tree kill)
- [x] OS sandbox for build tools (bubblewrap or Landlock on Linux, Seatbelt on macOS)
- [x] `api.http` and web fetch
- [x] Egress proxy in the host: every HTTP client and command-line tool goes through it (HTTP clients: spec 011; Maven, Gradle and npm: spec 013; git: spec 017)
- [x] Rules files `~/.jharness/network.rules` (general) and `.jharness/network.rules` (project); DNS pinning; `/network` commands
- [x] OS sandbox allows network only to the proxy port
- [x] Offline mode: network default deny, Maven/Gradle/npm offline flags, no update checks (D-033)
- [x] Injection, malicious-repo and sandbox-escape test suites, on JVM and native (spec 018)
- [x] Retries of build and git runs that the macOS sandbox refuses at the proxy port (spec 019)
- [x] Model profiles; retries and rate-limit handling (spec 020)
- [x] `--debug`, `jharness log <session>`, local crash reports (spec 021)
- [x] Control characters escaped in the TUI and `-p` output (spec 022)
- [x] Typed build parameters: skip tests, Maven profiles, threads and `install`, Gradle parallel and continue, npm omit-dev (spec 023)
- [x] `/` and Tab list and complete the slash commands (spec 024)

**Gate:** every runaway, out-of-memory, injection and escape test is contained on Linux and macOS.

**Gate passed 2026-11-23:** all 35 security cases contained on linux-amd64, linux-aarch64 and macos-aarch64, on the JVM and in the native binary; RUN-08 on macOS is the recorded exception (D-056). Run in `../docs/p2-gate.md`.

## Phase 3 — Extensibility

- [x] Custom JS agents in `.jharness/agents/` (spec 025)
- [x] Subagents via `api.spawnAgent` (spec 026)
- [x] Background subagents, `/agents` and `/stop` (spec 027)
- [x] Follow a subagent live (`/follow sub-N`) (spec 030)
- [x] Switch to an agent, answer its questions and talk to it (spec 032)
- [x] Held background questions: wait for the user with a notice (spec 033)
- [x] Build tools as definition files in `~/.jharness/tools/` and trusted projects; `api.tool` and `api.tools()` (spec 028)
- [x] Prompt examples for small models, each run by a test against a test project; why git and `rg` stay in Java (spec 029)
- [x] JS hooks (pre-tool, post-tool, stop); no shell hooks (spec 034; `examples/.jharness/hooks/guard.js` is the gate's hook example)
- [x] Slash commands (Markdown templates and JS) (spec 035)
- [x] MCP client (stdio, streamable HTTP), called from JS through `api.mcp`; stdio servers started through `ProcessRunner` in the OS sandbox (spec 037)
- [x] Todo list and plan mode (spec 036)
- [x] Optional action reviewer: a System One API decision model (Jev, or a local equivalent such as Laya); only tightens; fails to ask (D-031, spec 038)

**Gate:** one custom agent and one hook ship as working examples.

**Gate passed 2026-11-24:** the shipped reviewer agent and guard hook pass all 13 checks of `scripts/p3-gate.sh` in the native binary on linux-amd64 and macos-aarch64, and CI runs it on every target. Run in `../docs/p3-gate.md`.

## Phase 4 — Context and release (v0.1)

- [x] Token counting and auto-compaction (spec 039)
- [x] Prompt caching, `@file` mentions (spec 040)
- [x] `/quit` and `/q` aliases; Tab completes command arguments (spec 041)
- [x] Status line: built-in items and `~/.jharness/statusline.js` (spec 042)
- [x] Resume and fork sessions (spec 043)
- [x] Headless mode with text, JSON and stream-JSON output; `--mode` (spec 047)
- [x] Read-only web session viewer on `127.0.0.1`, reading the same event stream as headless mode; no approvals from the browser; doesn't gate v0.1 (D-053, spec 048)
- [x] Manual browser and terminal-size checks of the viewer and TUI, never in CI (D-086, spec 053)
- [x] Usage tracking (tokens, cost) (spec 049)
- [x] Evals on real repo tasks (spec 051)
- [ ] GitHub releases with checksums, Homebrew tap, install script
- [ ] macOS code signing and notarization
- [ ] `jharness update` with signature verification
- [ ] Live Claude run of the P1 gate scenario (D-045)
- [ ] User guide, `api` reference, extension authoring guide

**Gate:** v0.1 tagged; signed binaries for Linux and macOS; the manual browser and terminal checks pass on both (D-086, `docs/manual-checks.md`).

## After v0.1

- Long-lived background processes (dev servers, watch modes)
- Windows
- Extensions installed from a git URL
- More build tools: pnpm, yarn, Python (uv, pip, pytest), Go, Cargo, make, Docker
- Image input and web search
- A Java SDK for embedding JHarness
- In-process local models with ModelJars, when D-035's conditions are met
- Spike a model helper binary with the Rust backend, started over stdio; first for a small reviewer model (D-035, D-031)
- Test memory protection keys on a Linux machine that has them; make `auto` the default if it works (D-036)
- IDE integration
- Web companion UI for a running session: prompts and approval answers from the browser; phone and tablet access with real authentication and TLS (D-053)
