# JHarness — Mission

Last updated: 2026-11-18

## Pitch

JHarness is a free, open-source terminal coding agent, in the spirit of Claude Code, that ships as one native executable and runs every line of agent-written code inside a sandbox, so developers get a capable agent that can run fully on their own machine without handing it the keys to that machine.

## The problem

- **Coding agents run with too much power.** Most terminal agents give the model a shell. One prompt-injected file or web page can turn that into arbitrary commands on the developer's machine.
- **Local models are hard to use well.** Running a local model usually means a separate server, and small models are unreliable at native tool-call formats.
- **Java teams are underserved.** Agent tools are built around Python and Node runtimes; Java and Maven/Gradle projects are an afterthought.

## Who it's for

| User | Need |
| --- | --- |
| Java and JavaScript developers working in a terminal | Fix bugs, write features and explore code faster, with full control |
| Privacy-minded developers and teams | Keep code on the machine with a local model |
| Team leads | Share custom agents, commands and guardrails through the repo |
| Security-minded users | Clear limits, approvals and an audit trail for what an agent may do |
| CI engineers | A scriptable, non-interactive agent with machine-readable output |

## The solution

- **One binary.** A Java host compiled with GraalVM Native Image. Nothing else to install.
- **The model acts only by writing JavaScript.** Each JS block runs in a GraalJS polyglot isolate with its own heap, CPU and memory limits, and no access to anything except a small `api`.
- **The host owns every side effect.** Files, network, git, Maven, Gradle and npm are reached only through typed, permission-checked host operations. There is no shell for the agent.
- **Local or hosted models.** Local models through Ollama or llama.cpp, Claude, and any OpenAI-compatible server. In-process local models (ModelJars) come after v0.1 (D-035).
- **Everything is an event.** A JSON event log drives the UI, hooks, audit and session resume.

## What makes it different

| Typical terminal agent | JHarness |
| --- | --- |
| Model gets a shell | Model gets a sandboxed JS `api`; no shell |
| Many tool calls, one per action | One JS block can do many operations at once |
| Local models need a separate server and tool-call support | Local models run in-process; a code block is all the model must produce |
| Built on Node or Python runtimes | Single native binary; first-class Maven and Gradle support |
| Network access is all or nothing | Per-domain and per-IP allow and deny rules, enforced by one proxy for every client and tool |

## Principles

The binding rules are in `constitution.md`. In short: one binary; the host owns every side effect; agent code is untrusted and has no shell; all traffic goes through the host proxy; safe by default; events are the source of truth.

## Scope for v0.1

Interactive TUI, headless mode and a read-only web session viewer; RunJS with file, git, Maven, Gradle, npm and HTTP operations; permissions, project trust, network rules; checkpoints and undo; subagents, custom agents, hooks and slash commands; MCP client; sessions with resume; ModelJars, OpenAI-compatible and Claude providers; macOS (Apple Silicon) and Linux.

## Not in v0.1

Windows; long-lived background processes such as dev servers; IDE plugins; a GUI that drives the agent (the web viewer only shows sessions); a multi-user server; remote or cloud execution; isolate languages other than JS; build tools beyond git, Maven, Gradle and npm; extension installs from a registry.

## Success measures (proposed, to confirm)

| Measure | Target for v0.1 |
| --- | --- |
| Seeded-bug benchmark solved end to end | With Claude and with the default local model |
| Sandbox-escape and injection suites | 100% contained on Linux and macOS |
| Start-up to prompt | Under 2 seconds, excluding model load |
| Install | One command on macOS and Linux |
| Security reports | Disclosure process published from day one |
