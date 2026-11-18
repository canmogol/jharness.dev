# JHarness — User Stories

High-level user stories for stakeholders. Each story names who it serves, what they get, and how we'll know it works. Detailed acceptance criteria go into each feature's `specs/NNN-feature/spec.md`.

Priority: **MVP** = needed for v0.1 · **Later** = after v0.1.
Phase refers to `product/roadmap.md`.

Last updated: 2026-11-18

---

## Personas

| Persona | Who they are | What they care about |
| --- | --- | --- |
| **Developer** | Writes Java or JavaScript daily, works in a terminal | Getting code changes done fast without losing control of their machine |
| **Privacy-minded developer** | Can't or won't send code to a cloud model | Running a capable agent fully on their own machine |
| **Team lead** | Sets up conventions and tools for a team | Sharing team-specific agents and guardrails |
| **Security-minded user** | Responsible for what runs on developer machines | Clear limits, approvals and an audit trail |
| **CI engineer** | Runs automation in pipelines | A non-interactive, scriptable agent with predictable output |
| **Extension author** | Builds agents, hooks and commands for others | A small, documented `api` and an easy way to share work |
| **Contributor** | Works on JHarness itself | Fast local builds, deterministic tests |

---

## Epic 1 — Install and first run

| ID | Story | Acceptance (high level) | Priority | Phase |
| --- | --- | --- | --- | --- |
| US-1.1 | As a developer, I want to install JHarness as one file on macOS or Linux, so I don't have to set up Java or other runtimes. | Single binary; Homebrew tap and install script; macOS binary is signed and notarized | MVP | P4 |
| US-1.2 | As a privacy-minded developer, I want to run a local model, so my code never leaves my machine. | A local model served by Ollama or llama.cpp works through the OpenAI-compatible client; in-process models are US-L.7 (D-035) | MVP | P0–P1 |
| US-1.3 | As a developer, I want to use Claude or any OpenAI-compatible server instead, so I can pick quality, cost or privacy per project. | Named model profiles; `--model` and `/model` switch; Claude, Ollama and llama.cpp work | MVP | P1 |
| US-1.5 | As a Java developer with my dependencies already on my machine, I want to work fully offline with a local model. | `--offline` sets network default deny and passes offline flags to Maven, Gradle and npm; everything cached works | MVP | P2 |
| US-1.4 | As a developer, I want JHarness to ask whether I trust a project the first time I open it, so a cloned repo can't run its own code without my say-so. | Trust prompt once per folder; untrusted projects can't run builds, wrappers or scripts | MVP | P1 |

## Epic 2 — Everyday coding

| ID | Story | Acceptance (high level) | Priority | Phase |
| --- | --- | --- | --- | --- |
| US-2.1 | As a developer, I want to describe a bug and have the agent find it, fix it and run the tests, so I spend less time on routine fixes. | Agent searches, edits and runs tests via Maven, Gradle or npm (up to 10 min per run on request); shows the diff | MVP | P1–P2 |
| US-2.2 | As a developer, I want to ask questions about an unfamiliar codebase in read-only mode, so I can learn it without risk. | Plan/read-only mode makes no changes; answers cite files | MVP | P1 |
| US-2.3 | As a developer, I want to see every change as a diff, so I know exactly what the agent did. | Diff shown before or after applying, per permission mode | MVP | P1 |
| US-2.4 | As a developer, I want to undo the agent's changes per turn, so a bad edit costs nothing. | `/undo` and `/rewind <turn>` restore files | MVP | P1 |
| US-2.5 | As a developer, I want to stop the agent at once, so a wrong direction doesn't keep running. | Esc cancels the model stream and the running JS | MVP | P1 |
| US-2.6 | As a developer, I want to commit and inspect history through the agent, so I stay in one tool. | git status, diff, log, add, commit and more, with approval for changes | MVP | P1 |
| US-2.7 | As a developer, I want to resume yesterday's session, so I don't have to re-explain the task. | `jharness --resume` restores the conversation from the session log | MVP | P4 |
| US-2.8 | As a developer, I want long sessions to keep working when context fills up, so big tasks can finish. | Automatic compaction keeps the session going | MVP | P4 |

## Epic 3 — Safety and control

| ID | Story | Acceptance (high level) | Priority | Phase |
| --- | --- | --- | --- | --- |
| US-3.1 | As a security-minded user, I want every state-changing action to ask first, so nothing changes without approval. | Writes, commits, builds and network calls prompt unless a rule allows them | MVP | P1 |
| US-3.2 | As a developer, I want to make an approval permanent for a project, so I'm not asked about the same safe step every time. | "Always for this project" option saved to project rules | MVP | P1 |
| US-3.3 | As a security-minded user, I want to allow and deny domains and IP ranges, so the agent only talks to hosts I accept. | One general and one project rules file (`allow`, `deny`, `default`); `/network` commands; enforced by the host proxy for every client and tool | MVP | P2 |
| US-3.4 | As a security-minded user, I want agent code to have no shell and run under CPU and memory limits, so runaway or malicious code is contained. | No process access from JS; limits trip cleanly and are reported | MVP | P1–P2 |
| US-3.5 | As a security-minded user, I want build tools to run in an OS sandbox, so a malicious build script can't reach outside the project. | Maven, Gradle and npm write only to the workspace and caches; network through rules | MVP | P2 |
| US-3.6 | As a security-minded user, I want a log of everything the agent did, so I can audit a session. | Every action is an event in the session log; `jharness log <session>` shows it | MVP | P1–P2 |
| US-3.7 | As a developer, I want the agent to read reference folders without changing them, so it can use docs and other repos safely. | Mounted folders are read-only | MVP | P1 |
| US-3.8 | As a security-minded user, I want an optional reviewer model to flag actions that don't fit my task, so off-task or injected actions get stopped for my review. | Kev, Jev or own model; can only turn allow into ask or deny; falls back to asking when unavailable | MVP | P3 |

## Epic 4 — Teams and extensions

| ID | Story | Acceptance (high level) | Priority | Phase |
| --- | --- | --- | --- | --- |
| US-4.1 | As a team lead, I want to add a custom agent to the repo, so the whole team gets the same workflow. | A folder in `.jharness/agents/` defines prompt, model, tools and optional JS | MVP | P3 |
| US-4.2 | As a team lead, I want hooks that block risky actions, so team guardrails apply automatically. | A JS pre-tool hook can block or change a call | MVP | P3 |
| US-4.3 | As a developer, I want slash commands for repeated tasks, so common prompts are one command. | Markdown templates and JS commands in `.jharness/commands/` | MVP | P3 |
| US-4.4 | As a developer, I want the agent to delegate work to subagents, so big tasks split into focused parts. | `api.spawnAgent` runs a subagent in its own isolate and returns its result | MVP | P3 |
| US-4.5 | As a developer, I want to connect MCP servers, so the agent can use my other tools. | stdio and HTTP MCP servers usable from JS, under permissions | MVP | P3 |
| US-4.6 | As an extension author, I want a documented `api` with typings and examples, so I can build extensions quickly. | `api.d.ts`, reference docs and example agents and hooks | MVP | P4 |

## Epic 5 — Automation

| ID | Story | Acceptance (high level) | Priority | Phase |
| --- | --- | --- | --- | --- |
| US-5.1 | As a CI engineer, I want to run a task non-interactively with machine-readable output, so I can use JHarness in pipelines. | `jharness -p "..." --output json|stream-json`; non-zero exit on failure | MVP | P4 |
| US-5.2 | As a CI engineer, I want to set the permission mode on the command line, so runs never stop for prompts. | `--mode accept-edits` and rule flags | MVP | P4 |

## Epic 6 — Building JHarness

| ID | Story | Acceptance (high level) | Priority | Phase |
| --- | --- | --- | --- | --- |
| US-6.1 | As a contributor, I want to develop and test on the JVM, so I'm not waiting on native builds. | Full test suite runs on the JVM; native in CI | MVP | P0 |
| US-6.2 | As a contributor, I want deterministic tests of the agent loop, so changes can be checked without paying for model calls. | Replay provider with recorded responses | MVP | P1 |
| US-6.3 | As a maintainer, I want evals on real repo tasks before each release, so we know if the agent got better or worse. | Eval suite with scores tracked per release | MVP | P4 |
| US-6.4 | As a user who finds a vulnerability, I want a clear way to report it. | `SECURITY.md` with a disclosure process | MVP | P0 |

## Later (after v0.1)

| ID | Story |
| --- | --- |
| US-L.1 | As a Windows developer, I want JHarness on Windows. |
| US-L.2 | As a developer, I want to install extensions from a git URL. |
| US-L.3 | As a developer, I want more build tools (pnpm, yarn, Python, Go, Cargo). |
| US-L.4 | As a developer, I want image input and web search. |
| US-L.5 | As a Java developer, I want to embed JHarness in my own app through a Java SDK. |
| US-L.6 | As a developer, I want the agent to start a dev server or watch mode in the background and check on it later. |
| US-L.7 | As a privacy-minded developer, I want a local model inside JHarness with no extra server (ModelJars, D-035). |
