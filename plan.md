# JHarness — Plan

How JHarness gets built: architecture, modules, phases with exit gates, the testing approach and the spec-driven workflow for each feature.
Product scope lives in `product/mission.md`, feature order in `product/roadmap.md`, technology in `product/tech-stack.md`, and the reasons behind each choice in `product/decisions.md`.

Last updated: 2026-11-18

---

## 1. Architecture in one page

JHarness is one native process:

```
Terminal UI / headless CLI
          │
┌─────────▼────────────────────── Host process (Java, GraalVM native image) ──────────────┐
│  Agent loop ──► Event bus ──► UI, session log (JSONL), hooks                             │
│      │                                                                                   │
│      ├─► Model providers: OpenAI-compatible (local and hosted) │ Claude (native)          │
│      │                                                                                   │
│      └─► Isolate manager: one Engine per agent, fresh Context per RunJS block            │
│              │   (UNTRUSTED policy, CPU/heap/wall-clock limits, 1 thread)                │
│              ▼                                                                           │
│          api facade ──► Permissions ──► Host operations                                  │
│                                          ├─ Files (workspace rw, mounted folders ro)     │
│                                          ├─ ProcessRunner: git, Maven, Gradle, npm, rg   │
│                                          ├─ HTTP client                                  │
│                                          ├─ MCP client (api.mcp; stdio via ProcessRunner)│
│                                          └─ spawnAgent (subagent in its own isolate)     │
│                                                                                          │
│  Egress proxy (127.0.0.1, random port): every outbound connection from the HTTP client,  │
│  MCP client, model providers, git, Maven, Gradle and npm goes through it. It applies the │
│  allow/deny rules from ~/.jharness/network.rules and .jharness/network.rules.            │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

**One turn**
1. The user sends a message; the host emits `turn.started`.
2. The loop streams the model's reply; `model.delta` events feed the UI.
3. The host takes the first `js` block from the reply and runs it in the agent's isolate, in a fresh context.
4. Each `api` call goes through pre-tool hooks and permissions, then runs in the host and emits events.
5. The block's return value, or its error with line numbers, goes back to the model. The loop repeats until the model stops, then emits `turn.completed`.

**Ground rules** (see `product/decisions.md`)
- The host owns every side effect. Agent JS reaches the world only through `api`.
- Only strings and JSON cross the isolate boundary.
- Agent code never starts a process. The host starts processes only through `ProcessRunner`.
- All network traffic leaves through the host's egress proxy.
- Every event is JSON with a host-stamped envelope; the session log is the source of truth.

The full rule set is `product/constitution.md`.

---

## 2. Module layout (proposed)

Maven multi-module build. Dependencies flow downward.

| Module | Responsibility | Depends on |
| --- | --- | --- |
| `jharness-events` | Event envelope, types, validation, bus, JSONL session log | — |
| `jharness-config` | Settings layers (user, project, session), project trust, permission rules, network rules | events |
| `jharness-security` | Permission engine, file policy, optional action reviewer client (Kev, Jev, own model) | config |
| `jharness-proxy` | Egress proxy: rules-file parser, rule matching, DNS pinning, CONNECT and plain-HTTP forwarding, per-session credentials | config, events |
| `jharness-process` | `ProcessRunner`, typed git / Maven / Gradle / npm / rg operations, OS sandbox launchers | security |
| `jharness-sandbox` | Isolate manager, `api` facade, limits, watchdog, `api.d.ts` generation | security, process |
| `jharness-llm` | Provider interface; OpenAI-compatible and Claude providers (ModelJars after v0.1, D-035); streaming | events |
| `jharness-agent` | Agent loop, JS block extraction, subagents, hooks, slash commands, checkpoints | sandbox, llm |
| `jharness-mcp` | MCP client (stdio, streamable HTTP) | security |
| `jharness-cli` | picocli entry point, JLine TUI, headless mode, native-image config | agent |
| `jharness-testkit` | Replay provider, malicious-repo fixtures, sandbox-escape suite | all |

---

## 3. Phases and gates

Each phase ends with a gate. The next phase starts only when the gate passes. Every phase leaves a usable binary. Feature lists per phase are in `product/roadmap.md`.

### P0 — Spike (prove the risky parts)

**Build**
- A native binary on Linux and macOS that creates a `js-isolate` context under the UNTRUSTED policy and calls a host `api` method.
- A ModelJars model loaded and run inside the same native binary.

**Measure** binary size, startup time, isolate creation time and memory, memory protection availability on Linux, ModelJars token speed on an 8k-token prompt.

**Also in P0:** CI skeleton, `SECURITY.md`, CONTRIBUTING. The repository and MIT license already exist (D-024).

**Gate:** all numbers recorded in `docs/p0-results.md`; both OS builds run; a go/no-go on ModelJars-in-native-image (no-go → local models use the OpenAI-compatible client).

### P1 — Core loop MVP

Model providers, agent loop with RunJS, file `api` and git, ask-mode permissions with per-project permanent approvals, project trust, checkpoints and undo, diff review, interrupt, JLine TUI, session log, `JHARNESS.md`, `api.d.ts` in the system prompt, approvals inside a JS run, replay provider.

**Gate:** fixes a seeded bug in a sample repo end to end, with a local model and with Claude.

### P2 — Build tools and hardening

Maven, Gradle and npm operations; offline mode; web fetch; the egress proxy with its two rules files and `/network` commands; OS sandbox for build tools (which also blocks any direct connection that skips the proxy); full limits and watchdog; injection and sandbox-escape suites; model profiles; retries; debugging tools.

**Gate:** every runaway, out-of-memory, injection and escape test is contained, on Linux and macOS.

### P3 — Extensibility

Custom JS agents, subagents (`api.spawnAgent`), JS-only hooks, slash commands, MCP client reached through `api.mcp`, todo list and plan mode, optional action reviewer.

**Gate:** one custom agent and one hook ship as working examples.

### P4 — Context and release

Compaction, prompt caching, resume and fork, headless JSON output, usage tracking, evals, Homebrew tap and install script, signed and notarized macOS binaries, self-update, docs.

**Gate:** v0.1 tagged; signed binaries for Linux and macOS.

---

## 4. Testing approach

| Layer | What | Where |
| --- | --- | --- |
| Unit | Rule matching, argv builders, event validation, path checks | JVM, every PR |
| Property-based | Argv builders and network rule parser fuzzed with jqwik | JVM, every PR |
| Replay | The full loop against recorded model responses (no model cost) | JVM, every PR |
| Security suites | Injection strings, malicious repos (git hooks, `core.fsmonitor`, tampered `mvnw`, npm install scripts), sandbox escapes, environment leaks, approval bypass | JVM and native, every PR |
| Native | The same suites against the native binary | CI, Linux and macOS |
| Evals | Real repo tasks scored automatically | Before each release |

Every security bug found becomes a regression test.

---

## 5. Spec-driven workflow for each feature

Product-level docs (`product/`) are written once and kept current. `product/constitution.md` holds the rules no feature may break. Each feature then goes through:

1. **Spec** — `specs/NNN-feature/spec.md`: user stories, acceptance criteria, out of scope. Links to the stories in `user-stories.md`.
2. **Clarify** — open questions answered in the spec; anything architectural goes into `product/decisions.md`.
3. **Plan** — `specs/NNN-feature/plan.md`: design, contracts (`api` signatures, event types, settings keys), risks, and a constitution check.
4. **Tasks** — `specs/NNN-feature/tasks.md`: ordered, testable tasks, each linked to an acceptance criterion.
5. **Implement and verify** — code, tests, and a check that every acceptance criterion has a passing test.

Written so far: `001-p0-spike`, `002-host-foundations` (events and session log, settings, permission gate, `ProcessRunner` with git and `rg`, isolate manager and `api`, model providers).

Next specs: `003-agent-loop-runjs` (loop, `api.d.ts`, system prompt, replay provider), then permissions and file policy, project trust and checkpoints, settings and secrets, the TUI, then the network proxy in P2.

**Contracts:** the event envelope, the `api` methods and the user settings format are in `specs/002-host-foundations/plan.md`. Still to write: `api.d.ts` and the system prompt (003), the permission rules format, the network rules format, and the CLI flags and slash commands.

---

## 6. Top risks

| Risk | Mitigation |
| --- | --- |
| Maven plugins and npm scripts run project code outside the isolate | Approval by default, project trust, `--ignore-scripts`, OS sandbox |
| ModelJars may not run, or run fast enough, in a native image | Settled in P0: no-go for v0.1; local models through the OpenAI-compatible client (D-035) |
| A library fails under Native Image (JLine, MCP SDK) | Native builds from P0; thin clients of our own |
| Isolate start time and memory per agent | Measure in P0; cap concurrent agents |
| Small local models write poor JS | `api.d.ts` and examples in the prompt; errors with line numbers; model profiles |
| Long native builds | Develop on the JVM; native in CI |
