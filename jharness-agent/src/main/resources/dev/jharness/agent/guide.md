JHarness is a terminal coding agent in one native executable. The model acts only by writing JavaScript that runs in a sandbox and reaches the project through the `api`.

- **Safe changes.** Edits, deletes, commits, builds and network writes ask first: `ask`, `accept-edits`, `plan` or `bypass` mode, with saved "always" rules. A deny list keeps secrets out, and `/undo` and `/rewind` restore files.
- **Build tools.** Maven, Gradle, npm, Go and gofmt run in an OS sandbox from JSON definitions in `~/.jharness/tools/`, online only through JHarness's egress proxy and `~/.jharness/network.rules`.
- **Subagents.** The agent can start helpers, in the foreground or in the background; the user follows, talks to and answers them.
- **Extensions.** Custom agents (`.jharness/agents/`), JS hooks (`.jharness/hooks/`), slash commands (`.jharness/commands/`), MCP servers and an optional action reviewer.
- **Sessions.** Every event goes to a session log. Context is counted, compacted and cached, and sessions can be resumed or forked.
- **Headless.** `jharness -p` runs one prompt for scripts and CI; `jharness view` shows a session in the browser.

Commands in the terminal UI (the user types them; the agent can't):

| Command | What it does |
| --- | --- |
| `/agent` | List custom agents (start one with `jharness --agent NAME`) |
| `/agents [NAME]` | List this session's subagents, or show one's last 20 events |
| `/answer [NAME]` | Answer a held question (the main agent's or a background subagent's) and stay in the main chat |
| `/commands` | List the user's and the project's slash commands |
| `/compact` | Summarize the conversation now to free context |
| `/context` | Show how full the model's context is |
| `/diff` | Show the last turn's file changes |
| `/events [LEVEL]` | Show or set the event level (none, steps, all) and the session log file |
| `/exit` | Quit (also `/quit`, `/q` or Ctrl-D) |
| `/follow [NAME]` | Watch a background subagent live |
| `/help` | Show the commands |
| `/hooks` | List the JS hooks |
| `/mcp` | List the MCP servers and their tools |
| `/model [NAME]` | List model profiles, or switch to one |
| `/network [allow\|deny\|remove X \| reload]` | List or change the network rules |
| `/plan` | Turn plan mode on or off (read-only; the agent proposes a plan) |
| `/rewind N` | Undo every turn after turn N, files included |
| `/stop NAME\|all` | Stop a running background subagent, or all of them |
| `/switch NAME` | Talk to a subagent (`/switch main` goes back) |
| `/thinking` | Show or hide the model's thinking |
| `/todos` | Show the agent's todo list |
| `/undo` | Undo the last turn's file changes and remove it from the conversation |
| `/usage` | Show this session's tokens and cost, for each agent |
| `/view` | Show this session in the browser, read-only |

Keys: `@PATH` attaches a file or folder; Shift+Enter, Alt+Enter or Ctrl-J adds a line; Esc stops the running turn. Messages sent during a turn wait for it to end.

Command line:

| Command | What it does |
| --- | --- |
| `jharness` | Start the terminal UI in the current folder (`--cwd DIR` for another) |
| `jharness -p PROMPT` (`--print`) | Run one prompt without the UI; `--output text\|json\|stream-json` |
| `--resume[=ID]`, `--fork[=ID]` | Continue a saved session, or start from a copy of one |
| `--model NAME`, `--mode MODE`, `--agent NAME` | Pick a model profile, a permission mode, or a custom agent |
| `--offline`, `--debug` | Allow only rule-listed connections; show every event and the thinking |
| `jharness log` | List this folder's sessions, or show one's events |
| `jharness tools` | List, show, check or reset the tool definitions |
| `jharness usage` | Show tokens and cost of recent sessions |
| `jharness view` | Show a session in the browser, read-only |
| `--help`, `--version` | Help and version |

Settings live in `~/.jharness/settings.json`, and a trusted project can add `.jharness/settings.json`.
