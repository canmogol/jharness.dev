# Spec 012 — OS sandbox for child processes

Status: Draft · Phase: P2 · Created: 2026-11-19

## Summary

Run every process JHarness starts (today git and `rg`) inside an OS sandbox: Seatbelt on macOS and bubblewrap on Linux. Children may write only to the workspace (not `.jharness/`) and a private temp folder, can't read credential folders, and have no network; spec 013 opens the proxy port for build tools. The sandbox is probed at session start, and when it isn't available nothing runs unsandboxed. Decision D-048. Scope taken from the recommended options while the user was away (2026-11-19).

## Roadmap items

- OS sandbox for build tools (bubblewrap or Landlock on Linux, Seatbelt on macOS)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.5 | Build tools run in an OS sandbox, so a malicious build script can't reach outside the project | `user-stories.md` (the sandbox; build tools come in 013) |
| US-3.4 | Agent code has no shell and runs under limits | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `ProcessRunner` starts every process through the OS sandbox: `/usr/bin/sandbox-exec` with a generated deny-by-default profile on macOS, `/usr/bin/bwrap` with generated arguments on Linux. Only argv lists; no shell. |
| AC-02 | Children can write only inside the workspace and a private per-session temp folder (0700, passed as `TMPDIR`, removed when the session closes). Writes elsewhere, and to the workspace's `.jharness/`, fail. |
| AC-03 | Children can't read `~/.jharness`, `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud` or `~/.netrc`; other reads work. |
| AC-04 | Children have no network: TCP and UDP connections fail, to loopback too. |
| AC-05 | The session probes the sandbox once at start and emits `sandbox.os` with `mechanism`, `available` and, when unavailable, `reason`. When unavailable, every process operation fails with that reason and how to fix it, search uses the Java fallback, and nothing runs unsandboxed. |
| AC-06 | Timeouts and process-tree kill work through the sandbox wrapper. |
| AC-07 | git operations, `rg` search and the P1 gate scenario still pass on macOS and on Linux in CI (with bubblewrap installed); the native smoke binary checks a sandboxed git run and a denied write. |

## Out of scope

- Network to the proxy port for child processes, and pointing tools at the proxy (spec 013).
- Landlock and Windows.
- Sandboxing the JHarness process itself (agent code is already in the GraalJS isolate).

## Dependencies and assumptions

- Spec 002 (`ProcessRunner`, git, `rg`), spec 011 (proxy, for 013).
- Linux users install bubblewrap and allow unprivileged user namespaces; the error message says so.

## Open questions

None.
