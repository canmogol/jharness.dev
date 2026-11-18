# Plan 006 — Project trust, layered settings and JHARNESS.md

Spec: `spec.md` · Status: Draft · Created: 2026-11-18

## Approach

`Settings.layered(home, workspace, trusted)` in `jharness-config` merges the JSON trees before the usual parsing, so every layer is validated by the same code. `TrustStore` (in `jharness-security`) reads and writes `trusted.json`. The CLI decides trust before opening the session: stored answer, else ask in the TUI, else untrusted. `SystemPrompt` gains a project-instructions slot filled from both `JHARNESS.md` files. Decision D-043.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | — |
| II Host owns side effects | Settings and trust are host code | Yes |
| III Untrusted | Sandbox limits can be set by a trusted project only; an untrusted one can't loosen them | Yes |
| IV No shell | No process | — |
| V Proxy | No network | — |
| VI Strings | No boundary change | — |
| VII Safe by default | Untrusted by default; untrusted settings only tighten; headless never trusts | Yes |
| VIII Events | `project.trusted`, `settings.ignored`, `trusted` in `session.started` | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | Trust store, merge rules and the untrusted filter have tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

### Merge

```java
public record Layered(Settings settings, List<String> ignored) {}
public static Layered layered(Path home, Path workspace, boolean trusted, ObjectNode session) throws ConfigException;
```

1. Read the user file and the project file (`<workspace>/.jharness/settings.json`) as JSON objects; a missing file is `{}`.
2. Untrusted: keep only `files.deny` and `permissions.mode` when it equals `plan`; record every other leaf key as `section.key`.
3. Deep-merge user ← project ← session. Arrays replace, except `files.deny`, which is joined.
4. Parse the result with the existing section parsers; errors name the file that set the bad key where it can (the project file is validated alone first).

### Trust

`TrustStore.load(home)`, `trusted(workspace)` returns `Optional<Boolean>`, `record(workspace, boolean)`. The TUI question:

```
Do you trust /path/to/project?
Trusting it lets its .jharness/settings.json loosen JHarness rules (permission mode, sandbox limits, mounts, models).
Untrusted projects can only make JHarness stricter.  [y] trust  [n] don't trust:
```

### Memory

`SystemPrompt.build(context, projectMd, userMd)`: `# Project instructions (JHARNESS.md)` and `# Your instructions (~/.jharness/JHARNESS.md)` sections, omitted when empty; each cut at 32 KB with `[cut at 32 KB]`.

## Checks

- [ ] `mvn -B -ntp verify`
- [ ] `mvn -B -ntp -Pnative -pl jharness-cli package -DskipTests && python3 -I scripts/tui-pty-check.py jharness-cli/target/jharness`

## Risks

| Risk | Response |
| --- | --- |
| `JHARNESS.md` in an untrusted repo carries a prompt injection | It is text, as any file the agent reads; the gate and file policy still apply to every action |
| A trusted project points a model profile at another server | Trusting means accepting the project's settings; the question says so |
