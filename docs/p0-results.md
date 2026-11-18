# P0 spike results

Spec: `../specs/001-p0-spike/` · Measured: 2026-11-17 and 2026-11-18 · GraalVM: Oracle GraalVM 25.0.4 (JDK 25.0.4), polyglot 25.0.5

Sources: CI run 44189315472 (native matrix, all three targets), CI run 44218796821 (model job, linux-amd64), and local runs on an Apple Silicon Mac (10 cores) for the model numbers on macos-aarch64. CI runners: `ubuntu-24.04`, `ubuntu-24.04-arm`, `macos-26`.

## Summary

| Item | Go if (D-030) | Result | Call |
| --- | --- | --- | --- |
| Polyglot isolates in native | AC-02 and AC-03 pass on all three targets | Pass on all three | Go |
| Cold start-up | Under 2 s to first output | 5–95 ms in CI; 1.67 s once on a Mac with an empty file cache | Go |
| Isolate creation | Under 300 ms median | 3.7–4.7 ms median; 10–20 ms for the first isolate in a process | Go |
| ModelJars in native | Runs, at no less than 70% of JVM speed | Runs, at 0.1–1.2% of JVM speed | **No-go** |
| Local 7B model | Soft target | Not measurable: no 7B coder model in the ModelJars catalog | Use Ollama or llama.cpp |
| JLine | AC-10 on macOS and Linux | Pass on all three | Go |

## Results by target

| Number | linux-amd64 | linux-aarch64 | macos-aarch64 |
| --- | --- | --- | --- |
| Binary size, isolates + JLine (MB) | 210.7 | 210.6 | 204.0 |
| Binary size with ModelJars pure-Java (MB) | | | 244.4 |
| Cold start-up, `isolate --noop` (ms, median / first) | 4.9 / 5.0 | 5.0 / 5.2 | 11.6 / 94.7 |
| Start-up plus one isolate run, `isolate --echo` (ms, median / first) | 17.2 / 125.4 | 17.4 / 65.9 | 45.0 / 414.0 |
| Isolate creation (ms, median of 20) | 3.8 | 4.7 | 3.7 |
| First isolate in the process (ms) | 10.0 | 10.2 | 19.9 |
| RSS per idle isolate (MB, 1, 5 and 10 engines) | 24.8 | 23.5 | 11.8 |
| Limits AC-03 | Pass | Pass | Pass |
| Config overrides AC-13 | Pass | Pass | Pass |
| Blocked time not counted as CPU, AC-14 | Pass | Pass | Pass |
| Memory protection AC-05 | Fails isolate creation (no `pku`) | Not available | n/a |
| Model prompt speed, native / JVM (tok/s, 219-token prompt) | 0.04 / 32.7 | not measured | 0.17 / 14.3 |
| Model generation speed, native / JVM (tok/s) | 0.02 / 16.1 | not measured | 0.11 / 30.7 |
| Model 8k-token prompt, JVM (prefill s / decode tok/s) | 3,151 / 2.9 | not measured | 1,394 / 4.7 |
| Open without marker JAR AC-08 | | | By coordinates: no. From cache path: yes |
| JLine AC-10 | Pass | Pass | Pass |

On a local Mac with a cold file cache the first `isolate --noop` took 1.67 s and the first isolate 1.1 s, from paging in the 200 MB binary. Both drop to the CI numbers once the file is cached.

## Isolates (AC-02, AC-04, AC-05)

- JS in a `SandboxPolicy.UNTRUSTED` context calls `api.echo("hi")` and gets `"hi"` on every target.
- `engine.IsolateMode` is an experimental option in polyglot 25.0; its default is `internal`. The spike relies on the default and checks it (D-034).
- `engine.MemoryProtection` is named `engine.IsolateMemoryProtection` in 25.0 and is also experimental. None of the CI machines has protection keys (`pku`, `ospke` absent). Forcing it on:

| Target | Result |
| --- | --- |
| linux-amd64 | Isolate creation fails: "Setting the protection of the heap memory failed" (error 9) |
| linux-aarch64 | "Memory Protection not available" |
| macos-aarch64 | "Memory Protection not available" |

No machine with protection keys was available, so the "works" case is untested.

## Limits (AC-03, AC-13, AC-14)

Same result on all three targets. Each limit surfaces as a `PolyglotException`, and a fresh context on the same engine then runs `api.echo`.

| Check | Setting | Result |
| --- | --- | --- |
| `while(true){}` | `sandbox.MaxCPUTime=2s` | Resource exhausted after ~2.0 s |
| Growing allocation | defaults (heap 256 MB, isolate 512 MB) | Stopped by `sandbox.MaxHeapMemory` at ~256 MB |
| Growing allocation | heap 500 MB, isolate 512 MB | Still stopped by `sandbox.MaxHeapMemory` |
| Growing allocation | heap 1 GB, isolate 256 MB (probe only; config rejects it) | Stopped by `engine.MaxIsolateMemory` ("Garbage-collected heap size exceeded"); the engine survives |
| `api.sleep(60000)` | `wallClock=3s` | Cancelled by the watchdog after 3.0 s |
| `api.sleep(5000)` | `MaxCPUTime=2s` | Completes: blocked host time is not CPU time |
| `api.busy(5000)` | `MaxCPUTime=2s` | Resource exhausted when the call returns (5.0 s): host CPU work counts |
| Loop at 1 s and 3 s CPU limits | `--set` | Stops at 1.0 s and 3.0 s |
| 384 KB of output | 256 KB default, then 512 KB | Stopped at 256 KB; completes at 512 KB |
| Bad key, zero value, fixed setting | `--set` | All rejected at start-up |

Findings:
- With valid config the heap limit always trips first; the isolate limit is a backstop that also works.
- Once in CI (run 44218796821, `macos-26`, 3 vCPU) the heap-500 MB / isolate-512 MB allocation hung for 28 minutes: the 60 s watchdog's `Context.close(true)` did not end it, and the job timed out. It did not happen in 14 other runs (8 local in a row, 6 in CI). The probe's watchdog now escalates: context close at the wall clock, engine close 10 s later, then a recorded failure and process stop 30 s after that (D-037).
- A CPU limit can only stop guest code when a host call returns, so a long host call overruns it. Host operations need their own timeouts (D-028 already plans them).
- AC-14 passes, so the CPU and wall-clock design in D-003 and D-028 stands.

## Local model (AC-06 to AC-09)

Model: Qwen3-0.6B Q4_0 (`org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1`, 409 MiB), the smallest Qwen GGUF qualified on the pure-Java backend. ModelJars 0.1.80, Integrallis Models 0.5.22. Greedy decoding.

### Catalog (AC-09)

| Model | In the catalog? |
| --- | --- |
| Qwen2.5-Coder-7B | No |
| DeepSeek-Coder-V2-Lite | No |
| Closest coder models | Qwen2.5-Coder 0.5B and 1.5B, deepseek-coder-1.3b-instruct, yi-coder-1.5b |

Almost every text model is qualified on the Rust FFM backend only; ModelJars refuses to open a model on a backend it is not qualified on.

### Speed, macos-aarch64 (AC-06, AC-07)

| Run | Prompt | Prefill (tok/s) | Decode (tok/s) |
| --- | --- | --- | --- |
| JVM, pure Java | 28 tokens, 128 generated | 12.0 | 48.5 |
| JVM, pure Java | 219 tokens, 59 generated | 14.3 | 30.7 |
| JVM, pure Java | 8,283 tokens, 64 generated | 5.9 (1,394 s) | 4.7 |
| Native, pure Java | 28 tokens, 8 generated | 0.18 | 0.12 |
| Native, pure Java | 219 tokens, 59 generated | 0.17 | 0.11 |
| JVM, Rust FFM (`backend-native`) | 219 tokens, 59 generated | 137.9 | 43.3 |
| Native, Rust FFM | — | does not build | — |

- Native pure-Java runs at 1.2% (prefill) and 0.4% (decode) of the JVM, with `-H:+VectorAPISupport`. An 8k prompt would take about 13.5 hours, so it was not run natively.
- Even on the JVM, an 8k prompt takes 23 minutes on a 0.6B model, mostly in attention (the backend reports `fused-grouped-attention-disabled` for Qwen3).
- `backend-native` (T023) is 10× faster on the JVM but cannot go into this binary: it uses `Arena.ofShared`, and Native Image rejects shared arenas when runtime compilation (Truffle, needed for the JS isolates) is in the image. Shipping cost if it could: one kernel library per platform, 3.3–4.1 MB, extracted to a cache folder at run time.

### Speed, linux-amd64 (AC-07)

CI runner `ubuntu-24.04` (4 vCPU, 16 GB; the JVM ran with `-Xmx8g`, because the 8k KV cache ran out of the default 3.9 GB heap in an earlier run).

| Run | Prompt | Prefill (tok/s) | Decode (tok/s) |
| --- | --- | --- | --- |
| JVM, pure Java | 28 tokens, 128 generated | 8.7 | 16.5 |
| JVM, pure Java | 219 tokens, 60 generated | 32.7 | 16.1 |
| JVM, pure Java | 8,283 tokens, 64 generated | 2.6 (3,151 s) | 2.9 |
| Native, pure Java | 219 tokens, 60 generated | 0.04 | 0.02 |

Native runs at 0.1% of the JVM here; the 219-token prompt took 100 minutes.

### Open without the marker JAR (AC-08)

Same on the JVM and native:

| Attempt | Result |
| --- | --- |
| `ModelJars.openRuntime` by coordinates | Fails: "No qualified ModelJar matched" |
| `PureJavaBackend.loadAutomatic` on `<cache>/sha256/<aa>/<sha256>/model.gguf` | Opens and generates; no qualification check, and the SHA-256 that names the folder comes from the marker |

### Other findings

- ModelJars downloads weights itself with `HttpURLConnection` from Hugging Face. It honours the JDK proxy settings, so it could go through the egress proxy.
- `vectors-core` (a ModelJars dependency) runs `/usr/sbin/sysctl` with `ProcessBuilder` on macOS when it starts. That breaks constitution Article IV.
- The pure-Java backend refuses `-Xint` and C1-only JVMs; on the JVM it needs `--add-modules=jdk.incubator.vector`.

## Terminal (AC-10)

JLine 3.30.17 with the FFM terminal provider (no JNI library, no `stty`). `spike/scripts/jline-pty-check.py` drives the native binary through a pseudo-terminal: type a line, edit it with Left and insert, recall it with Up, end with Ctrl-D. Pass on all three targets in CI and locally on macOS.

## Decisions taken from these results

See `../product/decisions.md`: D-034 (isolate mode), D-035 (ModelJars no-go for v0.1, changing D-013), D-036 (memory protection off in v0.1, changing D-018), D-037 (watchdog escalation).
