# Spec 037 — MCP client

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

The agent calls the user's MCP servers from JS with `api.mcp`. JHarness has its own thin client for stdio and streamable HTTP servers, configured only in the user's settings. Stdio servers run in the OS sandbox with the network only through the proxy, and every tool call asks unless the user saved "always". Decisions D-023 and D-076.

## Roadmap items

- MCP client (stdio, streamable HTTP), called from JS through `api.mcp`; stdio servers started through `ProcessRunner` in the OS sandbox

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.5 | As a developer, I want to connect MCP servers, so the agent can use my other tools | `user-stories.md` (stdio and HTTP MCP servers usable from JS, under permissions) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `mcp.servers` in the user's settings defines stdio servers (`command`, `args`, `env`, `secretEnv`, `writable`, `timeout`) and HTTP servers (`url`, `tokenSecret`, `timeout`); bad entries stop start-up with a clear message. A project's `mcp` is always ignored. |
| AC-02 | A stdio server starts through `ProcessRunner` in the OS sandbox, from `PATH` outside the workspace, in the workspace folder, with its `writable` folders, the proxy variables and its secrets as variables; it lives for the session. An HTTP server is reached through the proxy, with its endpoint allowed automatically and the secret as a bearer token. |
| AC-03 | The client speaks JSON-RPC 2.0 (`initialize`, `notifications/initialized`, paged `tools/list`, `tools/call`), answers `ping`, refuses other server requests, and handles JSON and event-stream HTTP answers and `Mcp-Session-Id`. |
| AC-04 | Servers connect at session start; one that fails (program missing, secret unset, no answer within 30 s) is listed with its error in `api.mcp({list: true})`, the prompt, `/mcp` and `mcp.connected`. |
| AC-05 | `api.mcp({server, tool, input, timeoutMs})` refuses unknown servers, unknown tools, a non-object input and unknown keys before asking. It then asks (`kind: mcp`; plan mode denies, "always" saves `server/tool`) and returns the server's result. `api.mcp({list: true})` lists the servers and tools without asking. `mcp.called` records each call. |
| AC-06 | The system prompt lists the servers and their tools. `api.d.ts`, the shim and the native metadata include `mcp`. CI's native step lists a stdio server's tools. |
| AC-07 | The README and D-076 document MCP. |

## Out of scope

- MCP resources, prompts, sampling and elicitation.
- OAuth for HTTP servers.
- Project-defined servers.

## Dependencies and assumptions

- D-023, Article IV, the egress proxy (D-025), the OS sandbox (D-049).

## Open questions

None. Recommended options accepted while the user was away.
