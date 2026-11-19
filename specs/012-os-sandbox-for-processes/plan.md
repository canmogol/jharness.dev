# Plan 012 — OS sandbox for child processes

Spec: `spec.md` · Status: Done · Created: 2026-11-19

## Approach

A `ChildSandbox` in `jharness-process` holds the policy (workspace, private temp folder, hidden folders) and the mechanism for the OS. `ProcessRunner` takes one and wraps every argv with it; a runner whose sandbox is unavailable refuses to start anything. `Session` creates the temp folder and the sandbox, probes it, emits `sandbox.os`, and passes it to git and search. Decision D-048.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | `sandbox-exec` and `bwrap` are OS tools that wrap the same child processes; no new long-lived process | Yes |
| II Host owns side effects | The host builds the profile and arguments; agent code can't influence them | Yes |
| III Untrusted | No change to the isolate | — |
| IV No shell | `ProcessRunner` stays the only process starter; the wrapper is a pinned absolute path with an argv list | Yes |
| V Proxy | Children get no network at all in 012; the proxy port opens in 013 | Yes |
| VI Strings | No boundary change | — |
| VII Safe by default | Fail closed: no sandbox, no process; no setting turns it off | Yes |
| VIII Events | `sandbox.os` at start; `process.started` records the mechanism | Yes |
| IX Native first | No new dependency; the native smoke binary runs a sandboxed process | Yes |
| X Tested | Write, read, network and timeout tests on macOS locally and Linux in CI | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
public final class ChildSandbox {
    public record Policy(Path workspace, Path tmp, List<Path> hidden) {
        public static Policy forSession(Path workspace, Path tmp, Path home);   // the D-048 hidden list
    }
    public static ChildSandbox detect(Policy p);   // by os.name; probes once with /usr/bin/true
    public boolean available();
    public String mechanism();                     // "seatbelt", "bubblewrap" or "none"
    public String reason();                        // why unavailable, with the fix
    List<String> wrap(List<String> argv, Path cwd);
    Map<String, String> env();                     // TMPDIR
}
```

### Seatbelt profile (macOS)

```
(version 1)
(deny default)
(allow process-fork) (allow process-exec) (allow signal (target same-sandbox))
(allow sysctl-read) (allow mach-lookup) (allow ipc-posix-shm-read-data)
(allow file-read*)
(deny file-read* (subpath "<hidden>") ...)
(allow file-write* (subpath "<workspace>") (subpath "<tmp>") (literal "/dev/null") (literal "/dev/tty") (regex #"^/dev/fd/"))
(deny file-write* (subpath "<workspace>/.jharness"))
(allow file-ioctl (literal "/dev/null"))
```

Paths are real paths (`/tmp` is `/private/tmp`) and quoted for SBPL (Sandbox Profile Language). No `network*` rule, so all network is denied. Run as `sandbox-exec -p <profile> -- argv`.

### bubblewrap arguments (Linux)

`bwrap --die-with-parent --new-session --unshare-all --ro-bind / / --dev /dev --proc /proc --bind <ws> <ws> --ro-bind <ws>/.jharness <ws>/.jharness --bind <tmp> <tmp> [--tmpfs <hidden dir> | --ro-bind /dev/null <hidden file>] --chdir <cwd> -- argv`. `--unshare-all` includes the network namespace. The host creates `<ws>/.jharness` (empty) when missing so it can be mounted read-only.

### Probe and fail closed

`detect` runs the wrapped `/usr/bin/true` (must succeed) and `/usr/bin/touch` on a path outside the workspace (must fail) through `ProcessRunner.probe` (the only other `ProcessBuilder` use stays in `ProcessRunner.java`). A non-zero exit or a missing wrapper makes the sandbox unavailable with a reason: "install bubblewrap (`apt install bubblewrap`) and allow unprivileged user namespaces" or the wrapper's own error. `Search.create` uses `JavaSearch` when the sandbox is unavailable; git operations throw.

## Checks

- [x] `mvn -pl jharness-process -am test`
- [x] `mvn verify` on macOS; CI on Linux with bubblewrap
- [x] Native smoke binary on macOS

## Risks

| Risk | Response |
| --- | --- |
| GitHub's Ubuntu 24.04 runners restrict unprivileged user namespaces (AppArmor) | CI installs bubblewrap; if bwrap is refused, CI relaxes the sysctl for the test job; users see the reason and the fix |
| Seatbelt (`sandbox-exec`) is marked deprecated by Apple | It still ships, and Chrome, Codex CLI and Claude Code rely on it; no plan to replace it |
| A tool needs a write outside the workspace (caches) | Comes with 013 per tool (Maven `~/.m2`, Gradle `~/.gradle`, npm cache) |
