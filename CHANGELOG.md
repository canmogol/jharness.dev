# Changelog

<!-- last-commit: 8fe6290d12881b692e3fcef8934f00e185d01c55 -->

## 2026-11-24 · 034 JS hooks

- Hooks are JavaScript files in `~/.jharness/hooks/` or a trusted project's `.jharness/hooks/`. A file defines any of `preTool(call)`, `postTool(call)` and `stop(turn)`.
- `preTool` runs before every api call of every agent, before the permission check. `{block: "reason"}` refuses the call, and a hook that fails blocks the call too.
- `postTool` sees each call's outcome and result. `stop` can return `{continue: "message"}` to make the agent go on, at most 3 times a turn.
- Hooks only tighten: a call they let through still asks as usual. Each runs in its own sandbox, and its own api calls run no hooks.
- `/hooks` lists the hooks, the trust question lists a project's hooks, and the UI shows blocks, continues and errors.
- `examples/.jharness/hooks/guard.js` blocks pushes to `main` or `master` and hand edits of lock files, and asks the agent to run the tests when it changed files without doing so. CI runs it in the native binary.
- Decision: JS hooks (D-073).

## 2026-11-24 · 033 Held background questions

- When a background subagent needs your approval, its question waits instead of failing, and only that subagent pauses. A line above the prompt says `[sub-2] is waiting for you`, `/agents` shows it as `waiting`, and `/switch sub-2` asks the question.
- After 10 minutes (`agent.backgroundQuestionWait`), or when the subagent is stopped, the question is denied as before. `"backgroundQuestions": "deny"` and `-p` deny at once.
- Decision: held background questions (D-072).

## 2026-11-24 · 032 Switch to a subagent

- `/switch sub-2` makes the prompt talk to that subagent: it shows `sub-2> `, and each message continues the subagent's own conversation. `/switch main` goes back, and the subagent keeps its conversation.
- While you're switched to a subagent, its approval questions are asked as in a normal turn. Other background subagents still have theirs denied.
- Its reply and steps show as in `/follow`. Esc stops its current turn. Switching to a running subagent waits for its turn to end, asking its questions meanwhile.
- Subagents stay open after their task, stopped ones too, so you can talk to them later. Up to 16 idle ones are kept, and quitting closes them all.
- Decision: switching to a subagent (D-071).

## 2026-11-24 · 031 Readable session paths

- Each project's folder under `~/.jharness/sessions/`, `projects/` and `checkpoints/` is now its path as a readable name plus a short hash, such as `users-xyz-projects-jharness.dev-2787870`. A long path keeps its last 80 characters, which name the project.
- Session logs are named by their UTC start time, such as `s-2026-11-24T105226Z-18a9.jsonl`, so they sort by start. `jharness log` orders sessions that started in the same second by their last write.
- Folders from older versions are left in place and no longer read; the README has the command that deletes them. Saved "always" approvals in them no longer apply, so JHarness asks once more. Trust and confirmed project tools are unaffected.
- Decision: readable session paths (D-070).

## 2026-11-24 · 030 Follow a subagent

- `/follow sub-1` shows a running background subagent the way the main loop is shown: its reply as the model writes it, its thinking when `/thinking` is on, then its steps, results and diffs. `/follow` alone picks the only running one.
- Esc stops watching; the subagent keeps running, and `/stop` stops it. When it ends, its end line prints and the prompt returns.
- In sessions without a model profile, such as replays, a subagent's model text is now labelled with its own name instead of the main agent's.
- Decision: following a subagent (D-069).

## 2026-11-24 · 029 Prompt examples

- The system prompt's examples grew from 4 to 11 short tasks, aimed at small local models.
- The new ones explore a project, find a tool that runs the tests and keep only the failures, run one test class, check `git status` before committing, fetch a page, start a background subagent and collect its result, and record progress with `api.emit`.
- `ExamplesTest` runs every example in a real session against a small test project, so an example that stops working fails the build.
- Decision: git and `rg` stay in Java code, because approvals per operation, use in untrusted projects, injection defences, secrets, undo and structured results need host code (D-068).

## 2026-11-24 · 028 Tool definitions

- Maven, Gradle and npm now come from JSON definition files. The first start writes the shipped ones from `examples/.jharness/tools/` to `~/.jharness/tools/`; after that, editing, adding or deleting a file there changes the tools.
- Each operation is an argument template over typed fields (`flag`, `string` with a pattern, `enum`, `integer`, `folder`). Unknown keys, bad patterns and unused fields are refused.
- A definition can't name a shell, set the loader, path or proxy variables, make sensitive home folders writable, or pass a value that starts with `-`.
- A trusted project's `.jharness/tools/` can add, replace or turn off tools, but can't name a wrapper, make home folders writable or allow loopback.
- The trust question lists the project's tools, and each start names them. A new or changed project tool asks again; `-p` leaves it out and says so.
- `api.tool(name, request)` and `api.tools()` replace `api.maven`, `api.gradle` and `api.npm`. The system prompt lists the tools, and custom agents can name one tool as `tool:NAME`.
- "Always" approvals for tools also record the program that runs, so builds saved as "always" ask once more.
- `jharness tools` lists the tools; `show NAME` prints one, `check` validates every file and `reset [NAME]` writes a shipped file again.
- `jharness -p tools` and `-p log` now run as prompts instead of failing as subcommand names.
- Security case REPO-11 checks hostile project tool files.
- Decisions: tool definitions (D-066) and `api.tool` (D-067).

## 2026-11-24 · Tool definitions decided

- Decided, not built yet: Maven, Gradle and npm will be JSON definition files, and git and `rg` stay in code. On first run the files are copied into `~/.jharness/tools/`; trusted projects can add their own in `.jharness/tools/` (D-066).
- A definition maps typed, pattern-checked fields to fixed arguments, never free-form ones, and may not name a shell. Only the user's own files may name a workspace wrapper or allow loopback.
- The trust question will list a project's tools, and a new or changed project tool asks again.
- `api.tool(name, request)` and `api.tools()` will replace `api.maven`, `api.gradle` and `api.npm` (D-067).
- Constitution 1.6 amends Articles IV and V to match. D-008, D-009, D-050 and D-061 are marked as amended.
- `examples/.jharness/tools/` holds `maven.json`, `gradle.json` and `npm.json`, which give today's operations exactly. The reviewer agent example moved to `examples/.jharness/agents/reviewer/`.

## 2026-11-24 · 027 Background subagents

- `api.spawnAgent({task, background: true})` returns `{agent, status: "running"}` at once; the subagent runs on its own thread. At most `agent.subagentsRunning` run at once (default 4, 1 to 16).
- `api.waitAgent(name)` collects a result (default wait 10 minutes, at most 1 hour; a timeout returns `status: "running"`), `api.agents()` lists the session's subagents and `api.stopAgent(name)` stops one. Replies the main agent doesn't collect come at the start of its next prompt, once each (`subagent.delivered`).
- A background subagent never asks: a change that needs approval is denied with a reason, unless a saved rule or the permission mode allows it. Questions that come while no turn runs, such as network questions, are denied the same way.
- Esc stops the turn but not background subagents; closing the session, including the end of `-p`, stops them (`stopReason: "stopped"`). `/undo` and `/rewind` wait until none is running.
- The UI shows a background subagent's start and end lines, above the prompt while you type. `/agents` lists the subagents, `/agents NAME` shows one's last 20 events, and `/stop NAME` or `/stop all` stops them.
- `jharness log --agent NAME` (repeatable; `main` is the main agent) and `--source host|guest` filter the timeline.
- Decision: background subagents (D-065).

## 2026-11-24 · 026 Subagents

- `api.spawnAgent({task, agent, model, maxSteps})`, or a task string, runs a subagent and returns `{agent, reply, stopReason, steps}`. It starts with only the task, in its own isolate with the `subagent` sandbox limits, and its prompt says it must end with a complete reply.
- It goes through the session's permission gate, so approvals, plan mode and the main agent's `tools` apply. A custom agent named in `agent` narrows it further with its `tools`, `plan`, `maxSteps` and `helpers.js`.
- `model` names a model profile; it wins over the custom agent's `model`, which wins over the session's. `api.models()` lists the profiles (name, provider, model id, notes) and the current one, never base URLs or key variable names.
- A subagent can't start subagents. `agent.subagentsPerTurn` caps them per turn of the main agent (default 10, 1 to 1000); `agent.subagentMaxSteps` sets their step limit (default 100, 1 to 500), never above `agent.maxSteps`.
- Esc stops a running subagent with the turn. Its events carry `sub-N` and the main agent's turn, so `/diff` and `/undo` include its changes. `subagent.started` and `subagent.completed` frame each run; the UI and `jharness log` show `[sub-1]` on its lines.
- CI's native step runs a subagent and calls `api.models()`.
- Decision: subagents (D-064).

## 2026-11-23 · 025 Custom agents

- A custom agent is a folder `NAME/` in `~/.jharness/agents/` or the project's `.jharness/agents/`; a project agent replaces a user agent of the same name.
- `agent.json` takes `description` (required), `model`, `tools` (the `api` methods it may call), `maxSteps` and `plan`. `prompt.md` is added to the system prompt, and the functions in `helpers.js` are defined in every block, in the same isolate and limits. Both are at most 32 KB; links are refused.
- `jharness --agent NAME` starts a session as the agent; its `model` is used unless `--model` names another. `/agent` lists the agents and the TUI's first line names the running one.
- Agents only narrow: methods outside `tools` are denied before the permission engine, `plan: true` is plan mode, `maxSteps` replaces the session's limit. Project agents load only in trusted projects.
- `session.started` records `customAgent` and `agentSource`.
- `examples/agents/reviewer/` ships as a working read-only reviewer; CI runs it in the native binary.
- Decision: custom agents (D-063).

## 2026-11-23 · Phase 2 gate and Linux CI

- The Phase 2 gate passed: all 35 security cases are contained on linux-amd64, linux-aarch64 and macos-aarch64, on the JVM and in the native binary, with RUN-08 on macOS as the recorded exception (`docs/p2-gate.md`).
- CI had been red on Linux since spec 015: the hosted runner image leaves node and npm writable by everyone, so JHarness refused npm and four security cases were skipped. CI now makes those files owner-writable first; JHarness's check is unchanged.
- CI retries Maven Central when it answers 429.

## 2026-11-23 · 024 `/` and Tab list the commands

- In the terminal UI, Tab on a line holding only `/` prints the `/help` text above the prompt and keeps the `/`.
- Tab after part of a command completes it (`/mo` → `/model`); when several match, they are listed with their descriptions.
- The names and descriptions come from the `/help` text, so the two can't drift apart. Tab elsewhere completes nothing, as before.
- The pseudo-terminal check of the native binary in CI types `/` and Tab.
- Decision: `/` and Tab list the commands (D-062).

## 2026-11-23 · 023 Build parameters

- Builds still take no free-form arguments, but now have typed parameters that each become one fixed flag.
- Maven: a new `install` operation; `skipTests` on `package`, `verify` and `install` (`-DskipTests`, refused together with `tests`); `profiles` such as `dev,!ci` (`-P`); `threads` from 1 to 32 or `1C` to `4C` (`-T`).
- Gradle: `skipTests` on `build` and `check` (`-x test`), `parallel` (`--parallel`) and `continue` (`--continue`). npm: `omitDev` on `ci` and `install` (`--omit=dev`).
- Booleans must be JSON booleans and strings must match their patterns. The approval question shows the parameters (`maven package · skipTests, profiles dev`); "always" still saves the operation alone.
- `api.d.ts` documents every field. A real Maven `install` with `skipTests`, a profile and threads is tested in the OS sandbox.
- Decision: typed build parameters (D-061).

## 2026-11-23 · 022 Escape control characters in live output

- The terminal UI prints through one escaping writer. Control characters (except newline and tab), DEL, C1 controls and bidi controls from replies, thinking, diffs, errors, approval questions, events and paths show as `\e`, `\xNN` or `\uNNNN`; carriage returns are dropped. Only JHarness's own colors reach the terminal raw.
- `jharness -p` escapes its stdout and stderr the same way, including characters split across writes.
- The session log keeps the exact text.
- Decision: escape control characters in live output (D-060).

## 2026-11-23 · 021 Debug mode, `jharness log` and crash reports

- `--debug` shows every event and the model's thinking from the start and prints stack traces; with `-p`, every logged event also goes to stderr as a JSON line.
- `jharness log` lists the workspace's sessions, newest first (`--all`, `--limit N`). `jharness log SESSION` (an id, a unique prefix, `last` or a `.jsonl` path) prints one event per line; `--type PREFIX`, `--full` and `--json` change the output. Control characters from logged text are escaped.
- An internal error writes a crash report to `~/.jharness/crash/` and exits 3. The report has the version, OS, stack trace and the types of the last 30 events, but no event bodies; the home folder shows as `~`. The newest 20 are kept and nothing is sent anywhere. The crash is logged as `session.crashed`.
- Each model response is logged as `model.response` with its status, request id and allow-listed headers (`retry-after*`, `x-should-retry`, `content-type`, rate-limit headers).
- The CI check of the native `jharness` binary runs `jharness log`.
- Decision: debug mode, `jharness log` and crash reports (D-059).

## 2026-11-23 · 020 Model retries and rate limits

- A failed model request is sent again when nothing from it reached the user yet: HTTP 408, 409, 429 and every 5xx (529 included), a failed or dropped connection, and an Anthropic `overloaded_error`, `rate_limit_error` or `api_error` that arrives before any text or thinking. An `x-should-retry` header overrides the status rule.
- Never retried: other 4xx, the response timeout, a tunnel the egress proxy refused, a missing API key, Esc, and any failure after text reached the user.
- The wait is the server's `retry-after-ms` or `retry-after` (seconds or an HTTP date); without one it is 1 s, 2 s, 4 s and so on, each cut to a random 50–100%. A server asking for longer than `retryMaxWait` fails at once with the wait it asked for.
- New profile keys: `retries` (0 to 10, default 4), `retryMaxWait` (1s to 10m, default 60s) and `responseTimeout` (5s to 30m, default 120s, the old fixed value).
- Each retry publishes `model.retry` and shows `▸ HTTP 429 from anthropic, retrying in 7 s (attempt 2 of 5)`; each attempt has its own `model.request` with `attempt`. `model.error` comes once, at the end, with `(gave up after N attempts)`. Esc ends a wait at once.
- The native smoke binary checks a 429 retry.
- Decision: model retries and rate limits (D-058).

## 2026-11-23 · 019 Build retry on proxy refusal

- On macOS, when Seatbelt refuses a Maven, Gradle or npm run's connection to the proxy port, JHarness runs the same approved command again, up to 3 runs in all. A refusal is a line with `EPERM` or "Operation not permitted" and the proxy's exact port; any other failure, a success or a timeout is never repeated.
- All runs share the call's timeout, and a retry starts only with at least 1 s left. Nothing is asked again.
- git network operations use the same retry. Each retry emits a `process.retried` event (`executable`, `attempt`, `attempts`, `reason`, `remainingMs`).
- The npm proxy checks in the tests and the native smoke binary now fail the way npm does, so they get the retry; this was the macOS smoke flake.
- The README glossary lists the security case IDs (`INJ-NN`, `REPO-NN`, `ESC-NN`, `RUN-NN`) and the terms the docs use. The RUN-08 macOS exception is accepted for the Phase 2 gate.
- Decision: retry runs the sandbox refused at the proxy port (D-057).

## 2026-11-22 · 018 Security test suites

- 35 attack cases in four suites run against a real session (egress proxy, OS sandbox, permission engine, isolate): prompt injection (`INJ-01`–`INJ-10`), malicious repository (`REPO-01`–`REPO-10`), sandbox escape (`ESC-01`–`ESC-06`) and runaway code (`RUN-01`–`RUN-09`).
- Each case assumes a model that obeys the attack and writes the hostile JavaScript itself. A case passes only when the host contains it.
- Every case runs in its own world: a fake home with canary secrets, a git workspace with a canary `.env`, a folder next to it, and an attacker server on loopback. Shared checks: no canary reaches the model, the session log or the attacker; the attacker gets no connections; nothing outside the workspace changes.
- In the injection suite the user denies every prompt. In the repository and escape suites the user approves everything except new network hosts, so containment doesn't depend on the user.
- The cases run in `mvn install` (`SecuritySuiteTest`, one test per ID) and in the native smoke binary (`jharness-smoke --security` runs only them). A case that can't run on a machine reports `skip`, which never counts as a pass.
- Fixed: a CPU limit that tripped inside the regex engine's backtracking was reported as limit `unknown`. It is now `sandbox.MaxCPUTime`.
- Recorded exception on macOS, `RUN-08`: a build-script grandchild orphaned before the timeout outlives the process-tree kill. It stays in the Seatbelt sandbox, and the case checks that it can't write outside the workspace or connect out. The case reports `exception` on macOS.
- `docs/security-suites.md` lists every case, what contains it and how to run the suites.
- Decision: security test suites (D-056).

## 2026-11-22 · 017 Git network operations and offline mode

- `api.git` adds `fetch`, `pull` (fast-forward only), `push` (never forced, no deletes or tags) and `clone`. `fetch` needs only the network rules; `pull`, `push` and `clone` ask first, and plan mode denies them. The prompt shows the URL git will use, e.g. `git push origin main → https://github.com/a/b.git`.
- Only HTTPS remotes work. Every other transport, redirects and cookie files are off, and a remote that isn't `https://` is refused before anything is sent. Calls are refused when the repository's own config sets credential helpers, `core.askPass`, `core.sshCommand`, `protocol.*`, cookie files, or upload or receive packs.
- git runs in the OS sandbox with only the proxy port open. The proxy settings reach git through `GIT_CONFIG_*` environment entries, never argv, and no askpass, credential helper or terminal prompt runs.
- `"git": {"credentials": {"github.com": "GITHUB_TOKEN"}}` in the user settings maps a host to a secret from the environment or `~/.jharness/secrets`. The token is sent only to that host and shows as `***` in output and events. Project settings can't set it, and a mapped secret that isn't set stops start-up.
- Offline mode: `--offline`, or `"offline": true` in any settings layer, an untrusted project's included. The proxy denies everything no `allow` rule matches, model endpoints are allowed automatically only on loopback or private addresses, Maven runs with `-o`, Gradle with `--offline` and npm offline, and git network ops fail at once. `session.started` records `offline` and the start line shows it.
- On macOS, Seatbelt now and then refuses a sandboxed connect to the proxy port with `EPERM`. git network runs that never reach the proxy retry up to 3 times; the README says to re-run a build that fails this way.
- The native smoke binary clones from a local HTTPS git server through the proxy and checks that a denied host fails at the proxy.
- Decision: git network operations and offline mode (D-055).

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
