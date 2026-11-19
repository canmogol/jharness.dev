# JHarness

A free, open-source terminal coding agent that ships as one native executable and runs all agent-written code in a GraalJS sandbox.

This repository is planned with spec-driven development: product docs first, then one spec per feature, then code.

## Where things are

| Path | What it holds |
| --- | --- |
| `product/mission.md` | What JHarness is, who it's for, what makes it different |
| `product/constitution.md` | Rules every feature must follow, and the check each plan must pass |
| `product/roadmap.md` | Features by phase (P0–P4), with the gate that closes each phase |
| `product/tech-stack.md` | Technology choices |
| `product/decisions.md` | Every decision, why it was made, and the questions behind it |
| `plan.md` | How JHarness gets built: architecture, modules, phases, testing |
| `user-stories.md` | Stakeholder-level user stories |
| `specs/NNN-feature/` | One folder per feature: `spec.md`, `plan.md`, `tasks.md` |

## How a feature gets built

1. Write `specs/NNN-feature/spec.md`: stories and acceptance criteria.
2. Answer open questions; record architectural ones in `product/decisions.md`.
3. Write `plan.md`: design, contracts and the constitution check.
4. Write `tasks.md`: ordered tasks, each tied to an acceptance criterion.
5. Implement, then confirm every acceptance criterion has a passing test.

## Status

Phase 1 (core loop MVP) in progress. Done: the P0 spike (`specs/001-p0-spike/`), the host modules (002), the agent loop (003), permissions and the file policy (004), the CLI and terminal UI (005), project trust, layered settings and `JHARNESS.md` (006), and checkpoints, undo and Esc (007). Every Phase 1 item is built; the Phase 1 gate (a live run with a local model and with Claude) is next.

## Build and run

Needs Oracle GraalVM 25 (Java 25) and Maven; `rg` (ripgrep) is used when installed.

```
mvn install                                          # build and test on the JVM
mvn -Pnative -pl jharness-cli package -DskipTests    # native binary: jharness-cli/target/jharness
```

Model profiles go in `~/.jharness/settings.json`:

```json
{
  "model": "ollama-qwen",
  "models": {
    "ollama-qwen": { "provider": "openai-compatible", "baseUrl": "http://localhost:11434/v1", "model": "qwen2.5-coder:7b" },
    "claude": { "provider": "anthropic", "model": "<Claude model id>", "apiKeyEnv": "ANTHROPIC_API_KEY" }
  }
}
```

API keys come from the environment or `~/.jharness/secrets` (`NAME=value` lines, mode 0600). Then, in a project folder:

```
jharness                      # terminal UI; /help lists the commands
jharness --model claude       # another profile
jharness -p "explain src/"    # one prompt, no UI; changes are denied
```

In the UI, Alt+Enter or Ctrl-J starts a new line and Enter sends. Every event goes to the session log printed at start (`tail -f` it); `/events all` or `"ui": {"events": "all"}` in settings also shows them inline. `NO_COLOR=1` turns colors off.

## License

The source code is under the [MIT License](LICENSE).

Release binaries will be built with Oracle GraalVM, which is distributed under the GraalVM Free Terms and Conditions (GFTC). GFTC allows free redistribution but not for a fee, so anyone who wants to sell binaries should build with GraalVM Community Edition 25.1 or later instead.
