# JHarness user guide

JHarness is a terminal coding agent in one native executable. The model never runs commands itself: it writes JavaScript that runs in a sandbox and reaches your project only through the `api` ([docs/api.md](api.md)). Every change asks you first unless you say otherwise. To add your own agents, commands, hooks, tools or MCP servers, see [docs/extensions.md](extensions.md).

## Contents

1. [Install](#install)
2. [Models and keys](#models-and-keys)
3. [The terminal UI](#the-terminal-ui)
4. [Permissions and trust](#permissions-and-trust)
5. [Files, undo and clear](#files-undo-and-clear)
6. [Build tools](#build-tools)
7. [The network](#the-network)
8. [git](#git)
9. [The web](#the-web)
10. [MCP servers and the reviewer](#mcp-servers-and-the-reviewer)
11. [Subagents](#subagents)
12. [Sessions, logs and usage](#sessions-logs-and-usage)
13. [Headless runs](#headless-runs)
14. [Troubleshooting](#troubleshooting)
15. [Settings reference](#settings-reference)

## Install

On Linux (x86-64, ARM64) or an Apple silicon Mac:

```bash
curl -fsSL https://raw.githubusercontent.com/canmogol/jharness.dev/master/scripts/install.sh | sh
```

It downloads the newest release for your system, checks its SHA-256 against the release's `SHA256SUMS`, and puts `jharness` in `~/.local/bin`, with no sudo. `JHARNESS_VERSION=0.0.1` picks a version, and `JHARNESS_INSTALL_DIR` another folder. With Homebrew:

```bash
brew install canmogol/jharness/jharness
```

Every release file has a build attestation: `gh attestation verify FILE --repo canmogol/jharness.dev` shows that this repository's release workflow built it from the tag.

Every process JHarness starts (git, `rg`, Maven, Gradle, npm, Go, MCP servers) runs in an OS sandbox: Seatbelt on macOS (built in), bubblewrap on Linux (`sudo apt install bubblewrap`). Without it JHarness runs no processes and searches with its Java fallback. `rg` (ripgrep) is used when installed.

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

To build from source instead, see the README.

## Models and keys

Model profiles go in `~/.jharness/settings.json`. `model` names the default; `--model NAME` or `/model NAME` picks another.

```json
{
  "model": "claude",
  "models": {
    "claude": { "provider": "anthropic", "model": "claude-opus-5-5", "apiKeyEnv": "ANTHROPIC_API_KEY" },
    "local": { "provider": "openai-compatible", "baseUrl": "http://localhost:11434/v1", "model": "qwen2.5-coder:7b" }
  }
}
```

[examples/settings.json](../examples/settings.json) is a fuller example.

- **Providers.** `anthropic` (base URL `https://api.anthropic.com`, key in `ANTHROPIC_API_KEY` unless `apiKeyEnv` says otherwise) and `openai-compatible` (Ollama, llama.cpp, vLLM, LM Studio and hosted servers; `baseUrl` is required).
- **Keys.** A key never goes in a settings file; JHarness refuses one that looks like a key. `apiKeyEnv` names a variable, read from the environment or from `~/.jharness/secrets`, which has `NAME=value` lines and must be yours with mode 0600.
- **Retries.** A failed request is sent again when nothing from it has reached you yet: rate limits (429), overloads (529 and other 5xx), and a server that can't be reached or drops the connection. JHarness waits as long as the server's `retry-after` asks, or 1 s, 2 s, 4 s and so on, and shows `▸ HTTP 429 from anthropic, retrying in 7 s (attempt 2 of 5)`. Esc stops the wait. `retries` (0 to 10, default 4), `retryMaxWait` (default `60s`; a server asking for longer fails at once) and `responseTimeout` (default `120s`; raise it for a slow local model, since a timed-out request isn't retried) change it.
- **Context.** `contextWindow` is the model's window in tokens (default 32,768 for `openai-compatible`, 200,000 for `anthropic`). When the conversation reaches 80% of the window minus `maxTokens`, the model first summarizes the older messages, which then replace them: `▸ context compacted: about 27,000 → 3,100 tokens`. `/context` shows how full it is and `/compact` compacts now. `"agent": {"compactAt": 0.7}` compacts earlier and `"autoCompact": false` turns it off. Turns inside a summary can't be rewound.
- **Prompt cache.** Claude profiles mark the system prompt and the conversation so the next request reads them from Anthropic's cache, which is faster and costs less. `"promptCache": false` turns it off. Local servers cache on their own.
- **Cost.** Give a profile a `price` in US dollars per million tokens, from your provider's price list: `"price": {"input": 3, "output": 15}`, with `cacheRead` and `cacheWrite` if they differ (for Claude, a tenth and 1.25 times the input price). JHarness ships no prices, since they change.

## The terminal UI

Run `jharness` in a project folder (or `--cwd DIR`). The README's [What it does](../README.md#what-it-does) lists every command and option, and `/help` lists the commands.

- **Typing.** Enter sends; Shift+Enter, Alt+Enter or Ctrl-J starts a new line. `/` and Tab list the commands; after a command and a space, Tab lists its choices: event levels, profiles, subagent names, turns, `/network` subcommands and hosts. `/quit`, `/q` and Ctrl-D leave.
- **Attaching files.** `@` and a path attaches a file or folder: `Why does @src/main/java/App.java fail?` Tab completes the path, and `@"a file.txt"` takes spaces. The contents, or the folder's listing, go with the prompt, and `▸ attached src/main/java/App.java (2,310 characters)` says so. The deny list applies. Big files are cut at 50,000 characters, and one prompt attaches at most a quarter of the context window.
- **The prompt stays live.** The agent's reply prints above the line you're typing, a whole line at a time. A message sent during a turn is queued (`▸ queued: …`) and goes when the turn ends. Esc stops the turn. When the main agent needs your approval, a line says `▸ the main agent is waiting for you: …` and `/answer` asks the question; Esc denies it and stops the turn.
- **Commands that wait.** `/clear`, `/compact`, `/diff`, `/undo`, `/rewind`, `/model NAME`, `/plan`, `/switch NAME` and `/follow` wait for the turn to end and say so. `/agents`, `/usage`, `/stop` and the others work at once.
- **Steps.** A turn runs up to 100 code steps (`"agent": {"maxSteps": N}`, 1 to 500).
- **Events and thinking.** `/events all` (or `"ui": {"events": "all"}`) shows every event inline. `/thinking` (or `"ui": {"thinking": "show"}`) shows the model's thinking, dim.
- **Layout.** On a real terminal the input starts at the bottom and stays there, and a status line sits under it: `claude (claude-opus-5-5) · 41% of 200K · ask · 1 running, 1 held · cache 77K read · $0.42 · turn 3 · shop`. `"ui": {"statusLine": false}` hides it, and a list such as `["model", "context", "turn"]` picks the items. A `~/.jharness/statusline.js` script can write your own line ([docs/extensions.md](extensions.md#status-line-script)).
- **Colors.** `NO_COLOR=1` turns them off.

Shift+Enter works in terminals that report it (xterm's `modifyOtherKeys`, or CSI u). Where it sends a plain Enter, map it to Alt+Enter (`ESC` then `CR`):

| Terminal | Mapping |
| --- | --- |
| VS Code | In `keybindings.json`: `{"key": "shift+enter", "command": "workbench.action.terminal.sendSequence", "args": {"text": "\u001b\r"}, "when": "terminalFocus"}` |
| kitty | In `kitty.conf`: `map shift+enter send_text all \x1b\r` |
| iTerm2 | Settings → Profiles → Keys → Key Mappings: add Shift+Enter, action "Send Hex Code", `0x1b 0x0d` |

## Permissions and trust

Reads never ask. Changes ask according to the permission mode, set with `"permissions": {"mode": ...}`, `--mode` or `/plan`:

| Mode | Edits and file deletes | Folder deletes, git writes, builds, HTTP writes, MCP calls |
| --- | --- | --- |
| `ask` (default) | Ask | Ask |
| `accept-edits` | Allowed | Ask |
| `plan` | Refused | Refused |
| `bypass` | Allowed | Allowed |

- **Answers.** Each question offers yes, no and always. "Always" saves a rule for this project in `~/.jharness/projects/`, such as a folder, a git operation, a tool operation or `POST api.example.com`.
- **Plan mode.** `/plan` turns it on: the agent reads but every change is refused, and the prompt shows `[plan]`. When it has a plan it calls `api.proposePlan`, which shows the plan and asks whether to carry it out; yes turns plan mode off. The agent's todo list shows as it changes and with `/todos`.
- **Trust.** The first time you open a folder in the UI, JHarness asks whether you trust it, and lists the tools and hooks the project defines. The answer is kept in `~/.jharness/trusted.json`; `-p` never asks and treats a folder not marked trusted as untrusted. A trusted project's `.jharness/settings.json` may loosen settings (permission mode, sandbox limits, mounts, models) and its tools, hooks, commands and agents load. An untrusted project can only make JHarness stricter: its deny globs, `plan` mode and `offline: true` count, nothing else.
- **Only yours.** A project can never set `git` credentials, `mcp` servers or the `reviewer`, even when trusted.
- **The deny list.** `.env`, `.env.*`, `*.pem`, `*.key`, `id_rsa*`, `id_ecdsa*`, `id_ed25519*` and `.git/` internals can't be read, written or listed, and never reach the model. `"files": {"deny": [...]}` adds globs.
- **Mounts.** `"files": {"mounts": ["/abs/path"]}` lets the agent read folders outside the workspace, never change them.
- **Headless.** With `-p`, anything left to ask is denied; the result lists it.

## Files, undo and clear

- **Reads.** `api.readFile` reads whole UTF-8 files up to 1 MB, or a part: `{fromLine, lines}` or `{offset, length}`. `api.readBytes` reads any file, binary too, as base64, hex or text in a charset, at most 1 MB per call.
- **Writes and deletes.** `api.writeFile`, `api.editFile` and `api.deleteFile` are edits. `api.deleteFolder` always asks, even in `accept-edits`, unless you answered "always" for that folder or the mode is `bypass`; the question says how many files go and lists the first 20. JHarness refuses, even if you approve:
  - the workspace itself, `.git`, `.jharness` and mounted folders;
  - paths outside the workspace and files on the deny list;
  - folders holding a denied file, a link, a file over 10 MB or more than 1,000 files.
- **Undo.** Every changed or deleted file is saved first. `/diff` shows the last turn's changes, `/undo` restores them and removes the turn from the conversation, and `/rewind N` undoes every turn after turn N. Subagents' changes are included. Empty folders inside a deleted folder don't come back.
- **Clear.** `/clear` empties the conversation so the model starts fresh and leaves your files as they are, unlike `/rewind 0`. The session, its log, plan mode and subagents go on; the todo list goes with the conversation. Undo can't go back past a clear or a compaction.
- **Where it's kept.** Saved files live in `~/.jharness/checkpoints/`, only for the session that saved them. When a session opens, this project's checkpoints of other sessions untouched for 30 days are removed (`"files": {"checkpointDays": N}`, 0 to 3650; 0 keeps them for ever).

## Build tools

In a trusted project the agent runs the tools defined in `~/.jharness/tools/`, each after your approval, with `api.tool(name, request)`; `api.tools()` lists them. The first start writes the shipped definitions:

| Tool | Operations | Typed options |
| --- | --- | --- |
| Maven | `compile`, `test`, `verify`, `package`, `install`, `clean` | skip tests (`-DskipTests`), profiles (`-P`), threads (`-T`) |
| Gradle | `build`, `test`, `check`, `assemble`, `clean` | skip tests (`-x test`), `--parallel`, `--continue` |
| npm | `ci`, `install`, `test`, `run <script>` | `--omit=dev`; install scripts never run |
| Go | `build`, `test`, `vet`, `mod-tidy`, `mod-download`, `version` | one package pattern (default `./...`), a test `-run` filter |
| gofmt | `check`, `check-folder` | changes nothing |

- **No free-form arguments.** Only typed fields, which the approval question shows. A value can't start with `-`, so no flag gets in.
- **Wrappers.** `./mvnw` and `./gradlew` are used when present; `./gradlew` must pin `distributionSha256Sum`.
- **The sandbox.** Builds may write only to the workspace and their caches (`~/.m2/repository`, `~/.gradle/caches`, `~/.npm`, `~/go/pkg`, `~/.cache/go-build`), and reach the network only through the proxy and its rules. When a build fails on a refused write, the result carries a `hint` naming the folders it may write.
- **Hosts.** JHarness ships no allow rules, so the first download asks at the proxy. [examples/network.rules](../examples/network.rules) lists the Maven, Gradle, npm and Go hosts to copy into `~/.jharness/network.rules`.
- **Go.** Modules come only from `proxy.golang.org` through the proxy (`GOPROXY` has no `direct`) and are checked against `sum.golang.org`. Go never starts git or other version control (`GOVCS=*:off`), a `toolchain` line in `go.mod` fails rather than downloading another Go (`GOTOOLCHAIN=local`), and `go env -w` settings are ignored (`GOENV=off`). Offline, builds use the module cache or `vendor/`.
- **macOS.** Gradle builds can also reach services on your Mac's loopback and accept LAN connections (Gradle's daemon needs loopback, and Seatbelt can't narrow it; D-050). Maven and npm tests that start local servers can't run inside the sandbox yet. Seatbelt now and then refuses a connection to the proxy with `EPERM`; JHarness then runs the command again, up to 3 times, and logs `process.retried`.
- **Managing them.** `jharness tools` lists the tools (`NAME SOURCE EXECUTABLE STATUS OPERATIONS`), `jharness tools show NAME` prints one, `jharness tools check` validates the files and `jharness tools reset [NAME]` writes a shipped one again. When a newer JHarness ships a tool you don't have, or one that differs from yours, the start says so once.
- **Your own.** Tools are JSON files you can edit, add or delete: a `cargo.json` adds Rust without changing JHarness ([docs/extensions.md](extensions.md#tool-definitions)).

git and `rg` stay built in, since their rules don't fit a JSON file (D-068): git asks per operation and records its writes for undo, and `rg` gets your deny list.

## The network

All traffic goes through a proxy inside JHarness that applies `~/.jharness/network.rules` and the project's `.jharness/network.rules`:

```
default ask
allow repo.maven.apache.org
allow *.npmjs.org
deny 10.0.0.0/8
allow localhost:8080
```

- **Rules.** `default ask|allow|deny`, then `allow X` or `deny X`, where X is a host, `*.suffix`, an IP or a CIDR, with an optional `:port`. An untrusted project's file counts only for `deny` lines.
- **Automatic.** Model endpoints from your profiles are allowed. Loopback and private addresses are denied unless a rule names them.
- **Asking.** With `default ask`, a new host asks; "always" saves an `allow` line.
- **`/network`** lists the rules, and `/network allow X`, `deny X`, `remove X` and `reload` change them.
- **Offline.** `jharness --offline` (or `"offline": true`) denies everything no `allow` rule matches, and allows only model endpoints on this machine or your LAN. Maven runs with `-o`, Gradle with `--offline`, npm and Go from their caches, and git `fetch`, `pull`, `push` and `clone` fail at once.
- **No phoning home.** JHarness never checks for updates.

## git

- **Local operations.** `status`, `diff`, `log`, `show` and `branch` never ask. `add`, `commit` and `restore` ask, and undo covers them.
- **Remotes.** `fetch`, `pull` (fast-forward only), `push` and `clone` use HTTPS through the proxy and its rules; ssh and plain-HTTP remotes don't work. `pull`, `push` and `clone` ask, plan mode denies them, and push never forces.
- **Tokens.** git never runs a credential helper or asks for a password. To push or reach private repositories, map a host to a secret in your own settings: `"git": {"credentials": {"github.com": "GITHUB_TOKEN"}}`. The token is sent only to that host and shows as `***` in output and logs.

## The web

- **`api.http`.** Calls web APIs. GET and HEAD need only the network rules. POST, PUT, PATCH and DELETE ask ("always" saves a rule such as `POST api.example.com`), and plan mode denies them.
- **`api.fetch`.** Reads a page as text: headings, links, lists and code, without scripts or menus.
- **Limits.**
  - Responses are read up to 10 MiB and returned up to 200,000 characters (`"http": {"maxResponseBytes": N, "maxChars": N}`).
  - Binary bodies aren't returned.
  - Calls time out after 30 s (`timeouts.http`, at most `timeouts.httpMax`, 2 min).
- **Privacy.** No secrets or cookies are added. Headers and bodies stay out of the log unless you set `"http": {"logHeaders": true, "logBodies": true}`. Secret headers then show as `[redacted]` unless `"redactSecrets": false`.

## MCP servers and the reviewer

- **MCP.** Servers go in your own `settings.json` under `mcp.servers` ([docs/extensions.md](extensions.md#mcp-servers)). A stdio server runs in the OS sandbox with the network only through the proxy; an HTTP server's endpoint is allowed automatically. Each call asks unless you answer "always". `/mcp` lists the servers and their tools.
- **The action reviewer.** Optional. A decision model behind the System One API reviews each change against your request, and can only tighten. Turn it on in your own settings:

  ```json
  "reviewer": {"enabled": true, "url": "https://api.typesafe.ai/v1/systemone", "model": "jev-latest", "apiKeyEnv": "SYSTEMONE_API_KEY"}
  ```

  - **Scores.** Below `askBelow` (0.90) an allowed change asks you, even in bypass mode. Below `denyBelow` (0.20) it is refused.
  - **Failures.** If the reviewer is down, slow (`timeoutMs`, 3 s) or answers badly, you are asked (`"onError": "deny"` refuses instead).
  - **Other servers.** DigitalOcean serves the same API at `https://inference.do-ai.run/v1/systemone` (model `typesafe-jev-1.13.0`), and a server on your own machine needs no key.
  - **What leaves.** Reads are never sent. With a hosted reviewer, each change, its diff and your request leave your machine; the start-up line says so.

## Subagents

The agent can hand part of a task to a subagent with `api.spawnAgent({task, agent, model})`.

- **What it gets.** A subagent starts with only the task, runs in its own sandbox and returns its final reply. It has the session's permissions, so each change still asks, narrowed by the custom agent it runs as. It can't start subagents.
- **Limits.**
  - A turn starts up to 10 subagents (`agent.subagentsPerTurn`).
  - Each runs up to 100 steps (`agent.subagentMaxSteps`).
  - Up to 4 run in the background at once (`agent.subagentsRunning`).
- **Background.** With `background: true` the main turn ends and you keep chatting. When a subagent finishes, the main agent takes a turn with its reply by itself; after 10 such turns in a row it waits for you (`agent.autoTurns`, 0 never starts one). With `-p` the run goes on until no background subagent is left.
- **Its questions.** A background subagent's approval question never interrupts you. A line says `[sub-2] is waiting for you`, the status line counts it as held, and `/answer sub-2` asks it. After 10 minutes (`agent.backgroundQuestionWait`), with `"backgroundQuestions": "deny"`, and always with `-p`, it is denied unless a rule or the mode allows it.
- **Commands.**
  - `/agents` lists the subagents, and `/agents sub-2` shows one's last events.
  - `/follow sub-2` watches one live; Esc stops watching, not the subagent.
  - `/switch sub-2` talks to it, and `/switch main` goes back.
  - `/stop sub-2` or `/stop all` stops them.
- **Lifetime.** Subagents stay open after their task, up to 16 idle ones. Quitting stops them all.

## Sessions, logs and usage

- **The log.** Every event goes to a session log in `~/.jharness/sessions/<project>/`, printed at start, so you can `tail -f` it. Each project's folder is named after its path with a short hash.
- **`jharness log`** lists this folder's sessions, newest first.
  - `jharness log SESSION` prints one event per line. SESSION is an id, a unique prefix, `last` or a `.jsonl` path.
  - `--type network.` and `--agent sub-1` filter (both repeatable), `--source host|guest` picks who wrote the event, `--full` shows whole events, `--json` the raw lines and `--all` every project.
  - Control characters are shown escaped, so a hostile reply or file can't rewrite your terminal.
- **Resume and fork.**
  - After each turn the conversation is saved next to the log (mode 0600).
  - `--resume[=ID]` continues the last session of the folder, or the one you name, in the same log.
  - `--fork[=ID]` starts a new session from a copy.
  - Background subagents, the todo list and plan mode start fresh, and earlier turns can't be undone.
- **The browser view.**
  - `jharness view [ID]`, or `/view` for the running session, prints an address such as `http://127.0.0.1:49731/?t=…`.
  - The page shows each turn's prompt, the streamed reply as Markdown, steps, approvals, diffs, subagents and, on request, the thinking and every event.
  - It is read-only, listens on `127.0.0.1` only and needs the token. From another machine, use an SSH port forward.
  - `jharness view` follows a running session until Ctrl-C and prints each event as `jharness log` does (`--quiet` prints only the address); `/view` stops when you quit.
- **Usage.**
  - `/usage` shows this session's requests, tokens and cost for each agent.
  - `jharness usage` lists the last 30 days of this folder's sessions with a total. `--days N`, `--all` for every project, `jharness usage ID` for one session and `--json` change it.
  - A cost ending in `+` leaves out requests from a profile without a price.
- **Crash reports.**
  - If JHarness itself fails, it writes a report to `~/.jharness/crash/`, prints its path and exits with code 3.
  - The report has the version, OS, stack trace and the last 30 event types, without event bodies, and your home shows as `~`.
  - It never leaves your machine; check it before attaching it to an issue.

## Headless runs

```
jharness -p "explain src/"                                     # print the reply
jharness -p "fix the test" --output json --mode accept-edits   # for scripts and CI
jharness -p "/review src"                                      # slash commands work too
```

- **`--output text`** (the default) prints the reply.
- **`--output json`** prints one object when the run ends. It has `type` (`result`), `session`, `stopReason`, `reply`, `steps`, `error`, `exitCode` and `durationMs`. `usage` has the requests and tokens, and `costUsd` with a price. `filesChanged`, `denied` (what was refused, since no one can approve) and `subagents` follow.
- **`--output stream-json`** prints every event as a JSON line as it happens, then the same result object.
- **Exit codes.**
  - 0: the turn finished.
  - 1: it failed or stopped early (such as at `agent.maxSteps`).
  - 2: bad flags or settings.
  - 3: JHarness crashed.
- **Permissions.** `--mode` sets the mode; anything still left to ask is denied.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| "bubblewrap is not installed" or "needs unprivileged user namespaces"; no processes run | Install bubblewrap; on Ubuntu 24.04 also add the AppArmor profile under [Install](#install) |
| Start-up stops with a settings error | Unknown keys and bad values stop start-up; the message names the file, key and allowed values. Check against the [settings reference](#settings-reference) |
| "secrets don't go in settings files" | Put the key in the environment or `~/.jharness/secrets` and name the variable with `apiKeyEnv` |
| A build can't download dependencies | The proxy asked and was denied, or `default deny` is set: add the hosts from `examples/network.rules`, or answer "always" |
| A build fails writing a cache folder | The tool may write only the workspace and its own folders; the result's `hint` names them. Add the folder to the tool's `writable` in your own copy, or point the build at an allowed one |
| `jharness tools` says `not found on PATH` | Install the tool; JHarness looks it up on `PATH` |
| A project's tools, hooks or commands don't load | The project isn't trusted. The answer is in `~/.jharness/trusted.json`: remove the folder's entry and start `jharness` there to be asked again |
| Shift+Enter sends the message | Map it to Alt+Enter (see [The terminal UI](#the-terminal-ui)) or use Ctrl-J |
| HTTP 429 or 529 from the model | JHarness retries; raise `retries` or `retryMaxWait`, or wait |
| A slow local model times out | Raise the profile's `responseTimeout` |
| The conversation forgets early work | It was compacted; `/context` shows how full it is, `agent.compactAt` changes when |
| Old `~/.jharness/sessions/` folders named by 16 hex characters | Older versions made them and they are no longer read: `rm -rf ~/.jharness/{sessions,projects,checkpoints}/????????????????` |

`jharness --debug` shows every event and the thinking, and prints stack traces on errors. To report a bug, attach the crash report or the output of `jharness log last --json` after reading it, since both can quote your files.

## Settings reference

Settings live in `~/.jharness/settings.json`. A trusted project's `.jharness/settings.json` is merged over it key by key, and `files.deny` lists are joined. Durations are written like `30s`, `10m` or `2h`; sizes like `256KB` or `512MB`.

| Key | What it holds | Defaults and ranges |
| --- | --- | --- |
| `model` | The default profile's name | — |
| `models` | Named profiles: `provider`, `model`, `baseUrl`, `apiKeyEnv`, `maxTokens`, `contextWindow`, `retries`, `retryMaxWait`, `responseTimeout`, `promptCache`, `price`, `notes` | `maxTokens` 8192; see [Models and keys](#models-and-keys) |
| `permissions` | `mode`: `ask`, `accept-edits`, `plan` or `bypass` | `ask` |
| `agent` | `maxSteps`; `subagentsPerTurn`, `subagentMaxSteps`, `subagentsRunning`; `backgroundQuestions` (`wait`, `deny`) and `backgroundQuestionWait`; `autoCompact`, `compactAt`; `autoTurns` | 100 (1–500); 10, 100, 4 (1–16); `wait`, `10m`; true, 0.8; 10 (0–100) |
| `files` | `mounts` (absolute read-only folders), `deny` (globs), `checkpointDays` | none, none, 30 (0–3650) |
| `ui` | `events` (`none`, `steps`, `all`), `thinking` (`hide`, `show`), `statusLine` (true, false, or a list of `model`, `context`, `mode`, `agents`, `cache`, `cost`, `turn`, `workspace`) | `steps`, `hide`, true |
| `timeouts` | `process` and `processMax` (git and tools), `rg`, `http` and `httpMax`, `outputChars` (output returned to the model) | `2m`, `10m`, `60s`, `30s`, `2m`, 30,000 (at most 150,000) |
| `http` | `maxResponseBytes`, `maxChars`, `logHeaders`, `logBodies`, `redactSecrets` | 10 MiB (64 KiB–50 MiB), 200,000 (1,000–1,000,000), false, false, true |
| `sandbox` | Limits for agent code per profile, `main` and `subagent`: `engine.MaxIsolateMemory`, `sandbox.MaxHeapMemory`, `sandbox.MaxCPUTime`, `sandbox.MaxASTDepth`, `sandbox.MaxOutputStreamSize`, `sandbox.MaxErrorStreamSize`, `wallClock`, `watchdog.engineCloseAfter`, `watchdog.failAfter`, `engine.MemoryProtection` | main 512MB, 256MB, 30s, 100, 256KB, 256KB, 15m, 10s, 30s, `off`; subagent memory 256MB and 128MB. A limit can be changed, never removed |
| `git` | `credentials`: host to secret name. Your settings only | none |
| `offline` | true denies every connection no rule allows | false |
| `mcp` | `servers`: name to `{command, args, env, secretEnv, writable, timeout}` or `{url, tokenSecret, timeout}`. Your settings only | none; `timeout` 60s |
| `reviewer` | `enabled`, `url`, `model`, `apiKeyEnv`, `askBelow`, `denyBelow`, `timeoutMs`, `onError` (`ask`, `deny`). Your settings only | off; 0.90, 0.20, 3000, `ask` |

For example, `"sandbox": {"main": {"sandbox.MaxCPUTime": "60s"}}` gives the main agent's code a minute per step.

Other files in `~/.jharness/`:

| File | What it holds |
| --- | --- |
| `secrets` | `NAME=value` lines for keys and tokens, mode 0600 |
| `network.rules` | Your network rules |
| `tools/` | Tool definitions |
| `agents/`, `commands/`, `hooks/`, `statusline.js` | Your extensions ([docs/extensions.md](extensions.md)) |
| `trusted.json` | Your trust answers per folder |
| `sessions/`, `projects/`, `checkpoints/`, `crash/` | Logs and saved conversations, saved approvals, undo copies, crash reports |
