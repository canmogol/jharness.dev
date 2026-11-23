# Plan 023 — Build parameters

Spec: `spec.md` · Status: Done · Created: 2026-11-23

## Approach

- `BuildOperations.FIELDS` gets the new fields per operation and Maven `install`. `args` turns each into a fixed flag after checking it: `flag()` for booleans, `matching()` with `PROFILES` and `THREADS` for strings.
- `BuildOperations.summary` names the operation and its parameters for the approval question; `detail` (the "always" key) is unchanged.
- `HostApi.build` passes `summary`. `api.d.ts` documents the fields.
- Decision D-061.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new dependency | Yes |
| II Host owns side effects | Builds still run only through `BuildOperations` and `ProcessRunner` | Yes |
| III Untrusted | No limit removed; each field is a fixed flag or a checked value | Yes |
| IV No shell | Typed fields only; no free-form argument | Yes |
| V Proxy | Network unchanged: proxy and rules only | Yes |
| VI Strings | Requests stay JSON | Yes |
| VII Safe by default | Every parameter only skips work, picks a project-defined profile or changes parallelism; `install` writes only to the Maven cache builds already write | Yes |
| VIII Events | `process.started` shows the flags; the approval shows them | Yes |
| IX Native first | No reflection change | Yes |
| X Tested | Argv per field, refusals, approval text, a real Maven install in the sandbox | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A profile in the user's `~/.m2/settings.xml` changes repositories | The generated global settings and the proxy rules still apply; a profile can only name repositories the rules allow |
| Skipped tests hide a failure | The approval question shows `skipTests`, so the user sees it before the build runs |
