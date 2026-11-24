# Plan 037 — MCP client

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Config: `McpSettings`; `Settings.mcp`; `layered` drops a project's `mcp` like `git`.
- Process: `ProcessRunner.start` returns a `Live` child with pipes and a stderr tail, in the OS sandbox.
- New module `jharness-mcp`:
  - `McpClient` with stdio and HTTP transports.
  - `McpServers` starts every server (processes on the caller's thread, because bubblewrap's `--die-with-parent` follows the starting thread), runs the handshakes in parallel, lists, checks and calls tools, and publishes events.
- Sandbox: `ApiServices.mcp`; `HostApi.mcp`; `Operation.Kind.MCP` (changes state); `ProjectRules` saves `mcp` rules; shim, `api.d.ts`, metadata; `ApiContractTest` counts 20 methods.
- Agent: `Session` starts the servers, allows HTTP endpoints, adds the prompt section, closes them, and offers `describeMcp`; subagents get the same servers.
- CLI: `/mcp`.
- Tests: `McpClientTest` (stdio pipes, HTTP JSON and SSE); `McpSessionTest` (a Python stdio server in the sandbox, list and call under the gate, denial, a secret, failing servers, a project's `mcp` ignored); CI native step.
- D-076, Q81, tech-stack, README, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | MCP servers are child processes the user configured, allowed by Article IV | Yes |
| II Host owns side effects | Agent JS reaches servers only through `api.mcp` and the gate | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | `command` is a program on `PATH` with an argv list; shells refused; started through `ProcessRunner` | Yes |
| V Proxy | Stdio servers get only the proxy; HTTP servers go through it | Yes |
| VI Strings | Requests and results cross as JSON strings | Yes |
| VII Safe by default | Every call asks by default; plan mode denies; projects can't add servers; secrets only as variables or a header | Yes |
| VIII Events | `mcp.connected`, `mcp.called`, process events | Yes |
| IX Native first | No new dependency (own client on `java.net.http`); CI runs it natively | Yes |
| X Tested | Client transports, sandboxed server, gate, secrets, failures, settings | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks (with a stdio server) on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A slow server delays start-up | 30 s limit per server, handshakes in parallel |
| Node-based servers ignore `HTTP_PROXY` | Documented; they then can't reach the network, which fails safe |
