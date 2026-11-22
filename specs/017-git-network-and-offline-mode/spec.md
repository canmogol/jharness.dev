# Spec 017 — Git network operations and offline mode

Status: Done · Phase: P2 · Created: 2026-11-22

## Summary

`api.git` gains `fetch`, `pull`, `push` and `clone`. They use HTTPS only, go through the egress proxy and the network rules, and run in the OS sandbox with only the proxy port open, like the build tools (spec 013). Push and private fetches can use a token that the user maps to a host in their own settings; the token goes only to that host and never shows in output or events. Offline mode (`--offline` or `"offline": true`) makes the proxy deny anything no rule allows, runs Maven, Gradle and npm offline, and refuses git network operations up front. Decision D-055 (it builds on D-025, D-033 and D-039).

## Roadmap items

- Egress proxy in the host: every HTTP client and command-line tool goes through it (HTTP clients: spec 011; Maven, Gradle and npm: spec 013; git: spec 017)
- Offline mode: network default deny, Maven/Gradle/npm offline flags, no update checks (D-033)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-2.6 | Commit and inspect history through the agent | `user-stories.md` (fetch, pull and push complete the git set) |
| US-3.3 | The proxy enforces the network rules for every client and tool | `user-stories.md` |
| US-3.1 | State-changing actions ask first | `user-stories.md` (pull, push, clone) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | `api.git` takes `{op:"fetch", remote?, ref?, prune?}`, `{op:"pull", remote?, ref?}`, `{op:"push", remote?, ref?, setUpstream?}` and `{op:"clone", url, dir?}`, plus `timeoutMs`. `remote` is a remote name (default `origin`). `ref` is a branch or tag name, with no `:`, no leading `+` or `-`. `url` is an absolute `https://` URL with no user info. `dir` is a new workspace path, by default the URL's last segment without `.git`. Bad values fail with a message that names the rule. |
| AC-02 | The commands are fixed: `fetch --no-recurse-submodules [--prune] <remote> [<ref>]`, `pull --ff-only --no-rebase --no-recurse-submodules --no-edit <remote> [<ref>]`, `push --no-verify [--set-upstream] <remote> [<ref>]` (never `--force`, never a deletion) and `clone --no-recurse-submodules -- <url> <dir>`. |
| AC-03 | Network operations allow only HTTPS: `protocol.https.allow=always`, with file, git, ssh, http and ext set to never. They also disable `http.followRedirects` and `http.cookieFile`. For fetch, pull and push, the remote's URL (`git remote get-url`, and `--push` for push) must be `https://`, or the call fails before anything is sent. |
| AC-04 | Network operations are refused when the repository's own config (local or worktree scope) sets `credential.*`, `core.askPass`, `core.sshCommand`, `protocol.*`, `http.*.cookieFile`, or `remote.*.uploadpack` / `receivepack`. The message names the key. |
| AC-05 | The child reaches the network only through the proxy port (Seatbelt rule on macOS, the bridge on Linux). The proxy URL and its credentials reach git through `GIT_CONFIG_COUNT` environment entries, never argv, with `http.proxyAuthMethod=basic`. `GIT_ASKPASS` is empty and terminal prompts are off, so git never asks for a password. A run that can't reach the proxy port at all is tried up to 3 times; Seatbelt refuses that first connect now and then on macOS, and nothing has been sent at that point. |
| AC-06 | `"git": {"credentials": {"<host>": "<SECRET_NAME>"}}` in the user settings maps a host to a secret, looked up as D-042 says. For a mapped host, git gets `http.https://<host>/.extraHeader=Authorization: Basic <x-access-token:token>`, so the token goes only to that host. Project settings can't set `git.credentials`. The token and its Base64 form are shown as `***` in output and events. A mapped secret that isn't set stops start-up with its name. |
| AC-07 | Permissions: `fetch` is a `git-read`, so the network rules alone decide. `pull`, `push` and `clone` are `git-write`: they ask, plan mode denies them, and "always" saves the op. The prompt shows the remote URL, e.g. `git push origin main → https://github.com/a/b.git`. `pull` and `clone` mark the turn's checkpoint as touched by git. |
| AC-08 | Offline mode is turned on by `jharness --offline` or `"offline": true` in settings. An untrusted project's `"offline": true` counts, because it only tightens. In offline mode the proxy denies every connection that no `allow` rule matches: the rules files' `default` lines don't apply, and the decision says "offline mode". Model endpoints are allowed automatically only when they are on loopback or private addresses. |
| AC-09 | In offline mode, Maven runs with `-o`, Gradle with `--offline` and npm with `npm_config_offline=true`. Git `fetch`, `pull`, `push` and `clone` fail at once with "offline mode: git <op> needs the network". JHarness makes no update checks, online or offline. |
| AC-10 | `session.started` records `offline`, and the TUI's start line shows "offline" when it is on. |
| AC-11 | Tests: clone, fetch, pull and push against a local HTTPS git server through the real proxy and sandbox; denied hosts; non-HTTPS remotes; hostile repo config; credentials only to the mapped host, and redacted; offline refusals and flags; and the rules decisions in offline mode. The native smoke binary clones a local repository through the proxy. |
| AC-12 | `api.d.ts` (which the system prompt includes) and the README describe the new operations, credentials and offline mode. |

## Out of scope

- SSH remotes (still blocked, D-025), plain-HTTP remotes, and git credential helpers.
- Force push, deleting remote branches, pushing tags, and submodules.
- `api.http` with user tokens (D-054 still adds no secrets).
- Per-call offline switching, and a `/offline` command.

## Dependencies and assumptions

- git 2.31 or later, for `GIT_CONFIG_COUNT`. The macOS system git is 2.39.
- Tests run `git http-backend` as a CGI from a test HTTPS server, with a self-signed certificate made by `keytool`. The tests and the smoke binary pass the certificate to git through a constructor parameter that sessions never use.

## Open questions

None.
