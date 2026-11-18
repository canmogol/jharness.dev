# Plan 005 — CLI and TUI

Spec: `spec.md` · Status: Draft · Created: 2026-11-18

## Approach

A new module `jharness-cli` with the picocli entry point `dev.jharness.cli.Main`, the `Tui` (JLine) and `TerminalPrompter`. `Secrets` goes in `jharness-config`; the providers take it in place of `System::getenv`. `AgentLoop` gains a way to swap the provider, and `Session` gains `switchModel`. The native profile in `jharness-cli` builds the `jharness` binary; CI builds and runs it next to the smoke binary.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | `jharness` is the one binary; no new process | Yes |
| II Host owns side effects | The TUI only drives the session; effects still go through `api` and the gate | Yes |
| III Untrusted | No sandbox change | — |
| IV No shell | No process started | — |
| V Proxy | Providers as before (D-025 exception until P2) | Exception (D-025: proxy in P2) |
| VI Strings | No boundary change | — |
| VII Safe by default | Default mode `ask`; headless denies; the secrets file must be 0600; keys never logged | Yes |
| VIII Events | `session.model_changed`; everything else as before | Yes |
| IX Native first | picocli (with its annotation processor) and JLine, both checked by the native `jharness` build in CI | Yes |
| X Tested | Secrets permissions, key redaction, approval answers and headless denial have tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

### Command line

```
jharness [--cwd DIR] [--model NAME] [-p PROMPT]
jharness --version | --help
```

Hidden, for tests: `--replay FILE` uses a `ReplayProvider` with that recording instead of the profile's provider; `--home DIR` replaces `~`. Exit codes: 0 done, 1 turn error, 2 settings or usage error.

### TUI

- `LineReader` with history file `~/.jharness/history` (created 0600), prompt `> `.
- A turn runs on the main thread; deltas are printed as they arrive. The TUI subscribes to the bus and prints `▸ step N: running M lines` on `loop.code` and `▸ step N: ok|error|limit|cancelled` on `loop.result`.
- Commands: `/help`, `/model [NAME]`, `/exit`.
- `TerminalPrompter` prints `Allow <api>: <summary>? [y] once  [a] always for this project  [n] no` and reads one line through the same `LineReader`.

### Secrets

```java
public final class Secrets implements Function<String, String> {
    public static Secrets load(Path home, Function<String, String> env) throws ConfigException;
}
```

The file check: regular file, owner is the current user, permissions exactly `rw-------`.

## Checks

- [ ] `mvn -B -ntp verify`
- [ ] `mvn -B -ntp -Pnative -pl jharness-cli package -DskipTests && jharness-cli/target/jharness --version`

## Risks

| Risk | Response |
| --- | --- |
| picocli reflection under Native Image | The annotation processor generates the config; CI runs the native binary |
| JLine terminal behaviour differs on Linux | Tested with a dumb terminal on streams in unit tests; the P0 pty check covered the FFM provider on all targets |
| Reading the approval answer while a turn runs | The turn and the prompt share the main thread's `LineReader`; the agent thread blocks on the answer, the main thread is waiting in the turn and not reading |
