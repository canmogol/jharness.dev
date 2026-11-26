# Plan 059 — Go tool

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **Definitions.** `examples/.jharness/tools/go.json` and `gofmt.json`, which the build packs as the shipped defaults. `ToolDefinitions.DEFAULTS` adds `go` and `gofmt`. A first start seeds them; existing users get them with `jharness tools reset go` and `jharness tools reset gofmt`.
- **Proxy.** `"proxy": "env"`, the existing method. No new code per tool.
- **Home paths in `env`.**
  - `ToolDefinition` refuses `~/` values that leave the home.
  - `BuildOperations.env` expands the definition's `~/` values, normal and offline, to the home.
- **Patterns.**
  - `packages`: `.`, `./...`, or slash-separated segments that each start with a letter, digit or `_`, optionally `./` first and `/...` last.
  - `run`: starts with a letter, digit, `_`, `^`, `(` or `[`.
- **Hosts.** `examples/network.rules`; README.
- **CI.** `actions/setup-go` pinned to 1.27.4; the native step runs `go test -run` and `gofmt check` through `api.tool` in a trusted folder.
- **Tests.**
  - `BuildOperationsTest`: argv per operation, refused values, the environment, offline.
  - `ToolDefinitionTest`: `~/` values, the defaults.
  - `BuildToolsTest`, with real Go in the sandbox: build, vet, test all and by name, version, the toolchain line, the refused proxy host with no git, an offline vendor build, `gofmt`.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | Data files and a small general change; Go is the user's own install | Yes |
| II Host owns side effects | Runs only through `api.tool`, asked as a build | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | Typed argv from checked fields; no `go generate`; git never starts (`GOVCS=*:off`, `-buildvcs=false`, no `direct`) | Yes |
| V Proxy | `HTTPS_PROXY` to the egress proxy; the OS sandbox gives no other way out | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | No shipped allow rules; no toolchain downloads; `GOENV=off`; builds don't run in untrusted projects | Yes |
| VIII Events | The existing `api.call` and process events | Yes |
| IX Native first | The native binary runs go and gofmt on Linux and macOS | Yes |
| X Tested | Unit tests and real Go in the sandbox | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux, with Go 1.27.4 in `~/sdk/go`
- [x] `mvn install` on macOS, with Go 1.27.4
- [x] Native binary: go and gofmt through `api.tool` on Linux and macOS; CI's native step locally on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A field value turns into a go flag (`-exec`, `-toolexec`) | Patterns refuse a leading `-`; argv only |
| Go fetches with git or downloads a toolchain | No `direct`, `GOVCS=*:off`, `GOTOOLCHAIN=local`, `-buildvcs=false` |
| A user's `go env -w` file changes flags | `GOENV=off` |
| macOS caches under `~/Library`, which tools can't write | `GOCACHE` under `~/.cache` |
