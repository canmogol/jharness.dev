# Spec 025 — Custom agents

Status: Done · Phase: P3 · Created: 2026-11-23

## Summary

The first Phase 3 item. A custom agent is a folder in `.jharness/agents/` (project) or `~/.jharness/agents/` (user), as D-017 says. It holds `agent.json` (description, model profile, allowed `api` methods, plan mode, step limit), an optional `prompt.md` added to the system prompt and an optional `helpers.js` whose functions are defined before every block. `jharness --agent NAME` runs as it; `/agent` lists them. An agent can only narrow what the session may do. The repository ships one working example, a read-only reviewer, for the Phase 3 gate. Decision D-063.

## Roadmap items

- Custom JS agents in `.jharness/agents/`

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-4.1 | A team lead adds a custom agent to the repo so the whole team gets the same workflow | `user-stories.md` (prompt, model, tools and optional JS in `.jharness/agents/`) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | An agent is a folder `NAME/` (lowercase letters, digits and `-`, at most 40) with `agent.json` in `~/.jharness/agents/` or the project's `.jharness/agents/`. A project agent replaces a user agent of the same name. Folders without `agent.json` or with other names are skipped. |
| AC-02 | `agent.json` takes `description` (required, 1 to 200 characters), `model` (a profile name), `tools` (a list of `api` method names; `readContext` is always allowed), `maxSteps` (1 to 500) and `plan` (true or false). Unknown keys and bad values stop start-up with the file and key named. `prompt.md` and `helpers.js` are optional regular files of at most 32 KB; links are refused. |
| AC-03 | In a project that isn't trusted, its agents are ignored: `--agent` naming one stops start-up and says to trust the project, and `/agent` marks it ignored. User agents always load. |
| AC-04 | `jharness --agent NAME` (TUI and `-p`) runs as the agent. Its `model` is used unless `--model` names another. An unknown agent stops start-up with the known names. |
| AC-05 | The system prompt ends with the agent's name, description and `prompt.md`, the allowed methods, a note when in plan mode, and the `helpers.js` source. |
| AC-06 | A call to a method not in `tools` is denied before the permission engine, with `agent NAME can't use api.X (its tools: …)`. `plan: true` runs the session in plan mode. `maxSteps` replaces `agent.maxSteps`. |
| AC-07 | `helpers.js` runs in the same isolate and context as each block, just before it, with the same limits and `api`. An error in it fails the block with `in the agent's helpers.js: …`. |
| AC-08 | `session.started` carries `customAgent` (`default` or the name) and `agentSource` (`user` or `project`). The TUI's first line names the agent. `/agent` lists the built-in agent and each found agent with its source and description, or why it is ignored or invalid. |
| AC-09 | `examples/agents/reviewer/` is a working agent: read-only tools, plan mode, `changedFiles()` and `diffOf(path)` helpers and review instructions. A test runs it in a session, and CI runs it in the native binary. |
| AC-10 | The README and D-063 document agents. |

## Out of scope

- Switching agents within a session (start a new one with `--agent`).
- Subagents and `api.spawnAgent` (next Phase 3 item).
- Agents loosening anything: more methods, other permission modes, larger limits.
- Installing agents from a git URL (D-017).

## Dependencies and assumptions

- Project trust (D-011), plan mode (D-041), model profiles (D-032).

## Open questions

None. Recommended options accepted while the user was away.
