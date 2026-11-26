# Extending JHarness

JHarness loads six kinds of extension from plain files. Each kind has a working example in [`examples/`](../examples), and tests load every example, so the files shown here run as they are. The [user guide](user-guide.md) covers using JHarness; the [`api` reference](api.md) lists what agent code can call.

| Kind | Yours | A project's (once trusted) | Can do |
| --- | --- | --- | --- |
| [Custom agents](#custom-agents) | `~/.jharness/agents/NAME/` | `.jharness/agents/NAME/` | Add instructions and helpers; narrow the `api`, steps and mode |
| [Slash commands](#slash-commands) | `~/.jharness/commands/` | `.jharness/commands/` | Send a prompt, or run `api` calls and print |
| [Hooks](#hooks) | `~/.jharness/hooks/` | `.jharness/hooks/` | Block calls, see results, make the agent go on |
| [Tool definitions](#tool-definitions) | `~/.jharness/tools/` | `.jharness/tools/` | Let the agent run a build tool with typed options |
| [MCP servers](#mcp-servers) | `mcp` in `~/.jharness/settings.json` | Never | Give the agent another server's tools |
| [Status line script](#status-line-script) | `~/.jharness/statusline.js` | Never | Write the status line |

Rules that hold for all of them:

- **They only narrow.** None can approve an action. Whatever they let through still asks as usual.
- **Agent code is sandboxed.** JavaScript in agents, commands, hooks and the status line runs in its own GraalJS isolate with limits, and reaches the world only through `api`. Each `api` call there goes through the same permission checks.
- **Projects need trust.** A project's files load only after you trust it, and the trust question lists its tools and hooks. A project can't add MCP servers or a status line.
- **Names.** File and folder names are the extension's name: `review.md` is `/review`, `agents/reviewer/` is `--agent reviewer`.

## Custom agents

A custom agent is a folder with up to three files. Start one with `jharness --agent NAME`; `/agent` lists them, and the main agent can run one as a subagent with `api.spawnAgent({task, agent: "NAME"})`.

| File | Holds |
| --- | --- |
| `agent.json` | `description` (required), and optionally `model` (a profile name), `tools` (the `api` methods it may call; `readContext` is always allowed), `maxSteps` and `"plan": true` (start in plan mode) |
| `prompt.md` | Instructions added to the system prompt |
| `helpers.js` | Functions defined before every block the agent runs |

[`examples/.jharness/agents/reviewer/`](../examples/.jharness/agents/reviewer) reviews the uncommitted changes and can't change anything. Copy it into `.jharness/agents/` and run `jharness --agent reviewer`.

<!-- file: examples/.jharness/agents/reviewer/agent.json -->
```json
{
  "description": "Reviews the uncommitted changes and reports problems; never changes anything.",
  "tools": ["readFile", "listFiles", "grep", "git"],
  "plan": true,
  "maxSteps": 30
}
```

<!-- file: examples/.jharness/agents/reviewer/helpers.js -->
```js
// Helpers for the reviewer agent. JHarness defines them before every block.

/** Paths with uncommitted changes, staged or not, from `git status --porcelain`. */
function changedFiles() {
  const out = JSON.parse(api.git({op: "status"})).output;
  return out.split("\n")
    .filter(l => l.length > 3 && !l.startsWith("##"))
    .map(l => l.slice(3).split(" -> ").pop());
}

/** The diff of one path against HEAD, staged and unstaged together. */
function diffOf(path) {
  return JSON.parse(api.git({op: "diff", rev: "HEAD", paths: [path]})).output;
}
```

`prompt.md` tells it to call `changedFiles()` and `diffOf(path)`, read around the diff, and report findings with file and line, most serious first.

## Slash commands

A command is a file named after the command. `/commands` lists them, Tab completes them, and `jharness -p "/NAME args"` runs one headless. A file named like a built-in command or alias, such as `q.md`, isn't used, and the start says so.

- **`NAME.md`** is a prompt. `$ARGUMENTS` becomes what you type after `/NAME`. Front matter may give a `description` and `args: a, b, c`, the choices Tab offers.
- **`NAME.js`** defines `run(args)`. It may call the `api` first and returns a prompt string, `{prompt}`, `{print: text}` (shown, not sent) or nothing. `const description` and `const args = [...]` work like the front matter.

<!-- file: examples/.jharness/commands/review.md -->
```markdown
---
description: review the uncommitted changes, optionally only in the given paths
---
Review the uncommitted changes in this project $ARGUMENTS.

Use api.git({op: "diff"}) and api.git({op: "status"}) to see them, and read the changed files where you need more context.
List concrete problems first (bugs, missing tests, risky changes), each with the file and line, then smaller remarks.
Don't change any files.
```

<!-- file: examples/.jharness/commands/changed.js -->
```js
// An example JS slash command (spec 035): /changed prints the changed files without asking the model.
const description = "list the files git sees as changed";

function run(args) {
  const lines = JSON.parse(api.git({op: "status"})).output.split("\n").filter(l => l && !l.startsWith("##"));
  return {print: lines.length ? lines.join("\n") : "No changes."};
}
```

## Hooks

A hook file defines any of three functions. Hooks run for every agent, main and subagents, each call in a fresh sandbox; their own `api` calls never run hooks.

| Function | Runs | Gets | May return |
| --- | --- | --- | --- |
| `preTool(call)` | Before each `api` call | `agent`, `api`, `kind`, `summary`, `detail` (the call's arguments) | `{block: "reason"}` to refuse the call |
| `postTool(call)` | After each `api` call | The same, with `status`, and `result` (up to 10,000 characters) or `error` | Nothing; for logging and checks |
| `stop(turn)` | When an agent would finish | `agent`, `turn`, `steps`, `continued`, `reply`, `calls` (`api`, `kind`, `summary`, `status`) | `{continue: "message"}` to make it go on, at most 3 times a turn |

A pre-tool hook that throws or hits a limit blocks the call. `/hooks` lists the hooks. Copy the example to `~/.jharness/hooks/` or a project's `.jharness/hooks/`:

<!-- file: examples/.jharness/hooks/guard.js -->
```js
// An example hook (spec 034). Copy it to ~/.jharness/hooks/ (yours) or a project's .jharness/hooks/.
// A hook file defines any of preTool(call), postTool(call) and stop(turn). It runs in its own sandbox
// before or after every api call of every agent; it can block a call, never approve one.

// Before a call: return {block: "reason"} to refuse it. call has agent, api, kind, summary and detail.
function preTool(call) {
  if (call.api === "git" && call.detail.op === "push" && /^(main|master)$/.test(call.detail.ref || "")) {
    return {block: "pushing to " + call.detail.ref + " is not allowed; push a branch and open a pull request"};
  }
  const path = call.detail.path || "";
  if ((call.api === "writeFile" || call.api === "editFile") && /(^|\/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|gradle\.lockfile)$/.test(path)) {
    return {block: path + " is written by the build tool; change the dependency and run the tool instead"};
  }
  return null;
}

// When an agent would finish: return {continue: "message"} to make it go on (at most 3 times a turn).
// turn has agent, turn, steps, continued, reply and calls ([{api, kind, summary, status}]).
function stop(turn) {
  const changed = turn.calls.some(c => (c.api === "writeFile" || c.api === "editFile") && c.status === "ok");
  const tested = turn.calls.some(c => c.api === "tool" && / test\b/.test(c.summary));
  if (changed && !tested && turn.continued === 0 && turn.agent === "main") {
    return {continue: "You changed files but didn't run the tests. Run them (api.tools() shows which tool can), or say why you can't."};
  }
  return null;
}
```

## Tool definitions

A tool definition, `NAME.json`, lets the agent run one program with typed options: `api.tool("NAME", {op, ...fields})`. Nothing from the request reaches the command line except checked field values, each in a fixed place, and the approval question shows them. The shipped Maven, Gradle, npm, Go and gofmt definitions are in [`examples/.jharness/tools/`](../examples/.jharness/tools). `jharness tools check` validates your files.

The smallest one:

<!-- file: examples/.jharness/tools/gofmt.json -->
```json
{
  "description": "gofmt as a check: lists the Go files whose formatting differs. It changes nothing.",
  "executable": "gofmt",
  "proxy": "none",
  "operations": {
    "check":        { "args": ["-l", "."] },
    "check-folder": { "args": ["-l", "{folder}"] }
  },
  "fields": {
    "folder": {
      "type": "folder",
      "required": true,
      "args": ["{}"],
      "description": "A folder in the workspace to check"
    }
  }
}
```

Top-level keys:

| Key | Holds |
| --- | --- |
| `description` | One line the agent sees in `api.tools()` |
| `executable` | A program on `PATH`. Shells and command runners (`sh`, `bash`, `env`, `sudo`, `xargs`, `timeout` and others) are refused |
| `wrapper` | `{"file": "mvnw", "checksum": "path/to/properties"}`: a project script used instead when present. Your files only |
| `proxy` | How it reaches the egress proxy: `env` (`HTTP_PROXY` and friends), `java-options`, `maven-settings` (adds `{proxySettings}`), `npm`, or `none` (no network) |
| `javaHome` | true passes the `JAVA_HOME` JHarness was started with, its `bin` first on `PATH` |
| `env` | Variables to set. `~/` at the start means your home. Loader, shell, path, proxy and `GIT_`/`SSH_` variables are refused |
| `writable` | Home folders it may write besides the workspace, such as `~/.npm`. Your files only; `.ssh`, `.config`, `.jharness` and other sensitive folders are refused |
| `loopback` | `"macos"`: allow loopback on macOS (Gradle's daemon). Your files only |
| `args` | Fixed arguments before every operation's |
| `offline` | `{"args": [...], "env": {...}}` used in offline mode |
| `operations` | Op name to `{"args": [...]}`, an argument template where `{field}` takes that field's arguments |
| `fields` | Field name to its type and arguments (below) |
| `conflicts` | Lists of fields that can't be used together, such as `[["tests", "skipTests"]]` |
| `disabled` | true hides a shipped tool; only `description` may stand beside it |

Field types; each field's `args` gives its arguments, with `{}` for the value:

| Type | Value | Keys |
| --- | --- | --- |
| `flag` | true or false; adds `args` when true | |
| `string` | Must match `pattern` whole; never starts with `-` | `pattern` (required), `check: "npm-script"` |
| `enum` | One of `values`, each with its own arguments: `{"short": ["-short"], "full": []}` | `values` |
| `integer` | A whole number | `min`, `max` |
| `folder` | A folder inside the workspace | |

Every field may also have `required`, `default` and `description`.

A project's definitions add or replace yours, once you trust the project. JHarness asks again when one is new or changed, and `-p` leaves unconfirmed ones out.

## MCP servers

MCP servers go in your own `~/.jharness/settings.json`; a project's `mcp` is ignored. From [`examples/settings.json`](../examples/settings.json):

```json
"mcp": {
  "servers": {
    "files": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."], "writable": ["~/.npm"] },
    "docs": { "url": "https://mcp.example.com/mcp", "tokenSecret": "DOCS_TOKEN" }
  }
}
```

- **stdio servers** take `command` (on `PATH`), `args`, `env`, `secretEnv` (variable name to secret name) and `writable` home folders. They run in the OS sandbox, with the network only through the proxy and your rules.
- **HTTP servers** take `url` and `tokenSecret`, a secret sent as a bearer token. Their endpoint is allowed through the proxy automatically.
- **Both** take `timeout` (default `60s`).
- **Using them.** The agent lists tools with `api.mcp({list: true})` and calls one with `api.mcp({server, tool, input})`, which asks unless you answered "always". `/mcp` lists the servers and their tools.
- **Secrets.** They come from the environment or `~/.jharness/secrets`, never from the settings file.

## Status line script

`~/.jharness/statusline.js` defines `status(s)` and returns the line. It runs in its own sandbox with no `api`, after the events that change the line and at most every 250 ms.

<!-- file: examples/statusline.js -->
```js
// An example status line script (spec 042). Copy it to ~/.jharness/statusline.js.
// status(s) returns the line; s.line is the default one. It runs in its own sandbox with no api.
function status(s) {
  const parts = [s.model, s.context.percent + "%" + (s.context.estimated ? "~" : "")];
  if (s.plan) {
    parts.push("PLAN");
  }
  if (s.agents.running + s.agents.held > 0) {
    parts.push(s.agents.running + " running" + (s.agents.held ? ", " + s.agents.held + " waiting for you" : ""));
  }
  if (s.costUsd !== null && s.costUsd !== undefined) {
    parts.push("$" + s.costUsd.toFixed(2));
  }
  parts.push(s.busy ? "working" : "turn " + s.turn);
  return parts.join(" | ");
}
```

`s` has:

- `profile`, `provider` and `model`;
- `context`: `tokens`, `window`, `percent`, `estimated` and `compactAt`;
- `mode` and `plan`;
- `agents`: `running`, `held` and `names`;
- `cache`: `readTokens`, `writeTokens`, `inputTokens` and `readTotal`;
- `costUsd` (or null), `turn`, `busy` and `workspace`;
- `line`, the default line.

If it throws, hits a limit or returns something other than a string, the default line shows and the TUI says why once. A run over 1 second turns it off for the session.
