# Changelog

<!-- last-commit: 882f65025383fa27c09c572f85bb8a56edac761e -->

## 2026-11-18 · 001 P0 Spike

- Added SpikeApi and SandboxConfig (built-in defaults, `--config` file, `--set` overrides, validated keys) and the isolate probe: echo, creation timing, RSS per engine, memory protection.
- Added the limits probe: CPU, heap and isolate memory, wall-clock watchdog, blocked vs. busy host time, config overrides; each limit is followed by a fresh context on the same engine.
- Added the JLine probe with the FFM terminal provider and an automated pseudo-terminal check.
- Added the ModelJars model probe (pure-Java and Rust backends, 8k-token prompt, opening without the marker JAR) and a manual CI model job.
- Moved the macOS CI job to `macos-26` and kept pushes from cancelling manual spike runs.
- The wall-clock watchdog now escalates from context close to engine close to a recorded failure (D-037).
- Recorded the P0 results in `docs/p0-results.md` and passed the Phase 0 gate.
- Decisions: isolate mode comes from the checked engine default (D-034); ModelJars is a no-go in the native image for v0.1, so local models run through Ollama or llama.cpp, with a Rust-backend helper process to revisit after v0.1 (D-035, changes D-013); memory protection keys are off by default, with opt-in `auto` and `on` (D-036, changes D-018).

## 2026-11-17 · 001 P0 Spike

- Added the P0 spike spec, plan and tasks.
- Made sandbox and engine limits configurable, with 256 KB stream caps.
- Raised run time limits and added operation timeouts and an output cap.
- Confirmed the P0 go/no-go thresholds (D-030).
- Added the spike Maven build, the GitHub Actions native matrix (linux-amd64, linux-aarch64, macos-aarch64), `SECURITY.md` and `CONTRIBUTING.md`.

## 2026-11-17

- Started the repository with the MIT license and README.
- Added the product docs: mission, constitution, roadmap, tech stack, decisions, user stories and architecture plan.
- Hooks are JavaScript only (D-022), and MCP is reached through `api.mcp` (D-023).
- Allowed `rg` for search (D-027).
- Added the optional action reviewer, model profiles and offline mode (D-031 to D-033).
- Added `CLAUDE.md` and enabled the p-spec plugin for the repo.
