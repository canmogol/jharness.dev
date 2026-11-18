# Plan 004 — Permissions and file policy

Spec: `spec.md` · Status: Draft · Created: 2026-11-18

## Approach

All new code is in `jharness-security`, `jharness-config` and the `api` facade. `FilePolicy` wraps spec 002's `Workspace` and is what `HostApi` and search use for paths. `PermissionEngine` implements `PermissionGate` from the mode, the saved rules and a `Prompter`. `Session` builds both from settings. Decisions D-040 and D-041.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process | — |
| II Host owns side effects | Every `api` call still passes the gate; the gate is now the real engine | Yes |
| III Untrusted | No change to the sandbox; waiting for an answer pauses the wall clock as D-003 says | Yes |
| IV No shell | No new process; `rg` gains `-g '!glob'` exclusions only | Yes |
| V Proxy | No network | — |
| VI Strings | No change at the boundary | Yes |
| VII Safe by default | Default mode `ask`; headless denies; rules only from the user folder; the file policy can't be turned off | Yes |
| VIII Events | `permission.asked`, `permission.rule_added`, plus the existing `permission.decided` | Yes |
| IX Native first | No new dependency | Yes |
| X Tested | Every mode, the deny list in every mode, mounts and rules have tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

### Settings

```json
{
  "permissions": { "mode": "ask" },
  "files": { "mounts": ["/Users/me/docs/spring"], "deny": ["secrets/**", "*.p12"] }
}
```

### `FilePolicy`

```java
public final class FilePolicy {
    public Target read(String path);    // workspace or mount; not denied
    public Target write(String path);   // workspace only; not denied; not .jharness/**
    public List<String> denyGlobs();    // for rg -g '!…' and the Java walk
    public List<Path> mounts();
    public record Target(Path path, String display, boolean mounted) {}
}
```

- Paths inside a mount are given as absolute paths; `display` is the absolute path for mounts and the workspace-relative path otherwise.
- Deny globs match the workspace- or mount-relative path; a glob without `/` also matches the file name in any folder.
- Built-in deny: `.env`, `.env.*`, `*.pem`, `*.key`, `id_rsa*`, `id_ecdsa*`, `id_ed25519*`, `.git/**`. Writes also denied: `.jharness/**`.

### Permission engine

```java
public enum PermissionMode { ASK, ACCEPT_EDITS, PLAN, BYPASS }
public interface Prompter { Answer ask(Operation op); enum Answer { ONCE, ALWAYS, DENY } }
public final class PermissionEngine implements PermissionGate { … }
```

Order of checks for a state-changing operation: `plan` denies; `bypass` allows; `accept-edits` allows file writes; a saved rule allows; otherwise ask (or deny when headless). Reads and `emit` are allowed.

`ProjectRules` reads and writes `~/.jharness/projects/<hash>/permissions.json`:

```json
{"allow": [{"kind": "write", "path": "src/App.java"}, {"kind": "git-write", "op": "commit"}]}
```

### Events

| Type | Fields |
| --- | --- |
| `permission.asked` | `api`, `kind`, `summary` |
| `permission.rule_added` | `kind`, `path` or `op` |

## Checks

- [ ] `mvn -B -ntp verify`
- [ ] `mvn -B -ntp -Pnative -pl jharness-testkit package -DskipTests && jharness-testkit/target/jharness-smoke`

## Risks

| Risk | Response |
| --- | --- |
| A path checked by the policy changes before it is used (TOCTOU) | Same exposure as spec 002's confinement; the OS sandbox (P2) is the backstop |
| A denied file is reachable through git (`git show` of a committed `.env`) | Noted; git operations on denied paths are refused for `add` and `restore`; history output is not filtered in P1 |
| Rules saved for a path stay valid after the file's meaning changes | Rules are per path and kind only; users can edit or delete the file |
