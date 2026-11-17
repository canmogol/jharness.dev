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
    IsolateProbe.java           AC-02, AC-04, AC-05
    LimitsProbe.java            AC-03
    ModelProbe.java             AC-06, AC-07, AC-08
    JLineProbe.java             AC-10
  src/main/resources/META-INF/native-image/dev.jharness/spike/
    reachability-metadata.json  SpikeApi methods
docs/p0-results.md              results template (below)
```

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | One native executable; no helper processes | Yes |
| II Host owns side effects | `SpikeApi` is the only exported surface | Yes |
| III Untrusted | Contexts use `SandboxPolicy.UNTRUSTED` with all required limits | Yes |
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
Engine engine = Engine.newBuilder("js")
    .option("engine.SpawnIsolate", "true")          // or .spawnIsolate(true) where available
    .option("engine.IsolateMode", "internal")
    .option("engine.MaxIsolateMemory", "512MB")
    .build();

Context ctx = Context.newBuilder("js")
    .engine(engine)
    .sandbox(SandboxPolicy.UNTRUSTED)
    .allowHostAccess(HostAccess.UNTRUSTED)
    .in(InputStream.nullInputStream())
    .out(out).err(err)
    .option("sandbox.MaxHeapMemory", "256MB")
    .option("sandbox.MaxCPUTime", "10s")
    .option("sandbox.MaxThreads", "1")
    .option("sandbox.MaxASTDepth", "100")
    .option("sandbox.MaxOutputStreamSize", "64KB")
    .option("sandbox.MaxErrorStreamSize", "64KB")
    .build();
ctx.getBindings("js").putMember("api", new SpikeApi());
```

The exact option set UNTRUSTED requires is checked against the GraalVM version used; the builder refuses a context with a missing limit, which the probe records.

- Memory protection: build a second engine with `engine.MemoryProtection=true`; record success, error or no effect.
- Wall clock: a `ScheduledExecutorService` calls `ctx.close(true)` after N seconds while JS waits in `api.sleep(...)`.
- After each limit trips: close the context, build a new one on the same engine, run `api.echo` again.

### Measurements (IsolateProbe)

| Number | Method |
| --- | --- |
| Binary size | `ls -l` of the built executable |
| Cold start-up | `hyperfine --warmup 0 --runs 20 './spike isolate --noop'` |
| Isolate creation | `System.nanoTime()` around engine + first context build, 20 runs, median |
| Memory per idle isolate | RSS before and after creating 1, 5 and 10 engines (`/proc/self/status` on Linux, `ps -o rss` on macOS) |

### Local model (ModelProbe)

- Dependencies: `org.modeljars:modeljars` (JVM Runtime) and `com.integrallis:backend-java`; the smallest qualified Qwen GGUF marker for the first run.
- Native build flag: `--add-modules=jdk.incubator.vector`.
- Steps: open with `ModelJars.openRuntime(...)`; render a chat template; generate 128 tokens at temperature 0; time prompt processing on a fixed 8k-token prompt and generation separately.
- AC-08: build a second variant without the marker JAR and try opening by coordinates string, then from the local cache path.
- Run each on the JVM too, for comparison.
- AC-09: `modeljars search qwen2.5-coder` and `modeljars search deepseek-coder` with the ModelJars CLI.

### JLine (JLineProbe)

Read three lines in raw mode with editing and history; exit on Ctrl-D. Check by hand on macOS and Linux terminals.

## Proposed go/no-go thresholds (confirm before running)

| Item | Go if |
| --- | --- |
| Polyglot isolates in native | AC-02 and AC-03 pass on all three targets |
| Cold start-up | Under 1 s to first output (mission target) |
| Isolate creation | Under 300 ms median |
| ModelJars in native | Runs, and native speed is at least 70% of JVM speed |
| Local 7B model usable | On a 16 GB Apple Silicon Mac: 8k-token prompt processed in under 60 s, generation at least 5 tokens/s |
| JLine | AC-10 passes on macOS and Linux |

A no-go on ModelJars keeps the plan intact: local models then run through the OpenAI-compatible client (Ollama, llama.cpp), recorded as a change to D-013.

## Results template (`docs/p0-results.md`)

| Number | linux-amd64 | linux-aarch64 | macos-aarch64 |
| --- | --- | --- | --- |
| Binary size (MB) | | | |
| Cold start-up (ms, median) | | | |
| Isolate creation (ms, median) | | | |
| RSS per idle isolate (MB) | | | |
| Limits AC-03 (pass/fail) | | | |
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
