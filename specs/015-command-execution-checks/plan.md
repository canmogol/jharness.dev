# Plan 015 — Command-execution checks

Spec: `spec.md` · Status: Done · Created: 2026-11-21

## Approach

All changes stay in `jharness-process`. `Executable` records a pin when it is built; `ProcessRunner` checks the pin, the environment names and the argument sizes before it wraps the argv for the sandbox. `GitOperations` adds the new hardening flags and runs one extra `git config --show-scope --name-only --get-regexp ^filter\.` call (hardened, sandboxed, with its own `process.*` events) before every operation, then adds `-c` overrides for each driver the repo defines. Decision D-052.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process kind; git gets one extra local `git config` call per operation | Yes |
| II Host owns side effects | Host code only; no `api` change | Yes |
| III Untrusted | No isolate change | — |
| IV No shell | Tightens it: pinned executables, named environment, argument caps, and no repo-configured programs in git | Yes |
| V Proxy | No network; git transports stay off (`protocol.allow=never`) | Yes |
| VI Strings | No `api` change | — |
| VII Safe by default | Approvals unchanged; a cloned repo's git config can no longer run code, trusted or not | Yes |
| VIII Events | New `process.refused` event when a pin check fails; the filter lookup emits the usual `process.*` events | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | `CommandExecutionTest` covers every check; git hardening is tested against a hostile repo config | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
// Executable
record Pin(Object fileKey, long size, FileTime modified) {}   // null fields when the file is missing
Executable(String name, Path path, Path searchDir)            // reads the pin
void verify()                                                 // throws OperationException if the pin changed

// Executables.resolve: skip if the file or toRealPath().getParent() has OTHERS_WRITE

// ProcessRunner
static final int MAX_ARGS = 4096, MAX_ARG_BYTES = 131_072, MAX_TOTAL_BYTES = 1 << 20;
static final Set<String> ENV_NAMES = Set.of("GIT_CONFIG_NOSYSTEM", "GIT_TERMINAL_PROMPT", "GIT_OPTIONAL_LOCKS",
        "GIT_WORK_TREE", "JAVA_HOME", "JAVA_TOOL_OPTIONS", "PATH", "npm_config_proxy", "npm_config_https_proxy",
        "npm_config_update_notifier", "npm_config_fund", "npm_config_audit", "npm_config_color", "npm_config_progress");
// run(): sandbox available → args checked → env names checked → exe.verify() (emits process.refused on failure)
//        → wrap → start. Base environment: PATH, HOME, LANG, LC_ALL; TMPDIR from the sandbox.

// GitOperations.HARDENING adds:
//   -c commit.gpgSign=false -c tag.gpgSign=false -c log.showSignature=false
//   -c submodule.recurse=false -c gc.auto=0 -c maintenance.auto=false
// status, diff: --ignore-submodules=all
// run(req): args(req) validated first, then
//   filters = git HARDENING config --show-scope --name-only --get-regexp ^filter\.   (exit 1 = none)
//   for each line "local|worktree<TAB>filter.<driver>.<key>":
//     -c filter.<driver>.clean= -c filter.<driver>.smudge= -c filter.<driver>.process= -c filter.<driver>.required=false
//   env + GIT_WORK_TREE=<root> when <root>/.git exists
```

`PATH` stays in the caller set because build operations put `JAVA_HOME/bin` and the tool's folder in front of the base `PATH` (spec 013).

### Event

| Type | Fields |
| --- | --- |
| `process.refused` | `executable`, `path`, `reason` |

## Checks

- [x] `mvn install` on Linux (bubblewrap)
- [x] `mvn test` on macOS (Seatbelt)
- [x] Native smoke binary on macOS and Linux: git ignores a hostile repo filter and `gpg.program`
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A package manager upgrades git or Maven during a session, and the pin then fails | The message says to restart JHarness; the upgraded file is pinned on the next start |
| Turning off repo-defined filters changes what `git add` stores (for example, LFS set up with `git lfs install --local`) | Global drivers keep working; the spec records the trade; content is stored as is, never run through repo code |
| The extra `git config` call adds latency and events | It's a local read of a few files; it runs in the same sandbox |
| Folders such as `/usr/local/bin` are group-writable on macOS | Only `o+w` is refused, not group write |
| Detached processes on macOS outlive a normal exit | Recorded as out of scope; Linux ends them with the PID namespace |
