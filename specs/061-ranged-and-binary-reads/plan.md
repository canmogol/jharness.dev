# Plan 061 — Ranged and binary reads

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **`Reads`** (sandbox):
  - `checkTextOptions` and `checkByteOptions` check the option keys and numbers before the gate.
  - `text()` streams lines through a strict UTF-8 reader and stops at whole lines within 1 MB. For byte ranges, it decodes with leading continuation bytes skipped.
  - `bytes()` reads a range with a `FileChannel` and returns base64, hex or strict charset text.
  - With `endOfInput=false`, a decoder leaves a cut character at the end unconsumed, so `length` and `nextOffset` count only the decoded bytes.
- **`HostApi`.** `readFile(path, json)` (two strings across the boundary) and the new `readBytes(path, json)`, both `READ`; the summaries name the range.
- **Wiring.**
  - The shim passes options as JSON.
  - `api.d.ts`, the reachability metadata (two parameters, the new method), `AgentDefinition.API` and the system prompt.
  - `-H:+AddAllCharsets` for the native build.
- **Tests.**
  - `ReadsApiTest`: a 12 MB file by lines, cuts, binary, hex, charsets, cut characters, rules, bad options.
  - `ApiContractTest` (22 methods).
  - CI's native step.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | All charsets in the binary (about 6 MB more) | Yes |
| II Host owns side effects | Reads only, on the host | Yes |
| III Untrusted | Strings in, JSON out; binary as base64 or hex | Yes |
| IV No shell | No process | Yes |
| V Proxy | No network | Yes |
| VI Strings | Only strings and JSON cross the boundary | Yes |
| VII Safe by default | 1 MB per call; the read rules and deny list apply | Yes |
| VIII Events | The existing `api.call` events | Yes |
| IX Native first | Native checks on Linux and macOS; CI decodes windows-1252 | Yes |
| X Tested | Every option and refusal | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build, a native reads check and CI's native step on Linux; native build and reads check on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A chunk splits a character | Text ranges are cut to whole characters; `nextOffset` says where to go on |
| A charset missing in the native binary | `-H:+AddAllCharsets`; CI checks windows-1252 |
| A model asks for huge ranges | 1 MB per call, 100,000 lines |
