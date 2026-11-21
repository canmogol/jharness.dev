# Spec 015 — Command-execution checks

Status: Done · Phase: P2 · Created: 2026-11-21

## Summary

Closes the gaps left in how JHarness starts processes. Most of the roadmap item already exists: argv lists with no shell (spec 002), executables resolved outside the workspace (spec 002), a clean environment (spec 002), the git hardening prefix (spec 002), and a process-tree kill on timeout (specs 002 and 013). Probes on git 2.53 found programs a cloned repo can still make git run, despite the current prefix. They are a `filter.<driver>.clean`/`smudge`/`process` in `.git/config`, which `git add` and `git status` run; `commit.gpgSign` with `gpg.program`, which `git commit` runs; and `log.showSignature` with `gpg.program`, which `git log` and `git show` run. The probes also found that `core.worktree` can point git at a folder outside the workspace. This spec closes those and pins each executable to the file it resolved to. It also allows only named environment variables and caps argument sizes, and it collects the checks in one test suite. Decision D-052. Scope taken from the recommended options while the user was away (2026-11-21).

## Roadmap items

- Command-execution checks (no shell, pinned executables, argument validation, git hardening, clean environment, process-tree kill)

## User stories

| ID | Story | From |
| --- | --- | --- |
| US-3.4 | No shell for agent code; runaway or malicious code is contained | `user-stories.md` |
| US-1.4 | A cloned repo can't run its own code without the user's say-so | `user-stories.md` (git configuration from the repo) |
| US-2.6 | Commit and inspect history through the agent | `user-stories.md` (git keeps working in normal repos) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | Git never runs a filter driver defined in the repository's own configuration (scope `local` or `worktree`, includes too). Before each call, JHarness lists the `filter.*` keys with their scope and overrides every repo-defined driver with empty `clean`, `smudge` and `process` and `required=false`. Drivers from the user's global config (such as git-lfs) keep working unless the repo redefines them. |
| AC-02 | The hardening prefix also sets `commit.gpgSign=false`, `tag.gpgSign=false`, `log.showSignature=false`, `submodule.recurse=false`, `gc.auto=0` and `maintenance.auto=false`. `status` and `diff` pass `--ignore-submodules=all`. A repo with `commit.gpgSign=true` and its own `gpg.program`, or with `log.showSignature=true`, doesn't run that program, and `commit` succeeds unsigned. |
| AC-03 | When the workspace root has a `.git` entry, git runs with `GIT_WORK_TREE` set to the workspace root, so `core.worktree` in the repo config can't point git at another folder. |
| AC-04 | Every `Executable` is pinned to the file it resolved to: its file key, size and modification time. `ProcessRunner` checks the pin before each start. If the file was replaced or changed, the run fails with "<tool> changed since it was resolved; restart JHarness", a `process.refused` event is emitted, and nothing starts. |
| AC-05 | `Executables.resolve` skips a candidate whose file or real parent folder is writable by others (mode `o+w`). |
| AC-06 | Child processes get only named environment variables. The base set is `PATH`, `HOME`, `LANG`, `LC_ALL` and `TMPDIR`. Callers may add only `GIT_CONFIG_NOSYSTEM`, `GIT_TERMINAL_PROMPT`, `GIT_OPTIONAL_LOCKS`, `GIT_WORK_TREE`, `JAVA_HOME`, `JAVA_TOOL_OPTIONS` and the `npm_config_*` names that build operations set. Any other name is refused before the process starts. Nothing from JHarness's own environment is passed on beyond `TMPDIR`, which the sandbox replaces. |
| AC-07 | `ProcessRunner` refuses argument lists with more than 4,096 arguments, any argument over 131,072 UTF-8 bytes, or more than 1 MiB in total, as well as NUL characters (as before). Refused runs start nothing. |
| AC-08 | A `CommandExecutionTest` suite in `jharness-process` covers each check: no shell (metacharacters reach the child literally; `Executables` knows no shell), pinning, the `o+w` skip, the environment allowlist, the argument limits, the git hardening against a hostile repo config (AC-01 to AC-03) and the process-tree kill. Tests that need the OS sandbox run on Linux with bubblewrap and on macOS. |

## Out of scope

- A repo whose `.git` is a file pointing at another repository's git folder (part of the malicious-repo suite, the next roadmap item).
- Pinning `node`, which the `npm` script starts through `PATH`.
- Killing processes that detach from the tree on macOS after a normal exit. On Linux the PID namespace already ends them.
- git network operations (the egress proxy item).

## Dependencies and assumptions

- Specs 002, 012 and 013 (`ProcessRunner`, `ChildSandbox`, `BuildOperations`).
- git 2.26 or later, for `git config --show-scope`.
- An empty `filter.<driver>.clean`/`smudge`/`process` value turns the driver off (checked on git 2.53).

## Open questions

None.
