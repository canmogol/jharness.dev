# Plan 056 — File and folder deletes

Spec: `spec.md` · Status: Done · Created: 2026-11-26

## Approach

- **Security.**
  - `Operation.Kind.DELETE` (`delete`) changes state. `PermissionEngine` allows it only in bypass mode, or with a saved rule.
  - `ProjectRules` saves and loads `{"kind": "delete", "path": …}`.
  - `FilePolicy.delete(path)` is `write(path)` plus refusals of the workspace root and of `.git` and anything under it.
- **`HostApi.deleteFile`.** Resolve the path with `FilePolicy.delete`. Read the text for the diff and check the size, all before the gate. Then call the gate with `WRITE`, save the checkpoint, delete, and publish `file.changed` with `deleted: true`.
- **`HostApi.deleteFolder`.** Walk the tree without following links before the gate, refusing:
  - symlinks;
  - denied paths;
  - files over 10 MB;
  - more than 1,000 files.

  The question carries the count, bytes and the first 20 paths. After approval, checkpoint each file, delete deepest first, and publish one `folder.deleted` event.
- **Wiring.**
  - The JS shim, `api.d.ts` and the reachability metadata.
  - `AgentDefinition.API`, the system prompt and an example.
  - `EventTypes`, `LogFormat`, and the TUI line.
- **Tests.**
  - `DeleteTest` (agent): deletes, results, events, refusals, approvals by mode, the saved rule, undo restore, `/diff`.
  - `MainTest` (`-p` with accept-edits and bypass, `filesChanged`) and `TuiTest` (the question and the line).
  - `PermissionEngineTest`: the `delete` kind in each mode and its saved rule.
  - `ApiContractTest` and `ExamplesTest`.
  - A `SecuritySuite` case.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | No new process or dependency | Yes |
| II Host owns side effects | Deletes run on the host after the gate; the agent only names paths | Yes |
| III Untrusted | Strings in, JSON out; no new isolate access | Yes |
| IV No shell | No `rm`; `java.nio.file.Files` only | Yes |
| V Proxy | No network | Yes |
| VI Strings | Path strings and JSON results | Yes |
| VII Safe by default | Folders always ask outside bypass; root, `.git`, `.jharness`, mounts, denied files and symlinks refused; limits keep undo complete | Yes |
| VIII Events | `api.call`, `checkpoint.saved`, `file.changed` with `deleted`, `folder.deleted` | Yes |
| IX Native first | Reachability metadata for both methods; checked with a native build | Yes |
| X Tested | Api, engine, undo and security suite tests | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Checks

- [x] `mvn install` on Linux
- [x] `mvn install` on macOS
- [x] Native build and CI's native step on Linux
- [ ] CI on all targets

## Risks

| Risk | Response |
| --- | --- |
| A delete that undo can't bring back | Files over the checkpoint limit, symlinks and huge folders are refused |
| A symlink leads the walk outside the workspace | The walk never follows links and refuses a folder that has one |
| Deleting what the agent can't see | A folder with a denied file is refused |
