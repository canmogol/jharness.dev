# Spec 051 — Evals on real repo tasks

Status: Done · Phase: P4 · Created: 2026-11-25

## Summary

A suite of small, realistic repository tasks with hidden checks, and `scripts/eval.py`, which runs a jharness binary on each in headless mode and scores it: pass or fail, steps, time, tokens and cost. Scores are recorded per release in `docs/evals.md`. CI runs the suite with recorded solutions. Decision D-085.

## Roadmap items

- Evals on real repo tasks

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-6.3 | As a maintainer, I want evals on real repo tasks before each release, so we know if the agent got better or worse. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `evals/tasks/` has six tasks: bug fixes, a feature, a refactor and a question, in Java and JavaScript. Each has `task.json`, `repo/`, a hidden check and a `replay.jsonl` that solves it. |
| AC-02 | `scripts/eval.py BIN` copies each repo into a fresh git repository, runs the check first and stops with exit 2 if it already passes, runs `jharness -p --output json --mode accept-edits`, then copies the check in and runs it. A question's check reads the reply and expects no file changes. |
| AC-03 | It prints a line per task with pass or fail, the reason, steps, tokens, cost and time, then a score; exit 1 if any task fails. `--out` writes JSON with the label, version, model, date, summary and each task's results. |
| AC-04 | `--replay` uses the recordings with a fresh home; `--model` and `--home` are for live runs; `--tasks` picks tasks; `--timeout` bounds each. |
| AC-05 | CI's native step runs `scripts/eval.py --replay` and needs 6/6; recordings that change nothing score 0/6 (checked when the suite was made). |
| AC-06 | `docs/evals.md` documents running, adding tasks and the per-release score table; README, D-085, roadmap. |

## Out of scope

- Running a live model in CI (no key there; a live run is a release check).
- A `jharness eval` command: checks run `javac`, `java` and `node`, which the binary must never start (Article IV).
- Large or networked repositories.

## Dependencies and assumptions

- Headless output and exit codes (D-082), usage and cost (D-084), `--replay`.
- `git`, a JDK and `node` where the evals run.

## Open questions

None.
