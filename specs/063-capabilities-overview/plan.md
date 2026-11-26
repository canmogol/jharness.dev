# Plan 063 — Capabilities overview

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **`guide.md`** is written by hand, one line per command, in the agent module, because the agent module builds the system prompt and the CLI module depends on it. `SystemPrompt.guide()` reads it; the prompt template gets `{{GUIDE}}` under "# JHarness itself".
- **README.** A "What it does" section with markers; `scripts/sync-guide.py` copies the guide in.
- **`GuideTest`** (CLI module, which sees `Tui.COMMANDS`, picocli's `Main` and the README) checks the coverage and the copy.
- **Native.** The guide is registered as a resource; a native `-p` run proves the prompt builds.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | A resource in the binary | Yes |
| II Host owns side effects | Text only | Yes |
| III Untrusted | No isolate change | Yes |
| IV No shell | Unchanged | Yes |
| V Proxy | Unchanged | Yes |
| VI Strings | Unchanged | Yes |
| VII Safe by default | The prompt says the agent can't run the user's commands | Yes |
| VIII Events | No new action | Yes |
| IX Native first | Registered resource; native run checked | Yes |
| X Tested | `GuideTest` | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build and a native `-p` run on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| The guide falls behind the commands | `GuideTest` fails on a missing command or option, or a stale README |
| Prompt size on small local models | About 1,000 tokens; one table line per command |
