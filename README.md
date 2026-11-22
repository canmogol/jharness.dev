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

## IDs used in the docs

| ID | Means | Defined in |
| --- | --- | --- |
| `P0`–`P4` | Roadmap phase | `product/roadmap.md` |
| `US-N.N` | User story | `user-stories.md` |
| `D-NNN` | Accepted decision | `product/decisions.md` |
| `QNN` | Question behind a decision (Q&A table) | `product/decisions.md` |
| `NNN-<slug>` | Feature: spec folder and its branch | `specs/` |
| `AC-NN` | Acceptance criterion, numbered per spec | `specs/NNN-<slug>/spec.md` |
| `TNNN` | Task, numbered per spec | `specs/NNN-<slug>/tasks.md` |
| Article I–XI | Constitution rule | `product/constitution.md` |

## How a feature gets built

1. Write `specs/NNN-feature/spec.md`: stories and acceptance criteria.
2. Answer open questions; record architectural ones in `product/decisions.md`.
3. Write `plan.md`: design, contracts and the constitution check.
4. Write `tasks.md`: ordered tasks, each tied to an acceptance criterion.
5. Implement, then confirm every acceptance criterion has a passing test.

## Status

Phase 1 (core loop MVP) in progress. Done: the P0 spike (`specs/001-p0-spike/`), the host modules (002), the agent loop (003), permissions and the file policy (004), the CLI and terminal UI (005), project trust, layered settings and `JHARNESS.md` (006), and checkpoints, undo and Esc (007). Every Phase 1 item is built; the Phase 1 gate (a live run with a local model and with Claude) is next.

## Build and run

Needs Oracle GraalVM 25 (Java 25) and Maven; `rg` (ripgrep) is used when installed. Every process JHarness starts (git, `rg`, Maven, Gradle, npm) runs in an OS sandbox: Seatbelt on macOS (built in), bubblewrap on Linux (`sudo apt install bubblewrap`). Without it JHarness runs no processes and searches with its Java fallback.

Ubuntu 24.04 blocks the user namespaces bubblewrap needs. Allow them for bwrap only:

```
sudo tee /etc/apparmor.d/bwrap <<'EOF'
abi <abi/4.0>,
include <tunables/global>
profile bwrap /usr/bin/bwrap flags=(unconfined) {
  userns,
  include if exists <local/bwrap>
}
EOF
sudo apparmor_parser -r /etc/apparmor.d/bwrap
```

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

In the UI, Shift+Enter, Alt+Enter or Ctrl-J starts a new line and Enter sends. A turn runs up to 100 code steps; set `"agent": {"maxSteps": N}` (1 to 500) to change it. Every event goes to the session log printed at start (`tail -f` it); `/events all` or `"ui": {"events": "all"}` in settings also shows them inline. The log keeps each model reply, and its thinking text, whole on `model.completed`; `/thinking` or `"ui": {"thinking": "show"}` shows the thinking dim in the UI. `NO_COLOR=1` turns colors off.

Shift+Enter works in terminals that report it (xterm's `modifyOtherKeys`, which JHarness turns on only while the prompt reads, or CSI u). Where Shift+Enter sends a plain Enter, map it to Alt+Enter (`ESC` then `CR`):

| Terminal | Mapping |
| --- | --- |
| VS Code | In `keybindings.json`: `{"key": "shift+enter", "command": "workbench.action.terminal.sendSequence", "args": {"text": "\u001b\r"}, "when": "terminalFocus"}` |
| kitty | In `kitty.conf`: `map shift+enter send_text all \x1b\r` |
| iTerm2 | Settings → Profiles → Keys → Key Mappings: add Shift+Enter, action "Send Hex Code", `0x1b 0x0d` |

All network traffic goes through a proxy inside JHarness that applies `~/.jharness/network.rules` and the project's `.jharness/network.rules` (`default ask|allow|deny`, `allow X`, `deny X`; X is a host, `*.suffix`, IP or CIDR with an optional `:port`). Model endpoints from your profiles are allowed automatically; loopback and private addresses are denied unless a rule names them. `/network` lists and edits the rules.

In a trusted project the agent can run Maven (`compile`, `test`, `verify`, `package`, `clean`), Gradle (`build`, `test`, `check`, `assemble`, `clean`) and npm (`ci`, `install`, `test`, `run <script>`), each after your approval. `./mvnw` and `./gradlew` are used when present; `./gradlew` must pin `distributionSha256Sum`. Builds may write only to the workspace and their caches (`~/.m2/repository`, `~/.gradle/caches` and friends, `~/.npm`), and reach the network only through the proxy and its rules, so add `allow repo.maven.apache.org`, `allow plugins.gradle.org` or `allow registry.npmjs.org` (or answer "always" when asked). On macOS, Gradle builds can also reach services on your Mac's loopback and accept connections from your LAN (Gradle's daemon needs loopback, and macOS's sandbox can't narrow it; D-050), and Maven and npm tests that start their own local servers can't run inside the sandbox yet.

The agent can also call web APIs with `api.http` and read pages as text with `api.fetch`, both through the same proxy and rules. GET and HEAD need only the network rules; POST, PUT, PATCH and DELETE ask first ("always" saves a rule such as `POST api.example.com`), and plan mode denies them. Responses are read up to 5 MiB and returned as text up to 100,000 characters (`"http": {"maxResponseBytes": N, "maxChars": N}`); binary bodies aren't returned. Calls time out after 30 s by default (`timeouts.http`, at most `timeouts.httpMax`, 2 min). No secrets or cookies are added to requests, and headers and bodies stay out of the session log.

## License

The source code is under the [MIT License](LICENSE).

Release binaries will be built with Oracle GraalVM, which is distributed under the GraalVM Free Terms and Conditions (GFTC). GFTC allows free redistribution but not for a fee, so anyone who wants to sell binaries should build with GraalVM Community Edition 25.1 or later instead.
