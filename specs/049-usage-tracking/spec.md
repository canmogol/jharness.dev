# Spec 049 — Usage tracking

Status: Done · Phase: P4 · Created: 2026-11-25

## Summary

Token use and cost for each session and agent. A model profile can set a `price` in US dollars per million tokens; each `model.completed` then carries its `costUsd`. `/usage` in the UI, `jharness usage` on the command line, the headless result and a new `cost` status line item add the logged events up. Decision D-084.

## Roadmap items

- Usage tracking (tokens, cost)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-1.3 | As a developer, I want to use Claude or any OpenAI-compatible server instead, so I can pick quality, cost or privacy per project. | `user-stories.md` |
| US-3.6 | As a security-minded user, I want a log of everything the agent did, so I can audit a session. | `user-stories.md` |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | A profile's `price` is `{input, output, cacheRead?, cacheWrite?}`, US dollars per million tokens, each 0 to 10,000; `input` and `output` are required. Missing cache prices are a tenth and 1.25 times `input` for `anthropic`, and `input` for `openai-compatible`. Unknown keys and bad values are refused. No price is set by default, and JHarness ships no price list. |
| AC-02 | With a price, `model.completed` has `costUsd`: fresh prompt tokens (input minus cache reads and writes) at `input`, cache reads at `cacheRead`, cache writes at `cacheWrite`, output at `output`. Without one, no `costUsd`. |
| AC-03 | `/usage` adds up this session's log by agent (main, sub-N), with a total row when there are several: requests, input, output, cache reads, cache writes and cost. Cost is `-` with no price and ends in `+` when some requests had none; a note says how to set a price. |
| AC-04 | `jharness usage` lists this folder's sessions from the last 30 days (`--days N`, `--all` for every project) with their last model and a total; `jharness usage SESSION` shows one session by agent; `--json` prints one object; bad arguments exit 2. |
| AC-05 | The headless result's `usage` adds `costUsd` when any request had a price and `unpricedRequests`. |
| AC-06 | The status line has a `cost` item, every agent's cost so far (a resumed session's earlier runs included), shown only once a request had a price; the script snapshot has `costUsd`. |
| AC-07 | README, D-084, D-080 and D-082 notes, roadmap; CI's native step runs `jharness usage`. |

## Out of scope

- A shipped price list or fetching prices.
- Spending limits that stop a session (later, if wanted).
- Currencies other than US dollars.

## Dependencies and assumptions

- Token counts on `model.completed`, cache counts included (specs 039, 040); session logs (D-038); the status line (D-080); the headless result (D-082).

## Open questions

None.
