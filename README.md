# JHarness

A free, open-source terminal coding agent that ships as one native executable and runs all agent-written code in a GraalJS sandbox.

## What it does

<!-- guide: the same text as jharness-agent/src/main/resources/dev/jharness/agent/guide.md, which the agent also gets; GuideTest checks they match; scripts/sync-guide.py copies it -->
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
| `/clear` | Empty the conversation so the model starts fresh; files stay as they are |
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
<!-- /guide -->

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

Phases 0 to 3 are done; their gates passed (`docs/p0-results.md`, `docs/p1-gate.md`, `docs/p2-gate.md`, `docs/p3-gate.md`). Phase 2 added build tools in the OS sandbox, the egress proxy and network rules, `api.http`, git network operations, offline mode, the security suites, model retries and debugging tools (specs 011–024). Phase 3 (extensibility) added custom agents, subagents, tool definition files, hooks, slash commands, MCP, plan mode and the optional action reviewer (specs 025–038). Phase 4 (context and release, v0.1) is under way (specs 039–065).

Evals on real repo tasks measure each release: `scripts/eval.py path/to/jharness --model PROFILE` runs the tasks in `evals/tasks/` in headless mode and scores them with hidden checks. `docs/evals.md` explains it and keeps the scores.

## Install

On Linux (x86-64, ARM64) or an Apple silicon Mac:

```bash
curl -fsSL https://raw.githubusercontent.com/canmogol/jharness.dev/master/scripts/install.sh | sh
```

or `brew install canmogol/jharness/jharness`. JHarness needs bubblewrap on Linux to run processes. The [user guide](docs/user-guide.md#install) has the details, and [docs/releasing.md](docs/releasing.md) says how releases are made.

## Documentation

| Document | For |
| --- | --- |
| [docs/user-guide.md](docs/user-guide.md) | Installing, models and keys, the terminal UI, permissions, build tools, the network, subagents, sessions, headless runs, troubleshooting and every setting |
| [docs/extensions.md](docs/extensions.md) | Writing custom agents, slash commands, hooks, tool definitions, MCP server entries and a status line script |
| [docs/api.md](docs/api.md) | Every `api` method agent code can call, generated from `api.d.ts` |

## Build from source

Needs Oracle GraalVM 25 (Java 25) and Maven, and bubblewrap on Linux (see the [user guide](docs/user-guide.md#install)).

```
mvn install                                          # build and test on the JVM
mvn -Pnative -pl jharness-cli package -DskipTests    # native binary: jharness-cli/target/jharness
```

After changing `api.d.ts`, run `scripts/api-reference.sh` to regenerate `docs/api.md`; after changing the agent's `guide.md`, run `scripts/sync-guide.py`. Tests fail until you do.

The security suites attack a real session: prompt injection, a malicious repository, sandbox escapes and runaway code. They run with `mvn install` and in the native smoke binary (`jharness-smoke --security`). `docs/security-suites.md` lists every case and what contains it.

Two manual checks cover what CI doesn't: `node scripts/viewer-check.mjs BIN` opens the web viewer in Chromium, Firefox, WebKit and Safari with Playwright and checks the page, its console and the Markdown, and `scripts/tui-tmux-check.py BIN` checks the TUI at several terminal sizes in tmux. They never run in CI; run them after changing the viewer or the terminal UI and before each release, on Linux and macOS. `docs/manual-checks.md` says how to set them up.

## License

The source code is under the [MIT License](LICENSE).

Release binaries will be built with Oracle GraalVM, which is distributed under the GraalVM Free Terms and Conditions (GFTC). GFTC allows free redistribution but not for a fee, so anyone who wants to sell binaries should build with GraalVM Community Edition 25.1 or later instead.
