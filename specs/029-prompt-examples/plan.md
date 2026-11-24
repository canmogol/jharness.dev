# Plan 029 — Prompt examples

Spec: `spec.md` · Status: Done · Created: 2026-11-24

## Approach

- Rewrite `jharness-agent/src/main/resources/dev/jharness/agent/examples.md`: the four examples stay, and seven new ones are added in task order.
- `ExamplesTest`:
  - Reads the blocks from the resource.
  - Builds a test project: a git repository, a README and `src/App.java`, a `maven.json` stand-in whose executable is `echo`, a local `HttpServer` with a network rule allowing it, and a prompter that denies `notes.txt`.
  - Replays every block, plus the subagent's reply, as model replies in one trusted session.
  - Asserts each `loop.result` is `ok` and checks each answer.
- D-068 with its index row and Q73; a README paragraph; the `api.d.ts` fix.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | Prompt text only | Yes |
| II Host owns side effects | Examples use the `api` only | Yes |
| III Untrusted | Unchanged | Yes |
| IV No shell | Unchanged; D-068 records why git stays typed host code | Yes |
| V Proxy | The fetch example goes through the proxy and a rule | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | The refused-write example teaches handling a denial | Yes |
| VIII Events | The emit example writes a guest event | Yes |
| IX Native first | Resource text only | Yes |
| X Tested | Every example runs in a test | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| Longer prompt for small context windows | 11 short blocks, about 1,000 tokens more; each block is at most 12 lines |
| Models copy an example too literally | Examples use the test project's own file names and say what each step is for |
