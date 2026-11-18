# Contributing to JHarness

Thanks for helping. JHarness is planned with spec-driven development: the docs in this repository are the source of truth.

## Before you start

1. Read `product/constitution.md`. Every change must follow it.
2. Check `product/decisions.md`. Don't contradict an accepted decision (`D-NNN`); open an issue to propose a change instead.
3. Find or open an issue for the work, so it can be matched to a roadmap item in `product/roadmap.md`.

## How a change is made

1. Every feature has a spec folder `specs/NNN-<slug>/` with `spec.md`, `plan.md` and `tasks.md`. No production code without one (Article XI).
2. Work on a branch named after the spec (`NNN-<slug>`) and open a pull request to `master`.
3. Tick tasks in `tasks.md` as they are done; each task names the acceptance criteria (`AC-NN`) it proves.
4. Small fixes (typos, docs, a bug with a regression test) don't need a spec.

## Build and test

| Need | Version |
| --- | --- |
| JDK | Oracle GraalVM 25.x for Java 25 |
| Build tool | Maven 3.9+ |

Develop and run tests on the JVM. Native builds run in CI and before releases.

```sh
cd spike
mvn -B verify                 # JVM build
mvn -B -Pnative package       # native executable in spike/target/
```

## Rules that reviewers check

- Agent JavaScript reaches the world only through the `api` facade; only strings and JSON cross the isolate boundary.
- Processes start only through `ProcessRunner` with typed arguments. Never `sh -c`, never a command string.
- Network traffic goes only through the host egress proxy.
- Every new dependency builds and runs under Native Image on Linux and macOS.
- Security rules have tests; a security bug gets a regression test first.

## Commits and pull requests

- Short imperative subjects: `Add ProcessRunner argument checks`.
- One focused change per pull request.

## Security issues

Don't report them in public. See `SECURITY.md`.

## License

By contributing you agree that your contribution is licensed under the MIT License in `LICENSE`.
