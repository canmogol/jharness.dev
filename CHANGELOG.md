# Changelog

<!-- last-commit: 399e428971aeff12ec65fb8aecec1c2cbb48f58d -->

## 2026-11-22 · 016 `api.http` and web fetch

- Added `api.http({method, url, headers, body, timeoutMs})`, which returns `{status, url, headers, contentType, body, bytes, truncated, binary}`, and `api.fetch({url, timeoutMs})`, which returns a page as Markdown-like text (`{status, url, title, text, truncated}`). Both also take a bare URL string.
- Every request goes through the egress proxy and the network rules. GET and HEAD need only the rules. POST, PUT, PATCH and DELETE ask first, "always" saves a rule such as `POST api.example.com`, and plan mode denies them.
- Requests are checked before anything is sent. Only `http` and `https` URLs without user info are accepted, bodies are capped at 1 MiB, and `Host`, framing and `Proxy-*` headers are refused. JHarness sets its own `User-Agent` and never adds secrets or cookies to a request.
- Redirects are followed by hand, up to 5, for GET and HEAD only; each hop goes through the proxy again. Proxy refusals carry an `X-JHarness-Proxy: denied` header and come back as "blocked by the network rules: <host>". Without a session proxy, nothing is sent.
- By default a response is read up to 10 MiB and returned as text up to 200,000 characters. Change these with `http.maxResponseBytes` (64 KiB to 50 MiB) and `http.maxChars` (1,000 to 1,000,000). Binary bodies come back as type and size only.
- Calls time out after `timeouts.http` (default 30 s, at most `timeouts.httpMax`, 2 min) and Esc cancels them. Waiting doesn't count against the run's wall clock.
- `http.request` and `http.response` events record method, URL, status, type, size, time and redirects. They leave out headers and bodies unless the debug switches `http.logHeaders` and `http.logBodies` are on. Logged secret headers such as `Authorization`, `Cookie` and API keys show as `[redacted]` unless `http.redactSecrets` is false.
- jsoup 1.24.1 turns HTML into text. It drops scripts, styles, navigation and hidden elements, and makes links absolute. The native smoke binary checks jsoup and an `api.fetch` through the proxy.
- `HttpClients` moved from `jharness-llm` to `jharness-net` (`dev.jharness.net.http`).
- Decision: `api.http` and `api.fetch` (D-054).

## 2026-11-22

- Decision: a read-only web session viewer, served on `127.0.0.1` with a session token and built in Phase 4 next to headless mode. It doesn't gate v0.1. Approvals from the browser and phone access come after v0.1 (D-053).

## 2026-11-21 · 015 Command-execution checks

- Git no longer runs programs from a repository's own config. Filter drivers defined in `.git/config` (found with `git config --show-scope` before each call) are switched off; global ones such as git-lfs keep working. Commit and tag signing, signature display, submodule recursion and automatic gc and maintenance are off; `status` and `diff` ignore submodules.
- Git runs with `GIT_WORK_TREE` set to the workspace root, so `core.worktree` can't point it at another folder.
- Each executable is pinned to the file it resolved to (file key, size, modification time). If the file changes during the session, the run is refused with a `process.refused` event. Tools in folders anyone can write to (`o+w`) are skipped.
- Child processes get only named environment variables; any other name is refused, and JHarness's own `TMPDIR` is no longer passed on.
- Argument lists are capped: 4,096 arguments, 131,072 UTF-8 bytes each, 1 MiB in total.
- A `CommandExecutionTest` suite tests each check against a hostile repo config, and the native smoke binary checks that git ignores a repo's filter and `gpg.program`. The git hook test now puts its marker where the sandbox lets git write, so it can't pass by accident.
- Decision: command-execution checks (D-052).

## 2026-11-20 · 014 Shift+Enter and a higher step limit

- Shift+Enter and Ctrl+Enter insert a newline at the prompt in terminals that report them. While the `> ` prompt reads a line, JHarness turns on xterm `modifyOtherKeys` level 1 and resets it afterward, so approvals and Esc during a turn see ordinary keys. Alt+Enter and Ctrl-J still work; Enter sends.
- `/help` and the README name Shift+Enter; the README shows how to map it to Alt+Enter in VS Code, kitty and iTerm2.
- `agent.maxSteps` now defaults to 100 steps per turn (was 25) and accepts 1 to 500 (was up to 200).
- The TUI tests use in-process terminals, so output can't lag behind a PTY copy thread.
- Decision: Shift+Enter through `modifyOtherKeys`, not the kitty keyboard protocol, and the new step limit (D-051).

## 2026-11-20 · 013 Maven, Gradle and npm in the sandbox

- Added `api.maven` (`compile`, `test`, `verify`, `package`, `clean`), `api.gradle` (`build`, `test`, `check`, `assemble`, `clean`) and `api.npm` (`ci`, `install`, `test`, `run <script>`). Requests are typed, with no free-form arguments, and builds run only in trusted projects after approval; "always" saves rules such as `maven test`.
- Fixed flags: Maven in batch mode, Gradle with `--no-daemon`, npm `ci` and `install` with `--ignore-scripts`. `./mvnw` and `./gradlew` run when present, and `./gradlew` must pin `distributionSha256Sum`.
- Git and build calls take `timeoutMs` (default 2 min, at most 10). Time spent in a process no longer counts against the run's wall clock.
- The OS sandbox opens one network path for build tools, the proxy port: a Seatbelt rule on macOS, and on Linux a bridge mode of the JHarness binary (`__proxy-bridge`) that forwards to the proxy over a Unix socket. git and `rg` still have no network.
- Build tools get the proxy settings from a generated Maven settings file, `JAVA_TOOL_OPTIONS` and `npm_config_*` variables, never from argv. The proxy password is shown as `***` in captured output. Java build tools use IPv4 so Seatbelt lets them reach the proxy.
- Build tools may write to their caches (`~/.m2/repository`, parts of `~/.gradle`, `~/.npm`), but not to their config files.
- On macOS, Gradle runs may use any loopback port for Gradle's daemon. Seatbelt can't narrow this, so a Gradle build there can also accept LAN connections; it still can't connect out to other hosts. A test pins this behavior on macOS and checks that Linux stays closed.
- The native smoke binary runs an npm script through the proxy (via the bridge on Linux) and checks that a direct connection fails.
- The README has a table of the IDs used in the docs (D, Q, AC, T, US, P, Article).
- Decisions: build tools in the sandbox (D-049, which moves Gradle's proxy settings from D-025's argv into `JAVA_TOOL_OPTIONS`); Gradle on macOS gets loopback, a recorded exception that Article V now names (D-050, constitution 1.5).

## 2026-11-19 · 012 OS sandbox for child processes

- Every child process (git, `rg`) now runs in an OS sandbox: Seatbelt (`sandbox-exec`) on macOS, bubblewrap (`bwrap`) on Linux.
- Children can write only to the workspace (not `.jharness/`) and a private per-session temp folder passed as `TMPDIR`; they can't read `~/.jharness`, `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud` or `~/.netrc`, and have no network, loopback included.
- The session probes the sandbox at start and emits `sandbox.os`; without a working sandbox, process operations fail with the reason and fix, search falls back to Java, and nothing runs unsandboxed. `process.started` records the mechanism.
- Linux CI installs bubblewrap; the README documents a bwrap-only AppArmor profile for Ubuntu 24.04, which blocks unprivileged user namespaces by default.
- The native smoke binary checks a sandboxed run and a blocked write.
- Decision: Seatbelt on macOS, bubblewrap on Linux, fail closed, with a fixed hidden-folder list (D-048).

## 2026-11-19 · 011 Egress proxy and network rules

- Added the `jharness-net` module: `~/.jharness/network.rules` and `.jharness/network.rules` (`default`, `allow`, `deny`; host, `*.suffix`, IP or CIDR, optional `:port`), evaluated as in D-025, with built-in denies for loopback, private, link-local and metadata addresses unless a rule names them.
- Added the egress proxy on `127.0.0.1` with per-session credentials: HTTPS `CONNECT` tunnels and plain HTTP, every resolved IP checked and the connection pinned, and `network.allowed` / `network.blocked` / `network.asked` events.
- Every HTTP client now goes through the proxy; each model profile's endpoint is allowed automatically.
- Unmatched hosts ask in the TUI (`a` saves the allow to the project rules file); added `/network list|allow|deny|remove|reload`.
- Native builds enable the `http` and `https` URL protocols, which the JDK client needs to use a proxy; the native smoke binary checks a model turn and a `CONNECT` through it.
- Decision: Phase 2 runs proxy, then OS sandbox, then build tools; the proxy is built in-house; "always" network answers go to the project rules file (D-047).


## 2026-11-19 · Phase 1 gate

- Recorded the by-hand P1 gate procedure and both attempts in `docs/p1-gate.md`. Attempt 1 (`qwen2.5-coder-7b-instruct`) fixed the bug but committed only after a second prompt; attempt 2 (`qwen/qwen3.6-27b`, after specs 008–010) passed. Phase 1 gate passed.
- Decision: the gate passes on a local model, and the live Claude run becomes a Phase 4 release check (D-045).

## 2026-11-19 · 010 Thinking text and a quieter session log

- Streamed `model.delta` and the new `model.thinking_delta` go to the TUI but not to the session log; `model.completed` now carries the whole reply as `text` and the thinking as `thinking`, each capped at 65,536 characters.
- Thinking text is captured from `reasoning_content` / `reasoning` fields, a leading `<think>…</think>` (tags may be split across chunks) and Claude thinking blocks. It is never run or sent back to the model.
- Added `ui.thinking` and `/thinking` to show the thinking dim in the TUI.
- Decision: streamed deltas stay off the log, and thinking is kept apart from the reply (D-046).

## 2026-11-19 · 009 TUI usability

- Alt+Enter or Ctrl-J inserts a newline at the prompt; Enter sends.
- Replies are colored as Markdown line by line; step lines, errors and diffs are colored; `NO_COLOR` turns colors off.
- The start line shows the session log path; added `ui.events` (`none`, `steps`, `all`) and `/events`.
- A failed step shows its error and line number; `loop.result` carries both.

## 2026-11-19 · 008 Loop notice for extra code blocks

- When a reply has more than one `js` block, the result sent back tells the model how many didn't run, and `loop.code` records `ignoredBlocks`. Found in P1 gate attempt 1, where a dropped commit block was reported as done.

## 2026-11-19

- Claude may run `git switch` and `gh pr create` in this repo.


## 2026-11-18 · 007 Checkpoints, undo and interrupt

- Added per-turn checkpoints that save a file's content before the agent first changes it; `/undo` and `/rewind N` restore files and cut the conversation back, refusing when a file changed since (`--force` overrides).
- File changes now carry a unified diff: shown in the approval question in `ask` mode, printed after the change in other modes, and with `/diff` for the last turn.
- Esc stops a running turn at once (model stream and running block), with stop reason `interrupted`; the session log stays intact on interrupt.
- Decision: checkpoints hold file contents per turn, and undo restores files and the conversation, not git history (D-044).

## 2026-11-18 · 006 Project trust, layered settings and JHARNESS.md

- Added the project trust question, remembered in `~/.jharness/trusted.json`; headless runs and unknown folders are untrusted.
- Settings now merge user, project and session layers; an untrusted project's settings can only tighten (`files.deny`, `permissions.mode: plan`), and ignored keys are reported in a `settings.ignored` event.
- Workspace and user `JHARNESS.md` files are read into the system prompt, each capped at 32 KB.
- Decision: trust answers, settings layers and the untrusted-only-tightens rule (D-043).

## 2026-11-18 · 005 CLI and TUI

- Added the `jharness` command: JLine TUI with streamed replies, code steps, approvals (`y`/`a`/`n`) and history; headless `-p` mode; `--model`, `--cwd`, `--version`, `--help`.
- Added `/model` to list and switch profiles mid-session.
- API keys now come from the environment, then a 0600 `~/.jharness/secrets` file; key values never reach events or the log.
- CI builds the native `jharness` binary on all three targets and checks a headless run and the TUI; the README covers building and running.
- Decision: secrets from the environment, then `~/.jharness/secrets` (D-042).

## 2026-11-18 · 004 Permissions and file policy

- Added the permission engine with `ask`, `accept-edits`, `plan` and `bypass` modes; approvals are asked inside a running block with the watchdog paused.
- "Always for this project" approvals are saved per project in the user folder; repository files can't add rules.
- Added the file policy: read-only mounted folders and a deny list that applies in every mode, including search with `rg` and the Java fallback.
- Decisions: permanent approvals live in the user folder (D-040); the four permission modes, with the file policy in every mode (D-041).

## 2026-11-18 · 003 Agent loop with RunJS

- Added the agent loop: stream the reply, run the first JS block in the isolate, send back the result or the error with its line number, and stop at `agent.maxSteps`.
- Added `api.d.ts` (checked against the host methods) and a system prompt with examples and workspace context.
- Added the replay and recording providers, a replay test of the P1 gate scenario, and live loop tests against Ollama and Claude when configured.

## 2026-11-18 · 002 Host foundations

- Added the host modules: event bus with host-stamped envelopes and the JSONL session log, settings with validation, workspace confinement and a permission gate, and `ProcessRunner` with git and `rg` plus a Java search fallback.
- Added the isolate manager (one engine per agent, fresh context per run, limits, watchdog) and the `api` facade.
- Added the OpenAI-compatible and Claude providers with model profiles, a stub model server, and an architecture test that keeps process and network access in their modules.
- Added a native smoke binary and a CI matrix for the three targets.
- Claude may now commit and push feature branches in this repo.
- Decisions: session logs live in the user folder (D-038); git runs local operations only until the egress proxy (D-039).

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
