# JHarness — Constitution

The rules every feature must follow. A feature's `plan.md` must pass the constitution check below before tasks are written. Changing an article needs a new entry in `decisions.md` that says why.

Version 1.1 · Ratified 2026-11-16 · Amended 2026-11-17 (Article III: JS-only hooks, D-022; Article IV: stdio MCP servers, D-023)

---

## Articles

### I. One process, one binary
JHarness ships as one native executable. Agent isolates run inside that process (`engine.IsolateMode=internal`). The only child processes are the host's typed tool operations (Article IV).
*Source: D-001, D-002*

### II. The host owns every side effect
Files, network, processes, model calls and secrets are handled only by Java host code. Agent JavaScript reaches them only through the `api` facade, and every `api` call goes through permissions.
*Source: D-005, D-007*

### III. Agent code is untrusted
Every agent context runs under `SandboxPolicy.UNTRUSTED` and `HostAccess.UNTRUSTED`, with hard memory and CPU limits, one thread and a wall-clock watchdog. One `Engine` per agent; a fresh `Context` per run. Hooks are JS only and run under the same rules. No feature may weaken these settings.
*Source: D-003, D-004, D-022*

### IV. No shell
Agent code cannot start processes. The host starts processes only through `ProcessRunner`: typed git, Maven, Gradle and npm operations, and stdio MCP servers from the user's own configuration. Every process gets an argv list: never `sh -c`, never a command string built by concatenation, never an executable from inside the workspace except wrappers in trusted projects.
*Source: D-008, D-009, D-023*

### V. All network traffic goes through the host proxy
Every outbound connection, from the Java `HttpClient` and from every command-line tool, goes through the host egress proxy and its rules files. No code may build an HTTP client or open a socket that skips it. Build tools run in an OS sandbox that only allows the proxy port.
*Source: D-012, D-025*

### VI. Strings at the boundary
Only strings and JSON cross into and out of isolates. No live host object is handed to the guest.
*Source: D-005, D-006*

### VII. Safe by default
State-changing operations ask for approval unless a rule allows them. A project's own code and settings run only after the user trusts the project. Secrets never reach prompts, the guest or the event log.
*Source: D-010, D-011*

### VIII. Events are the source of truth
Everything that happens is a JSON event with a host-stamped envelope, appended to the session log. UI, hooks, audit and resume read events; nothing reads hidden state instead.
*Source: D-006*

### IX. Native Image first
Every dependency must build and run under GraalVM Native Image on Linux and macOS before it is adopted. Prefer small libraries and our own thin clients over large SDKs.
*Source: D-001, D-021*

### X. Security is tested, not assumed
Every security rule (Articles III, IV, V, VII) has automated tests, on the JVM and on the native binary. Every security bug found becomes a regression test before it is fixed.

### XI. Spec before code
No production code without a spec in `specs/NNN-feature/` that has acceptance criteria, a plan that passes the constitution check, and tasks linked to those criteria. Spikes are allowed, but their code is thrown away or rewritten under a spec.

---

## Constitution check

Copy this into each feature's `plan.md` and answer every line.

| Article | Question | Pass? |
| --- | --- | --- |
| I | Does the feature add a process, server or runtime outside the binary? | |
| II | Does any side effect happen outside host code or skip permissions? | |
| III | Does it change sandbox policy, limits, engine or context lifetime? | |
| IV | Does it start a process? Only through `ProcessRunner` with typed arguments? | |
| V | Does it make a network connection? Only through the proxy? | |
| VI | Does it pass anything but strings or JSON across the isolate boundary? | |
| VII | Does it change approvals, trust or secret handling? | |
| VIII | Does every new action emit an event? | |
| IX | Does every new dependency build and run under Native Image? | |
| X | Are the security rules it touches covered by tests? | |
| XI | Does every task trace to an acceptance criterion? | |

## Quality gates (every pull request)

- All tests pass on the JVM; security suites also pass on the native binary for Linux and macOS.
- No new dependency without a Native Image check (Article IX).
- No new `HttpClient`, `Socket`, `ProcessBuilder` or `Runtime.exec` outside their owning modules (enforced by an architecture test).
