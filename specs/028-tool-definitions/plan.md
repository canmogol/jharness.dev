# Plan 028 — Tool definitions

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Process module:
  - `ToolDefinition` parses and checks one file.
  - `ToolDefinitions` seeds, loads, merges the user's and the project's folders, hashes the project files and describes them.
  - `BuildOperations` runs any definition: request checks, argv expansion, environment per proxy method, wrapper and checksum, writable folders, grants, approval summary and detail, and `list()` for `api.tools()`.
  - The `Tool` enum is gone.
  - `Executables` refuses shells and paths instead of allowing a fixed list.
  - `ProcessRunner.checkEnv` also allows the variables a definition may set.
  - The pom embeds `examples/.jharness/tools/*.json`, and the native metadata lists them.
- Sandbox: `HostApi.tool(name, json)` and `tools()`, which replace the three methods. The shim, `api.d.ts` and the reachability metadata are updated; `ApiContractTest` counts 17 methods.
- Agent:
  - `Session` seeds and loads the tools (`Access.projectTools`), publishes `tools.loaded` and adds `SystemPrompt.tools` to the main and subagent prompts.
  - `AgentDefinition.API` and `ToolsGate` understand `tool`, `tools` and `tool:NAME`.
- Security: `TrustStore` keeps each project tool file's hash with the trust entry.
- CLI:
  - The trust question lists the project's tools, and a new question asks about changed tools.
  - The banner line names the project's tools.
  - Headless runs leave unconfirmed project tools out.
  - `jharness tools` is a new command.
  - picocli accepts subcommand names as `-p` values.
- Testkit: the security suite seeds the home before its snapshot and calls `api.tool`; REPO-11 is new. Smoke uses the definitions.
- CI: a native step for `jharness tools` and `api.tools()`.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process kinds; definitions are data in the binary and the home | Yes |
| II Host owns side effects | Tools run only through `api.tool`, the gate and `ProcessRunner` | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | Typed fields into fixed argv slots; shells and command runners refused; wrappers only from the user's files (amended Article IV) | Yes |
| V Proxy | Every tool gets the proxy through a built-in method or no network; loopback only from the user's files (amended Article V) | Yes |
| VI Strings | `tool` and `tools` take and return strings | Yes |
| VII Safe by default | Tools need trust and approval; project tools are shown when trusting and asked about when they change; "always" keys on the executable | Yes |
| VIII Events | `tools.loaded`, `tools.project_ignored`; process events unchanged | Yes |
| IX Native first | Resources and host methods in the metadata; CI runs them natively | Yes |
| X Tested | Format checks, project limits, seeding, merging, hashes, trust flow, headless, `jharness tools`, `api.tool`/`tools`, agent narrowing, REPO-11, the old build tests against the shipped files | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness` checks and the TUI pseudo-terminal check on Linux
- [x] Native smoke (security cases) on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A user's copied files fall behind a new release | `jharness tools` marks files that differ; `reset` writes the new default |
| A trusted repo adds a tool that runs code | Same as its build scripts: trust, approval, sandbox; shown when trusting and when changed |
| Saved "always" rules for builds stop matching (they now include the executable) | One new question per operation; documented |
