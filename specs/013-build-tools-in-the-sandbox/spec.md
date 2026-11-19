# Spec 013 — Maven, Gradle and npm in the sandbox

Status: Draft · Phase: P2 · Created: 2026-11-19

## Summary

Agent code can run Maven, Gradle and npm through typed operations (`api.maven`, `api.gradle`, `api.npm`), focused on building and running tests. Each call runs inside the OS sandbox from spec 012, which now opens one network path for build tools: the egress proxy's port. Every tool is pointed at the proxy, so the network rules from spec 011 decide where it can connect. Calls take `timeoutMs` (2 minutes by default, at most 10), and the model gets the exit code and the last 30,000 characters of output. Decision D-049. Scope taken from the recommended options while the user was away (2026-11-19).

## Roadmap items

- Maven, Gradle and npm typed operations through `ProcessRunner`
- Per-operation timeouts with `timeoutMs` (2 min default, 10 min max for builds) and process-tree kill
- Output returned to the model capped at the last 30,000 characters plus exit code; full output in the session log
- OS sandbox allows network only to the proxy port

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.1 | The agent searches, edits and runs tests with Maven, Gradle or npm (up to 10 min per run on request) | `user-stories.md` |
| US-3.5 | Build tools run in an OS sandbox and write only to the workspace and caches; network goes through the rules | `user-stories.md` |
| US-3.3 | The proxy enforces the rules for every client and tool | `user-stories.md` (command-line build tools) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.maven`, `api.gradle` and `api.npm` take typed requests and never free-form arguments. Maven: `compile`, `test`, `verify`, `package`, `clean`, with `module` and `tests`. Gradle: `build`, `test`, `check`, `assemble`, `clean`, with `project` and `tests`. npm: `ci`, `install`, `test` and `run` with a `script` that `package.json` defines. Each field is validated. Unknown ops or fields, and values that start with `-`, are rejected. |
| AC-02 | Fixed flags on every run. Maven: batch mode, no transfer progress, no color. Gradle: plain console and `--no-daemon` (D-029). npm: `--ignore-scripts` on `ci` and `install`, and no audit, fund or update checks. Wrappers: `./mvnw` and `./gradlew` run when present (the project is trusted). `./gradlew` needs `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`. Otherwise `mvn` and `gradle` come from `PATH` outside the workspace, and npm always does. |
| AC-03 | Build operations need a trusted project (D-011); otherwise they fail with "trust this project first". They change state: they ask for approval (D-010), plan mode denies them, and "always" saves a rule for that tool and op (for example `maven test`) in the user-folder permissions file (D-040). |
| AC-04 | `timeoutMs` is accepted on every git, Maven, Gradle and npm call. Without it the timeout is `timeouts.process` (2 min); values above `timeouts.processMax` (10 min) or below 1 are rejected. On timeout the whole process tree is killed and the result has `timedOut: true` with the output so far. |
| AC-05 | Results are `{exitCode, output, truncated, timedOut, durationMs}`, where `output` is the last `timeouts.outputChars` (30,000) characters. `process.completed` in the session log keeps the output from the start, up to 1 MiB. The proxy password never appears in argv, in events or in returned output. |
| AC-06 | Build tools reach the network only through the proxy. Maven gets a generated `--global-settings` file with the proxy. Gradle and every JVM it starts get the proxy system properties through `JAVA_TOOL_OPTIONS`. npm gets `npm_config_proxy` and `npm_config_https_proxy`. The rules decide each host (`network.allowed`, `network.blocked`, `network.asked`). |
| AC-07 | The OS sandbox opens only the proxy port, and only for build tools. On macOS, Seatbelt allows outbound connections to `localhost:<proxy port>` only. On Linux, bubblewrap keeps its own network namespace, and a small bridge inside it (the JHarness binary itself) forwards `127.0.0.1:<proxy port>` over a Unix socket to the proxy. Direct connections to anything else fail. git and `rg` still have no network. |
| AC-08 | Besides the workspace and the temp folder, build tools may write only to their caches: `~/.m2/repository`; `~/.gradle/caches`, `wrapper`, `native`, `jdks`, `daemon`, `notifications` and `.tmp`; and `~/.npm`. `~/.m2/settings.xml`, `~/.gradle/init.d`, `~/.gradle/gradle.properties` and `~/.npmrc` stay read-only. JVMs get `-Djava.io.tmpdir` set to the private temp folder. |
| AC-09 | Tests on macOS locally and on Linux in CI check three things. A sandboxed client reaches an allowed stub server through the proxy, and fails to connect directly. Maven, npm and Gradle (when installed) run their ops in the sandbox. The native smoke binary checks an npm script that connects through the bridge and proxy, and fails to connect directly. |

## Out of scope

- git `fetch`, `pull`, `push` and `clone` through the proxy (D-039 stays; later spec).
- Offline mode (D-033), `api.http`, and the injection and escape test suites.
- Local servers in tests (for example Spring Boot on a random port): on macOS, Seatbelt can't tell them apart from other local services, so they fail there for now.
- Checkpoints of build outputs: `/undo` does not remove `target/`, `build/` or `node_modules/`.
- Installing new npm packages by name, `npx`, pnpm and yarn (D-009).

## Dependencies and assumptions

- Spec 011 (proxy, rules), spec 012 (sandbox), spec 006 (trust).
- Maven, Gradle and npm are installed by the user; JHarness doesn't download them. `JAVA_HOME` is passed through when set.

## Open questions

None.
