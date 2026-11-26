# Spec 059 — Go tool

Status: Done · Phase: P3 · Created: 2026-11-26

## Summary

Two shipped tool definitions add Go, like the Maven, Gradle and npm tools. `go.json` covers `build`, `test`, `vet`, `mod-tidy`, `mod-download` and `version`; `gofmt.json` covers `check` and `check-folder`, which change nothing. Go reaches the network only through the egress proxy, and only `proxy.golang.org`. It never starts git, never downloads another toolchain and ignores `go env -w` settings. The one code change is general: an `env` value that starts with `~/` is a folder in the home. Decision D-092.

## Roadmap items

- Go tool (user request, 2026-11-26)

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Add a Go tool like the Maven and npm tools, with downloads through the egress proxy | User request, 2026-11-26 |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `go.json` operations: `build`, `test` and `vet` over one package pattern (`packages`, default `./...`); `test` with an optional `run` (`-run`); `mod-tidy`; `mod-download`; `version`. `gofmt.json`: `check` (`gofmt -l .`) and `check-folder` (one folder in the workspace). Field values can't start with `-` or leave the module, so `-exec`, `-toolexec`, `-ldflags` and `../x` are refused. There is no `go generate`, `go run`, `go install` or `go get`. |
| AC-02 | Go gets the proxy through the existing `env` method (`HTTPS_PROXY` and friends, with the session's credentials). The cleaned environment means no `GOFLAGS` or `GOPROXY` of the user's reaches it. `proxy.golang.org` is refused when the rules don't allow it. |
| AC-03 | JHarness ships no allow rules for Go (nor for Maven or npm). `examples/network.rules` lists `proxy.golang.org`, `sum.golang.org` and `golang.org`, with the Maven and npm hosts and a private module proxy example; the README says how to use it. |
| AC-04 | No version control or downloaded programs: `GOPROXY=https://proxy.golang.org` (no `direct`), `GOSUMDB=sum.golang.org`, `GOVCS=*:off`, `GOFLAGS=-buildvcs=false` (go build doesn't run git), `GOTOOLCHAIN=local` (a go.mod toolchain line fails instead of downloading Go), `GOENV=off`. |
| AC-05 | Caches: `GOPATH=~/go`, `GOMODCACHE=~/go/pkg/mod`, `GOCACHE=~/.cache/go-build` (also on macOS, whose default is under `~/Library`, which tools can't write); `~/go/pkg` and `~/.cache/go-build` are writable in the OS sandbox. An `env` value that starts with `~/` is expanded to the home and must stay below it. Offline, `GOPROXY=off`: builds work from the module cache or `vendor/`. |
| AC-06 | The native binary runs `go test` and `gofmt check` on Linux and macOS (CI's native step and by hand); `actions/setup-go` gives CI a real Go. |
| AC-07 | Tests for the operations, the refused values, the environment, a refused host, the toolchain line, an offline vendor build, `gofmt`, and home paths in `env`. README, D-092, Q101, roadmap. |

## Out of scope

- `go generate`, `go run`, `go install`, `go get` and `go work`.
- Several package patterns in one call (one pattern such as `./...` covers a tree).
- Private modules fetched with git (only through a module proxy).

## Dependencies and assumptions

- Specs 013, 015, 023 and 028 (build tools, process rules, tool parameters, tool definitions); D-066.
- Go 1.21 or later for `GOTOOLCHAIN`; checked with Go 1.27.4.

## Open questions

None. Design confirmed by the user on 2026-11-26: a separate `gofmt` tool, hosts in an example file, Go installed under `~/sdk` and in CI, one package pattern.
