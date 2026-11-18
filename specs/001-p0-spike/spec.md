# Spec 001 — P0 Spike

Status: Done · Phase: P0 · Created: 2026-11-16

## Summary

Before building features, prove that the riskiest parts of JHarness work together in one native binary on Linux and macOS: GraalJS polyglot isolates under the UNTRUSTED sandbox policy, a host `api` call from JS, limits that trip cleanly, a ModelJars model running in the same process, and JLine in a native image. Record the numbers and make go/no-go calls.

## Why now

The architecture rests on these assumptions (`product/decisions.md` D-001, D-002, D-003, D-013). If any fails, the plan changes before code is written against it.

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-6.1 | As a contributor, I want to develop on the JVM and build native in CI, so I'm not waiting on native builds. | `user-stories.md` |
| US-1.2 (part) | As a privacy-minded developer, I want a local model inside JHarness with no extra server. | `user-stories.md` |
| S-001 | As a maintainer, I want measured numbers for size, start-up, isolate cost and local model speed, so later choices rest on facts. | This spec |

## Questions this spike answers

| # | Question |
| --- | --- |
| Q1 | Does a native binary with `js-isolate` build and run on linux-amd64, linux-aarch64 and macos-aarch64? |
| Q2 | Can JS in an UNTRUSTED context call an exported host method and get a string back? |
| Q3 | Do CPU, memory and wall-clock limits trip cleanly, and can a fresh context run afterwards? |
| Q4 | How big is the binary, how fast does it start, and what does an isolate cost in time and memory? |
| Q5 | Is `engine.MemoryProtection` available on the Linux test machines? |
| Q6 | Does ModelJars (Integrallis Models, `backend-java`) load and generate text inside the native binary? |
| Q7 | How fast is it on an 8k-token prompt, and can a model be opened without its marker JAR on the classpath? |
| Q8 | Are Qwen2.5-Coder-7B and DeepSeek-Coder-V2-Lite available and qualified in the ModelJars catalog? |
| Q9 | Does JLine 3 work (raw mode, line editing) in the native binary? |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | A native binary builds in CI for linux-amd64, linux-aarch64 and macos-aarch64 from one Maven profile. |
| AC-02 | On each target, JS in a `SandboxPolicy.UNTRUSTED` context calls `api.echo("hi")` and prints the returned string. |
| AC-03 | `while(true){}` is stopped by `sandbox.MaxCPUTime`; a large allocation is stopped by `engine.MaxIsolateMemory`; a blocking host call is stopped by the wall-clock watchdog. Each surfaces as a `PolyglotException` with `isResourceExhausted()` or `isCancelled()`, and a new context then runs successfully. |
| AC-04 | Binary size, cold start-up time, isolate creation time and resident memory per idle isolate are measured on each target and recorded. |
| AC-05 | On Linux, whether `engine.MemoryProtection=true` works is recorded per test machine, along with what happens when it isn't supported (error or ignored). |
| AC-06 | A ModelJars model (smallest qualified Qwen GGUF first, then Qwen2.5-Coder-7B if available) generates text inside the native binary, or the failure is documented with its cause. |
| AC-07 | Tokens per second for prompt processing (8k tokens) and for generation are recorded for the native binary and for the JVM, on macos-aarch64 and linux-amd64. |
| AC-08 | Whether a model can be opened by coordinates or from the cache without its marker JAR on the classpath is recorded. |
| AC-09 | Catalog availability of the two default models is recorded, with exact coordinates if present. |
| AC-10 | JLine reads a line with editing and history in raw mode inside the native binary on macOS and Linux. |
| AC-11 | `docs/p0-results.md` holds every number and finding, and `product/decisions.md` has a go/no-go entry for ModelJars-in-native-image and any decision that changed. |
| AC-12 | Repository basics exist: MIT license (done), `SECURITY.md`, `CONTRIBUTING.md`, CI running the spike build. |
| AC-13 | Every engine and sandbox limit is read from built-in defaults, a config file and `--set` overrides (later wins). A changed value changes behavior (a lower `sandbox.MaxCPUTime` stops a loop sooner; a larger output cap lets more output through). Unknown keys, bad values and attempts to change fixed settings are rejected. Output and error stream caps default to 256KB. |
| AC-14 | Time spent blocked inside a host callback (waiting, not computing) does not count toward `sandbox.MaxCPUTime`, and CPU work done inside a host callback does. If blocked time does count, record it: the CPU and wall-clock design (D-003, D-028) must change. |

## Out of scope

Agent loop, permissions, file `api`, egress proxy, build tools, TUI design, model providers other than ModelJars. Spike code is not production code; it is thrown away or rewritten under later specs (constitution Article XI).

## Dependencies and assumptions

- Oracle GraalVM 25.x for JDK 25, with `js-isolate` artifacts for each target.
- Test machines: an Apple Silicon Mac with at least 16 GB RAM; Linux amd64 and aarch64 machines or CI runners, at least one Linux CPU with protection keys.
- Network access to Maven Central and the model download hosts.

## Open questions

None. Go/no-go thresholds were confirmed on 2026-11-17 (see `plan.md`).
