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

Phases 0 to 3 are done; their gates passed (`docs/p0-results.md`, `docs/p1-gate.md`, `docs/p2-gate.md`, `docs/p3-gate.md`). Phase 2 added build tools in the OS sandbox, the egress proxy and network rules, `api.http`, git network operations, offline mode, the security suites, model retries and debugging tools (specs 011–024). Phase 3 (extensibility) added custom agents, subagents, tool definition files, hooks, slash commands, MCP, plan mode and the optional action reviewer (specs 025–038). Phase 4 (context and release, v0.1) is under way (specs 039–051).

Evals on real repo tasks measure each release: `scripts/eval.py path/to/jharness --model PROFILE` runs the tasks in `evals/tasks/` in headless mode and scores them with hidden checks. `docs/evals.md` explains it and keeps the scores.

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

Long sessions keep going when the model's context fills up. Each profile has a `"contextWindow"` in tokens (default 32,768 for `openai-compatible`, 200,000 for `anthropic`; set it to what your model and server allow). When the conversation reaches 80% of the window minus `maxTokens`, the model first writes a summary of the older messages, which then replace them, and the line `▸ context compacted: about 27,000 → 3,100 tokens` says so. `/context` shows how full it is and `/compact` compacts now. `"agent": {"compactAt": 0.7}` compacts earlier, and `"autoCompact": false` turns it off. Turns inside a summary can no longer be rewound.

Claude profiles use Anthropic's prompt cache: the system prompt and the conversation so far are marked so the next request reads them from the cache, which is faster and costs less. `"promptCache": false` in the profile turns it off. Local OpenAI-compatible servers cache on their own. `jharness log` shows `cacheReadTokens` and `cacheWriteTokens` on each `model.completed`.

To see what a session costs, give the profile a `"price"` in US dollars per million tokens: `"price": {"input": 3, "output": 15}`, with `"cacheRead"` and `"cacheWrite"` if your provider charges other rates for cached prompt tokens (for Claude profiles they default to a tenth and 1.25 times the input price). JHarness ships no prices, since they change; take them from your provider's price list. Each request's `model.completed` then logs its `costUsd`. `/usage` shows this session's requests, tokens and cost for each agent, and `jharness usage` lists the last 30 days of this folder's sessions with a total (`--days N`, `--all` for every project, `jharness usage ID` for one session by agent, `--json`). A cost ending in `+` leaves out requests from a profile without a price.

Type `@` and a path in a prompt to attach a file or folder: `Why does @src/main/java/App.java fail?` Tab completes the path. The file's contents, or the folder's listing, go with the prompt, and `▸ attached src/main/java/App.java (2,310 characters)` says so. Paths follow the same rules as the agent's reads: the workspace and read-only mounts, never the deny list. Use `@"a file.txt"` for paths with spaces. Big files are cut at 50,000 characters, and one prompt attaches at most a quarter of the context window.

Then, in a project folder:

```
jharness                      # terminal UI; /help lists the commands
jharness --model claude       # another profile
jharness -p "explain src/"    # one prompt, no UI; changes are denied
jharness --debug              # every event and the thinking shown; stack traces on errors
jharness log                  # this folder's sessions, newest first
jharness log last             # the newest session as a timeline
jharness --resume             # continue this folder's last session (or --resume ID)
jharness --fork ID            # a new session from a copy of a saved one
jharness -p "fix the test" --output json --mode accept-edits   # for scripts and CI
jharness view                 # the last session in the browser, read-only (or view ID)
jharness usage                # tokens and cost of this folder's recent sessions
```

`-p` prints the reply by default (`--output text`). `--output json` prints one JSON object when the turn ends: `type` (`result`), `session`, `stopReason`, `reply`, `steps`, `error`, `exitCode`, `durationMs`, `usage` (requests and tokens, cache included, and `costUsd` when the profile has a price), `filesChanged`, `denied` (the actions refused, since no one can approve them) and `subagents`. `--output stream-json` prints every event as a JSON line as it happens, streamed reply chunks included, then the same result object. Both escape control characters the JSON way, so stdout stays valid JSON. The exit code is 0 when the turn finished, 1 when it failed or stopped early (such as at `agent.maxSteps`) and 2 for bad flags or settings, which still print a result object with `--output json`. `--mode ask|accept-edits|plan|bypass` sets the permission mode for the session, over your settings and the project's; with `-p`, anything still left to ask is denied.

To read a session in the browser, run `jharness view` (the last session, or `jharness view ID`), or type `/view` in the UI for the running one. It prints an address such as `http://127.0.0.1:49731/?t=…` to open on this machine. The page shows each turn's prompt, the reply as it streams (rendered as Markdown), steps, approvals, diffs, subagents and, on request, the thinking and every event. It is read-only: the browser can't send prompts or answer approvals. The viewer listens on `127.0.0.1` only and needs the token in the address; `jharness view` follows a running session's log until Ctrl-C and prints each event in the terminal as `jharness log` does (`--quiet` prints only the address); `/view` stops when you quit. From another machine, use an SSH port forward.

After each turn, JHarness saves the conversation next to the session log (`<session-id>.state.json`, mode 0600). `--resume` continues the last session of the folder, or the one whose id (or the start of it) you give, in the same session and log; the model gets the earlier turns back. `--fork` starts a new session from a copy and leaves the saved one as it was. Both work with `-p`, and the UI says which session it continues and shows its last reply. Background subagents, the todo list and plan mode start fresh, and turns from before the resume can't be undone with `/undo` or `/rewind`, since their file checkpoints belong to the earlier run.

The agent deletes with `api.deleteFile(path)` and `api.deleteFolder(path)`. A file delete is treated like a write: asked in `ask` mode, allowed in `accept-edits`, refused in plan mode, and its diff shows the removed lines. A folder delete always asks, even in `accept-edits`, unless you answered "always" for that folder before or the mode is `bypass`. The question says how many files go and lists the first 20. Every deleted file is saved first, so `/undo` and `/rewind` bring it back; empty folders inside a deleted folder don't come back. JHarness refuses, even if you approve:
- the workspace itself, `.git`, `.jharness` and mounted folders;
- paths outside the workspace and files on the deny list;
- folders holding a denied file, a link, a file over 10 MB or more than 1,000 files.

In the UI, Shift+Enter, Alt+Enter or Ctrl-J starts a new line and Enter sends. Type `/` and press Tab to list the commands, or Tab after `/mo` to complete `/model`. After a command and a space, Tab lists its choices and Tab again cycles through them: `/events` levels, `/model` profiles, subagent names for `/agents`, `/follow`, `/stop` and `/switch`, turns for `/rewind`, and `/network` subcommands and the hosts in your rules. `/quit` and `/q` also leave. A turn runs up to 100 code steps; set `"agent": {"maxSteps": N}` (1 to 500) to change it. Every event goes to the session log printed at start (`tail -f` it); `/events all` or `"ui": {"events": "all"}` in settings also shows them inline. The log keeps each model reply, and its thinking text, whole on `model.completed`; `/thinking` or `"ui": {"thinking": "show"}` shows the thinking dim in the UI. `NO_COLOR=1` turns colors off, in the UI and in `jharness --help`, which is colored only on a terminal.

The prompt never closes while the main agent works. Its reply prints above the line you're typing, a whole line at a time, and the status line says `working`. A message you send during a turn is queued (`▸ queued: …`); when the turn ends, everything queued goes to the agent as your next message. Esc on the prompt stops the turn, and then the queue goes. When the main agent needs your approval during a turn, it never takes the prompt: a line says `▸ the main agent is waiting for you: …`, the turn waits, and `/answer main` (or just `/answer`) asks the question there. Esc denies it and stops the turn. Commands that would change the conversation say they wait for the turn to end: `/compact`, `/diff`, `/undo`, `/rewind`, `/model NAME`, `/plan`, `/switch NAME` and `/follow`. Others, such as `/agents`, `/usage` or `/stop`, work at once. `/exit` and Ctrl-D stop the turn and quit. On dumb terminals, turns run in the foreground as before.

On a real terminal a status line sits under the input: `claude (claude-opus-5-5) · 41% of 200K · ask · 1 running, 1 held · cache 77K read · $0.42 · turn 3 · shop`. It shows the model, how full the context is, the permission mode (`[plan]` in plan mode), background subagents, the last request's cache reads, the session's cost (with a price set), the turn (with `working` while the main agent works) and the workspace. `"ui": {"statusLine": false}` turns it off, and a list such as `["model", "context", "turn"]` picks the items and their order. It never shows on dumb terminals or in `-p`.

To write your own, put `~/.jharness/statusline.js` with a `status(s)` function that returns a string:

```js
function status(s) {
  return `${s.model} · ${s.context.percent}% · ${s.agents.running} running · ${s.line}`;
}
```

`s` has `profile`, `provider`, `model`, `context` (`tokens`, `window`, `percent`, `estimated`, `compactAt`), `mode`, `plan`, `agents` (`running`, `held`, `names`), `cache` (`readTokens`, `writeTokens`, `inputTokens`, `readTotal`), `costUsd` (or null), `turn`, `busy`, `workspace`, and `line`, the default line. It runs in its own sandboxed isolate with no `api`, after the events that change the line and at most every 250 ms. If it throws, hits a limit or returns something other than a string, the default line shows instead and the TUI says why once; a run over 1 second turns it off for the session.

Each project has one folder, named after its path with a short hash, in `~/.jharness/sessions/` (logs, such as `users-xyz-projects-app-2787870/s-2026-11-24T105226Z-18a9.jsonl`), `~/.jharness/projects/` (saved "always" approvals) and `~/.jharness/checkpoints/` (what `/undo` restores). Folders from older versions, named by 16 hex characters, are no longer read; delete them with `rm -rf ~/.jharness/{sessions,projects,checkpoints}/????????????????`.

`jharness log SESSION` prints a session one event per line: time, turn, type and its main fields. SESSION is an id, a unique prefix, `last` or a path to a `.jsonl` log. `--type network.` (repeatable) filters by type, `--agent sub-1` (repeatable; `main` is the main agent) by agent and `--source host|guest` by who wrote the event (JHarness, or agent code through `api.emit`), `--full` shows whole events, `--json` prints the raw lines and `--all` lists every project's sessions. Control characters from logged text are shown escaped (`\e`, `\n`). The UI and `-p` do the same with replies, diffs and errors (newlines and tabs stay), so a hostile reply or file can't rewrite your terminal. Each model response is logged as `model.response` with its status, request id and rate-limit headers; no other header is logged.

If JHarness itself fails, it writes a crash report to `~/.jharness/crash/`, prints its path and exits with code 3. The report has the version, OS, stack trace and the types of the last 30 events, but no event bodies, and your home folder shows as `~`. It never leaves your machine. Check it before attaching it to an issue, since exception messages can quote files. The newest 20 reports are kept.

Shift+Enter works in terminals that report it (xterm's `modifyOtherKeys`, which JHarness turns on only while the prompt reads, or CSI u). Where Shift+Enter sends a plain Enter, map it to Alt+Enter (`ESC` then `CR`):

| Terminal | Mapping |
| --- | --- |
| VS Code | In `keybindings.json`: `{"key": "shift+enter", "command": "workbench.action.terminal.sendSequence", "args": {"text": "\u001b\r"}, "when": "terminalFocus"}` |
| kitty | In `kitty.conf`: `map shift+enter send_text all \x1b\r` |
| iTerm2 | Settings → Profiles → Keys → Key Mappings: add Shift+Enter, action "Send Hex Code", `0x1b 0x0d` |

All network traffic goes through a proxy inside JHarness that applies `~/.jharness/network.rules` and the project's `.jharness/network.rules` (`default ask|allow|deny`, `allow X`, `deny X`; X is a host, `*.suffix`, IP or CIDR with an optional `:port`). Model endpoints from your profiles are allowed automatically; loopback and private addresses are denied unless a rule names them. `/network` lists and edits the rules.

In a trusted project the agent can run the tools defined in `~/.jharness/tools/`, each after your approval, with `api.tool(name, request)`; `api.tools()` lists them. The first start writes the shipped definitions there: Maven (`compile`, `test`, `verify`, `package`, `install`, `clean`), Gradle (`build`, `test`, `check`, `assemble`, `clean`), npm (`ci`, `install`, `test`, `run <script>`), Go (`build`, `test`, `vet`, `mod-tidy`, `mod-download`, `version`) and gofmt (`check`, `check-folder`, which change nothing). There are no free-form arguments, only typed parameters: skip tests (Maven `-DskipTests`, Gradle `-x test`), Maven profiles (`-P`) and threads (`-T`), Gradle `--parallel` and `--continue`, and npm `--omit=dev`. The approval question shows them. `./mvnw` and `./gradlew` are used when present; `./gradlew` must pin `distributionSha256Sum`. Builds may write only to the workspace and their caches (`~/.m2/repository`, `~/.gradle/caches` and friends, `~/.npm`), and reach the network only through the proxy and its rules, so add `allow repo.maven.apache.org`, `allow plugins.gradle.org` or `allow registry.npmjs.org` (or answer "always" when asked). On macOS, Gradle builds can also reach services on your Mac's loopback and accept connections from your LAN (Gradle's daemon needs loopback, and macOS's sandbox can't narrow it; D-050), and Maven and npm tests that start their own local servers can't run inside the sandbox yet. Also on macOS, the sandbox now and then refuses a tool's connection to the proxy with `EPERM` (a Seatbelt bug with loopback rules). JHarness then runs the build or git command again, up to 3 runs within its timeout, and logs a `process.retried` event.

Tools are JSON files you can edit, add or delete: `maven.json` lists Maven's operations as argument templates over typed fields (`flag`, `string` with a pattern, `enum`, `integer`, `folder`), so you can drop `install` or add `dependency:tree`, and a `cargo.json` could add Rust without changing JHarness. `examples/.jharness/tools/` has the shipped files (an existing `~/.jharness/tools/` gets new ones with `jharness tools reset go`); `jharness tools` lists the tools as a table (`NAME SOURCE EXECUTABLE STATUS OPERATIONS`, where `STATUS` says `ok` or `not found on PATH`), `jharness tools show NAME` prints one, `jharness tools check` validates the files and `jharness tools reset [NAME]` writes a shipped one again. A definition can't name a shell, set the loader, path or proxy variables, or pass anything but checked field values. A trusted project can add or replace tools in its own `.jharness/tools/`; the trust question lists them, JHarness asks again when one is new or changed, and `-p` leaves unconfirmed ones out. Only your own files may name a wrapper script (`./mvnw`), make home folders writable or allow loopback.

Go takes one package pattern (`packages`, default `./...`) and, for `test`, a `run` filter (`-run`); a value can't start with `-`, so no go flag such as `-exec` or `-toolexec` gets in. Go fetches modules only from `proxy.golang.org` through the egress proxy (`GOPROXY` has no `direct`), checks them against `sum.golang.org`, and never starts git or other version control (`GOVCS=*:off`, `-buildvcs=false`). A `toolchain` line in `go.mod` fails rather than downloading another Go (`GOTOOLCHAIN=local`), and `go env -w` settings are ignored (`GOENV=off`). The module cache is `~/go/pkg/mod` and the build cache `~/.cache/go-build`, on macOS too; with `offline` on, builds use the cache or `vendor/` (`GOPROXY=off`). JHarness ships no allow rules, so the first download asks at the proxy. `examples/network.rules` lists the Go, Maven and npm hosts to copy into `~/.jharness/network.rules`, and shows how to add a private module proxy. An `env` value in a definition that starts with `~/` means a folder in your home.

git and `rg` are not tool definitions and stay built in: git needs a different approval per operation (`status` never asks, `push` always does), works before you trust a project, injects tokens only for their host and masks them, and records its writes for `/undo`; `rg` gets your file deny list and its output is parsed into structured matches. A JSON file can't hold those rules safely (D-068).

git can `fetch`, `pull` (fast-forward only), `push` and `clone` over HTTPS through the same proxy and rules; ssh and plain-HTTP remotes don't work. `pull`, `push` and `clone` ask first, and plan mode denies them; push never forces. git never runs a credential helper or asks for a password, so to push or reach private repositories, map a host to a token in your own `settings.json`: `"git": {"credentials": {"github.com": "GITHUB_TOKEN"}}`. The token comes from the environment or `~/.jharness/secrets`, is sent only to that host, and is shown as `***` in output and logs. Project settings can't set it.

`jharness --offline` (or `"offline": true` in settings) is for working without a connection. The proxy then denies everything no `allow` rule matches, and only model endpoints on this machine or your LAN are allowed automatically. Maven runs with `-o`, Gradle with `--offline` and npm from its cache, and git `fetch`, `pull`, `push` and `clone` fail at once. JHarness never checks for updates.

The agent can also call web APIs with `api.http` and read pages as text with `api.fetch`, both through the same proxy and rules. GET and HEAD need only the network rules; POST, PUT, PATCH and DELETE ask first ("always" saves a rule such as `POST api.example.com`), and plan mode denies them. Responses are read up to 10 MiB and returned as text up to 200,000 characters by default. Change both in `settings.json` with `"http": {"maxResponseBytes": N, "maxChars": N}` (64 KiB to 50 MiB, and 1,000 to 1,000,000 characters); binary bodies aren't returned. Calls time out after 30 s by default (`timeouts.http`, at most `timeouts.httpMax`, 2 min). No secrets or cookies are added to requests, and headers and bodies stay out of the session log. To debug a request, set `"http": {"logHeaders": true, "logBodies": true}`. The `http.request` and `http.response` events then carry headers and bodies. Secret headers such as `Authorization`, `Cookie` and API keys are shown as `[redacted]` unless you also set `"redactSecrets": false`. Bodies are logged as they are, so turn this off again when you are done.

MCP servers go in your own `~/.jharness/settings.json`: `"mcp": {"servers": {"files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."], "writable": ["~/.npm"]}, "docs": {"url": "https://mcp.example.com/mcp", "tokenSecret": "DOCS_TOKEN"}}}`. A stdio server runs in the OS sandbox with the network only through the proxy; an HTTP server's endpoint is allowed automatically. The agent lists tools with `api.mcp({list: true})` and calls one with `api.mcp({server, tool, input})`, which asks each time unless you answer "always". Project settings can't add servers. `/mcp` lists them.

An optional action reviewer asks a decision model, through the System One API, whether each change fits your request. Turn it on in your own `~/.jharness/settings.json`: `"reviewer": {"enabled": true, "url": "https://api.typesafe.ai/v1/systemone", "model": "jev-latest", "apiKeyEnv": "SYSTEMONE_API_KEY"}`. DigitalOcean serves the same API at `https://inference.do-ai.run/v1/systemone` (model `typesafe-jev-1.13.0`), and a server on your own machine, such as Laya, needs no key. The reviewer only tightens: below `askBelow` (0.90) it makes an allowed change ask you, even in bypass mode, and below `denyBelow` (0.20) it refuses it. If it is down, slow (`timeoutMs`, 3 s) or answers badly, you are asked (`"onError": "deny"` refuses instead). Reads are never sent. With a hosted reviewer, each change, its diff and your request leave your machine; the start-up line says so. A project can't set or turn off the reviewer.

`/plan` turns plan mode on: the agent can read but every change is refused, and the prompt shows `[plan]`. When the agent has a plan it calls `api.proposePlan`, which shows the plan and asks whether to carry it out; yes turns plan mode off. The agent keeps a todo list with `api.todos`, shown as it changes and with `/todos`.

Slash commands are files in `~/.jharness/commands/` or a trusted project's `.jharness/commands/`. `NAME.md` is a prompt, with `$ARGUMENTS` replaced by what you type after `/NAME`. `NAME.js` defines `run(args)`, which returns a prompt or `{print: text}` and can use the api first. A command declares choices for Tab with `args: patch, minor, major` in the front matter, or `const args = ['fix', 'check']` in a script. A file named like a built-in command or alias, such as `q.md`, is not used, and the TUI says so at the start. `/commands` lists them, Tab completes them, and `jharness -p "/review src"` works too. `examples/.jharness/commands/` has `review.md` and `changed.js`.

Hooks are JavaScript files in `~/.jharness/hooks/` (yours) or a trusted project's `.jharness/hooks/`. A file defines any of `preTool(call)`, which runs before every api call of every agent and can return `{block: "reason"}`; `postTool(call)`, which sees the result; and `stop(turn)`, which can return `{continue: "message"}` to make the agent go on, at most 3 times a turn. Hooks run in their own sandbox and can only tighten: a call they let through still asks as usual, and a pre-tool hook that fails blocks the call. `/hooks` lists them. `examples/.jharness/hooks/guard.js` blocks pushes to `main` and hand edits of lock files, and asks the agent to run the tests when it changed files without doing so.

Custom agents live in `.jharness/agents/NAME/` (shared with the repo) or `~/.jharness/agents/NAME/` (yours). `agent.json` gives a `description` and optionally a `model` profile, `tools` (the `api` methods it may call), `maxSteps` and `"plan": true`. `prompt.md` adds instructions to the system prompt, and the functions in `helpers.js` are defined in every block it runs. Start one with `jharness --agent NAME`; `/agent` lists them. An agent can only narrow what JHarness may do, and a project's agents load only once you trust the project. `examples/.jharness/agents/reviewer/` is a read-only reviewer: copy it into `.jharness/agents/` and run `jharness --agent reviewer`.

The agent can hand part of a task to a subagent with `api.spawnAgent({task, agent, model})`, where `model` is a profile from `api.models()`. A subagent starts with only the task, runs in its own sandbox and returns its final reply. It has the same permissions as the session (each change still asks), narrowed by the custom agent it runs as, and it can't start subagents itself. A turn can start up to 10 and each runs up to 100 steps; set `"agent": {"subagentsPerTurn": N, "subagentMaxSteps": N}` (1 to 1000, 1 to 500) to change them. In the UI its lines show its name, such as `[sub-1]`, and `/undo` includes its changes.

With `background: true` the subagent runs on its own and the main agent's turn ends, so you can keep using the main chat; no turn waits for a subagent. When one finishes and no main turn is running, the main agent takes a turn with its reply by itself, behind the prompt, so you can keep typing; otherwise the reply comes with the next turn. (On a dumb terminal that happens only at an empty prompt.) After 10 such turns in a row it waits for a message from you (`"agent": {"autoTurns": N}`, 0 to 100; 0 never starts one), and Esc on one stops the chain until you write again. With `-p` the run goes on until no background subagent is left, each reply taking a turn, and the result is the last turn's. Up to 4 run at once (`"agent": {"subagentsRunning": N}`, 1 to 16). When a background subagent needs your approval, its question waits and never interrupts you: a line above the prompt says `[sub-2] is waiting for you`, the status line counts it as held (the main agent's own held question counts too), `/agents` shows it as `waiting`, and `/answer` (or `/answer sub-2`) asks it right there and leaves you in the main chat. After 10 minutes (`"agent": {"backgroundQuestionWait": "30m"}`) or with `"backgroundQuestions": "deny"`, and always with `-p`, it is denied unless a saved rule or the permission mode allows it. In the UI you see its start and end lines; `/agents` lists the session's subagents, `/agents sub-2` shows one's last events, `/follow sub-2` watches it live like the main loop (its reply streams, then its steps; Esc stops watching, not the subagent), and `/stop sub-2` or `/stop all` stops them. `/switch sub-2` makes the prompt talk to that subagent (it shows `sub-2> `): your messages continue its conversation, its questions are asked as in a normal turn, and Esc stops its turn; `/switch main` goes back. Subagents stay open after their task, up to 16 idle ones. Esc stops the turn but not background subagents; quitting stops them all. Every event in the session log names its agent (`main`, `sub-1`, …), so `jharness log last --agent sub-2` shows one agent's work.

The security suites attack a real session: prompt injection, a malicious repository, sandbox escapes and runaway code. They run with `mvn install` and in the native smoke binary (`jharness-smoke --security`). `docs/security-suites.md` lists every case and what contains it.

Two manual checks cover what CI doesn't: `node scripts/viewer-check.mjs BIN` opens the web viewer in Chromium, Firefox, WebKit and Safari with Playwright and checks the page, its console and the Markdown, and `scripts/tui-tmux-check.py BIN` checks the TUI at several terminal sizes in tmux. They never run in CI; run them after changing the viewer or the terminal UI and before each release, on Linux and macOS. `docs/manual-checks.md` says how to set them up.

## License

The source code is under the [MIT License](LICENSE).

Release binaries will be built with Oracle GraalVM, which is distributed under the GraalVM Free Terms and Conditions (GFTC). GFTC allows free redistribution but not for a fee, so anyone who wants to sell binaries should build with GraalVM Community Edition 25.1 or later instead.
