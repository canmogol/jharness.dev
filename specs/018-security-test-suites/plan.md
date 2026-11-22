# Plan 018 — Security test suites

Spec: `spec.md` · Status: Done · Created: 2026-11-22

## Approach

The cases are code in `jharness-testkit/src/main`, package `dev.jharness.testkit.security`:
- `SecurityCase` holds the ID, suite, attack, containment, layer and body.
- `SecuritySuite` holds the catalogue and the runner.
- `World` builds one case's temporary world and drives a real `Session`.
- `Js` builds the hostile model's replies.

Each attack runs through `t(name, fn)` in the JS, which records `ok:` or `err:`. The run returns the results between markers, and the Java side reads them from the replay provider's requests. `SecuritySuiteTest` turns each case into a dynamic test. `Smoke` runs the cases as part of the full smoke and on its own with `--security`. Decision D-056.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | Test code only; the smoke binary gains a `--security` mode | Yes |
| II Host owns side effects | The suites check that every side effect goes through the host; no product change to the `api` | Yes |
| III Untrusted | No limit removed. The one product change names a CPU limit that tripped without a message | Yes |
| IV No shell | Setup runs git directly in the test world only; hostile scripts run through `ProcessRunner` as the product does | Yes |
| V Proxy | The attacker and docs servers are reached only through the session's proxy; direct connections are tested to fail | Yes |
| VI Strings | The cases test that only strings and JSON cross (ESC-02) | Yes |
| VII Safe by default | The suites are the evidence. One recorded exception on macOS (RUN-08), still sandboxed, is raised with the user | Yes, with exception |
| VIII Events | Cases check host stamping of agent events (INJ-10) and limit events (RUN cases) | Yes |
| IX Native first | Same cases in the native smoke binary on Linux and macOS | Yes |
| X Tested | This spec is tests; the bug it found has a regression test first | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux (bubblewrap)
- [x] `SecuritySuiteTest` on macOS (Seatbelt): all pass except RUN-08, which reports its recorded exception
- [x] Native smoke on Linux and macOS, including `--security`
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A case passes for the wrong reason (an unrelated error) | Every attempt's error is printed with `-Djharness.security.trace=true`, and each was read once on Linux and macOS. Cases also check that the attack really happened (the page was read, the prompt was shown) |
| The suites slow down `mvn install` | About 35 s on the JVM; each case is one short session |
| npm is missing on a machine | Its cases report `skip`, which doesn't count as a pass |
| The macOS Seatbelt loopback EPERM flake fails an npm case | npm cases that hit it fail visibly; re-run, as the README says (spec 017) |
