# Spec 028 — Tool definitions

Status: Done · Phase: P3 · Created: 2026-11-24

## Summary

Maven, Gradle and npm stop being Java code. Each tool is a JSON definition file in `~/.jharness/tools/` (seeded on first run from `examples/.jharness/tools/`) or a trusted project's `.jharness/tools/`. `api.tool(name, request)` runs any defined tool and `api.tools()` lists them; `api.maven`, `api.gradle` and `api.npm` are removed. git and `rg` stay in code. Decisions D-066 and D-067; constitution 1.6.

## Roadmap items

- Build tools as definition files in `~/.jharness/tools/` and trusted projects; `api.tool` and `api.tools()`

## User stories

| ID | Story | From |
| --- | --- | --- |
| — | Change which Maven operations and parameters the agent may use, or add a tool such as Go, without a new release | User request, 2026-11-24 (D-066) |
| US-2.1 | Describe a bug and have the agent fix it and run the tests | `user-stories.md` (tests run through the defined tools) |

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-01 | A definition file follows the D-066 format: `description`, `executable`, `wrapper`, `proxy` (`maven-settings`, `java-options`, `npm`, `env`, `none`), `javaHome`, `env`, `writable`, `loopback`, `args`, `offline`, `operations` (argv templates), `fields` (`flag`, `string`, `enum`, `integer`, `folder`, with `args`, `pattern`, `values`, `min`, `max`, `required`, `default`, `check`, `description`), `conflicts`, `disabled`. Unknown keys, bad patterns, unused fields and placeholders without fields are refused with the file and key in the message. |
| AC-02 | Every definition follows the rules: the executable is a name on `PATH` (no path) and not a shell or command runner; `env` can't set the loader, path, home, temp, locale, Java options, proxy, git, ssh or interpreter start-up variables; `writable` entries are folders below the home and not `.ssh`, `.jharness` or similar; a field value starting with `-` is always refused; each value goes only into the slot its definition names. |
| AC-03 | A project's definition can't set `wrapper`, `loopback` or `writable`; such a file is refused, and the tool is unavailable with that reason. |
| AC-04 | The shipped `maven.json`, `gradle.json` and `npm.json` give exactly the operations, argv, environment, cache folders, proxy wiring, wrapper rules and offline flags of specs 013 and 023; the existing build tests pass against them. The build embeds them from `examples/.jharness/tools/`. |
| AC-05 | When `~/.jharness/tools/` doesn't exist, JHarness creates it (0700) and writes the shipped files (0600). It never rewrites or re-adds a file after that, so a deleted file removes the tool. A user file others can write is refused. |
| AC-06 | `jharness tools` lists each tool (source, executable and whether it is found, operations, and files that differ from the shipped default); `show NAME`, `check` (exit 1 when a file is broken) and `reset [NAME]` work as D-066 says. |
| AC-07 | In a trusted project whose tools are used, a project file replaces the user's tool of the same name, `{"disabled": true}` removes it, and a broken file makes that tool unavailable. An untrusted project's files are ignored. |
| AC-08 | The trust question lists the project's tools (executable, operations, whether it replaces the user's, or its error), and trusting records each file's SHA-256 in `trusted.json`. Each later start prints one line naming the project's tools. A new or changed file asks whether to use the project's tools this session; yes records the hashes, no leaves them out (`tools.project_ignored`). `-p` leaves unconfirmed project tools out and says so on stderr. |
| AC-09 | `api.tool(name, request)` checks, asks (kind `build`) and runs in the OS sandbox; a string request is the op. The approval names a project's tool, and "always" is keyed on the tool, the operation and the resolved executable. `api.tools()` returns each tool's description, source, operations with their fields, fields, conflicts and `available` with a `reason`. `api.maven`, `api.gradle` and `api.npm` are gone. |
| AC-10 | The system prompt lists the tools from the definitions; custom agents' `tools` take `tool`, `tools` and `tool:NAME`. `tools.loaded` records the session's tools and errors. |
| AC-11 | Security case REPO-11: a repo's shell, own wrapper, writable folders and loopback are refused, and an untrusted repo's tools are never offered. All earlier cases still pass. |
| AC-12 | The native binary loads the shipped files from its resources, runs `jharness tools` and `api.tools()`, in CI. |
| AC-13 | The README, D-066 and D-067 document the definitions. |

## Out of scope

- git and `rg` as definitions.
- Host checks other than `npm-script`; tools that read the network without the proxy.
- Upgrading the user's copied files automatically.

## Dependencies and assumptions

- D-066, D-067 and constitution 1.6 (PR #51). The OS sandbox, proxy and `ProcessRunner` are unchanged.

## Open questions

None. Recommended options accepted while the user was away; `writable` was added to the user-only keys (D-066 updated).
