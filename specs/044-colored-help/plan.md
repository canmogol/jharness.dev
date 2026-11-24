# Plan 044 — Colored help

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- `Main.run(m, env, ansi, args)`: keep the raw streams before wrapping `m.out` and `m.err` with `TerminalSafe`; give picocli `PrintWriter`s on the raw ones; set the color scheme from `helpAnsi` (forced for tests, `OFF` with `NO_COLOR`, else `AUTO`); a parameter-exception handler that escapes the message, then prints suggestions or usage like picocli's default.
- `Main.run(m, args)` passes `System.getenv()` and auto mode.
- Tests in `MainTest`: colors forced on (root and `log --help`), `NO_COLOR` and no terminal, an escaped usage error, a `-p` reply escaped with colors on; spec 022's test unchanged.
- D-060 (index row, section, Q88), spec 022's "Later changes", README.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Output only | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | No process starts | Yes |
| V Proxy | No network use | Yes |
| VI Strings | Nothing crosses the isolate boundary | Yes |
| VII Safe by default | Only fixed help text is raw; typed arguments in errors are escaped; untrusted output stays escaped | Yes |
| VIII Events | No new action | Yes |
| IX Native first | The native binary's help checked on a pseudo-terminal | Yes |
| X Tested | Colors on, off, `NO_COLOR`, escaped errors, escaped replies | Yes |
| XI Spec first | This spec | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native `jharness --help` on a pseudo-terminal on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Outside text added to the help later | The comment in `Main.run` says to escape it |
