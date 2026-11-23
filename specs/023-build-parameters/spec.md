# Spec 023 — Build parameters

Status: Done · Phase: P2 · Created: 2026-11-23

## Summary

Builds are typed operations with no free-form arguments (D-049, spec 015), so a model can't skip tests, build one profile or install to the local repository. This spec adds a few common parameters that can't run new code or loosen the sandbox, each as a typed field that JHarness turns into a fixed flag. Maven gets an `install` operation. Decision D-061.

## Roadmap items

- Typed build parameters: skip tests, Maven profiles, threads and `install`, Gradle parallel and continue, npm omit-dev (new Phase 2 item)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.1 | The agent fixes a bug and runs the tests via Maven, Gradle or npm | `user-stories.md` (builds that skip tests, use a profile or install) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | Maven: `install` is a new operation (fields as `verify`); it writes the artifacts to `~/.m2/repository`, which builds can already write. `skipTests: true` on `package`, `verify` and `install` adds `-DskipTests`; with `tests` it is refused. `profiles` (`dev`, `dev,!ci`; at most 10 ids of letters, digits, `_`, `.`, `-`) adds `-P` on every operation but `clean`. `threads` (1 to 32, or `1C` to `4C`) adds `-T`, also on every operation but `clean`. |
| AC-02 | Gradle: `skipTests: true` on `build` and `check` adds `-x test`; `parallel: true` adds `--parallel` and `continue: true` adds `--continue` on every operation but `clean`. |
| AC-03 | npm: `omitDev: true` on `ci` and `install` adds `--omit=dev`. |
| AC-04 | A boolean field must be `true` or `false` (`false` adds nothing); a string field must match its pattern; a field an operation doesn't take is refused, as before. Nothing else reaches the command line. |
| AC-05 | The approval question shows the parameters, e.g. `Allow maven: maven package · skipTests, profiles dev?`. "Always" still saves the operation alone (`maven package`). |
| AC-06 | `api.d.ts` documents every field, so the model knows them. |
| AC-07 | A real Maven `install` with `skipTests`, a profile and threads runs in the OS sandbox and installs the artifact. |
| AC-08 | The README and D-061 list the parameters. |

## Out of scope

- Any `-D` property, Gradle `-P` property, plugin goal or other free-form argument (they can run code or change where the build reads and writes).
- `-U`, `-q`, `--offline` (offline mode sets it) and other flags.

## Dependencies and assumptions

- Builds run in trusted projects only, with approval, in the OS sandbox (specs 012, 013).

## Open questions

None. The user asked for skip-tests plus other harmless, common parameters; the set above is the recommended one.
