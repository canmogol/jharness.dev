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
| `docs/security-suites.md` | The prompt-injection, malicious-repository, sandbox-escape and runaway-code cases, and how to run them |

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
| `INJ-NN` | Prompt-injection security case | `docs/security-suites.md` |
| `REPO-NN` | Malicious-repository security case | `docs/security-suites.md` |
| `ESC-NN` | Sandbox-escape security case | `docs/security-suites.md` |
| `RUN-NN` | Runaway-code security case | `docs/security-suites.md` |

## Terms used in the docs

| Term | Means |
| --- | --- |
| Agent JS | The JavaScript the model writes; JHarness runs it and it acts only through the `api` object |
| Isolate | The GraalJS sandbox each run of agent JS gets: no Java, files, network or processes, and CPU, memory and time limits |
| Egress proxy | The proxy inside JHarness that every connection goes through and that applies the network rules |
| Network rules | `network.rules` lines (`allow X`, `deny X`, `default ask`) that decide which hosts can be reached |
| OS sandbox | The sandbox every child process (git, `rg`, Maven, Gradle, npm) runs in: Seatbelt on macOS, bubblewrap on Linux |
| Seatbelt | macOS's built-in sandbox (`sandbox-exec`) |
| bubblewrap (`bwrap`) | The Linux sandbox tool that uses namespaces |
| `EPERM` | The "Operation not permitted" error a sandbox returns when it refuses something |
| File policy | The rules for which paths agent JS may read and write; secrets such as `.env` and keys are denied |
| Trusted project | A workspace the user has trusted; its settings apply and builds may run |
| Plan mode | A mode that denies every change without asking |
| Prompt injection | Instructions hidden in a file, a web page or tool output that try to steer the model |
| Malicious repository | A workspace whose files (settings, `.git/config`, hooks, build scripts, symlinks) try to run code or loosen the rules |
| Sandbox escape | Agent JS or a build script trying to get out of the isolate or the OS sandbox |
| Runaway code | Code that never stops or uses too much: loops, heap bombs, deep recursion, output floods, hung processes |
| Canary | A fake secret planted by the security suites; a case fails if it shows up anywhere |
| Native smoke binary | `jharness-smoke`, a native build that checks JHarness works as a native executable |

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

API keys come from the environment or `~/.jharness/secrets` (`NAME=value` lines, mode 0600).

A failed model request is sent again when nothing from it has reached you yet: rate limits (429), overloads (529 and other 5xx), and a server that can't be reached or drops the connection. JHarness waits as long as the server's `retry-after` asks, or 1 s, 2 s, 4 s and so on, and shows `▸ HTTP 429 from anthropic, retrying in 7 s (attempt 2 of 5)`. Esc stops the wait. Each profile can set `"retries"` (0 to 10, default 4), `"retryMaxWait"` (default `"60s"`; a server asking for longer fails at once) and `"responseTimeout"` (default `"120s"`; raise it for a slow local model, since a timed-out request isn't retried).

Then, in a project folder:

```
jharness                      # terminal UI; /help lists the commands
jharness --model claude       # another profile
jharness -p "explain src/"    # one prompt, no UI; changes are denied
jharness --debug              # every event and the thinking shown; stack traces on errors
jharness log                  # this folder's sessions, newest first
jharness log last             # the newest session as a timeline
```

In the UI, Shift+Enter, Alt+Enter or Ctrl-J starts a new line and Enter sends. A turn runs up to 100 code steps; set `"agent": {"maxSteps": N}` (1 to 500) to change it. Every event goes to the session log printed at start (`tail -f` it); `/events all` or `"ui": {"events": "all"}` in settings also shows them inline. The log keeps each model reply, and its thinking text, whole on `model.completed`; `/thinking` or `"ui": {"thinking": "show"}` shows the thinking dim in the UI. `NO_COLOR=1` turns colors off.

`jharness log SESSION` prints a session one event per line: time, turn, type and its main fields. SESSION is an id, a unique prefix, `last` or a path to a `.jsonl` log. `--type network.` (repeatable) filters by type, `--full` shows whole events, `--json` prints the raw lines and `--all` lists every project's sessions. Control characters from logged text are shown escaped (`\e`, `\n`). The UI and `-p` do the same with replies, diffs and errors (newlines and tabs stay), so a hostile reply or file can't rewrite your terminal. Each model response is logged as `model.response` with its status, request id and rate-limit headers; no other header is logged.

If JHarness itself fails, it writes a crash report to `~/.jharness/crash/`, prints its path and exits with code 3. The report has the version, OS, stack trace and the types of the last 30 events, but no event bodies, and your home folder shows as `~`. It never leaves your machine. Check it before attaching it to an issue, since exception messages can quote files. The newest 20 reports are kept.

Shift+Enter works in terminals that report it (xterm's `modifyOtherKeys`, which JHarness turns on only while the prompt reads, or CSI u). Where Shift+Enter sends a plain Enter, map it to Alt+Enter (`ESC` then `CR`):

| Terminal | Mapping |
| --- | --- |
| VS Code | In `keybindings.json`: `{"key": "shift+enter", "command": "workbench.action.terminal.sendSequence", "args": {"text": "\u001b\r"}, "when": "terminalFocus"}` |
| kitty | In `kitty.conf`: `map shift+enter send_text all \x1b\r` |
| iTerm2 | Settings → Profiles → Keys → Key Mappings: add Shift+Enter, action "Send Hex Code", `0x1b 0x0d` |

All network traffic goes through a proxy inside JHarness that applies `~/.jharness/network.rules` and the project's `.jharness/network.rules` (`default ask|allow|deny`, `allow X`, `deny X`; X is a host, `*.suffix`, IP or CIDR with an optional `:port`). Model endpoints from your profiles are allowed automatically; loopback and private addresses are denied unless a rule names them. `/network` lists and edits the rules.

In a trusted project the agent can run Maven (`compile`, `test`, `verify`, `package`, `install`, `clean`), Gradle (`build`, `test`, `check`, `assemble`, `clean`) and npm (`ci`, `install`, `test`, `run <script>`), each after your approval. There are no free-form arguments, only typed parameters: skip tests (Maven `-DskipTests`, Gradle `-x test`), Maven profiles (`-P`) and threads (`-T`), Gradle `--parallel` and `--continue`, and npm `--omit=dev`. The approval question shows them. `./mvnw` and `./gradlew` are used when present; `./gradlew` must pin `distributionSha256Sum`. Builds may write only to the workspace and their caches (`~/.m2/repository`, `~/.gradle/caches` and friends, `~/.npm`), and reach the network only through the proxy and its rules, so add `allow repo.maven.apache.org`, `allow plugins.gradle.org` or `allow registry.npmjs.org` (or answer "always" when asked). On macOS, Gradle builds can also reach services on your Mac's loopback and accept connections from your LAN (Gradle's daemon needs loopback, and macOS's sandbox can't narrow it; D-050), and Maven and npm tests that start their own local servers can't run inside the sandbox yet. Also on macOS, the sandbox now and then refuses a tool's connection to the proxy with `EPERM` (a Seatbelt bug with loopback rules). JHarness then runs the build or git command again, up to 3 runs within its timeout, and logs a `process.retried` event.

git can `fetch`, `pull` (fast-forward only), `push` and `clone` over HTTPS through the same proxy and rules; ssh and plain-HTTP remotes don't work. `pull`, `push` and `clone` ask first, and plan mode denies them; push never forces. git never runs a credential helper or asks for a password, so to push or reach private repositories, map a host to a token in your own `settings.json`: `"git": {"credentials": {"github.com": "GITHUB_TOKEN"}}`. The token comes from the environment or `~/.jharness/secrets`, is sent only to that host, and is shown as `***` in output and logs. Project settings can't set it.

`jharness --offline` (or `"offline": true` in settings) is for working without a connection. The proxy then denies everything no `allow` rule matches, and only model endpoints on this machine or your LAN are allowed automatically. Maven runs with `-o`, Gradle with `--offline` and npm from its cache, and git `fetch`, `pull`, `push` and `clone` fail at once. JHarness never checks for updates.

The agent can also call web APIs with `api.http` and read pages as text with `api.fetch`, both through the same proxy and rules. GET and HEAD need only the network rules; POST, PUT, PATCH and DELETE ask first ("always" saves a rule such as `POST api.example.com`), and plan mode denies them. Responses are read up to 10 MiB and returned as text up to 200,000 characters by default. Change both in `settings.json` with `"http": {"maxResponseBytes": N, "maxChars": N}` (64 KiB to 50 MiB, and 1,000 to 1,000,000 characters); binary bodies aren't returned. Calls time out after 30 s by default (`timeouts.http`, at most `timeouts.httpMax`, 2 min). No secrets or cookies are added to requests, and headers and bodies stay out of the session log. To debug a request, set `"http": {"logHeaders": true, "logBodies": true}`. The `http.request` and `http.response` events then carry headers and bodies. Secret headers such as `Authorization`, `Cookie` and API keys are shown as `[redacted]` unless you also set `"redactSecrets": false`. Bodies are logged as they are, so turn this off again when you are done.

The security suites attack a real session: prompt injection, a malicious repository, sandbox escapes and runaway code. They run with `mvn install` and in the native smoke binary (`jharness-smoke --security`). `docs/security-suites.md` lists every case and what contains it.

## License

The source code is under the [MIT License](LICENSE).

Release binaries will be built with Oracle GraalVM, which is distributed under the GraalVM Free Terms and Conditions (GFTC). GFTC allows free redistribution but not for a fee, so anyone who wants to sell binaries should build with GraalVM Community Edition 25.1 or later instead.
