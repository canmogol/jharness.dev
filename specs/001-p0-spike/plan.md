# Plan 001 — P0 Spike

Spec: `spec.md` · Status: Draft · Created: 2026-11-16

## Approach

One small Maven project under `spike/` with a single native image and four probes run as subcommands. Each probe prints machine-readable results (JSON lines) so CI can collect them into `docs/p0-results.md`.

```
spike/
  pom.xml                       Java 25, polyglot + js-isolate, modeljars, jline, native profile
  src/main/java/dev/jharness/spike/
    Main.java                   picocli-free dispatcher: isolate | limits | model | jline | all
    SpikeApi.java               @HostAccess.Export echo(String), sleep(long)
    SandboxConfig.java          built-in defaults + config file + --set overrides (AC-13)
    IsolateProbe.java           AC-02, AC-04, AC-05
    LimitsProbe.java            AC-03
    ModelProbe.java             AC-06, AC-07, AC-08
    JLineProbe.java             AC-10
  src/main/resources/
    sandbox-defaults.json       built-in default values (below)
    META-INF/native-image/dev.jharness/spike/
      reachability-metadata.json  SpikeApi methods
docs/p0-results.md              results template (below)
```

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | One native executable; no helper processes | Yes |
| II Host owns side effects | `SpikeApi` is the only exported surface | Yes |
| III Untrusted | Contexts use `SandboxPolicy.UNTRUSTED` with all required limits. Limit values are configurable; the policy, isolate mode and thread count are not, and no limit can be removed | Yes |
| IV No shell | No processes started | Yes |
| V Proxy | Not in scope; the spike only downloads model weights from the host. Noted as an exception for the spike only | Exception (spike) |
| VI Strings | `echo` takes and returns `String` | Yes |
| VII Safe by default | Not applicable | — |
| VIII Events | Not applicable to the spike | — |
| IX Native first | This spike is the Native Image check for polyglot, ModelJars and JLine | Yes |
| X Tested | Limits are tested by `LimitsProbe` | Yes |
| XI Spec first | Spike code; thrown away or rewritten under later specs | Yes |

## Design

### Isolate context (IsolateProbe, LimitsProbe)

```java
SandboxConfig cfg = SandboxConfig.load(configPath, overrides)   // defaults, then file, then --set
                                 .profile("main");               // or "subagent"

Engine engine = Engine.newBuilder("js")
    .option("engine.SpawnIsolate", "true")          // or .spawnIsolate(true) where available
    .useSystemProperties(false)                     // options come only from SandboxConfig
    // engine.IsolateMode is experimental in polyglot 25.0; its default "internal" is checked, not set
    .options(cfg.engineOptions())                   // engine.MaxIsolateMemory, engine.IsolateMemoryProtection
    .build();

Context ctx = Context.newBuilder("js")
    .engine(engine)
    .sandbox(SandboxPolicy.UNTRUSTED)               // fixed
    .allowHostAccess(HostAccess.UNTRUSTED)          // fixed
    .in(InputStream.nullInputStream())
    .out(out).err(err)
    .option("sandbox.MaxThreads", "1")              // fixed
    .options(cfg.contextOptions())                  // every other sandbox.* limit
    .build();
ctx.getBindings("js").putMember("api", new SpikeApi());
// cfg.wallClock() drives the watchdog
```

### Configurable options (SandboxConfig)

Every engine and sandbox limit has a built-in default and can be changed without rebuilding. Values are resolved in this order, later wins:

1. Built-in defaults (`sandbox-defaults.json`, inside the binary)
2. Config file: `--config <file>` in the spike; `~/.jharness/settings.json` and, for trusted projects, `.jharness/settings.json` in the product (D-011)
3. Command-line overrides: `--set main.sandbox.MaxCPUTime=5s`

Defaults:

| Option | `main` | `subagent` |
| --- | --- | --- |
| `engine.MaxIsolateMemory` | 512MB | 256MB |
| `engine.MemoryProtection` | auto (on where supported, Linux) | auto |
| `sandbox.MaxHeapMemory` | 256MB | 128MB |
| `sandbox.MaxCPUTime` | 30s | 30s |
| `sandbox.MaxASTDepth` | 100 | 100 |
| `sandbox.MaxOutputStreamSize` | 256KB | 256KB |
| `sandbox.MaxErrorStreamSize` | 256KB | 256KB |
| `wallClock` (watchdog; excludes approval and subagent waits) | 15m | 15m |

Config shape (same in the spike file and in `settings.json`):

```json
{
  "sandbox": {
    "main":     { "sandbox.MaxCPUTime": "20s", "sandbox.MaxOutputStreamSize": "512KB" },
    "subagent": { "engine.MaxIsolateMemory": "384MB" }
  }
}
```

Rules:
- Only the keys in the table are accepted; an unknown key or a bad value (wrong unit, zero, negative) stops start-up with a clear error.
- Not configurable: `SandboxPolicy.UNTRUSTED`, `HostAccess.UNTRUSTED`, `engine.IsolateMode=internal`, `sandbox.MaxThreads=1`. A limit can be changed but never removed.
- `sandbox.MaxHeapMemory` must stay below `engine.MaxIsolateMemory`.
- The effective values are printed with every probe result (and, in the product, emitted in `session.started`).

### Probe notes

The exact option set UNTRUSTED requires is checked against the GraalVM version used; the builder refuses a context with a missing limit, which the probe records.

- Memory protection: build a second engine with `engine.MemoryProtection=true`; record success, error or no effect. Polyglot 25.0 names the option `engine.IsolateMemoryProtection` and marks it experimental; the config key keeps the plan's name, and only the probe engine allows experimental options.
- Wall clock: a `ScheduledExecutorService` calls `ctx.close(true)` when the wall clock runs out while JS waits in `api.sleep(...)`. The probe sets a short value with `--set main.wallClock=5s` instead of waiting 15 minutes.
- CPU time while blocked (AC-14): JS calls `api.sleep(20000)` with `sandbox.MaxCPUTime=5s`; the run must not be stopped by the CPU limit, showing that blocked time in a host callback is not counted. Then a host callback that does 10 s of busy work must trip the 5 s limit, showing that host CPU work is counted.
- After each limit trips: close the context, build a new one on the same engine, run `api.echo` again.

### Measurements (IsolateProbe)

| Number | Method |
| --- | --- |
| Binary size | `ls -l` of the built executable |
| Cold start-up | `hyperfine --warmup 0 --runs 20 './spike isolate --noop'` |
| Isolate creation | `System.nanoTime()` around engine + first context build, 20 runs, median |
| Memory per idle isolate | RSS before and after creating 1, 5 and 10 engines (`/proc/self/status` on Linux, `proc_pid_rusage` through FFM on macOS, so no child process is started) |

### Local model (ModelProbe)

- Dependencies: `org.modeljars:modeljars` (JVM Runtime) and `com.integrallis:backend-java`; the smallest qualified Qwen GGUF marker for the first run.
- Native build flag: `--add-modules=jdk.incubator.vector`.
- Steps: open with `ModelJars.openRuntime(...)`; render a chat template; generate 128 tokens at temperature 0; time prompt processing on a fixed 8k-token prompt and generation separately.
- AC-08: build a second variant without the marker JAR and try opening by coordinates string, then from the local cache path.
- Run each on the JVM too, for comparison.
- AC-09: `modeljars search qwen2.5-coder` and `modeljars search deepseek-coder` with the ModelJars CLI.

### JLine (JLineProbe)

Read three lines in raw mode with editing and history; exit on Ctrl-D. Check by hand on macOS and Linux terminals.

## Go/no-go thresholds (confirmed 2026-11-17)

| Item | Go if |
| --- | --- |
| Polyglot isolates in native | AC-02 and AC-03 pass on all three targets |
| Cold start-up | Under 2 s to first output |
| Isolate creation | Under 300 ms median |
| ModelJars in native | Runs, and native speed is at least 70% of JVM speed |
| Local 7B model usable | Soft target, see below |
| JLine | AC-10 passes on macOS and Linux |

**Local 7B model (soft target).** Measured on a 16 GB Apple Silicon Mac with Qwen2.5-Coder-7B at Q4_K_M:

| Result | 8k-token prompt | Generation | Outcome |
| --- | --- | --- | --- |
| Good | under 120 s | at least 3 tokens/s | Go: 7B is the default local model |
| Slow | under 240 s | at least 1.5 tokens/s | Go, with the 7B model marked "slow on this hardware" in its model profile and a smaller model suggested |
| Too slow | slower than that | | Not a no-go for the project: recommend running 7B through the OpenAI-compatible client (Ollama, llama.cpp) and keep ModelJars for smaller models |

None of these results stops P1; they only decide which local setup JHarness recommends by default.

A no-go on ModelJars keeps the plan intact: local models then run through the OpenAI-compatible client (Ollama, llama.cpp), recorded as a change to D-013.

## Results template (`docs/p0-results.md`)

| Number | linux-amd64 | linux-aarch64 | macos-aarch64 |
| --- | --- | --- | --- |
| Binary size (MB) | | | |
| Cold start-up (ms, median) | | | |
| Isolate creation (ms, median) | | | |
| RSS per idle isolate (MB) | | | |
| Limits AC-03 (pass/fail) | | | |
| Config overrides AC-13 (pass/fail) | | | |
| Blocked time not counted as CPU, AC-14 (pass/fail) | | | |
| Memory protection | | | n/a |
| Model prompt speed, native / JVM (tok/s) | | | |
| Model generation speed, native / JVM (tok/s) | | | |
| Open without marker JAR | | | |
| JLine AC-10 | | | |

## CI

GitHub Actions matrix (ubuntu x64, ubuntu arm64, macOS arm64) with Oracle GraalVM set up; the job builds the native image, runs `spike all --json`, and uploads results as an artifact. Model probes run only on manual dispatch (large downloads).

## Risks

| Risk | Response |
| --- | --- |
| UNTRUSTED requires options this plan misses | Probe records the builder's error; add the option |
| `js-isolate` artifact missing for a target | Record it; affects platform list in D-016 |
| ModelJars needs the Rust/FFM backend for usable speed | Try `backend-native` as a second run; record shipping cost |
| CI runners lack protection keys | Test memory protection on a known machine by hand |
