# Plan 051 — Evals on real repo tasks

Spec: `spec.md` · Status: Done · Created: 2026-11-25

## Approach

- `evals/tasks/`: `fix-pagination` and `add-median` (Java, checks compiled with `javac`), `fix-date-parse`, `fix-csv-quotes` and `rename-function` (JavaScript, `node` checks), `find-mailer` (a question with `replyContains` and `unchanged`).
- `scripts/eval.py` (Python, like `scripts/tui-pty-check.py`): prepare, baseline check, headless run, hidden check, report and JSON.
- Recordings made to solve each task; a do-nothing recording checked to fail all six.
- CI's native step runs the suite with `--replay`.
- `docs/evals.md`, `evals/README.md`, README, D-085, Q94, roadmap.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No product code changes; the runner is a maintainer script | Yes |
| II Host owns side effects | The agent acts only through `api` as always | Yes |
| III Untrusted | Isolates unchanged | Yes |
| IV No shell | The binary starts nothing new; the checks are started by the maintainer's script, outside JHarness, with argv lists | Yes |
| V Proxy | Unchanged; tasks need no network | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | Tasks run in throwaway folders with `accept-edits`; anything else is denied in `-p` | Yes |
| VIII Events | Results come from the headless result object and the session logs | Yes |
| IX Native first | CI runs the suite against the native binary on every target | Yes |
| X Tested | Baseline check per task; recordings pass; do-nothing recordings fail | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `scripts/eval.py --replay` with the Linux native binary: 6/6; do-nothing recordings: 0/6
- [x] Native build and CI's native step on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets
- [ ] First live run (a release check)

## Risks

| Risk | Response |
| --- | --- |
| Six tasks say little about a model | A start; adding a task is a folder and a recording, and the score table shows trends per release |
| A check passes without a fix | The baseline run stops a task whose check already passes |
| A live run writes to the user's home | Documented; `--home` points it elsewhere |
