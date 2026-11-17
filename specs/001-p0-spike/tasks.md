# Tasks 001 — P0 Spike

Spec: `spec.md` · Plan: `plan.md` · Created: 2026-11-16

Format: `- [ ] T### [P] description (AC-xx)`. `[P]` = can run in parallel with other `[P]` tasks in the same group.

## Group 0 — Before starting

- [x] T001 Decide the source license and record it in `product/decisions.md` D-024: MIT (AC-12)
- [x] T002 Confirm the go/no-go thresholds in `plan.md` (AC-11)

## Group 1 — Repository and build

- [x] T003 Add `SECURITY.md` (disclosure process) and `CONTRIBUTING.md`; the repository and LICENSE already exist (AC-12)
- [x] T004 Create `spike/pom.xml`: Java 25, `polyglot` and `js-isolate` with platform classifiers, `native-maven-plugin` profile (AC-01)
- [x] T005 Add `Main.java` dispatcher with subcommands `isolate | limits | model | jline | all` and JSON-lines output (AC-01)
- [x] T006 Set up the GitHub Actions matrix (ubuntu x64, ubuntu arm64, macOS arm64) building the native image and uploading results (AC-01, AC-12)

## Group 2 — Isolates

- [x] T007 Write `SpikeApi` with `@HostAccess.Export echo(String)` and `sleep(long)`, plus reachability metadata (AC-02)
- [x] T028 Write `SandboxConfig`: built-in defaults in `sandbox-defaults.json` (output and error caps 256KB), `--config` file, `--set` overrides, validation of keys, units and fixed settings (AC-13)
- [x] T008 Write `IsolateProbe`: UNTRUSTED context built from `SandboxConfig`, call `api.echo` from JS, print the result and the effective limits (AC-02, AC-13)
- [x] T009 [P] Add isolate creation timing, 20 runs, median (AC-04)
- [ ] T010 [P] Add RSS measurement for 1, 5 and 10 idle engines on Linux and macOS (AC-04)
- [ ] T011 [P] Measure binary size and cold start-up with `hyperfine` (AC-04)
- [ ] T012 [P] Try `engine.MemoryProtection=true` on Linux; record success, error or no effect per machine (AC-05)

## Group 3 — Limits

- [ ] T013 Write `LimitsProbe`: CPU limit with `while(true){}` (AC-03)
- [ ] T014 [P] Memory limit with a large allocation (AC-03)
- [ ] T015 [P] Wall-clock watchdog closing a context blocked in `api.sleep`, using a short `--set main.wallClock` value (AC-03)
- [ ] T030 [P] Check that blocked time in a host callback does not count as CPU time and host CPU work does (AC-14)
- [ ] T016 After each limit trips, build a fresh context on the same engine and run `api.echo` again (AC-03)
- [ ] T029 Show that overrides take effect: lower `sandbox.MaxCPUTime` stops a loop sooner; a larger output cap lets more output through; a bad key fails start-up (AC-13)

## Group 4 — Local model

- [ ] T017 [P] Search the ModelJars catalog for Qwen2.5-Coder-7B and DeepSeek-Coder-V2-Lite; record coordinates (AC-09)
- [ ] T018 Add ModelJars Runtime and `backend-java`; load the smallest qualified Qwen GGUF on the JVM (AC-06)
- [ ] T019 Build the same in the native image with `--add-modules=jdk.incubator.vector`; generate 128 tokens (AC-06)
- [ ] T020 Time an 8k-token prompt and generation, native and JVM, on macos-aarch64 and linux-amd64 (AC-07)
- [ ] T021 Repeat T019–T020 with Qwen2.5-Coder-7B if it is in the catalog (AC-06, AC-07)
- [ ] T022 Try opening a model without its marker JAR: by coordinates, then from the cache path (AC-08)
- [ ] T023 [P] If native speed misses the threshold, try `backend-native` once and record the extra shipping cost (AC-07)

## Group 5 — Terminal

- [ ] T024 [P] Write `JLineProbe`; check raw mode, editing and history by hand on macOS and Linux (AC-10)

## Group 6 — Results and decisions

- [ ] T025 Fill `docs/p0-results.md` from the CI artifacts and manual runs (AC-04, AC-07, AC-11)
- [ ] T026 Add the ModelJars-in-native-image go/no-go to `product/decisions.md`, and update D-013, D-016 or the limits in D-003 if the numbers change them (AC-11)
- [ ] T027 Update `product/roadmap.md` P0 checkboxes and close the P0 gate (AC-11)

## Traceability

| AC | Tasks |
| --- | --- |
| AC-01 | T004, T005, T006 |
| AC-02 | T007, T008 |
| AC-03 | T013–T016 |
| AC-04 | T009, T010, T011, T025 |
| AC-05 | T012 |
| AC-06 | T018, T019, T021 |
| AC-07 | T020, T021, T023, T025 |
| AC-08 | T022 |
| AC-09 | T017 |
| AC-10 | T024 |
| AC-11 | T002, T025, T026, T027 |
| AC-12 | T001, T003, T006 |
| AC-13 | T028, T029 |
| AC-14 | T030 |
