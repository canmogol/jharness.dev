# Evals

Evals on real repo tasks (spec 051, D-085, US-6.3): small repositories with a real bug, feature or question, each with a hidden check. They measure how well JHarness and a model finish coding tasks, and track it per release.

## Run

```
scripts/eval.py path/to/jharness --model claude                     # a live run with your settings and keys
scripts/eval.py path/to/jharness --model claude --label v0.1.0 --out evals/results/v0.1.0-claude.json
scripts/eval.py path/to/jharness --replay                           # the recorded solutions (CI)
scripts/eval.py path/to/jharness --tasks fix-pagination,add-median  # some tasks
```

Each task:

1. Its `repo/` is copied to a fresh folder and committed with git.
2. The check runs on the untouched repo and must fail; otherwise the task is broken (exit 2).
3. `jharness -p PROMPT --output json --mode accept-edits` runs there. A live run uses your home folder: your settings, model profiles and keys, and its session logs land with your others (`jharness log` reads them).
4. The task's `check/` is copied to `.eval/`, so the agent never saw it, and the check runs. A question's check reads the reply instead and expects no file changes.

It prints one line per task and a score, and exits 1 if any task fails. `--out` writes the results with each task's steps, time, tokens, cost (D-084, when the profile has a price), files changed and session id. `--replay` checks the tasks and the script, not a model; it always scores 6/6.

Checks need `git`, `javac`/`java` (JDK 17 or later) and `node`. They run on your machine, outside JHarness and its sandbox, so only run tasks you trust.

## Tasks

| Task | Kind | Language | The agent must |
| --- | --- | --- | --- |
| `add-median` | Feature | Java | Add `Stats.median` without changing its input |
| `find-mailer` | Question | Java | Name the class that sends the welcome email and its caller, through an event listener, and change nothing |
| `fix-csv-quotes` | Bug | JavaScript | Parse quoted CSV fields with commas and doubled quotes |
| `fix-date-parse` | Bug | JavaScript | Fix an off-by-one month and refuse impossible dates |
| `fix-pagination` | Bug | Java | Fix a page count that rounds down and a slice that drops a result |
| `rename-function` | Refactor | JavaScript | Rename a function, its export and its callers in three files |

To add one, make `evals/tasks/NAME/` with `task.json` (`title`, `prompt`, `check`, optional `mode` and `timeout` in seconds), `repo/`, `check/` and a `replay.jsonl` that solves it. `scripts/eval.py BIN --replay --tasks NAME` must pass.

## Scores

One row per release and model, from a live run with `--label` and `--out`; the results file goes in `evals/results/`.

| Release | Date | Model | Score | Steps | Tokens | Cost | Results |
| --- | --- | --- | --- | --- | --- | --- | --- |
| — | — | — | No live run yet | | | | |
