# P2 gate run

Gate (`../product/roadmap.md`): every runaway, out-of-memory, injection and escape test is contained on Linux and macOS.

Status: **passed** · Run: 2026-11-23 · Evidence: the security suites (spec 018, D-056; cases in `security-suites.md`)

## Summary

| Target | Where | JVM (`SecuritySuiteTest`) | Native (`jharness-smoke --security`) |
| --- | --- | --- | --- |
| linux-amd64 | CI and a local machine | 35 pass | 35 pass |
| linux-aarch64 | CI | 35 pass | 35 pass |
| macos-aarch64 | CI and a local machine | 34 pass, RUN-08 exception | 34 pass, RUN-08 exception |

No case was skipped on any target. A skip never counts as a pass.

| Suite | Cases | Result |
| --- | --- | --- |
| Prompt injection | INJ-01 to INJ-10 | Contained everywhere |
| Malicious repository | REPO-01 to REPO-10 | Contained everywhere |
| Sandbox escape | ESC-01 to ESC-06 | Contained everywhere |
| Runaway code, out of memory included | RUN-01 to RUN-09 | Contained everywhere, except the RUN-08 exception on macOS |

## The RUN-08 exception on macOS

A build-script grandchild that its parent orphans before the timeout outlives the process-tree kill, since Seatbelt has no PID namespace. It stays in the Seatbelt sandbox: the case checks that it can't write outside the workspace or connect out, and fails if it can. Recorded and accepted for this gate in D-056 (`security-suites.md`).

## Fix found while running the gate

CI had been red on Linux since spec 015. The hosted Linux image leaves `/usr/local/bin`, `node` and npm's files writable by everyone (mode 0777), and JHarness refuses to run a tool anyone may swap (spec 015, AC-05). npm looked missing, the npm smoke check failed, and REPO-10, ESC-04, RUN-08 and RUN-09 were skipped on Linux. The workflow now makes those paths owner-writable before the tests, which is how a normal install looks. JHarness's check is unchanged. A Maven Central 429 that failed one run is now retried.

## How to run it again

1. `mvn install` (runs `SecuritySuiteTest` among the JVM tests).
2. `mvn -Pnative -pl jharness-testkit package -DskipTests`, then `./jharness-testkit/target/jharness-smoke --security`.
3. On each target, every `security <ID>` line must be `pass`, except `exception` for RUN-08 on macOS.

The CI workflow (`.github/workflows/ci.yml`) does both on every target.
