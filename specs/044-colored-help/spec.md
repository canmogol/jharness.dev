# Spec 044 — Colored help

Status: Done · Phase: P4 · Created: 2026-11-24

## Summary

Bug fix: on a real terminal `jharness --help` printed picocli's colors as literal text (`\e[33m--debug\e[39m\e[0m`), because the streams picocli wrote to escape ESC (spec 022). picocli's own help, version and usage-error output now goes to the raw streams; everything else stays escaped. Refines D-060.

## Roadmap items

None (a bug fix in spec 022's escaping).

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.6 | As a security-minded user, I want a log of everything the agent did, so I can audit a session. | `user-stories.md` (spec 022's story; the escaping stays) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | picocli writes its help, version and usage errors to raw writers on stdout and stderr; with colors on, `--help` (and a subcommand's) has real ESC sequences and no literal `\e[`. |
| AC-02 | Colors follow picocli's auto mode (a terminal only) and are off when `NO_COLOR` is set and not empty, checked by JHarness as the TUI does. |
| AC-03 | A usage error's message, which echoes the typed arguments, is escaped with `TerminalSafe` before it reaches the raw stream. A code comment says the rest of the help text is fixed and must be escaped if outside text is ever added. |
| AC-04 | `-p` replies, errors with outside text and `jharness log` are escaped as before, also with help colors on. |
| AC-05 | D-060, spec 022 and the README note the change. |

## Out of scope

- Colors anywhere else.

## Dependencies and assumptions

- `TerminalSafe` (specs 021, 022); picocli 4.7.

## Open questions

None.
